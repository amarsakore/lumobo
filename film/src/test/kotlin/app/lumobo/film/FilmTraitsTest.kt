package app.lumobo.film

import org.junit.Test
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Measures each look on a ColorChecker chart and a grey ramp, so a film's character
 * (contrast, black / white points, casts, saturation, dye density and hue shifts of skin,
 * sky, foliage and primaries) can be compared with the real stock and kept from drifting.
 *
 * Run: ./gradlew :film:test --tests '*FilmTraitsTest*' -i   (prints the table)
 */
class FilmTraitsTest {

    data class Traits(
        val contrast: Float,   // OKLab L slope across the mid-greys (1 = unchanged)
        val black: Float,      // output L of pure black (lifted blacks > 0)
        val white: Float,      // output L of pure white
        val castS: Pair<Float, Float>, // (hue, chroma) of the shadow grey
        val castM: Pair<Float, Float>,
        val castH: Pair<Float, Float>,
        val sat: Float,        // mean chroma ratio over the colour patches
        val density: Float,    // mean L change of saturated patches (negative = denser, film-like)
        val shifts: Map<String, Pair<Float, Float>>, // patch -> (hue shift deg, chroma ratio)
    )

    private fun lab(r: Int, g: Int, b: Int): FloatArray {
        val o = FloatArray(3)
        ColorMath.linearToOklab(ColorMath.srgbToLinear(r / 255f), ColorMath.srgbToLinear(g / 255f), ColorMath.srgbToLinear(b / 255f), o)
        return o
    }

    private fun out(look: CompiledLook, r: Int, g: Int, b: Int): FloatArray {
        val o = FloatArray(3)
        look.apply(r / 255f, g / 255f, b / 255f, o)
        return lab((o[0] * 255f).toInt().coerceIn(0, 255), (o[1] * 255f).toInt().coerceIn(0, 255), (o[2] * 255f).toInt().coerceIn(0, 255))
    }

    fun measure(p: FilmProfile): Traits {
        val look = CompiledLook(p.look)
        fun grey(v: Int) = out(look, v, v, v)
        val g35 = grey(85); val g50 = grey(122); val g65 = grey(160); val g80 = grey(200)
        val contrast = (g65[0] - g35[0]) / (lab(160, 160, 160)[0] - lab(85, 85, 85)[0])
        fun cast(o: FloatArray) = ColorMath.hueDeg(o[1], o[2]) to ColorMath.chroma(o[1], o[2])
        var satSum = 0f; var n = 0
        var dSum = 0f; var dn = 0
        val shifts = LinkedHashMap<String, Pair<Float, Float>>()
        for ((name, rgb) in PATCHES) {
            val i = lab(rgb[0], rgb[1], rgb[2])
            val o = out(look, rgb[0], rgb[1], rgb[2])
            val ci = ColorMath.chroma(i[1], i[2])
            val co = ColorMath.chroma(o[1], o[2])
            if (ci > 0.04f) { satSum += co / ci; n++ }
            if (ci > 0.12f) { dSum += o[0] - i[0]; dn++ }
            if (name in KEY) {
                val dh = ColorMath.hueDelta(ColorMath.hueDeg(o[1], o[2]), ColorMath.hueDeg(i[1], i[2]))
                shifts[name] = dh to co / ci
            }
        }
        // Density relative to the grey response, so a brighter curve isn't read as "less dense".
        val greyLift = g50[0] - lab(122, 122, 122)[0]
        return Traits(
            contrast, grey(0)[0], grey(255)[0], cast(g35), cast(g50), cast(g80),
            satSum / n, dSum / dn - greyLift, shifts,
        )
    }

    @Test
    fun printTraits() {
        val sb = StringBuilder()
        sb.append(String.format("%-16s %5s %5s %5s %5s %5s  %-11s %-11s %-11s %s\n", "look", "con", "blk", "wht", "sat", "dens", "cast-shad", "cast-mid", "cast-high", "skin | sky | foliage | red | yellow | green | blue"))
        for (p in Profiles.all) {
            if (p.isIdentity || p.look.mono != null) continue
            val t = measure(p)
            fun c(x: Pair<Float, Float>) = if (x.second < 0.006f) "  ·        " else String.format("%4.0f°/%.3f", x.first, x.second)
            val sh = KEY.joinToString(" | ") { k -> t.shifts[k]!!.let { String.format("%+3.0f°x%.2f", it.first, it.second) } }
            sb.append(String.format("%-16s %5.2f %5.2f %5.2f %5.2f %+5.3f  %s %s %s  %s\n", p.id.take(16), t.contrast, t.black, t.white, t.sat, t.density, c(t.castS), c(t.castM), c(t.castH), sh))
        }
        println(sb)
    }

    companion object {
        /** ColorChecker Classic, sRGB (D65). */
        val PATCHES = linkedMapOf(
            "darkskin" to intArrayOf(115, 82, 68), "skin" to intArrayOf(194, 150, 130), "sky" to intArrayOf(98, 122, 157),
            "foliage" to intArrayOf(87, 108, 67), "blueflower" to intArrayOf(133, 128, 177), "bluegreen" to intArrayOf(103, 189, 170),
            "orange" to intArrayOf(214, 126, 44), "purpleblue" to intArrayOf(80, 91, 166), "modred" to intArrayOf(193, 90, 99),
            "purple" to intArrayOf(94, 60, 108), "yellowgreen" to intArrayOf(157, 188, 64), "orangeyellow" to intArrayOf(224, 163, 46),
            "blue" to intArrayOf(56, 61, 150), "green" to intArrayOf(70, 148, 73), "red" to intArrayOf(175, 54, 60),
            "yellow" to intArrayOf(231, 199, 31), "magenta" to intArrayOf(187, 86, 149), "cyan" to intArrayOf(8, 133, 161),
        )
        val KEY = listOf("skin", "sky", "foliage", "red", "yellow", "green", "blue")
    }
}
