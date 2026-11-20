package app.lumobo.film

import app.lumobo.film.ColorMath.clamp01
import app.lumobo.film.ColorMath.hueDelta
import app.lumobo.film.ColorMath.linearToSrgb
import app.lumobo.film.ColorMath.smoothstep
import app.lumobo.film.ColorMath.srgbToLinear
import java.util.stream.IntStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A display-referred tone curve, expressed with photographer-style controls.
 * Evaluated on gamma-encoded (sRGB) values in [0, 1].
 *
 * contrast   slope at the pivot (1 = neutral, >1 = S-curve, <1 = flatter)
 * shadows    lifts (+) or deepens (-) the quarter tones
 * highlights brightens (+) or compresses (-) the three-quarter tones
 * blacks     moves the deep shadows (0 – 0.3)
 * whites     moves the top end (0.7 – 1)
 */
data class Curve(
    val contrast: Float = 1f,
    val shadows: Float = 0f,
    val highlights: Float = 0f,
    val blacks: Float = 0f,
    val whites: Float = 0f,
    val pivot: Float = 0.5f,
) {
    val isIdentity get() = contrast == 1f && shadows == 0f && highlights == 0f && blacks == 0f && whites == 0f

    fun eval(x: Float): Float {
        val p = pivot
        val k = contrast.toDouble()
        var y = if (x < p) {
            p * (x / p).toDouble().coerceAtLeast(0.0).pow(k).toFloat()
        } else {
            1f - (1f - p) * ((1f - x) / (1f - p)).toDouble().coerceAtLeast(0.0).pow(k).toFloat()
        }
        y += shadows * 0.12f * bump(x, 0f, 0.5f)
        y += highlights * 0.12f * bump(x, 0.5f, 1f)
        y += blacks * 0.07f * bump(x, 0f, 0.3f)
        y += whites * 0.07f * bump(x, 0.7f, 1f)
        return clamp01(y)
    }

    companion object {
        fun bump(x: Float, a: Float, b: Float): Float {
            if (x <= a || x >= b) return 0f
            val s = sin(PI * (x - a) / (b - a)).toFloat()
            return s * s
        }
    }
}

/** Monotone 1D lookup table baked from a [Curve]. */
class ToneTable(curve: Curve) {
    private val n = 2048
    private val t = FloatArray(n + 1)
    val identity = curve.isIdentity

    init {
        var prev = 0f
        for (i in 0..n) {
            val v = max(prev, curve.eval(i / n.toFloat()))
            t[i] = v
            prev = v
        }
    }

    fun eval(x: Float): Float {
        if (identity) return x
        val f = clamp01(x) * n
        val i = f.toInt().coerceAtMost(n - 1)
        val fr = f - i
        return t[i] + (t[i + 1] - t[i]) * fr
    }
}

/** Per-hue adjustment, in OKLCh. [width] is the half-width of the raised-cosine window in degrees. */
data class Band(
    val hue: Float,
    val width: Float = 40f,
    val shift: Float = 0f,
    val sat: Float = 1f,
    val light: Float = 0f,
)

/** A colour push in OKLab, [amount] in OKLab chroma units (0.02 is subtle, 0.06 is strong). */
data class Tone(val hue: Float, val amount: Float)

/** Black & white conversion weights on linear RGB (models film spectral sensitivity / lens filters). */
data class Mono(val r: Float, val g: Float, val b: Float)

data class Rgb(val r: Float, val g: Float, val b: Float) {
    companion object {
        val BLACK = Rgb(0f, 0f, 0f)
        val WHITE = Rgb(1f, 1f, 1f)
        fun gray(v: Float) = Rgb(v, v, v)
    }
}

/**
 * Colour description of a film / camera look. Applied in this order:
 * exposure & white balance (linear) -> mixer -> tone curve (mix of per-channel and
 * luminance-preserving) -> per-channel curves -> OKLCh saturation & hue bands ->
 * shadow/mid/highlight colour pushes -> gamut mapping -> output black / white levels.
 */
data class Look(
    val exposure: Float = 0f,
    val temp: Float = 0f,
    val tint: Float = 0f,
    val mono: Mono? = null,
    val curve: Curve = Curve(),
    val rgbCurveMix: Float = 0.75f,
    val red: Curve? = null,
    val green: Curve? = null,
    val blue: Curve? = null,
    val saturation: Float = 1f,
    val vibrance: Float = 0f,
    val shadowSat: Float = 1f,
    val highlightSat: Float = 1f,
    val bands: List<Band> = emptyList(),
    val shadowTone: Tone? = null,
    val midTone: Tone? = null,
    val highlightTone: Tone? = null,
    val black: Rgb = Rgb.BLACK,
    val white: Rgb = Rgb.WHITE,
    /**
     * Dye density (subtractive colour): saturated colours get darker and richer, as more dye
     * builds up, instead of brighter as with digital saturation. In OKLab L units at full
     * colour (slide films ~0.03-0.07, negatives ~0.01).
     */
    val density: Float = 0f,
    /**
     * Lab-scan strength (0..1) for negative stocks: like a minilab scanner, each photo's
     * exposure and colour balance are partly normalised before the film's response.
     * Slide and instant films are what you exposed, so they keep 0.
     */
    val scan: Float = 0f,
)

/** White-balance channel gains shared by CPU and GPU paths. Luminance normalised. */
fun whiteBalanceGains(temp: Float, tint: Float): FloatArray {
    val r = 2.0.pow(0.26 * temp + 0.06 * tint).toFloat()
    val g = 2.0.pow(-0.16 * tint).toFloat()
    val b = 2.0.pow(-0.30 * temp + 0.06 * tint).toFloat()
    val n = 0.2126f * r + 0.7152f * g + 0.0722f * b
    return floatArrayOf(r / n, g / n, b / n)
}

/** Soft shoulder used whenever gains push linear values above 1. */
fun softClip(x: Float): Float {
    val t = 0.8f
    if (x <= t) return x
    return t + (1f - t) * (1f - exp(-(x - t) / (1f - t)))
}

/** A [Look] prepared for fast per-pixel evaluation. Thread-safe: no shared mutable state. */
class CompiledLook(val look: Look) {
    private val master = ToneTable(look.curve)
    private val rT = look.red?.let { ToneTable(it) }
    private val gT = look.green?.let { ToneTable(it) }
    private val bT = look.blue?.let { ToneTable(it) }
    private val gains: FloatArray
    private val needsClip: Boolean
    private val monoW: FloatArray?
    private val toneShadow = look.shadowTone?.let { floatArrayOf(ColorMath.polarA(it.amount, it.hue), ColorMath.polarB(it.amount, it.hue)) }
    private val toneMid = look.midTone?.let { floatArrayOf(ColorMath.polarA(it.amount, it.hue), ColorMath.polarB(it.amount, it.hue)) }
    private val toneHigh = look.highlightTone?.let { floatArrayOf(ColorMath.polarA(it.amount, it.hue), ColorMath.polarB(it.amount, it.hue)) }

    init {
        val wb = whiteBalanceGains(look.temp, look.tint)
        val ev = 2.0.pow(look.exposure.toDouble()).toFloat()
        gains = floatArrayOf(wb[0] * ev, wb[1] * ev, wb[2] * ev)
        needsClip = gains.any { it > 1.001f }
        monoW = look.mono?.let {
            val s = it.r + it.g + it.b
            floatArrayOf(it.r / s, it.g / s, it.b / s)
        }
    }

    /** Evaluate one sRGB-encoded colour. Writes sRGB-encoded result to [out]. [tmp] needs >= 3 floats. */
    fun apply(rs: Float, gs: Float, bs: Float, out: FloatArray, tmp: FloatArray = FloatArray(3)) {
        var r = ColorMath.decode(rs) * gains[0]
        var g = ColorMath.decode(gs) * gains[1]
        var b = ColorMath.decode(bs) * gains[2]
        if (needsClip) {
            r = softClip(r); g = softClip(g); b = softClip(b)
        }

        val mw = monoW
        if (mw != null) {
            val y = mw[0] * r + mw[1] * g + mw[2] * b
            val v = master.eval(ColorMath.encode(clamp01(y)))
            val lin = ColorMath.decode(v)
            finishLab(cbrt(lin), 0f, 0f, out, tmp)
            return
        }

        // Tone: blend of per-channel curve (film-like, adds saturation/crossover) and a
        // luminance-preserving curve (keeps hue and chroma).
        val pr = ColorMath.decode(master.eval(ColorMath.encode(r)))
        val pg = ColorMath.decode(master.eval(ColorMath.encode(g)))
        val pb = ColorMath.decode(master.eval(ColorMath.encode(b)))
        val y = ColorMath.luminance(r, g, b)
        val yt = ColorMath.decode(master.eval(ColorMath.encode(y)))
        val ratio = if (y > 1e-6f) yt / y else 0f
        val m = look.rgbCurveMix
        r = r * ratio + (pr - r * ratio) * m
        g = g * ratio + (pg - g * ratio) * m
        b = b * ratio + (pb - b * ratio) * m

        if (rT != null || gT != null || bT != null) {
            if (rT != null) r = ColorMath.decode(rT.eval(ColorMath.encode(r)))
            if (gT != null) g = ColorMath.decode(gT.eval(ColorMath.encode(g)))
            if (bT != null) b = ColorMath.decode(bT.eval(ColorMath.encode(b)))
        }

        ColorMath.linearToOklab(max(r, 0f), max(g, 0f), max(b, 0f), tmp)
        var L = tmp[0]
        val a0 = tmp[1]
        val b0 = tmp[2]
        var c = sqrt(a0 * a0 + b0 * b0)
        var h = ColorMath.hueDeg(a0, b0)

        var sat = look.saturation
        if (look.shadowSat != 1f) sat *= 1f + (look.shadowSat - 1f) * (1f - smoothstep(0.2f, 0.55f, L))
        if (look.highlightSat != 1f) sat *= 1f + (look.highlightSat - 1f) * smoothstep(0.62f, 0.97f, L)
        if (look.vibrance != 0f) sat *= 1f + look.vibrance * (1f - smoothstep(0f, 0.18f, c))

        if (look.bands.isNotEmpty()) {
            val cw = smoothstep(0f, 0.05f, c)
            var dh = 0f
            var satMul = 1f
            var dL = 0f
            for (band in look.bands) {
                val d = hueDelta(h, band.hue)
                if (abs(d) < band.width) {
                    val w = 0.5f * (1f + cos(PI * d / band.width).toFloat())
                    dh += w * band.shift
                    satMul *= 1f + w * (band.sat - 1f)
                    dL += w * band.light * cw
                }
            }
            h += dh * cw
            sat *= satMul
            L += dL
        }
        c *= sat
        var a = ColorMath.polarA(c, h)
        var bb = ColorMath.polarB(c, h)
        if (look.density != 0f) {
            // More dye where colour is stronger; spare the deepest shadows (already dense).
            val d = look.density * smoothstep(0.03f, 0.2f, c) * smoothstep(0.05f, 0.4f, L)
            if (d > 0f) {
                // Only as dense as the colour can stay this vivid on screen: a dark red can't
                // be as saturated as a mid red, so stop at the gamut edge instead of dulling it.
                if (!inGamut(L, a, bb, tmp)) {
                    // Already at the screen's limit: keep it vivid, add no density.
                } else if (inGamut(L - d, a, bb, tmp)) {
                    L -= d
                } else {
                    var lo = 0f
                    var hi = 1f
                    repeat(7) {
                        val mid = (lo + hi) * 0.5f
                        if (inGamut(L - d * mid, a, bb, tmp)) lo = mid else hi = mid
                    }
                    L -= d * lo
                }
            }
        }
        finishLab(L, a, bb, out, tmp)
    }

    private fun inGamut(L: Float, a: Float, b: Float, tmp: FloatArray): Boolean {
        ColorMath.oklabToLinear(L, a, b, tmp)
        val e = 1e-4f
        return tmp[0] >= -e && tmp[1] >= -e && tmp[2] >= -e && tmp[0] <= 1f + e && tmp[1] <= 1f + e && tmp[2] <= 1f + e
    }

    private fun finishLab(L0: Float, a0: Float, b0: Float, out: FloatArray, tmp: FloatArray) {
        var L = L0
        var a = a0
        var b = b0
        // Colour pushes act mostly on near-neutrals, so saturated subjects (blue sky, a red
        // flower) keep their identity instead of being muddied by the tint.
        val protect = 1f - 0.55f * smoothstep(0.04f, 0.16f, sqrt(a * a + b * b))
        if (toneShadow != null) {
            val w = protect * smoothstep(0f, 0.22f, L) * (1f - smoothstep(0.25f, 0.68f, L))
            a += toneShadow[0] * w; b += toneShadow[1] * w
        }
        if (toneMid != null) {
            val w = protect * (1f - smoothstep(0f, 0.4f, abs(L - 0.58f)))
            a += toneMid[0] * w; b += toneMid[1] * w
        }
        if (toneHigh != null) {
            val w = protect * smoothstep(0.5f, 0.92f, L)
            a += toneHigh[0] * w; b += toneHigh[1] * w
        }
        L = clamp01(L)
        ColorMath.oklabToLinear(L, a, b, tmp)
        val eps = 1e-4f
        if (tmp[0] < -eps || tmp[1] < -eps || tmp[2] < -eps || tmp[0] > 1f + eps || tmp[1] > 1f + eps || tmp[2] > 1f + eps) {
            // Reduce chroma at constant L/h until in gamut (smooth, hue preserving).
            var lo = 0f
            var hi = 1f
            for (i in 0 until 12) {
                val mid = (lo + hi) * 0.5f
                ColorMath.oklabToLinear(L, a * mid, b * mid, tmp)
                val ok = tmp[0] >= -eps && tmp[1] >= -eps && tmp[2] >= -eps &&
                    tmp[0] <= 1f + eps && tmp[1] <= 1f + eps && tmp[2] <= 1f + eps
                if (ok) lo = mid else hi = mid
            }
            ColorMath.oklabToLinear(L, a * lo, b * lo, tmp)
        }
        val bl = look.black
        val wh = look.white
        out[0] = bl.r + ColorMath.encode(clamp01(tmp[0])) * (wh.r - bl.r)
        out[1] = bl.g + ColorMath.encode(clamp01(tmp[1])) * (wh.g - bl.g)
        out[2] = bl.b + ColorMath.encode(clamp01(tmp[2])) * (wh.b - bl.b)
    }

    /**
     * Bake a size^3 RGB float LUT. Index order: r fastest, then g, then b
     * (matches GL 3D texture layout x=r, y=g, z=b).
     */
    fun bakeLut(size: Int): FloatArray {
        val data = FloatArray(size * size * size * 3)
        val inv = 1f / (size - 1)
        IntStream.range(0, size).parallel().forEach { bi ->
            val out = FloatArray(3)
            val tmp = FloatArray(3)
            for (gi in 0 until size) for (ri in 0 until size) {
                apply(ri * inv, gi * inv, bi * inv, out, tmp)
                val idx = ((bi * size + gi) * size + ri) * 3
                data[idx] = out[0]; data[idx + 1] = out[1]; data[idx + 2] = out[2]
            }
        }
        return data
    }
}
