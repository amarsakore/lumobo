package app.lumobo.editor

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import app.lumobo.data.AppSettings
import app.lumobo.film.Adjustments
import app.lumobo.film.Geometry
import app.lumobo.film.Profiles
import app.lumobo.render.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Works out how to re-open a developed photo in the editor. */
object ReEdit {

    class Plan(val source: Uri, val recipe: Recipe?, val target: Uri?, val note: String?)

    suspend fun resolve(context: Context, developed: Uri): Plan = withContext(Dispatchers.IO) {
        val settings = AppSettings.get(context)
        val (filmName, recipe) = Images.readDeveloped(context, developed)
        val saved = recipe?.source
        if (recipe != null && saved != null && Images.isReadable(context, saved)) {
            return@withContext Plan(saved, recipe, developed, null)
        }
        val profile = recipe?.filmId?.let(Profiles::byId)
            ?: Profiles.all.firstOrNull { it.name.equals(filmName, ignoreCase = true) }
            ?: Profiles.ORIGINAL
        val original = findOriginal(context, developed)
        if (original != null) {
            val r = recipe?.copy(source = original)
                ?: Recipe(original, profile.id, Adjustments(), settings.effectsFor(profile), Geometry())
            val note = if (recipe == null) "Reopened ${profile.name} on the original. Earlier slider tweaks weren't recorded." else null
            return@withContext Plan(original, r, developed, note)
        }
        Plan(developed, null, null, "Original photo not found, so you're editing the developed copy")
    }

    private val EXIF_TIME = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    /** Find the camera photo a developed image came from, by capture time and camera model. */
    private fun findOriginal(context: Context, developed: Uri): Uri? {
        val resolver = context.contentResolver
        val dev = runCatching { resolver.openInputStream(developed)?.use { ExifInterface(it) } }.getOrNull() ?: return null
        val taken = dev.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: return null
        val model = dev.getAttribute(ExifInterface.TAG_MODEL)
        val sub = dev.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL)
        val center = runCatching {
            LocalDateTime.parse(taken.trim(), EXIF_TIME).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull() ?: return null
        val window = 15L * 3600_000L // generous: DATE_TAKEN may be stored in UTC
        val selection = "${MediaStore.Images.Media.DATE_TAKEN} BETWEEN ? AND ? AND " +
            "${MediaStore.Images.Media.RELATIVE_PATH} NOT LIKE ? AND ${MediaStore.Images.Media.RELATIVE_PATH} NOT LIKE ?"
        val args = arrayOf((center - window).toString(), (center + window).toString(), "${Images.RELATIVE_PATH}%", "${Images.LEGACY_RELATIVE_PATH}%")
        val candidates = ArrayList<Uri>()
        resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID), selection, args, null)?.use { c ->
            while (c.moveToNext() && candidates.size < 400) {
                candidates += ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
            }
        }
        var fallback: Uri? = null
        for (u in candidates) {
            val e = runCatching { resolver.openInputStream(u)?.use { ExifInterface(it) } }.getOrNull() ?: continue
            if (e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) != taken) continue
            if (model != null && e.getAttribute(ExifInterface.TAG_MODEL) != model) continue
            if (sub == null || e.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL) == sub) return u
            if (fallback == null) fallback = u
        }
        return fallback
    }
}
