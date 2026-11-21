package app.lumobo.render

import android.graphics.Bitmap
import app.lumobo.film.ColorMath
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The photo's light sources, found once per photo the way a star filter "sees" them: each
 * separate point of light (a lamp, the sun, a glint on water or a bead) is one source, with its
 * brightness, its total light (density: how bright and how big), its size and colour. A star
 * filter turns each source into one star whose brightness follows the light and whose visible
 * size follows its total output, so stars are drawn per source instead of smearing every bright
 * pixel along the rays (which turns sparkly subjects into a mesh).
 */
internal class LightMap(
    val w: Int,
    val h: Int,
    private val r: FloatArray,   // linear SDR colour of the photo, as shot
    private val g: FloatArray,
    private val b: FloatArray,
    private val head: FloatArray, // HDR headroom in stops above the scene's own lift (gain map or estimate)
) {
    class Light(
        val x: Float, val y: Float,       // centre, source uv
        val peak: Float,                  // brightness at the core (1 = SDR white, more with HDR headroom)
        val energy: Float,                // total light of the source (density), in analysis pixels
        val radius: Float,                // core radius, source uv of the long side
        val cr: Float, val cg: Float, val cb: Float, // colour (max channel 1)
        val point: Float = 1f,            // 1 a point light, less for a long one (trail, tube)
    )

    /**
     * Light sources at the photographer's exposure / white balance [gain] (linear RGB), at least
     * [threshold] (0..1 slider) bright, strongest first, at most [max]. [mask] holds erased
     * discs (x, y, rx triples in source uv).
     */
    fun find(gain: FloatArray, threshold: Float, max: Int, mask: List<Float>): List<Light> {
        val n = w * h
        val v = FloatArray(n)
        // Light level: the brightest channel counts (coloured lights too), times HDR headroom.
        for (i in 0 until n) {
            val lr = r[i] * gain[0]; val lg = g[i] * gain[1]; val lb = b[i] * gain[2]
            val lum = 0.2126f * lr + 0.7152f * lg + 0.0722f * lb
            v[i] = max(lum, 0.85f * max(lr, max(lg, lb))) * 2f.pow(head[i])
        }
        val vs = boxBlur(v, w, h, 1)
        // Light density: how much light gathers around each point (a wide neighbourhood). A
        // light source sits where its light is densest, the centre of its glow (the sun inside
        // its halo), not on whatever single pixel clipped brightest beside it.
        val rw = max(2, max(w, h) / 48)
        val vw = boxBlur(boxBlur(v, w, h, rw), w, h, rw)
        val t = threshold.coerceIn(0f, 1f)
        // A light source must outshine the scene's own brightest surfaces (its top 3%): white
        // paint, a sunlit wall or sky make no star, while the sun, lamps and specular glints do.
        // At night that bar is low and the absolute one (light above SDR white) decides.
        val sorted = vs.copyOf().also { it.sort() }
        val p97 = sorted[(n * 0.97f).toInt().coerceIn(0, n - 1)]
        // The brightest light in the frame (the sun, the main lamp) always qualifies.
        val vMax = sorted[n - 1]
        val tAbs = max(0.7f + 1.3f * t, kotlin.math.min(p97 * (1.15f + 0.8f * t), 0.8f * vMax))
        val grow = 0.75f * tAbs
        // Each connected bright blob is ONE light (a lamp, the sun with its glare, a light
        // trail, one glint), as a filter sees it: one star each.
        val label = IntArray(n)
        val queue = IntArray(n)
        val out = ArrayList<Light>()
        var id = 0
        var rejected = 0
        var mainCand: Light? = null
        for (s0 in 0 until n) {
            if (vs[s0] < tAbs || label[s0] != 0) continue
            id++
            var qh = 0; var qt = 0
            queue[qt++] = s0; label[s0] = id
            var area = 0; var e = 0f; var peak = 0f; var pi = s0; var dens = -1f
            var mx = 0.0; var my = 0.0; var mxx = 0.0; var myy = 0.0; var mxy = 0.0
            while (qh < qt) {
                val i = queue[qh++]
                val x = i % w; val y = i / w
                area++; e += v[i]
                if (vs[i] > peak) peak = vs[i]
                if (vw[i] > dens) { dens = vw[i]; pi = i }
                mx += x; my += y; mxx += x.toDouble() * x; myy += y.toDouble() * y; mxy += x.toDouble() * y
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val xx = x + dx; val yy = y + dy
                    if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                    val j = yy * w + xx
                    if (label[j] == 0 && vs[j] >= grow) { label[j] = id; queue[qt++] = j }
                }
            }
            // Shape: how elongated the blob is (a light trail or neon tube vs a point).
            val cxm = mx / area; val cym = my / area
            val sxx = mxx / area - cxm * cxm; val syy = myy / area - cym * cym; val sxy = mxy / area - cxm * cym
            val tr = sxx + syy; val det = sxx * syy - sxy * sxy
            val disc = sqrt(max(tr * tr / 4 - det, 0.0))
            val l1 = max(tr / 2 + disc, 0.25); val l2 = max(tr / 2 - disc, 0.25)
            val elong = sqrt(l1 / l2).toFloat()
            // Its core, around the densest point: where the star starts, its size and colour.
            val px0 = pi % w; val py0 = pi / w
            var core = 0; var sx = 0f; var sy = 0f; var cw = 0f
            var cr = 0f; var cg = 0f; var cb = 0f
            for (dy in -10..10) for (dx in -10..10) {
                val xx = px0 + dx; val yy = py0 + dy
                if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                val j = yy * w + xx
                if (label[j] != id) continue
                val q = vs[j]
                if (q >= 0.8f * vs[pi]) {
                    core++; sx += xx * q; sy += yy * q; cw += q
                    cr += r[j] * q; cg += g[j] * q; cb += b[j] * q
                }
            }
            if (core == 0 || cw <= 0f) continue
            sx /= cw; sy /= cw
            val rad = sqrt(core / PI.toFloat()).coerceAtMost(8f)
            // It must outshine its wider neighbourhood (an eighth of the frame around it): the
            // sun or a lamp does, even inside its own glare; a lit wall or sky doesn't.
            val box = max(w, h) / 8
            val samples = ArrayList<Float>()
            var yy = max(0, py0 - box)
            while (yy <= kotlin.math.min(h - 1, py0 + box)) {
                var xx = max(0, px0 - box)
                while (xx <= kotlin.math.min(w - 1, px0 + box)) { samples.add(v[yy * w + xx]); xx += 3 }
                yy += 3
            }
            // The median, so the light's own glare or a narrow bright window beside it doesn't
            // count; a wall or sky filling the area does.
            samples.sort()
            val around = if (samples.isNotEmpty()) samples[samples.size / 2] else 0f
            val u = (sx + 0.5f) / w; val vv = (sy + 0.5f) / h
            if (erased(u, vv, mask)) continue
            val m = max(cr, max(cg, cb)).coerceAtLeast(1e-6f)
            // A point light makes a full star; a long light (trail, tube) spreads its rays along
            // its length, so its star is fainter.
            val point = (1f / elong.pow(0.75f)).coerceIn(0.15f, 1f)
            val light = Light(u, vv, peak, e * point, rad / max(w, h), cr / m, cg / m, cb / m, point)
            if (peak < 1.6f * around + 0.05f) {
                // Doesn't stand out. Still the frame's main light if it's the strongest, not a
                // huge area (sky, wall) and at least a little above its surroundings (a hazy sun
                // behind glass, swamped by its own glow).
                rejected++
                if (area < n / 4 && peak >= 1.12f * around && (mainCand == null || e * peak > mainCand!!.energy * mainCand!!.peak)) mainCand = light
                continue
            }
            out.add(light)
        }
        mainCand?.let { c -> if (out.none { it.energy * it.peak >= c.energy * c.peak }) out.add(c) }
        out.sortByDescending { it.energy * it.peak }
        android.util.Log.i("LumoboGL", "lights: p97 %.2f max %.2f bar %.2f blobs %d kept %d dim %d".format(p97, vMax, tAbs, id, out.size, rejected))
        return if (out.size > max) out.subList(0, max).toList() else out
    }

    private fun erased(u: Float, v: Float, mask: List<Float>): Boolean {
        val aspect = w.toFloat() / h
        var i = 0
        while (i + 2 < mask.size) {
            val rx = mask[i + 2]
            val dx = (u - mask[i]) / rx
            val dy = (v - mask[i + 1]) / (rx * aspect)
            if (dx * dx + dy * dy < 0.8f * 0.8f) return true
            i += 3
        }
        return false
    }

    companion object {
        private const val LONG = 384

        /**
         * Analyses [bmp] (the working photo) with its gain map: [gainLog] holds the log2
         * ratio min / max and gamma per channel, [base] the photo's median boost.
         */
        fun build(bmp: Bitmap, gainContents: Bitmap?, gainSingle: Boolean, gainLog: FloatArray, base: Float): LightMap {
            val s = LONG.toFloat() / max(bmp.width, bmp.height)
            val w = max(8, (bmp.width * s).roundToInt())
            val h = max(8, (bmp.height * s).roundToInt())
            val small = Bitmap.createScaledBitmap(bmp, w, h, true)
            val px = IntArray(w * h)
            small.getPixels(px, 0, w, 0, 0, w, h)
            if (small !== bmp) small.recycle()
            val r = FloatArray(w * h); val g = FloatArray(w * h); val b = FloatArray(w * h)
            for (i in px.indices) {
                val c = px[i]
                r[i] = ColorMath.decode(((c shr 16) and 255) / 255f)
                g[i] = ColorMath.decode(((c shr 8) and 255) / 255f)
                b[i] = ColorMath.decode((c and 255) / 255f)
            }
            val head = FloatArray(w * h)
            if (gainContents != null) {
                // Read the gain map: stops of boost above the photo's own lift.
                val gw = gainContents.width; val gh = gainContents.height
                val gp = IntArray(gw * gh)
                gainContents.getPixels(gp, 0, gw, 0, 0, gw, gh)
                val lmin = gainLog[1]; val lmax = gainLog[4]; val gamma = gainLog[7]
                for (y in 0 until h) for (x in 0 until w) {
                    val gx = ((x + 0.5f) / w * gw).toInt().coerceIn(0, gw - 1)
                    val gy = ((y + 0.5f) / h * gh).toInt().coerceIn(0, gh - 1)
                    val p = gp[gy * gw + gx]
                    val gv = (if (gainSingle) (p ushr 24) and 255 else (p shr 8) and 255) / 255f
                    val lb = lmin + (lmax - lmin) * gv.toDouble().pow(gamma.toDouble()).toFloat()
                    val i = y * w + x
                    val ls = 0.2126f * r[i] + 0.7152f * g[i] + 0.0722f * b[i]
                    head[i] = max(lb - base, 0f) * smooth(0.06f, 0.3f, ls)
                }
            } else {
                // No gain map: estimate it. Light that clipped and stands far above its wide
                // surroundings was brighter than the sensor could hold.
                val lum = FloatArray(w * h) { 0.2126f * r[it] + 0.7152f * g[it] + 0.0722f * b[it] }
                val wide = boxBlur(boxBlur(lum, w, h, 6), w, h, 6)
                for (i in 0 until w * h) {
                    val mx = max(r[i], max(g[i], b[i]))
                    val clip = smooth(0.80f, 0.98f, mx)
                    val lift = (ln((lum[i] + 0.02f) / (wide[i] + 0.02f)) / ln(2f)) * 0.8f
                    head[i] = clip * lift.coerceIn(0f, 3f)
                }
            }
            return LightMap(w, h, r, g, b, head)
        }

        private fun smooth(a: Float, b: Float, x: Float): Float {
            val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        /** Separable box blur of radius [rad] (edges clamped). */
        fun boxBlur(src: FloatArray, w: Int, h: Int, rad: Int): FloatArray {
            val tmp = FloatArray(w * h)
            val out = FloatArray(w * h)
            val k = 1f / (2 * rad + 1)
            for (y in 0 until h) for (x in 0 until w) {
                var s = 0f
                for (d in -rad..rad) s += src[y * w + (x + d).coerceIn(0, w - 1)]
                tmp[y * w + x] = s * k
            }
            for (y in 0 until h) for (x in 0 until w) {
                var s = 0f
                for (d in -rad..rad) s += tmp[(y + d).coerceIn(0, h - 1) * w + x]
                out[y * w + x] = s * k
            }
            return out
        }
    }
}
