package app.lumobo.data

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.request.Options
import coil.size.Dimension

/** A gallery tile: loaded from Android's own thumbnail cache, which is far faster than decoding the photo. */
data class MediaThumb(val uri: Uri, val version: Long)

class MediaThumbFetcher(private val data: MediaThumb, private val options: Options, private val context: Context) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val px = (options.size.width as? Dimension.Pixels)?.px ?: 320
        val bmp = context.contentResolver.loadThumbnail(data.uri, android.util.Size(px, px), null)
        return DrawableResult(BitmapDrawable(context.resources, bmp), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory(private val context: Context) : Fetcher.Factory<MediaThumb> {
        override fun create(data: MediaThumb, options: Options, imageLoader: ImageLoader): Fetcher =
            MediaThumbFetcher(data, options, context)
    }
}

class MediaThumbKeyer : Keyer<MediaThumb> {
    override fun key(data: MediaThumb, options: Options) = "${data.uri}#${data.version}"
}
