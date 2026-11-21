package app.lumobo.film

import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A point on a tone curve, both axes 0..1. */
data class Pt(val x: Float, val y: Float)

/** Point curves (Lightroom / Snapseed style). Master applies first, then each channel. */
data class Curves(
    val master: List<Pt> = IDENTITY,
    val red: List<Pt> = IDENTITY,
    val green: List<Pt> = IDENTITY,
    val blue: List<Pt> = IDENTITY,
) {
    val isIdentity get() = master == IDENTITY && red == IDENTITY && green == IDENTITY && blue == IDENTITY

    fun channel(i: Int): List<Pt> = when (i) { 0 -> master; 1 -> red; 2 -> green; else -> blue }
    fun with(i: Int, pts: List<Pt>): Curves = when (i) { 0 -> copy(master = pts); 1 -> copy(red = pts); 2 -> copy(green = pts); else -> copy(blue = pts) }

    /** [n] RGBA samples (interleaved), r = R(M(x)) etc. */
    fun bake(n: Int = 256): FloatArray {
        val out = FloatArray(n * 4)
        for (i in 0 until n) {
            val x = i / (n - 1f)
            val m = sample(master, x)
            out[i * 4] = sample(red, m)
            out[i * 4 + 1] = sample(green, m)
            out[i * 4 + 2] = sample(blue, m)
            out[i * 4 + 3] = 1f
        }
        return out
    }

    companion object {
        val IDENTITY = listOf(Pt(0f, 0f), Pt(1f, 1f))

        /** Monotone cubic (Fritsch–Carlson) through the points: smooth, never overshoots. */
        fun sample(pts: List<Pt>, x: Float): Float {
            if (pts.size < 2) return x
            if (x <= pts.first().x) return pts.first().y.coerceIn(0f, 1f)
            if (x >= pts.last().x) return pts.last().y.coerceIn(0f, 1f)
            val n = pts.size
            val d = FloatArray(n - 1) { (pts[it + 1].y - pts[it].y) / maxOf(1e-6f, pts[it + 1].x - pts[it].x) }
            val m = FloatArray(n)
            m[0] = d[0]; m[n - 1] = d[n - 2]
            for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
            for (i in 0 until n - 1) {
                if (d[i] == 0f) { m[i] = 0f; m[i + 1] = 0f; continue }
                val a = m[i] / d[i]
                val b = m[i + 1] / d[i]
                val h = a * a + b * b
                if (h > 9f) {
                    val t = 3f / kotlin.math.sqrt(h)
                    m[i] = t * a * d[i]; m[i + 1] = t * b * d[i]
                }
            }
            var k = 0
            while (k < n - 2 && x > pts[k + 1].x) k++
            val x0 = pts[k].x; val x1 = pts[k + 1].x
            val h = x1 - x0
            val t = (x - x0) / h
            val t2 = t * t; val t3 = t2 * t
            val y = (2 * t3 - 3 * t2 + 1) * pts[k].y + (t3 - 2 * t2 + t) * h * m[k] +
                (-2 * t3 + 3 * t2) * pts[k + 1].y + (t3 - t2) * h * m[k + 1]
            return y.coerceIn(0f, 1f)
        }
    }
}

/** Split-tone colour grading. Hues in degrees (0..360, HSV wheel), saturations 0..1. */
data class Grade(
    val shadowHue: Float = 220f,
    val shadowSat: Float = 0f,
    val midHue: Float = 40f,
    val midSat: Float = 0f,
    val highHue: Float = 45f,
    val highSat: Float = 0f,
    val balance: Float = 0f,
    val blending: Float = 0.5f,
) {
    val isIdentity get() = shadowSat == 0f && midSat == 0f && highSat == 0f
}

/** Glow looks. Lightroom's five (Bloom, Diffusion, Halation, Motion, Streak) plus Orton. */
enum class GlowStyle(val label: String, val blurb: String) {
    BLOOM("Bloom", "Bright lights spill a soft haze, in their own colour."),
    DIFFUSION("Diffusion", "Light scatters into the shadows. Dreamy, soft, gentler contrast."),
    HALATION("Halation", "Film halation. Red-orange halos hug the brightest lights."),
    ORTON("Orton", "The classic Orton glow. Luminous, painterly, rich colour."),
    MOTION("Motion", "Light trails off one side of each light, like a moving camera."),
    STREAK("Streak", "Anamorphic streaks run through each light, both ways."),
    ;
    val directional get() = this == MOTION || this == STREAK

    /** Starting Range when this look is first switched on. */
    val defaultRange get() = when (this) {
        BLOOM -> 0.4f; DIFFUSION -> 0.75f; HALATION -> 0.3f; ORTON -> 0.5f; MOTION, STREAK -> 0.25f
    }
}

/**
 * Glow: light spreading from the bright parts of the photo, as one of [GlowStyle].
 * Beyond Lightroom: each light can glow in its own colour (Lightroom's Bloom and Streak are
 * white only), Warmth and Tint give full colour control (Halation too), there's an Orton look,
 * and the light that glows can be shown on its own while adjusting.
 */
data class Glow(
    val style: GlowStyle = GlowStyle.BLOOM,
    val amount: Float = 0f,      // 0..1
    val range: Float = 0.4f,     // 0..1: 0 only the brightest lights glow, 1 reaches into the midtones
    val spread: Float = 0.5f,    // 0..1: how far the light reaches (streak length for Motion / Streak)
    val warmth: Float = 0f,      // -1..1 cool .. warm (Halation: yellow .. deep red)
    val tint: Float = 0f,        // -1..1 green .. magenta
    val color: Float = 1f,       // 0..1: 0 white light, 1 each light glows in its own colour
    val direction: Float = 0f,   // degrees 0..360 on the photo, 0 = to the right (Motion / Streak)
    val softness: Float = 0.15f, // 0..1 streak width (Motion / Streak)
) {
    val isOn get() = amount > 0f

    /**
     * Switch look. A glow that was off starts at a visible amount with the look's own Range; a
     * Range the user hasn't touched follows the new look (Diffusion's wide range would flood a
     * Streak), while one they set is kept.
     */
    fun withStyle(s: GlowStyle) = when {
        !isOn -> copy(style = s, amount = 0.5f, range = s.defaultRange)
        range == style.defaultRange -> copy(style = s, range = s.defaultRange)
        else -> copy(style = s)
    }
}

/** User adjustments layered on top of a film profile. Bipolar values are -1..1, others 0..1. */
data class Adjustments(
    val intensity: Float = 1f,
    // Light
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    // Colour
    val temp: Float = 0f,
    val tint: Float = 0f,
    val vibrance: Float = 0f,
    val saturation: Float = 0f,
    val fade: Float = 0f,
    // Detail & local
    val texture: Float = 0f,
    val clarity: Float = 0f,
    val dehaze: Float = 0f,
    val sharpen: Float = 0f,
    val hdr: Float = 0f,
    val glow: Glow = Glow(),
    /** 0..1: how much of the photo's HDR light (its gain map, or an estimate) the edit shows and saves. */
    val hdrLight: Float = 1f,
    // Colour mixer: 8 bands x (hue, saturation, luminance), each -1..1
    val mixer: List<Float> = MIXER_ZERO,
    val grade: Grade = Grade(),
    val curves: Curves = Curves(),
) {
    val mixerActive get() = mixer.any { it != 0f }

    fun mixerValue(band: Int, kind: Int) = mixer[band * 3 + kind]
    fun withMixer(band: Int, kind: Int, v: Float) = copy(mixer = mixer.toMutableList().also { it[band * 3 + kind] = v })

    companion object {
        val MIXER_ZERO: List<Float> = List(24) { 0f }
        val MIXER_NAMES = listOf("Red", "Orange", "Yellow", "Green", "Aqua", "Blue", "Purple", "Magenta")
        /** OKLCh hue centre of each mixer band (degrees). */
        val MIXER_HUES = floatArrayOf(29f, 58f, 108f, 142f, 195f, 264f, 296f, 333f)
    }
}

/** Thread-safe, size-bounded cache of baked LUTs keyed by profile id. */
class LutCache(private val size: Int = 64, private val capacity: Int = 10) {
    class Lut(val id: String, val size: Int, val data: FloatArray)

    private val map = object : LinkedHashMap<String, Lut>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lut>?) = this.size > capacity
    }
    private val compiled = HashMap<String, CompiledLook>()

    fun compiled(p: FilmProfile): CompiledLook = synchronized(compiled) {
        compiled.getOrPut(p.id) { CompiledLook(p.look) }
    }

    fun peek(p: FilmProfile): Lut? = synchronized(map) { map[p.id] }

    fun get(p: FilmProfile): Lut {
        synchronized(map) { map[p.id]?.let { return it } }
        val lut = Lut(p.id, size, compiled(p).bakeLut(size))
        synchronized(map) { map[p.id] = lut }
        return lut
    }
}

/**
 * Tileable film-grain field. RGBA8, each channel an independent zero-mean unit-variance
 * noise (stored as 0.5 + n/6), lightly clumped with a periodic gaussian so it reads as
 * grain rather than digital per-pixel noise.
 */
object GrainField {
    fun generate(size: Int = 512, seed: Long = 1977L, clump: Float = 0.7f): ByteArray {
        val rnd = Random(seed)
        val n = size * size
        val out = ByteArray(n * 4)
        val kr = 3
        val kernel = FloatArray(2 * kr + 1) { i -> exp(-((i - kr) * (i - kr)) / (2f * clump * clump)) }
        val ks = kernel.sum()
        for (i in kernel.indices) kernel[i] /= ks
        val a = FloatArray(n)
        val b = FloatArray(n)
        for (ch in 0 until 4) {
            for (i in 0 until n) {
                // Box-Muller
                val u1 = rnd.nextDouble().coerceAtLeast(1e-12)
                val u2 = rnd.nextDouble()
                a[i] = (sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)).toFloat()
            }
            for (y in 0 until size) for (x in 0 until size) {
                var s = 0f
                for (k in -kr..kr) s += a[y * size + ((x + k + size) % size)] * kernel[k + kr]
                b[y * size + x] = s
            }
            var sum = 0.0
            var sq = 0.0
            for (y in 0 until size) for (x in 0 until size) {
                var s = 0f
                for (k in -kr..kr) s += b[((y + k + size) % size) * size + x] * kernel[k + kr]
                a[y * size + x] = s
                sum += s
                sq += s * s
            }
            val mean = sum / n
            val std = sqrt(sq / n - mean * mean)
            for (i in 0 until n) {
                val v = ((a[i] - mean) / std).toFloat().coerceIn(-3f, 3f)
                out[i * 4 + ch] = ((0.5f + v / 6f) * 255f).roundToInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }
}
