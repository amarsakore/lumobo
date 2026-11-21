package app.lumobo.render

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.Build
import android.util.Log
import app.lumobo.film.Adjustments
import app.lumobo.film.ColorMath
import app.lumobo.film.Curves
import app.lumobo.film.Effects
import app.lumobo.film.Geometry
import app.lumobo.film.Glow
import app.lumobo.film.GlowStyle
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
    private var progBilateral = 0
    private var quadVbo = 0

    private var previewTex = 0
    private var previewW = 0
    private var previewH = 0
    private var grainTex = 0
    private val lutTextures = LinkedHashMap<String, Int>()

    private var fxW = 0
    private var fxH = 0
    // 0/1 halation, 2/3 bloom, 4/5 local blur, 6 star source, 7 star rays, 8/9 wide blur, 10/11 haze,
    // 12 tone guide (edge-aware Clarity / HDR), 13/14 HDR's edge-aware wide tone,
    // 15 Glow light, 16/17 Glow tight blur or streaks (8/9 is Glow's wide blur)
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
        progBilateral = program(Shaders.VERTEX, Shaders.BILATERAL)
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

    // Ultra HDR gain map of the photo (ProXDR on ColorOS, and most phone cameras now): how much
    // brighter than the SDR image the scene really was, per pixel. Lets stars, glow and bloom
    // tell real lights (sun, its glints, lamps) from merely white surfaces.
    private var gainTex = 0
    private var gainSingle = false
    private var gainLog = FloatArray(15)  // log2 min (3), log2 max (3), gamma (3), eps sdr (3), eps hdr (3)

    /** Whether the open photo has an HDR gain map. */
    var hasGainmap = false
        private set

    private fun uploadGainmap(bitmap: Bitmap) {
        if (gainTex != 0) GLES30.glDeleteTextures(1, intArrayOf(gainTex), 0)
        gainTex = 0
        hasGainmap = false
        if (Build.VERSION.SDK_INT < 34 || !bitmap.hasGainmap()) return
        runCatching {
            val g = bitmap.gainmap ?: return
            var c = g.gainmapContents
            gainSingle = c.config == Bitmap.Config.ALPHA_8
            if (!gainSingle && c.config != Bitmap.Config.ARGB_8888) c = c.copy(Bitmap.Config.ARGB_8888, false)
            gainTex = uploadBitmap(c, mipmaps = false)
            val mn = g.ratioMin; val mx = g.ratioMax; val ga = g.gamma; val es = g.epsilonSdr; val eh = g.epsilonHdr
            for (i in 0 until 3) {
                gainLog[i] = log2(mn[i].coerceAtLeast(1e-4f))
                gainLog[3 + i] = log2(mx[i].coerceAtLeast(1e-4f))
                gainLog[6 + i] = ga[i].coerceAtLeast(1e-3f)
                gainLog[9 + i] = es[i]
                gainLog[12 + i] = eh[i]
            }
            hasGainmap = true
            Log.i(TAG, "HDR gain map ${c.width}x${c.height} ${c.config} max ${mx.joinToString()}")
        }.onFailure { Log.w(TAG, "gain map unavailable", it) }
    }

    /** Upload the working (screen-sized) image. Must be ARGB_8888, software. */
    fun setPreviewSource(bitmap: Bitmap) {
        if (previewTex != 0) GLES30.glDeleteTextures(1, intArrayOf(previewTex), 0)
        previewTex = uploadBitmap(bitmap, mipmaps = true)
        previewW = bitmap.width
        previewH = bitmap.height
        analyseScene(bitmap)
        uploadGainmap(bitmap)
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
        // Lab scan (negative films): the photo's own exposure / colour correction, in part.
        val s = scan.coerceIn(0f, 1f)
        val sc = if (s > 0f) FloatArray(3) { sceneGain[it].toDouble().pow(s.toDouble()).toFloat() } else floatArrayOf(1f, 1f, 1f)
        return floatArrayOf(wb[0] * ev * sc[0], wb[1] * ev * sc[1], wb[2] * ev * sc[2])
    }

    /**
     * Gain for the light that feeds the optical effects (stars, streaks, mist, bloom, halation,
     * Glow): the photographer's exposure and white balance, without the film's lab scan, so
     * switching film doesn't change how much they glow.
     */
    private fun lightGains(adj: Adjustments): FloatArray {
        val wb = whiteBalanceGains(adj.temp, adj.tint)
        val ev = 2.0.pow(adj.exposure.toDouble() * 2.0).toFloat()
        return floatArrayOf(wb[0] * ev, wb[1] * ev, wb[2] * ev)
    }

    /** Colour of the Glow light: Warmth and Tint (luminance kept), or Halation's hue. */
    private fun glowTint(g: Glow, mono: Boolean): FloatArray {
        if (mono) return floatArrayOf(1f, 1f, 1f)
        val w = g.warmth.coerceIn(-1f, 1f)
        val t = g.tint.coerceIn(-1f, 1f)
        if (g.style == GlowStyle.HALATION) {
            // Yellow-orange at the cool end, through orange, to deep red at the warm end;
            // Tint leans it toward magenta-pink or a yellow-green.
            val k = (w + 1f) / 2f
            val gg = (0.62f + (0.14f - 0.62f) * k) * (1f - 0.35f * t)
            val b = (0.20f + (0.05f - 0.20f) * k) + 0.35f * max(t, 0f)
            val m = maxOf(1f, gg, b)
            return floatArrayOf(1f / m, gg / m, b / m)
        }
        val r = 1f + 0.32f * w + 0.10f * t
        val gg = 1f + 0.03f * w - 0.24f * t
        val b = 1f - 0.50f * w + 0.10f * t
        val l = 0.2126f * r + 0.7152f * gg + 0.0722f * b
        return floatArrayOf(r / l, gg / l, b / l)
    }

    /** Preview only: show just the Glow light (never used for saving). */
    var showGlow = false

    /** How much of the lab-scan correction the current film applies (its Look.scan). */
    var scan = 0f

    /**
     * Correction a minilab scanner would make for this photo: exposure toward a mid-grey key
     * and a gentle grey balance. Measured once per photo; mild and partial on purpose, so a
     * night shot stays a night shot and a sunset stays warm.
     */
    private var sceneGain = floatArrayOf(1f, 1f, 1f)

    private fun analyseScene(bmp: Bitmap) {
        val step = max(1, kotlin.math.sqrt(bmp.width.toDouble() * bmp.height / 40000.0).toInt())
        val row = IntArray(bmp.width)
        var logSum = 0.0; var wSum = 0.0
        var rs = 0.0; var gs = 0.0; var bs = 0.0; var n = 0
        var y = 0
        while (y < bmp.height) {
            bmp.getPixels(row, 0, bmp.width, 0, y, bmp.width, 1)
            val dy = y / (bmp.height - 1f) - 0.5f
            var x = 0
            while (x < bmp.width) {
                val c = row[x]
                val r = ColorMath.decode(((c shr 16) and 255) / 255f)
                val g = ColorMath.decode(((c shr 8) and 255) / 255f)
                val b = ColorMath.decode((c and 255) / 255f)
                val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val dx = x / (bmp.width - 1f) - 0.5f
                val w = 1.0 + (1f - (dx * dx + dy * dy) * 4f).coerceIn(0f, 1f)  // centre-weighted, like a scanner
                logSum += w * kotlin.math.ln(1e-4 + lum)
                wSum += w
                if (lum in 0.03f..0.6f) { rs += r; gs += g; bs += b; n++ }
                x += step
            }
            y += step
        }
        val key = kotlin.math.exp(logSum / wSum)
        // Exposure: ignore small deviations, correct half of the rest, within limits.
        var ev = kotlin.math.ln(0.18 / key) / kotlin.math.ln(2.0)
        ev = when {
            ev > 0.35 -> (ev - 0.35) * 0.5
            ev < -0.35 -> (ev + 0.35) * 0.5
            else -> 0.0
        }.coerceIn(-0.6, 0.5)
        val e = 2.0.pow(ev).toFloat()
        // Colour: a third of a grey-world balance on the mid-tones, at most ±8 % per channel.
        var cr = 1f; var cg = 1f; var cb = 1f
        if (n > 200) {
            val avg = (rs + gs + bs) / 3.0
            fun part(v: Double) = (1.0 + ((avg / v.coerceAtLeast(1e-6)) - 1.0) * 0.33).toFloat().coerceIn(0.92f, 1.08f)
            cr = part(rs); cg = part(gs); cb = part(bs)
            val l = 0.2126f * cr + 0.7152f * cg + 0.0722f * cb
            cr /= l; cg /= l; cb /= l
        }
        sceneGain = floatArrayOf(cr * e, cg * e, cb * e)
    }

    private fun glowPasses(adj: Adjustments, fx: Effects, geo: Geometry, mono: Boolean) {
        val g = gains(adj)
        val lg = lightGains(adj)
        val glow = adj.glow
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
            val gain = if (mode == 0 || mode == 1 || mode == 2 || mode == 6) lg else g
            GLES30.glUniform3f(uniform(progPrefilter, "uGain"), gain[0], gain[1], gain[2])
            GLES30.glUniform1f(uniform(progPrefilter, "uGlowT"), 0.95f - 0.85f * glow.range.coerceIn(0f, 1f))
            GLES30.glUniform1f(uniform(progPrefilter, "uGlowSat"), if (mono) 0f else glow.color.coerceIn(0f, 1f))
            GLES30.glUniform1f(uniform(progPrefilter, "uGlowAll"), if (glow.style == GlowStyle.ORTON) 1f else 0f)
            GLES30.glUniform1f(uniform(progPrefilter, "uGlowPeak"), if (glow.style == GlowStyle.STREAK) 1f else 0f)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (hasGainmap) gainTex else grainTex)
            GLES30.glUniform1i(uniform(progPrefilter, "uGainTex"), 2)
            GLES30.glUniform1f(uniform(progPrefilter, "uGmOn"), if (hasGainmap) 1f else 0f)
            GLES30.glUniform1f(uniform(progPrefilter, "uGmSingle"), if (gainSingle) 1f else 0f)
            val gl = gainLog
            GLES30.glUniform3f(uniform(progPrefilter, "uGmMin"), gl[0], gl[1], gl[2])
            GLES30.glUniform3f(uniform(progPrefilter, "uGmMax"), gl[3], gl[4], gl[5])
            GLES30.glUniform3f(uniform(progPrefilter, "uGmGamma"), gl[6], gl[7], gl[8])
            GLES30.glUniform3f(uniform(progPrefilter, "uGmEpsS"), gl[9], gl[10], gl[11])
            GLES30.glUniform3f(uniform(progPrefilter, "uGmEpsH"), gl[12], gl[13], gl[14])
            // Finer mip for glint search: one level below what the fx resolution would pick.
            GLES30.glUniform1f(uniform(progPrefilter, "uFineLod"), (log2(max(previewW, previewH) / max(fxW, fxH).toFloat()) - 1f).coerceAtLeast(0f))
            GLES30.glUniform2f(uniform(progPrefilter, "uFine"), 0.25f / fxW, 0.25f / fxH)
            GLES30.glUniform1i(uniform(progPrefilter, "uMode"), mode)
            GLES30.glUniform2f(uniform(progPrefilter, "uPeak"), 7f / fxW, 7f / fxH)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (maskId != 0) maskId else grainTex)
            GLES30.glUniform1i(uniform(progPrefilter, "uStarMask"), 1)
            GLES30.glUniform1f(uniform(progPrefilter, "uMaskOn"), if (maskId != 0) 1f else 0f)
            GLES30.glUniform1f(uniform(progPrefilter, "uThresh"), fx.starThreshold.coerceIn(0.2f, 0.98f))
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
        if (fx.bloom > 0f || fx.mist > 0f) {
            val sigma = 15f * scale
            prefilter(1, fxFbo[2])
            blur(fxTex[2], fxFbo[3], 1f / fxW, 0f, sigma)
            blur(fxTex[3], fxFbo[2], 0f, 1f / fxH, sigma)
        }
        if (adj.clarity != 0f || adj.hdr != 0f) prefilter(5, fxFbo[12])
        if (adj.hdr != 0f) {
            // Joint bilateral at fx resolution, guided by the tone in 12.
            val sigma = 20f * scale
            val stride = max(1f, sigma / 14f)
            fun bilateral(srcTex: Int, dstFbo: Int, dx: Float, dy: Float) {
                GLES30.glUseProgram(progBilateral)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, dstFbo)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTex)
                GLES30.glUniform1i(uniform(progBilateral, "uTex"), 0)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fxTex[12])
                GLES30.glUniform1i(uniform(progBilateral, "uGuide"), 1)
                GLES30.glUniform2f(uniform(progBilateral, "uStep"), dx * stride, dy * stride)
                GLES30.glUniform1f(uniform(progBilateral, "uSigma"), sigma / stride)
                GLES30.glUniform1i(uniform(progBilateral, "uRadius"), min(44, ceil(sigma / stride * 3f).toInt()))
                GLES30.glUniform1f(uniform(progBilateral, "uRange"), 1f / (2f * 0.08f * 0.08f))
                drawQuad()
            }
            bilateral(fxTex[12], fxFbo[13], 1f / fxW, 0f)
            bilateral(fxTex[13], fxFbo[14], 0f, 1f / fxH)
        }
        if (fx.tiltShift > 0f || fx.softFocus > 0f || fx.swirl > 0f) {
            prefilter(3, fxFbo[4])
            blurPair(4, 5, 4.5f * scale)
        }
        if (adj.dehaze != 0f) {
            prefilter(4, fxFbo[10])
            blurPair(10, 11, 10f * scale)
        }
        if (glow.isOn) {
            prefilter(6, fxFbo[15])
            val sp = glow.spread.coerceIn(0f, 1f)
            if (!glow.style.directional) {
                // Two scales: a tight core and a wide skirt, so the glow falls off like real
                // scattered light (long-tailed) rather than as one flat Gaussian.
                val wide = (if (glow.style == GlowStyle.HALATION) 4f + 16f * sp else 6f + 46f * sp) * scale
                val tight = wide * (if (glow.style == GlowStyle.HALATION) 0.4f else 0.28f)
                blur(fxTex[15], fxFbo[16], 1f / fxW, 0f, tight)
                blur(fxTex[16], fxFbo[17], 0f, 1f / fxH, tight)
                blur(fxTex[15], fxFbo[8], 1f / fxW, 0f, wide)
                blur(fxTex[8], fxFbo[9], 0f, 1f / fxH, wide)
            } else {
                // Streaks along Direction (Motion: one way only), then softened across.
                val (dx, dy) = displayToSource(-glow.direction.toDouble(), geo)
                val step = (0.8f + 3.4f * sp) * scale
                GLES30.glUseProgram(progStreak)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fxFbo[16])
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fxTex[15])
                GLES30.glUniform1i(uniform(progStreak, "uTex"), 0)
                GLES30.glUniform2f(uniform(progStreak, "uDir"), dx * step / fxW, dy * step / fxH)
                GLES30.glUniform1i(uniform(progStreak, "uTaps"), 64)
                GLES30.glUniform1f(uniform(progStreak, "uDecay"), 0.09f - 0.06f * sp)
                GLES30.glUniform3f(uniform(progStreak, "uTint"), 1f, 1f, 1f)
                GLES30.glUniform1f(uniform(progStreak, "uSpectral"), 0f)
                GLES30.glUniform1f(uniform(progStreak, "uOneSided"), if (glow.style == GlowStyle.MOTION) 1f else 0f)
                drawQuad()
                val soft = (0.6f + 6.4f * glow.softness.coerceIn(0f, 1f)) * scale
                blur(fxTex[16], fxFbo[17], -dy / fxW, dx / fxH, soft)
            }
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
            GLES30.glUniform1f(uniform(progStreak, "uOneSided"), 0f)
            fun arm(displayDeg: Double, stepTexels: Float, taps: Int, decay: Float, r: Float, g: Float, b: Float, spectral: Float = 0f) {
                GLES30.glUniform1f(uniform(progStreak, "uSpectral"), spectral)
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
                    arm(deg, step, 48, 0.065f, k, k * 0.97f, k * 0.92f, fx.starSpectral)
                }
            }
            if (fx.streak > 0f) {
                val k = fx.streak * 2.8f
                // Cool anamorphic blue to warm amber; Rainbow splits it like a prism.
                val w = fx.streakWarm.coerceIn(0f, 1f)
                arm(0.0, 1.7f * scale, 64, 0.045f, k * (0.35f + 0.65f * w), k * (0.6f + 0.02f * w), k * (1f - 0.7f * w), fx.starSpectral)
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
        // Create / update helper textures before binding units: doing it mid-sequence would
        // rebind whichever unit is active (it used to replace the Dehaze haze map with the curve).
        val curvesOn = !adj.curves.isIdentity
        val curveTex = if (curvesOn) curveTexture(adj.curves) else grainTex
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
        bind(7, GLES30.GL_TEXTURE_2D, fxTex[9], "uGlowWide")
        bind(8, GLES30.GL_TEXTURE_2D, fxTex[10], "uHaze")
        bind(9, GLES30.GL_TEXTURE_2D, curveTex, "uCurve")
        bind(10, GLES30.GL_TEXTURE_2D, fxTex[12], "uGuide")
        bind(11, GLES30.GL_TEXTURE_2D, fxTex[14], "uHdrBase")
        bind(12, GLES30.GL_TEXTURE_2D, fxTex[15], "uGlowSrc")
        bind(13, GLES30.GL_TEXTURE_2D, fxTex[17], "uGlowTight")
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
        val glow = adj.glow
        GLES30.glUniform1i(uniform(p, "uGlowStyle"), if (glow.isOn) glow.style.ordinal else -1)
        GLES30.glUniform1f(uniform(p, "uGlowAmt"), glow.amount.coerceIn(0f, 1f))
        glowTint(glow, mono).let { GLES30.glUniform3f(uniform(p, "uGlowTint"), it[0], it[1], it[2]) }
        GLES30.glUniform1f(uniform(p, "uGlowShow"), if (showGlow && glow.isOn) 1f else 0f)
        GLES30.glUniform1f(uniform(p, "uGlowLift"), 0.8f + glow.range.coerceIn(0f, 1f))
        GLES30.glUniform1f(uniform(p, "uGlowT"), 0.95f - 0.85f * glow.range.coerceIn(0f, 1f))
        GLES30.glUniform1f(uniform(p, "uGlowSat"), if (mono) 0f else glow.color.coerceIn(0f, 1f))
        lightGains(adj).let { GLES30.glUniform3f(uniform(p, "uLightGain"), it[0], it[1], it[2]) }
        val srcLong = max(srcW, srcH).toFloat()
        GLES30.glUniform1f(uniform(p, "uTexLod"), (1.6f + log2(srcLong / 2048f)).coerceAtLeast(0.5f))
        GLES30.glUniform2f(uniform(p, "uGuideTexel"), 1f / fxW, 1f / fxH)
        GLES30.glUniform1f(uniform(p, "uClarR"), 4.5f * max(fxW, fxH) / FX_LONG_EDGE.toFloat())
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
        GLES30.glUniform1f(uniform(p, "uMist"), fx.mist)
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
        glowPasses(adj, fx, geo, mono)
        mainPass(previewTex, previewW, previewH, target.width, target.height, lutTexture(lut), lut.size, adj, fx, mono, geo)
        readInto(target, target.width, target.height)
    }

    /** Render a full-resolution source. Glows come from the resident preview source. */
    fun renderFull(source: Bitmap, lut: LutCache.Lut, adj: Adjustments, fx: Effects, mono: Boolean, geo: Geometry): Bitmap {
        check(previewTex != 0) { "No preview source" }
        val sw = source.width
        val sh = source.height
        val (w, h) = outputSize(sw, sh, geo)
        // Mip-mapped so Texture samples the same relative scale as the preview.
        val tex = uploadBitmap(source, mipmaps = true)
        source.recycle()
        try {
            glowPasses(adj, fx, geo, mono)
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
        private const val FX_COUNT = 18

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
