package app.emulsion.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import app.emulsion.render.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MediaImage(
    val id: Long,
    val uri: Uri,
    val bucketId: Long,
    val bucket: String,
    val dateMillis: Long,
    val addedMillis: Long,
    val width: Int,
    val height: Int,
    val relativePath: String,
) {
    val isDeveloped get() = relativePath.trimEnd('/').endsWith(Images.RELATIVE_PATH)
}

data class Album(val id: Long, val name: String, val count: Int, val cover: Uri)

object MediaRepository {

    suspend fun loadImages(context: Context): List<MediaImage> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.RELATIVE_PATH,
        )
        val out = ArrayList<MediaImage>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection,
            "${MediaStore.Images.Media.IS_PENDING} = 0", null,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val iBid = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val iB = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val iDt = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val iDa = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val iW = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val iH = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val iP = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val taken = c.getLong(iDt)
                val added = c.getLong(iDa) * 1000
                out += MediaImage(
                    id = id,
                    uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                    bucketId = c.getLong(iBid),
                    bucket = c.getString(iB) ?: "Unknown",
                    dateMillis = if (taken > 0) taken else added,
                    addedMillis = added,
                    width = c.getInt(iW),
                    height = c.getInt(iH),
                    relativePath = c.getString(iP) ?: "",
                )
            }
        }
        out
    }

    fun albums(images: List<MediaImage>): List<Album> =
        images.groupBy { it.bucketId }
            .map { (id, list) -> Album(id, list.first().bucket, list.size, list.first().uri) }
            .sortedWith(compareByDescending<Album> { it.name.equals("Camera", true) }.thenByDescending { it.count })

    fun delete(context: Context, uri: Uri): Boolean =
        runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
}
