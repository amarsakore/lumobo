package app.lumobo.editor

import android.net.Uri
import app.lumobo.film.Adjustments
import app.lumobo.film.Effects
import app.lumobo.film.FrameStyle
import app.lumobo.film.Geometry
import org.json.JSONObject

/**
 * Everything needed to re-open a developed photo exactly where it was left: the original
 * photo, the film, every slider, effects, frame and crop. Stored in the developed JPEG's
 * EXIF UserComment, so it travels with the file.
 */
data class Recipe(
    val source: Uri?,
    val filmId: String,
    val adj: Adjustments,
    val fx: Effects,
    val geo: Geometry,
) {
    fun encode(): String = PREFIX + JSONObject().apply {
        put("v", 1)
        source?.let { put("src", it.toString()) }
        put("film", filmId)
        put("adj", JSONObject().apply {
            put("i", adj.intensity.toDouble()); put("e", adj.exposure.toDouble()); put("c", adj.contrast.toDouble())
            put("h", adj.highlights.toDouble()); put("s", adj.shadows.toDouble()); put("t", adj.temp.toDouble())
            put("n", adj.tint.toDouble()); put("sat", adj.saturation.toDouble()); put("f", adj.fade.toDouble())
        })
        put("fx", JSONObject().apply {
            put("g", fx.grain.toDouble()); put("gs", fx.grainSize.toDouble()); put("cg", fx.colorGrain.toDouble())
            put("ha", fx.halation.toDouble()); put("b", fx.bloom.toDouble()); put("v", fx.vignette.toDouble())
            put("ab", fx.aberration.toDouble()); put("l", fx.leak.toDouble()); put("ls", fx.leakStyle)
            put("d", fx.dateStamp); put("fr", fx.frame.name)
            put("lz", fx.lens); put("di", fx.distortion.toDouble()); put("fc", fx.fisheyeCircle)
            put("st", fx.star.toDouble()); put("sp", fx.starPoints); put("sl", fx.starLength.toDouble()); put("sa", fx.starAngle.toDouble())
            put("sk", fx.streak.toDouble()); put("ts", fx.tiltShift.toDouble()); put("fy", fx.focusY.toDouble()); put("fs", fx.focusSize.toDouble())
            put("sf", fx.softFocus.toDouble()); put("sw", fx.swirl.toDouble())
        })
        put("geo", JSONObject().apply {
            put("r", geo.rotation); put("l", geo.left.toDouble()); put("t", geo.top.toDouble())
            put("ri", geo.right.toDouble()); put("b", geo.bottom.toDouble()); put("a", geo.aspect)
            put("an", geo.angle.toDouble()); put("fh", geo.flipH); put("fv", geo.flipV)
        })
    }.toString()

    companion object {
        const val PREFIX = "LUMOBO:"
        private const val LEGACY_PREFIX = "EMULSION:"

        fun decode(text: String?): Recipe? {
            if (text == null) return null
            val prefix = if (text.contains(PREFIX)) PREFIX else LEGACY_PREFIX
            val i = text.indexOf(prefix)
            if (i < 0) return null
            return runCatching {
                val o = JSONObject(text.substring(i + prefix.length).trim().trimEnd('\u0000'))
                fun JSONObject.f(k: String, d: Float) = optDouble(k, d.toDouble()).toFloat()
                val a = o.optJSONObject("adj") ?: JSONObject()
                val x = o.optJSONObject("fx") ?: JSONObject()
                val g = o.optJSONObject("geo") ?: JSONObject()
                Recipe(
                    source = o.optString("src").takeIf { it.isNotBlank() }?.let(Uri::parse),
                    filmId = o.optString("film", "original"),
                    adj = Adjustments(
                        intensity = a.f("i", 1f), exposure = a.f("e", 0f), contrast = a.f("c", 0f),
                        highlights = a.f("h", 0f), shadows = a.f("s", 0f), temp = a.f("t", 0f),
                        tint = a.f("n", 0f), saturation = a.f("sat", 0f), fade = a.f("f", 0f),
                    ),
                    fx = Effects(
                        grain = x.f("g", 0f), grainSize = x.f("gs", 0.35f), colorGrain = x.f("cg", 0.35f),
                        halation = x.f("ha", 0f), bloom = x.f("b", 0f), vignette = x.f("v", 0f),
                        aberration = x.f("ab", 0f), leak = x.f("l", 0f), leakStyle = x.optInt("ls", 0),
                        dateStamp = x.optBoolean("d", false),
                        frame = runCatching { FrameStyle.valueOf(x.optString("fr", "NONE")) }.getOrDefault(FrameStyle.NONE),
                        lens = x.optInt("lz", 0), distortion = x.f("di", 0f), fisheyeCircle = x.optBoolean("fc", false),
                        star = x.f("st", 0f), starPoints = x.optInt("sp", 4), starLength = x.f("sl", 0.5f), starAngle = x.f("sa", 0f),
                        streak = x.f("sk", 0f), tiltShift = x.f("ts", 0f), focusY = x.f("fy", 0.55f), focusSize = x.f("fs", 0.25f),
                        softFocus = x.f("sf", 0f), swirl = x.f("sw", 0f),
                    ),
                    geo = Geometry(
                        rotation = g.optInt("r", 0), left = g.f("l", 0f), top = g.f("t", 0f),
                        right = g.f("ri", 1f), bottom = g.f("b", 1f), aspect = g.optString("a", "original"),
                        angle = g.f("an", 0f), flipH = g.optBoolean("fh", false), flipV = g.optBoolean("fv", false),
                    ),
                )
            }.getOrNull()
        }
    }
}
