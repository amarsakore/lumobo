package app.lumobo.film

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AccuracyTest {
    @Test
    fun fastTransferMatchesExact() {
        var worst = 0f
        for (i in 0..100000) {
            val v = i / 100000f
            worst = maxOf(worst, abs(ColorMath.decode(v) - ColorMath.srgbToLinear(v)))
            worst = maxOf(worst, abs(ColorMath.encode(v) - ColorMath.linearToSrgb(v)))
        }
        println("max transfer error = $worst (1/255 = ${1f / 255})")
        assertTrue(worst < 0.1f / 255f)
    }

    @Test
    fun originalIsIdentityAndLutIsSane() {
        val look = CompiledLook(Profiles.ORIGINAL.look)
        val out = FloatArray(3)
        var worst = 0f
        for (r in 0..16) for (g in 0..16) for (b in 0..16) {
            look.apply(r / 16f, g / 16f, b / 16f, out)
            worst = maxOf(worst, abs(out[0] - r / 16f), abs(out[1] - g / 16f), abs(out[2] - b / 16f))
        }
        println("identity max error = $worst")
        assertTrue(worst < 0.5f / 255f)
        val t0 = System.nanoTime()
        for (p in Profiles.all) {
            val lut = CompiledLook(p.look).bakeLut(33)
            assertTrue(p.id, lut.all { it in 0f..1f && !it.isNaN() })
        }
        println("baked ${Profiles.all.size} LUTs (33^3) in ${(System.nanoTime() - t0) / 1_000_000}ms")
    }
}

class GeometryAndCurvesTest {
    @Test
    fun curvesAreMonotoneAndIdentityByDefault() {
        val c = Curves()
        val baked = c.bake(256)
        for (i in 0 until 256) assertTrue(abs(baked[i * 4] - i / 255f) < 1e-4f)
        val s = listOf(Pt(0f, 0f), Pt(0.25f, 0.15f), Pt(0.75f, 0.9f), Pt(1f, 1f))
        var prev = -1f
        for (i in 0..1000) {
            val y = Curves.sample(s, i / 1000f)
            assertTrue("monotone", y >= prev - 1e-6f)
            prev = y
        }
    }

    @Test
    fun fillScaleCoversFrame() {
        assertTrue(Geometry().fillScale(0.75f) == 1f)
        val g = Geometry(angle = 10f, perspV = 0.6f, perspH = -0.3f)
        val z = g.fillScale(0.75f)
        assertTrue(z > 1f && z < 4f)
        val straight = Geometry(angle = 10f).fillScale(0.75f)
        val th = Math.toRadians(10.0)
        val analytic = (kotlin.math.cos(th) + (1 / 0.75) * kotlin.math.sin(th)).toFloat()
        assertTrue("got $straight want $analytic", abs(straight - analytic) < 1e-3f)
    }
}
