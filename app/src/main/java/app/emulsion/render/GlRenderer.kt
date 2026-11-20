package app.emulsion.render

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import app.emulsion.film.Adjustments
import app.emulsion.film.Effects
import app.emulsion.film.GrainField
import app.emulsion.film.LutCache
import app.emulsion.film.whiteBalanceGains
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Off-screen OpenGL ES 3 renderer. Owns one EGL context on a dedicated thread.
 * The preview source stays resident (mip-mapped) and also feeds the low-frequency
 * glow passes, so preview and full-resolution export render identical halation/bloom.
 */
class GlRenderer {

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "emulsion-gl").apply { priority = Thread.MAX_PRIORITY } }
    private val dispatcher = executor.asCoroutineDispatcher()

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var progPrefilter = 0
    private var progBlur = 0
    private var progMain = 0
    private var quadVbo = 0

    private var previewTex = 0
    private var previewW = 0
    private var previewH = 0
    private var grainTex = 0
    private val lutTextures = LinkedHashMap<String, Int>()

    private var fxW = 0
    private var fxH = 0
    private val fxTex = IntArray(4)    // halA, halB, bloomA, bloomB
    private val fxFbo = IntArray(4)
    private var fxFloat = true

    private var outFbo = 0
    private var outTex = 0
    private var outW = 0
    private var outH = 0

    /** Largest texture / render target edge this GPU supports. */
    var maxTextureSize = 4096
        private set

    private val uniformCache = HashMap<Int, HashMap<String, Int>>()

    suspend fun <T> onGl(block: GlRenderer.() -> T): T = withContext(dispatcher) {
        ensureInit()
        block()
    }

    // ───────────────────────────── setup ─────────────────────────────

    private fun ensureInit() {
        if (context != EGL14.EGL_NO_CONTEXT) return
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        check(EGL14.eglInitialize(display, ver, 0, ver, 1)) { "eglInitialize failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT_KHR */,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) { "No ES3 EGL config" }
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }

        val tmp = IntArray(2)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, tmp, 0)
        val maxTex = tmp[0]
        GLES30.glGetIntegerv(GLES30.GL_MAX_RENDERBUFFER_SIZE, tmp, 0)
        val maxRb = tmp[0]
        GLES30.glGetIntegerv(GLES30.GL_MAX_VIEWPORT_DIMS, tmp, 0)
        maxTextureSize = minOf(maxTex, maxRb, tmp[0], tmp[1])
        val exts = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty()
        val glVersion = GLES30.glGetString(GLES30.GL_VERSION).orEmpty()
        fxFloat = exts.contains("GL_EXT_color_buffer_half_float") || exts.contains("GL_EXT_color_buffer_float") || glVersion.contains("OpenGL ES 3.2")
        Log.i(TAG, "GL ready: $glVersion maxTex=$maxTextureSize fxFloat=$fxFloat")

        progPrefilter = program(Shaders.VERTEX, Shaders.PREFILTER)
        progBlur = program(Shaders.VERTEX, Shaders.BLUR)
        progMain = program(Shaders.VERTEX, Shaders.MAIN)

        val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val buf = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(quad)
        buf.position(0)
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        quadVbo = ids[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, buf, GLES30.GL_STATIC_DRAW)

        // Grain field
        val grain = GrainField.generate(GRAIN_SIZE)
        grainTex = genTexture()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, grainTex)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, GRAIN_SIZE, GRAIN_SIZE, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, ByteBuffer.wrap(grain))
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        texParams(GLES30.GL_LINEAR_MIPMAP_LINEAR, GLES30.GL_LINEAR, GLES30.GL_REPEAT)
    }

    private fun program(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) error("Shader compile failed: " + GLES30.glGetShaderInfoLog(s))
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glBindAttribLocation(p, 0, "aPos")
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) error("Program link failed: " + GLES30.glGetProgramInfoLog(p))
        return p
    }

    private fun genTexture(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        return ids[0]
    }

    private fun texParams(min: Int, mag: Int, wrap: Int, target: Int = GLES30.GL_TEXTURE_2D) {
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, min)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, mag)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, wrap)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, wrap)
        if (target == GLES30.GL_TEXTURE_3D) GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_R, wrap)
    }

    private fun uniform(prog: Int, name: String): Int =
        uniformCache.getOrPut(prog) { HashMap() }.getOrPut(name) { GLES30.glGetUniformLocation(prog, name) }

    private fun uploadBitmap(bitmap: Bitmap, mipmaps: Boolean): Int {
        val tex = genTexture()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        if (mipmaps) {
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            texParams(GLES30.GL_LINEAR_MIPMAP_LINEAR, GLES30.GL_LINEAR, GLES30.GL_CLAMP_TO_EDGE)
        } else {
            texParams(GLES30.GL_LINEAR, GLES30.GL_LINEAR, GLES30.GL_CLAMP_TO_EDGE)
        }
        return tex
    }

    // ───────────────────────────── sources & LUTs ─────────────────────────────

    /** Upload the working (screen-sized) image. Must be ARGB_8888, software. */
    fun setPreviewSource(bitmap: Bitmap) {
        if (previewTex != 0) GLES30.glDeleteTextures(1, intArrayOf(previewTex), 0)
        previewTex = uploadBitmap(bitmap, mipmaps = true)
        previewW = bitmap.width
        previewH = bitmap.height
        val s = FX_LONG_EDGE.toFloat() / max(previewW, previewH)
        val w = max(16, (previewW * s).roundToInt())
        val h = max(16, (previewH * s).roundToInt())
        if (w != fxW || h != fxH) allocFx(w, h)
    }

    private fun allocFx(w: Int, h: Int) {
        if (fxTex[0] != 0) {
            GLES30.glDeleteTextures(4, fxTex, 0)
            GLES30.glDeleteFramebuffers(4, fxFbo, 0)
        }
        fxW = w
        fxH = h
        GLES30.glGenTextures(4, fxTex, 0)
        GLES30.glGenFramebuffers(4, fxFbo, 0)
        for (i in 0 until 4) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fxTex[i])
            if (fxFloat) {
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, w, h, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
            } else {
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            }
            texParams(GLES30.GL_LINEAR, GLES30.GL_LINEAR, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fxFbo[i])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, fxTex[i], 0)
            val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            if (status != GLES30.GL_FRAMEBUFFER_COMPLETE && fxFloat) {
                Log.w(TAG, "Half-float FBO incomplete, falling back to RGBA8")
                fxFloat = false
                GLES30.glDeleteTextures(4, fxTex, 0)
                GLES30.glDeleteFramebuffers(4, fxFbo, 0)
                fxTex.fill(0)
                allocFx(w, h)
                return
            }
        }
    }

    private fun lutTexture(lut: LutCache.Lut): Int {
        lutTextures.remove(lut.id)?.let { lutTextures[lut.id] = it; return it }
        if (lutTextures.size >= 8) {
            val eldest = lutTextures.keys.first()
            GLES30.glDeleteTextures(1, intArrayOf(lutTextures.remove(eldest)!!), 0)
        }
        val tex = genTexture()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, tex)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        val fb = ByteBuffer.allocateDirect(lut.data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(lut.data).position(0)
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F, lut.size, lut.size, lut.size, 0, GLES30.GL_RGB, GLES30.GL_FLOAT, fb)
        texParams(GLES30.GL_LINEAR, GLES30.GL_LINEAR, GLES30.GL_CLAMP_TO_EDGE, GLES30.GL_TEXTURE_3D)
        lutTextures[lut.id] = tex
        return tex
    }

    private fun ensureOut(w: Int, h: Int) {
        if (w == outW && h == outH && outFbo != 0) return
        releaseOut()
        outTex = genTexture()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, outTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        texParams(GLES30.GL_NEAREST, GLES30.GL_NEAREST, GLES30.GL_CLAMP_TO_EDGE)
        val ids = IntArray(1)
        GLES30.glGenFramebuffers(1, ids, 0)
        outFbo = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, outTex, 0)
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) { "Output FBO incomplete ${w}x$h" }
        outW = w
        outH = h
    }

    private fun releaseOut() {
        if (outFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(outFbo), 0)
        if (outTex != 0) GLES30.glDeleteTextures(1, intArrayOf(outTex), 0)
        outFbo = 0; outTex = 0; outW = 0; outH = 0
    }

    // ───────────────────────────── rendering ─────────────────────────────

    private fun drawQuad() {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun gains(adj: Adjustments): FloatArray {
        val wb = whiteBalanceGains(adj.temp, adj.tint)
        val ev = 2.0.pow(adj.exposure.toDouble() * 2.0).toFloat()  // slider ±1 == ±2 stops
        return floatArrayOf(wb[0] * ev, wb[1] * ev, wb[2] * ev)
    }

    private fun glowPasses(adj: Adjustments, fx: Effects) {
        val g = gains(adj)
        GLES30.glViewport(0, 0, fxW, fxH)
        GLES30.glDisable(GLES30.GL_BLEND)
        fun prefilter(mode: Int, dstFbo: Int) {
            GLES30.glUseProgram(progPrefilter)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, dstFbo)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, previewTex)
            GLES30.glUniform1i(uniform(progPrefilter, "uSrc"), 0)
            GLES30.glUniform3f(uniform(progPrefilter, "uGain"), g[0], g[1], g[2])
            GLES30.glUniform1i(uniform(progPrefilter, "uMode"), mode)
            drawQuad()
        }
        fun blur(srcTex: Int, dstFbo: Int, dx: Float, dy: Float, sigma: Float) {
            GLES30.glUseProgram(progBlur)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, dstFbo)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTex)
            GLES30.glUniform1i(uniform(progBlur, "uTex"), 0)
            GLES30.glUniform2f(uniform(progBlur, "uStep"), dx, dy)
            GLES30.glUniform1f(uniform(progBlur, "uSigma"), sigma)
            GLES30.glUniform1i(uniform(progBlur, "uRadius"), min(48, ceil(sigma * 3f).toInt()))
            drawQuad()
        }
        val scale = max(fxW, fxH) / FX_LONG_EDGE.toFloat()
        if (fx.halation > 0f) {
            val sigma = 5.5f * scale
            prefilter(0, fxFbo[0])
            blur(fxTex[0], fxFbo[1], 1f / fxW, 0f, sigma)
            blur(fxTex[1], fxFbo[0], 0f, 1f / fxH, sigma)
        }
        if (fx.bloom > 0f) {
            val sigma = 15f * scale
            prefilter(1, fxFbo[2])
            blur(fxTex[2], fxFbo[3], 1f / fxW, 0f, sigma)
            blur(fxTex[3], fxFbo[2], 0f, 1f / fxH, sigma)
        }
    }

    private fun mainPass(srcTex: Int, w: Int, h: Int, lutTex: Int, lutSize: Int, adj: Adjustments, fx: Effects, mono: Boolean) {
        ensureOut(w, h)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outFbo)
        GLES30.glViewport(0, 0, w, h)
        val p = progMain
        GLES30.glUseProgram(p)
        fun bind(unit: Int, target: Int, tex: Int, name: String) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
            GLES30.glBindTexture(target, tex)
            GLES30.glUniform1i(uniform(p, name), unit)
        }
        bind(0, GLES30.GL_TEXTURE_2D, srcTex, "uSrc")
        bind(1, GLES30.GL_TEXTURE_3D, lutTex, "uLut")
        bind(2, GLES30.GL_TEXTURE_2D, grainTex, "uGrain")
        bind(3, GLES30.GL_TEXTURE_2D, fxTex[0], "uHal")
        bind(4, GLES30.GL_TEXTURE_2D, fxTex[2], "uBloom")
        val g = gains(adj)
        GLES30.glUniform1f(uniform(p, "uLutSize"), lutSize.toFloat())
        GLES30.glUniform1f(uniform(p, "uIntensity"), adj.intensity)
        GLES30.glUniform3f(uniform(p, "uGain"), g[0], g[1], g[2])
        GLES30.glUniform1f(uniform(p, "uContrast"), adj.contrast)
        GLES30.glUniform1f(uniform(p, "uSaturation"), adj.saturation)
        GLES30.glUniform1f(uniform(p, "uFade"), adj.fade)
        GLES30.glUniform1f(uniform(p, "uHighlights"), adj.highlights)
        GLES30.glUniform1f(uniform(p, "uShadows"), adj.shadows)
        GLES30.glUniform1f(uniform(p, "uHalation"), fx.halation)
        if (mono) GLES30.glUniform3f(uniform(p, "uHalColor"), 0.55f, 0.55f, 0.55f)
        else GLES30.glUniform3f(uniform(p, "uHalColor"), 1.0f, 0.30f, 0.10f)
        GLES30.glUniform1f(uniform(p, "uBloomAmt"), fx.bloom)
        GLES30.glUniform1f(uniform(p, "uVignette"), fx.vignette)
        GLES30.glUniform1f(uniform(p, "uAberration"), fx.aberration)
        GLES30.glUniform1f(uniform(p, "uLeak"), fx.leak)
        GLES30.glUniform1f(uniform(p, "uGrainAmt"), fx.grain)
        val density = 2300f + (520f - 2300f) * fx.grainSize   // grain texels across the short edge
        GLES30.glUniform1f(uniform(p, "uGrainScale"), density / GRAIN_SIZE)
        GLES30.glUniform1f(uniform(p, "uColorGrain"), if (mono) 0f else fx.colorGrain)
        val short = min(w, h).toFloat()
        GLES30.glUniform2f(uniform(p, "uAspect"), w / short, h / short)
        GLES30.glUniform2f(uniform(p, "uRes"), w.toFloat(), h.toFloat())
        GLES30.glUniform1f(uniform(p, "uSeed"), 0.37f)
        drawQuad()
    }

    private fun readInto(bitmap: Bitmap, w: Int, h: Int) {
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        buf.position(0)
        bitmap.copyPixelsFromBuffer(buf)
    }

    /** Render the preview source into [target] (same size as the preview source). */
    fun renderPreview(lut: LutCache.Lut, adj: Adjustments, fx: Effects, mono: Boolean, target: Bitmap) {
        check(previewTex != 0) { "No preview source" }
        glowPasses(adj, fx)
        mainPass(previewTex, previewW, previewH, lutTexture(lut), lut.size, adj, fx, mono)
        readInto(target, previewW, previewH)
    }

    /** Render a full-resolution source. Glows come from the resident preview source. */
    fun renderFull(source: Bitmap, lut: LutCache.Lut, adj: Adjustments, fx: Effects, mono: Boolean): Bitmap {
        check(previewTex != 0) { "No preview source" }
        val w = source.width
        val h = source.height
        val tex = uploadBitmap(source, mipmaps = false)
        source.recycle()
        try {
            glowPasses(adj, fx)
            mainPass(tex, w, h, lutTexture(lut), lut.size, adj, fx, mono)
            GLES30.glFinish()
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            readInto(out, w, h)
            return out
        } finally {
            GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
            releaseOut()
        }
    }

    val previewSize get() = previewW to previewH

    companion object {
        private const val TAG = "EmulsionGL"
        private const val GRAIN_SIZE = 512
        private const val FX_LONG_EDGE = 640
    }
}
