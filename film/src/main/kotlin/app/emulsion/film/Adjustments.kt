package app.emulsion.film

import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** User adjustments layered on top of a film profile. Bipolar values are -1..1, others 0..1. */
data class Adjustments(
    val intensity: Float = 1f,
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val temp: Float = 0f,
    val tint: Float = 0f,
    val saturation: Float = 0f,
    val fade: Float = 0f,
)

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
