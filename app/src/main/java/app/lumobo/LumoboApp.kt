package app.lumobo

import android.app.Application
import app.lumobo.data.MediaThumbFetcher
import app.lumobo.data.MediaThumbKeyer
import app.lumobo.editor.Engine
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LumoboApp : Application(), ImageLoaderFactory {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Warm the GPU pipeline (context, shaders, grain field) while the gallery is showing.
        appScope.launch { runCatching { Engine.renderer.prewarm() } }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            add(MediaThumbKeyer())
            add(MediaThumbFetcher.Factory(this@LumoboApp))
        }
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
        .crossfade(false)
        .build()
}
