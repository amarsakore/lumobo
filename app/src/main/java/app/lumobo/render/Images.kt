package app.lumobo.render

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import app.lumobo.editor.Recipe
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

object Images {

    const val ALBUM = "Lumobo"
    val RELATIVE_PATH = Environment.DIRECTORY_PICTURES + "/" + ALBUM

    /** Folder used before the app was renamed; still shown in Developed. */
    val LEGACY_RELATIVE_PATH = Environment.DIRECTORY_PICTURES + "/Emulsion"

    /**
     * Decode [uri] as a software ARGB_8888 sRGB bitmap with EXIF orientation applied.
     * Downscales so the long edge is <= [maxLongEdge] and the area is <= [maxPixels].
     */
    fun decode(context: Context, uri: Uri, maxLongEdge: Int, maxPixels: Long = Long.MAX_VALUE): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            val w = info.size.width
            val h = info.size.height
            var s = 1.0
            if (max(w, h) > maxLongEdge) s = maxLongEdge.toDouble() / max(w, h)
            val px = w.toLong() * h
            if (px * s * s > maxPixels) s = sqrt(maxPixels.toDouble() / px)
            if (s < 1.0) decoder.setTargetSize(max(1, (w * s).roundToInt()), max(1, (h * s).roundToInt()))
        }.let { if (it.config != Bitmap.Config.ARGB_8888) it.copy(Bitmap.Config.ARGB_8888, false) else it }
    }

    fun isReadable(context: Context, uri: Uri): Boolean =
        runCatching { context.contentResolver.openInputStream(uri)?.use { it.read() >= 0 } ?: false }.getOrDefault(false)

    /** Capture date of the photo, for the date imprint. */
    fun captureDate(context: Context, uri: Uri): LocalDate {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                if (raw != null) return LocalDateTime.parse(raw.trim(), DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")).toLocalDate()
            }
        }
        return LocalDate.now()
    }

    /** Film name and edit recipe stored in a developed photo, if any. */
    fun readDeveloped(context: Context, uri: Uri): Pair<String?, Recipe?> = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val exif = ExifInterface(input)
            exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION) to Recipe.decode(exif.getAttribute(ExifInterface.TAG_USER_COMMENT))
        }
    }.getOrNull() ?: (null to null)

    private val CAMERA_TAGS = listOf(
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_APERTURE_VALUE,
    )
    private val GPS_TAGS = listOf(
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
    )

    class SaveOptions(val quality: Int, val keepCameraInfo: Boolean, val keepLocation: Boolean)

    /**
     * Encode [bitmap] as JPEG with the chosen metadata and the edit [recipe]. When [overwrite]
     * is a developed photo this app owns, it is replaced in place; otherwise a new photo is
     * added to Pictures/Lumobo. Returns the saved photo's uri.
     */
    fun saveJpeg(
        context: Context,
        bitmap: Bitmap,
        sourceUri: Uri,
        filmName: String,
        recipe: Recipe,
        options: SaveOptions,
        overwrite: Uri? = null,
    ): Uri {
        val resolver = context.contentResolver
        val tmp = File(context.cacheDir, "develop-${System.nanoTime()}.jpg")
        try {
            tmp.outputStream().buffered().use { bitmap.compress(Bitmap.CompressFormat.JPEG, options.quality.coerceIn(60, 100), it) }
            runCatching {
                val src = resolver.openInputStream(sourceUri)?.use { ExifInterface(it) }
                val dst = ExifInterface(tmp)
                if (src != null) {
                    if (options.keepCameraInfo) for (tag in CAMERA_TAGS) src.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
                    if (options.keepLocation) for (tag in GPS_TAGS) src.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
                }
                dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                dst.setAttribute(ExifInterface.TAG_SOFTWARE, "Lumobo - $filmName")
                dst.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, filmName)
                dst.setAttribute(ExifInterface.TAG_USER_COMMENT, recipe.encode())
                dst.saveAttributes()
            }.onFailure { Log.w("Lumobo", "EXIF write failed", it) }

            if (overwrite != null) {
                val replaced = runCatching {
                    resolver.openOutputStream(overwrite, "wt")!!.use { out -> tmp.inputStream().use { it.copyTo(out, 1 shl 16) } }
                    true
                }.getOrElse { Log.w("Lumobo", "Cannot overwrite $overwrite, saving a copy", it); false }
                if (replaced) return overwrite
            }

            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val slug = filmName.uppercase().replace(Regex("[^A-Z0-9]+"), "")
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "LUMOBO_${stamp}_$slug.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.Images.Media.IS_PENDING, 1)
                put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Could not create file in gallery")
            resolver.openOutputStream(uri)!!.use { out -> tmp.inputStream().use { it.copyTo(out, 1 shl 16) } }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } finally {
            tmp.delete()
        }
    }
}
