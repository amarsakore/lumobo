package app.lumobo.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import app.lumobo.film.Character
import app.lumobo.film.CharacterLevels
import app.lumobo.film.Effects
import app.lumobo.film.FilmProfile
import app.lumobo.film.FrameStyle
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** User preferences, persisted in SharedPreferences and observable from Compose. */
class AppSettings private constructor(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("lumobo_settings", Context.MODE_PRIVATE)

    // ── Film character
    /** 0 Off (each film's own texture only), 1 Subtle 75%, 2 Natural 100%, 3 Dreamy 125%. */
    var characterStyle by int("character_style", 2)

    // ── Appearance: 0 follow the system, 1 light, 2 dark
    var themeMode by int("theme_mode", 0)
    /** Accent from the wallpaper (Material You) on Android 12+. */
    var dynamicAccent by bool("dynamic_accent", true)
    /** Show HDR photos in HDR while editing (HDR screens, Android 14+). Off still uses the HDR
     *  data for light, stars and glow, and still saves Ultra HDR. */
    var hdrView by bool("hdr_view", true)

    // ── New edits
    var lastFilmId by string("last_film", "original")
    var dateStampDefault by bool("date_default", false)
    var dateColor by int("date_color", 0)
    var dateFormat by int("date_format", 0)
    var defaultFrame by string("default_frame", FrameStyle.NONE.name)

    // ── Export
    var jpegQuality by int("jpeg_quality", 96)
    var exportSize by int("export_size", 0)
    var keepLocation by bool("keep_location", true)
    var keepCameraInfo by bool("keep_camera_info", true)

    // ── Gallery & app
    var gridColumns by int("grid_columns", 3)
    var showScreenshots by bool("show_screenshots", true)
    var haptics by bool("haptics", true)

    val levels: CharacterLevels get() {
        val l = when (characterStyle) { 1 -> 0.75f; 3 -> 1.25f; else -> 1f }
        return CharacterLevels(l, l, l)
    }

    /** Most pixels a developed photo may have, from [exportSize]. */
    val maxExportPixels: Long
        get() = when (exportSize) {
            1 -> 12_000_000L
            2 -> 8_000_000L
            3 -> 4_000_000L
            else -> 64_000_000L
        }

    /** Effects a film starts with, honouring the film-character and default settings. */
    fun effectsFor(p: FilmProfile): Effects {
        var e = Character.effects(p, characterStyle != 0, levels)
        if (dateStampDefault) e = e.copy(dateStamp = true)
        val frame = runCatching { FrameStyle.valueOf(defaultFrame) }.getOrDefault(FrameStyle.NONE)
        if (e.frame == FrameStyle.NONE && frame != FrameStyle.NONE) e = e.copy(frame = frame)
        return e
    }

    fun resetAll() {
        prefs.edit().clear().apply()
        instance = null
    }

    // ── delegates
    private fun bool(key: String, def: Boolean) = Pref(key, prefs.getBoolean(key, def)) { putBoolean(key, it) }
    private fun float(key: String, def: Float) = Pref(key, prefs.getFloat(key, def)) { putFloat(key, it) }
    private fun int(key: String, def: Int) = Pref(key, prefs.getInt(key, def)) { putInt(key, it) }
    private fun string(key: String, def: String) = Pref(key, prefs.getString(key, def) ?: def) { putString(key, it) }

    private inner class Pref<T>(val key: String, initial: T, val write: SharedPreferences.Editor.(T) -> Unit) : ReadWriteProperty<Any?, T> {
        private val state = mutableStateOf(initial)
        override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            state.value = value
            prefs.edit().apply { write(value) }.apply()
        }
    }

    companion object {
        @Volatile private var instance: AppSettings? = null
        fun get(context: Context): AppSettings =
            instance ?: synchronized(this) { instance ?: AppSettings(context.applicationContext).also { instance = it } }
    }
}
