package app.lumobo.data

import android.content.Context
import app.lumobo.editor.Recipe

/**
 * Auto-saved edits, one per photo, kept on the phone (never in the gallery). Opening the
 * photo again picks up exactly where you left off; only Save writes a developed copy.
 */
class Drafts private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("lumobo_drafts", Context.MODE_PRIVATE)

    fun load(key: String): Recipe? = Recipe.decode(prefs.getString(key, null))

    fun save(key: String, recipe: Recipe) {
        prefs.edit().putString(key, recipe.encode()).apply()
    }

    fun clear(key: String) {
        prefs.edit().remove(key).apply()
    }

    companion object {
        @Volatile private var instance: Drafts? = null
        fun get(context: Context): Drafts =
            instance ?: synchronized(this) { instance ?: Drafts(context.applicationContext).also { instance = it } }
    }
}
