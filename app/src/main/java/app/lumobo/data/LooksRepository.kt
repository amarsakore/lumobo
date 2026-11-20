package app.lumobo.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import app.lumobo.editor.Recipe
import app.lumobo.film.Adjustments
import app.lumobo.film.Effects
import app.lumobo.film.Geometry
import org.json.JSONArray
import org.json.JSONObject

/** A user-saved look: a film plus every slider, effect and lens setting (no crop). */
data class SavedLook(val id: Long, val name: String, val filmId: String, val adj: Adjustments, val fx: Effects)

/** "My Looks", stored as edit recipes in SharedPreferences and observable from Compose. */
class LooksRepository private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("lumobo_looks", Context.MODE_PRIVATE)
    val looks = mutableStateListOf<SavedLook>()

    init {
        runCatching {
            val arr = JSONArray(prefs.getString("looks", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val r = Recipe.decode(o.getString("recipe")) ?: continue
                looks += SavedLook(o.getLong("id"), o.getString("name"), r.filmId, r.adj, r.fx.copy(dateStamp = false, starMask = emptyList()))
            }
        }
    }

    fun add(name: String, filmId: String, adj: Adjustments, fx: Effects): SavedLook {
        // A look is the film + settings; per-photo star-eraser marks stay with the photo.
        val look = SavedLook(System.currentTimeMillis(), name.ifBlank { "My look ${looks.size + 1}" }, filmId, adj, fx.copy(starMask = emptyList()))
        looks.add(0, look)
        persist()
        return look
    }

    fun remove(id: Long) {
        looks.removeAll { it.id == id }
        persist()
    }

    private fun persist() {
        val arr = JSONArray()
        for (l in looks) {
            arr.put(JSONObject().apply {
                put("id", l.id)
                put("name", l.name)
                put("recipe", Recipe(null, l.filmId, l.adj, l.fx, Geometry()).encode())
            })
        }
        prefs.edit().putString("looks", arr.toString()).apply()
    }

    companion object {
        @Volatile private var instance: LooksRepository? = null
        fun get(context: Context): LooksRepository =
            instance ?: synchronized(this) { instance ?: LooksRepository(context.applicationContext).also { instance = it } }
    }
}
