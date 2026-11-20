package app.lumobo.render

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import app.lumobo.film.Adjustments
import app.lumobo.film.ColorMath
import app.lumobo.film.Curves
import app.lumobo.film.Effects
import app.lumobo.film.Geometry
import app.lumobo.film.GrainField
import app.lumobo.film.LutCache
import app.lumobo.film.whiteBalanceGains
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Off-screen OpenGL ES 3 renderer. Owns one EGL context on a dedicated thread.
 * The preview source stays resident (mip-mapped) and also feeds the low-frequency
 * glow passes, so preview and full-resolution export render identical halation/bloom.
 */
class GlRenderer {

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "lumobo-gl").apply { priority = Thread.MAX_PRIORITY } }
    private val dispatcher = executor.asCoroutineDispatcher()

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var progPrefilter = 0
    private var progBlur = 0
    private var progMain = 0
    private var progStreak = 0
    private var quadVbo = 0

    private var previewTex = 0
    private var previewW = 0
    private var previewH = 0
    private var grainTex = 0
    private val lutTextures = LinkedHashMap<String, Int>()

    private var fxW = 0
    private var fxH = 0
    // 0/1 halation, 2/3 bloom, 4/5 local blur, 6 star source, 7 star rays, 8/9 wide blur, 10/11 haze
    private val fxTex = IntArray(FX_COUNT)
    private val fxFbo = IntArray(FX_COUNT)
    private var fxFloat = true

    private var outFbo = 0
    private var outTex = 0
    private var outW = 0
    private var outH = 0

    /** Largest texture / render target edge this GPU supports. */
    var maxTextureSize = 4096
        private set

    private val uniformCache = HashMap<Int, HashMap<String, Int>>()
    private var curveTex = 0
    private var curveKey: Curves? = null

    /** Create the context, compile shaders and build the grain field ahead of first use. */
    suspend fun prewarm() = onGl { }

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
        progStreak = program(Shaders.VERTEX, Shaders.STREAK)

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
            GLES30.glDeleteTextures(FX_COUNT, fxTex, 0)
            GLES30.glDeleteFramebuffers(FX_COUNT, fxFbo, 0)
        }
        fxW = w
        fxH = h
        GLES30.glGenTextures(FX_COUNT, fxTex, 0)
        GLES30.glGenFramebuffers(FX_COUNT, fxFbo, 0)
        for (i in 0 until FX_COUNT) {
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
                GLES30.glDeleteTextures(FX_COUNT, fxTex, 0)
                GLES30.glDeleteFramebuffers(FX_COUNT, fxFbo, 0)
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

    private fun glowPasses(adj: Adjustments, fx: Effects, geo: Geometry) {
        val g = gains(adj)
        GLES30.glViewport(0, 0, fxW, fxH)
        GLES30.glDisable(GLES30.GL_BLEND)
        // Star eraser: painted areas emit no glints, so they make no stars at all.
        val maskId = if (fx.starMask.isNotEmpty() && (fx.star > 0f || fx.streak > 0f)) starMaskTexture(fx.starMask) else 0
        fun prefilter(mode: Int, dstFbo: Int) {
            GLES30.glUseProgram(progPrefilter)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, dstFbo)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, previewTex)
            GLES30.glUniform1i(uniform(progPrefilter, "uSrc"), 0)
            GLES30.glUniform3f(uniform(progPrefilter, "uGain"), g[0], g[1], g[2])
            GLES30.glUniform1i(uniform(progPrefilter, "uMode"), mode)
            GLES30.glUniform2f(uniform(progPrefilter, "uPeak"), 7f / fxW, 7f / fxH)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (maskId != 0) maskId else grainTex)
            GLES30.glUniform1i(uniform(progPrefilter, "uStarMask"), 1)
            GLES30.glUniform1f(uniform(progPrefilter, "uMaskOn"), if (maskId != 0) 1f else 0f)
            drawQuad()
        }
        fun blur(srcTex: Int, dstFbo: Int, dx: Float, dy: Float, sigma: Float) {
            // Wide kernels sample sparsely (bilinear taps) so cost stays bounded.
            val stride = max(1f, sigma / 14f)
            GLES30.glUseProgram(progBlur)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, dstFbo)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTex)
            GLES30.glUniform1i(uniform(progBlur, "uTex"), 0)
            GLES30.glUniform2f(uniform(progBlur, "uStep"), dx * stride, dy * stride)
            GLES30.glUniform1f(uniform(progBlur, "uSigma"), sigma / stride)
            GLES30.glUniform1i(uniform(progBlur, "uRadius"), min(44, ceil(sigma / stride * 3f).toInt()))
            drawQuad()
        }
        fun blurPair(a: Int, b: Int, sigma: Float) {
            blur(fxTex[a], fxFbo[b], 1f / fxW, 0f, sigma)
            blur(fxTex[b], fxFbo[a], 0f, 1f / fxH, sigma)
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
        if (fx.tiltShift > 0f || fx.softFocus > 0f || fx.swirl > 0f || adj.clarity != 0f) {
            prefilter(3, fxFbo[4])
            blurPair(4, 5, 4.5f * scale)
        }
        if (adj.glow > 0f || adj.hdr != 0f) {
            prefilter(3, fxFbo[8])
            blurPair(8, 9, 20f * scale)
        }
        if (adj.dehaze != 0f) {
            prefilter(4, fxFbo[10])
            blurPair(10, 11, 10f * scale)
        }
        if (fx.star > 0f || fx.streak > 0f) {
            prefilter(2, fxFbo[6])
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fxFbo[7])
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
            GLES30.glUseProgram(progStreak)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fxTex[6])
            GLES30.glUniform1i(uniform(progStreak, "uTex"), 0)
            fun arm(displayDeg: Double, stepTexels: Float, taps: Int, decay: Float, r: Float, g: Float, b: Float) {
                val (dx, dy) = displayToSource(displayDeg, geo)
                GLES30.glUniform2f(uniform(progStreak, "uDir"), dx * stepTexels / fxW, dy * stepTexels / fxH)
                GLES30.glUniform1i(uniform(progStreak, "uTaps"), taps)
                GLES30.glUniform1f(uniform(progStreak, "uDecay"), decay)
                GLES30.glUniform3f(uniform(progStreak, "uTint"), r, g, b)
                drawQuad()
            }
            if (fx.star > 0f) {
                val arms = (fx.starPoints / 2).coerceIn(2, 4)
                val step = (0.7f + fx.starLength * 1.8f) * scale
                val k = fx.star * 2.4f
                for (i in 0 until arms) {
                    val deg = fx.starAngle * (180.0 / arms) + i * 180.0 / arms
                    arm(deg, step, 48, 0.065f, k, k * 0.97f, k * 0.92f)
                }
            }
            if (fx.streak > 0f) {
                val k = fx.streak * 2.8f
                arm(0.0, 1.7f * scale, 64, 0.045f, k * 0.35f, k * 0.6f, k)
            }
            GLES30.glDisable(GLES30.GL_BLEND)
        }
    }

    private fun mainPass(srcTex: Int, srcW: Int, srcH: Int, w: Int, h: Int, lutTex: Int, lutSize: Int, adj: Adjustments, fx: Effects, mono: Boolean, geo: Geometry) {
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
        bind(5, GLES30.GL_TEXTURE_2D, fxTex[4], "uLocal")
        bind(6, GLES30.GL_TEXTURE_2D, fxTex[7], "uStarTex")
        bind(7, GLES30.GL_TEXTURE_2D, fxTex[8], "uWide")
        bind(8, GLES30.GL_TEXTURE_2D, fxTex[10], "uHaze")
        val curvesOn = !adj.curves.isIdentity
        bind(9, GLES30.GL_TEXTURE_2D, if (curvesOn) curveTexture(adj.curves) else grainTex, "uCurve")
        val g = gains(adj)
        GLES30.glUniform1f(uniform(p, "uLutSize"), lutSize.toFloat())
        GLES30.glUniform1f(uniform(p, "uIntensity"), adj.intensity)
        GLES30.glUniform3f(uniform(p, "uGain"), g[0], g[1], g[2])
        GLES30.glUniform1f(uniform(p, "uContrast"), adj.contrast)
        GLES30.glUniform1f(uniform(p, "uSaturation"), adj.saturation)
        GLES30.glUniform1f(uniform(p, "uFade"), adj.fade)
        GLES30.glUniform1f(uniform(p, "uHighlights"), adj.highlights)
        GLES30.glUniform1f(uniform(p, "uShadows"), adj.shadows)
        GLES30.glUniform1f(uniform(p, "uWhites"), adj.whites)
        GLES30.glUniform1f(uniform(p, "uBlacks"), adj.blacks)
        GLES30.glUniform1f(uniform(p, "uVibrance"), adj.vibrance)
        GLES30.glUniform1f(uniform(p, "uTexture"), adj.texture)
        GLES30.glUniform1f(uniform(p, "uClarity"), adj.clarity)
        GLES30.glUniform1f(uniform(p, "uDehaze"), adj.dehaze)
        GLES30.glUniform1f(uniform(p, "uSharpen"), adj.sharpen)
        GLES30.glUniform1f(uniform(p, "uHdr"), adj.hdr)
        GLES30.glUniform1f(uniform(p, "uGlow"), adj.glow)
        val srcLong = max(srcW, srcH).toFloat()
        GLES30.glUniform1f(uniform(p, "uTexLod"), (1.6f + log2(srcLong / 2048f)).coerceAtLeast(0.5f))
        GLES30.glUniform1f(uniform(p, "uSharpLod"), 0.9f)
        GLES30.glUniform1f(uniform(p, "uCurveOn"), if (curvesOn) 1f else 0f)
        GLES30.glUniform1f(uniform(p, "uMixerOn"), if (adj.mixerActive) 1f else 0f)
        if (adj.mixerActive) GLES30.glUniform3fv(uniform(p, "uMixer"), 8, adj.mixer.toFloatArray(), 0)
        val gr = adj.grade
        GLES30.glUniform1f(uniform(p, "uGradeOn"), if (gr.isIdentity) 0f else 1f)
        if (!gr.isIdentity) {
            fun dir(hue: Float, sat: Float): FloatArray {
                val d = gradeDirection(hue)
                return floatArrayOf(d[0] * sat * 0.085f, d[1] * sat * 0.085f)
            }
            val sh = dir(gr.shadowHue, gr.shadowSat); val md = dir(gr.midHue, gr.midSat); val hi = dir(gr.highHue, gr.highSat)
            GLES30.glUniform2f(uniform(p, "uGradeS"), sh[0], sh[1])
            GLES30.glUniform2f(uniform(p, "uGradeM"), md[0], md[1])
            GLES30.glUniform2f(uniform(p, "uGradeH"), hi[0], hi[1])
            GLES30.glUniform1f(uniform(p, "uBalance"), gr.balance)
            GLES30.glUniform1f(uniform(p, "uBlending"), gr.blending)
        }
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
        GLES30.glUniform4f(uniform(p, "uSrcRect"), geo.left, geo.top, geo.width, geo.height)
        val rot = ((geo.rotation % 4) + 4) % 4
        GLES30.glUniform1i(uniform(p, "uRot"), rot)
        val dispAspect = if (rot % 2 == 1) srcH.toFloat() / srcW else srcW.toFloat() / srcH
        val theta = Math.toRadians(geo.angle.toDouble())
        val geoOn = geo.angle != 0f || geo.perspV != 0f || geo.perspH != 0f
        val (kh, kv) = geo.keystone(dispAspect)
        GLES30.glUniform1f(uniform(p, "uGeoOn"), if (geoOn) 1f else 0f)
        GLES30.glUniform1f(uniform(p, "uAngle"), theta.toFloat())
        GLES30.glUniform1f(uniform(p, "uFillScale"), geo.fillScale(dispAspect))
        GLES30.glUniform2f(uniform(p, "uKeystone"), kh, kv)
        GLES30.glUniform1f(uniform(p, "uDispAspect"), dispAspect)
        GLES30.glUniform2f(uniform(p, "uFlip"), if (geo.flipH) 1f else 0f, if (geo.flipV) 1f else 0f)
        GLES30.glUniform1f(uniform(p, "uDistort"), fx.distortion)
        GLES30.glUniform1f(uniform(p, "uCircle"), if (fx.fisheyeCircle) 1f else 0f)
        GLES30.glUniform1f(uniform(p, "uStar"), if (fx.star > 0f || fx.streak > 0f) 1f else 0f)
        GLES30.glUniform1f(uniform(p, "uTilt"), fx.tiltShift)
        GLES30.glUniform1f(uniform(p, "uFocusY"), fx.focusY)
        GLES30.glUniform1f(uniform(p, "uFocusSize"), fx.focusSize)
        GLES30.glUniform1f(uniform(p, "uSoft"), fx.softFocus)
        GLES30.glUniform1f(uniform(p, "uSwirl"), fx.swirl)
        GLES30.glUniform1i(uniform(p, "uLeakStyle"), fx.leakStyle)
        drawQuad()
    }

    private fun readInto(bitmap: Bitmap, w: Int, h: Int) {
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        buf.position(0)
        bitmap.copyPixelsFromBuffer(buf)
    }

    /** Render the preview source into [target], which must be [outputSize] of the preview for [geo]. */
    fun renderPreview(lut: LutCache.Lut, adj: Adjustments, fx: Effects, mono: Boolean, geo: Geometry, target: Bitmap) {
        check(previewTex != 0) { "No preview source" }
        glowPasses(adj, fx, geo)
        mainPass(previewTex, previewW, previewH, target.width, target.height, lutTexture(lut), lut.size, adj, fx, mono, geo)
        readInto(target, target.width, target.height)
    }

    /** Render a full-resolution source. Glows come from the resident preview source. */
    fun renderFull(source: Bitmap, lut: LutCache.Lut, adj: Adjustments, fx: Effects, mono: Boolean, geo: Geometry): Bitmap {
        check(previewTex != 0) { "No preview source" }
        val sw = source.width
        val sh = source.height
        val (w, h) = outputSize(sw, sh, geo)
        // Mip-mapped so Texture / Sharpen sample the same relative scales as the preview.
        val tex = uploadBitmap(source, mipmaps = true)
        source.recycle()
        try {
            glowPasses(adj, fx, geo)
            mainPass(tex, sw, sh, w, h, lutTexture(lut), lut.size, adj, fx, mono, geo)
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

    private var maskTex = 0
    private var maskKey: List<Float>? = null

    /** Star-eraser mask: soft discs painted by the user, in source uv, as a small texture. */
    private fun starMaskTexture(mask: List<Float>): Int {
        if (maskTex == 0) {
            maskTex = genTexture()
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTex)
            texParams(GLES30.GL_LINEAR, GLES30.GL_LINEAR, GLES30.GL_CLAMP_TO_EDGE)
        }
        if (mask != maskKey) {
            val n = MASK_SIZE
            val aspect = if (previewH > 0) previewW.toFloat() / previewH else 1f
            val a = ByteArray(n * n)
            var i = 0
            while (i + 2 < mask.size) {
                val cx = mask[i] * n
                val cy = mask[i + 1] * n
                val rx = mask[i + 2] * n
                val ry = rx * aspect
                val x0 = (cx - rx).toInt().coerceIn(0, n - 1)
                val x1 = (cx + rx).toInt().coerceIn(0, n - 1)
                val y0 = (cy - ry).toInt().coerceIn(0, n - 1)
                val y1 = (cy + ry).toInt().coerceIn(0, n - 1)
                for (y in y0..y1) for (x in x0..x1) {
                    val dx = (x + 0.5f - cx) / rx
                    val dy = (y + 0.5f - cy) / ry
                    val d = kotlin.math.sqrt(dx * dx + dy * dy)
                    if (d < 1f) {
                        val v = (((1f - d) / 0.35f).coerceIn(0f, 1f) * 255f).toInt()
                        val idx = y * n + x
                        if (v > (a[idx].toInt() and 255)) a[idx] = v.toByte()
                    }
                }
                i += 3
            }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTex)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, n, n, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, ByteBuffer.wrap(a))
            maskKey = mask
        }
        return maskTex
    }

    private fun curveTexture(curves: Curves): Int {
        if (curveTex == 0) {
            curveTex = genTexture()
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, curveTex)
            texParams(GLES30.GL_LINEAR, GLES30.GL_LINEAR, GLES30.GL_CLAMP_TO_EDGE)
        }
        if (curves != curveKey) {
            val data = curves.bake(256)
            val fb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            fb.put(data).position(0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, curveTex)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, 256, 1, 0, GLES30.GL_RGBA, GLES30.GL_FLOAT, fb)
            curveKey = curves
        }
        return curveTex
    }

    /** Unit OKLab (a, b) direction of an HSV hue, so the grading wheel shows what it applies. */
    private fun gradeDirection(hue: Float): FloatArray {
        val h = ((hue % 360f) + 360f) % 360f / 60f
        val x = 1f - kotlin.math.abs(h % 2f - 1f)
        val (r, g, b) = when (h.toInt()) {
            0 -> Triple(1f, x, 0f); 1 -> Triple(x, 1f, 0f); 2 -> Triple(0f, 1f, x)
            3 -> Triple(0f, x, 1f); 4 -> Triple(x, 0f, 1f); else -> Triple(1f, 0f, x)
        }
        val o = FloatArray(3)
        ColorMath.linearToOklab(ColorMath.srgbToLinear(r), ColorMath.srgbToLinear(g), ColorMath.srgbToLinear(b), o)
        val len = kotlin.math.sqrt(o[1] * o[1] + o[2] * o[2]).coerceAtLeast(1e-4f)
        return floatArrayOf(o[1] / len, o[2] / len)
    }

    /** A direction given in the displayed photo, mapped into source-texture axes (unit length in pixels). */
    private fun displayToSource(deg: Double, geo: Geometry): Pair<Float, Float> {
        val t = Math.toRadians(deg + geo.angle)
        var x = kotlin.math.cos(t)
        var y = kotlin.math.sin(t)
        when (((geo.rotation % 4) + 4) % 4) {
            1 -> { val nx = y; y = -x; x = nx }
            2 -> { x = -x; y = -y }
            3 -> { val nx = -y; y = x; x = nx }
        }
        return x.toFloat() to y.toFloat()
    }

    companion object {
        private const val FX_COUNT = 12

        /** Pixel size of the developed frame for a source of [w]x[h] under [geo]. */
        fun outputSize(w: Int, h: Int, geo: Geometry): Pair<Int, Int> {
            val odd = ((geo.rotation % 4) + 4) % 2 == 1
            val rw = if (odd) h else w
            val rh = if (odd) w else h
            return max(1, (rw * geo.width).roundToInt()) to max(1, (rh * geo.height).roundToInt())
        }

        private const val TAG = "LumoboGL"
        private const val GRAIN_SIZE = 512
        private const val FX_LONG_EDGE = 640
        private const val MASK_SIZE = 256
    }
}
