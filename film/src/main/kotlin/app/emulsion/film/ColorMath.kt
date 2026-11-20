package app.emulsion.film

import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Small colour-science helpers shared by the CPU model (LUT baking, thumbnails, tests). */
object ColorMath {

    fun srgbToLinear(v: Float): Float =
        if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

    fun linearToSrgb(v: Float): Float {
        if (v <= 0f) return 0f
        return if (v <= 0.0031308f) v * 12.92f else (1.055 * v.toDouble().pow(1.0 / 2.4) - 0.055).toFloat()
    }

    private const val DN = 4096
    private val DEC = FloatArray(DN + 2) { srgbToLinear((it.coerceAtMost(DN)) / DN.toFloat()) }
    private val ENC = FloatArray(DN + 2) { val u = it.coerceAtMost(DN) / DN.toFloat(); linearToSrgb(u * u) }

    /** Table-driven sRGB decode for [0, 1] input (max error ~1e-6). */
    fun decode(v: Float): Float {
        val f = clamp01(v) * DN
        val i = f.toInt()
        return DEC[i] + (DEC[i + 1] - DEC[i]) * (f - i)
    }

    /** Table-driven sRGB encode, indexed in sqrt domain so the steep toe stays accurate. */
    fun encode(x: Float): Float {
        if (x <= 0f) return 0f
        val f = kotlin.math.sqrt(if (x > 1f) 1f else x) * DN
        val i = f.toInt()
        return ENC[i] + (ENC[i + 1] - ENC[i]) * (f - i)
    }

    /** Rec.709 / sRGB relative luminance of linear RGB. */
    fun luminance(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b

    /** Linear sRGB -> OKLab, written into [out] as (L, a, b). */
    fun linearToOklab(r: Float, g: Float, b: Float, out: FloatArray) {
        val l = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b
        val m = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b
        val s = 0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b
        val l_ = cbrt(l)
        val m_ = cbrt(m)
        val s_ = cbrt(s)
        out[0] = 0.2104542553f * l_ + 0.7936177850f * m_ - 0.0040720468f * s_
        out[1] = 1.9779984951f * l_ - 2.4285922050f * m_ + 0.4505937099f * s_
        out[2] = 0.0259040371f * l_ + 0.7827717662f * m_ - 0.8086757660f * s_
    }

    /** OKLab -> linear sRGB (unclamped), written into [out]. */
    fun oklabToLinear(L: Float, a: Float, b: Float, out: FloatArray) {
        val l_ = L + 0.3963377774f * a + 0.2158037573f * b
        val m_ = L - 0.1055613458f * a - 0.0638541728f * b
        val s_ = L - 0.0894841775f * a - 1.2914855480f * b
        val l = l_ * l_ * l_
        val m = m_ * m_ * m_
        val s = s_ * s_ * s_
        out[0] = 4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s
        out[1] = -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s
        out[2] = -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s
    }

    fun hueDeg(a: Float, b: Float): Float {
        val h = Math.toDegrees(atan2(b.toDouble(), a.toDouble())).toFloat()
        return if (h < 0f) h + 360f else h
    }

    fun chroma(a: Float, b: Float) = sqrt(a * a + b * b)

    fun polarA(c: Float, hDeg: Float) = c * cos(Math.toRadians(hDeg.toDouble())).toFloat()
    fun polarB(c: Float, hDeg: Float) = c * sin(Math.toRadians(hDeg.toDouble())).toFloat()

    /** Signed shortest distance between two hue angles in degrees, in [-180, 180]. */
    fun hueDelta(h: Float, center: Float): Float {
        var d = h - center
        while (d > 180f) d -= 360f
        while (d < -180f) d += 360f
        return d
    }

    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun clamp01(v: Float) = if (v < 0f) 0f else if (v > 1f) 1f else v
}

/**
 * Approximate OKLCh hue angles (degrees) of familiar colours, used to aim the per-hue
 * adjustments in film looks. Measured from sRGB swatches.
 */
object Hue {
    const val RED = 29f        // #FF0000
    const val CRIMSON = 15f
    const val ORANGE = 55f     // #FF8000
    const val SKIN = 58f       // typical skin tones sit ~45-70
    const val YELLOW = 105f    // #FFFF00 sits at ~110
    const val FOLIAGE = 128f   // yellow-green leaves
    const val GREEN = 142f     // #00FF00
    const val TEAL = 180f
    const val CYAN = 195f      // #00FFFF
    const val SKY = 238f       // daylight sky blue
    const val BLUE = 264f      // #0000FF
    const val PURPLE = 300f
    const val MAGENTA = 328f   // #FF00FF
}
