package app.emulsion.editor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.emulsion.film.Adjustments
import app.emulsion.film.Effects
import app.emulsion.film.FilmProfile
import app.emulsion.film.FrameStyle
import app.emulsion.film.LutCache
import app.emulsion.film.Profiles
import app.emulsion.render.GlRenderer
import app.emulsion.render.Images
import app.emulsion.render.Overlays
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.min
import kotlin.math.roundToInt

/** Process-wide rendering resources. */
object Engine {
    val renderer by lazy { GlRenderer() }
    val luts = LutCache(size = 64, capacity = 10)
}

sealed interface EditorStatus {
    data object Loading : EditorStatus
    data object Ready : EditorStatus
    data class Failed(val message: String) : EditorStatus
}

class EditorController(private val context: Context, val uri: Uri) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var status by mutableStateOf<EditorStatus>(EditorStatus.Loading)
        private set
    var profile by mutableStateOf(Profiles.ORIGINAL)
        private set
    var adj by mutableStateOf(Adjustments())
        private set
    var fx by mutableStateOf(Effects())
        private set
    var rendered by mutableStateOf<ImageBitmap?>(null)
        private set
    var original by mutableStateOf<ImageBitmap?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    var lastRenderMs by mutableStateOf(0L)
        private set
    val thumbs = mutableStateMapOf<String, ImageBitmap>()

    private var captureDate: LocalDate = LocalDate.now()
    private val buffers = arrayOfNulls<Bitmap>(3)
    private val frameBuffers = arrayOfNulls<Bitmap>(3)
    private var bufIdx = 0
    private val dirty = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        scope.launch { load() }
    }

    fun dispose() {
        Log.i(TAG, "editor disposed")
        scope.cancel()
    }

    private suspend fun load() {
        try {
            val src = withContext(Dispatchers.IO) { Images.decode(context, uri, PREVIEW_EDGE) }
            captureDate = withContext(Dispatchers.IO) { Images.captureDate(context, uri) }
            original = src.asImageBitmap()
            Engine.renderer.onGl { setPreviewSource(src) }
            for (i in buffers.indices) buffers[i] = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            status = EditorStatus.Ready
            scope.launch { renderLoop() }
            invalidate()
            scope.launch { buildThumbs(src) }
        } catch (t: Throwable) {
            Log.e(TAG, "load failed", t)
            status = EditorStatus.Failed(t.message ?: "Could not open this image")
        }
    }

    private fun invalidate() {
        dirty.trySend(Unit)
    }

    private suspend fun renderLoop() {
        for (u in dirty) {
            val p = profile
            val a = adj
            val f = fx
            busy = true
            try {
                val t0 = System.nanoTime()
                val lut = Engine.luts.peek(p) ?: withContext(Dispatchers.Default) { Engine.luts.get(p) }
                val t1 = System.nanoTime()
                val idx = bufIdx
                val target = buffers[idx]!!
                bufIdx = (bufIdx + 1) % buffers.size
                Engine.renderer.onGl { renderPreview(lut, a, f, p.isMono, target) }
                val t2 = System.nanoTime()
                val shown = withContext(Dispatchers.Default) {
                    if (f.dateStamp) Overlays.drawDateStamp(target, captureDate)
                    Overlays.frame(target, f.frame, p.name, frameBuffers[idx]).also { if (it !== target) frameBuffers[idx] = it }
                }
                rendered = shown.asImageBitmap()
                lastRenderMs = (System.nanoTime() - t0) / 1_000_000
                Log.i(TAG, "render ${p.id} ${target.width}x${target.height} lut=${(t1 - t0) / 1_000_000}ms gl=${(t2 - t1) / 1_000_000}ms total=${lastRenderMs}ms")
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.e(TAG, "render failed", t)
            } finally {
                busy = false
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun buildThumbs(src: Bitmap) = withContext(Dispatchers.Default) {
        val tStart = System.nanoTime()
        val s = min(src.width, src.height)
        val crop = Bitmap.createBitmap(src, (src.width - s) / 2, (src.height - s) / 2, s, s)
        val small = Bitmap.createScaledBitmap(crop, THUMB, THUMB, true)
        val px = IntArray(THUMB * THUMB)
        small.getPixels(px, 0, THUMB, 0, 0, THUMB, THUMB)
        // Decode once to floats.
        val rf = FloatArray(px.size) { ((px[it] shr 16) and 255) / 255f }
        val gf = FloatArray(px.size) { ((px[it] shr 8) and 255) / 255f }
        val bf = FloatArray(px.size) { (px[it] and 255) / 255f }
        withContext(Dispatchers.Main) { thumbs[Profiles.ORIGINAL.id] = small.asImageBitmap() }
        val pool = Dispatchers.Default.limitedParallelism(3)
        Profiles.all.filter { !it.isIdentity }.map { p ->
            async(pool) {
                val look = Engine.luts.compiled(p)
                val out = IntArray(px.size)
                val o = FloatArray(3)
                val t = FloatArray(3)
                for (i in px.indices) {
                    look.apply(rf[i], gf[i], bf[i], o, t)
                    out[i] = (0xFF shl 24) or
                        ((o[0] * 255f).roundToInt().coerceIn(0, 255) shl 16) or
                        ((o[1] * 255f).roundToInt().coerceIn(0, 255) shl 8) or
                        (o[2] * 255f).roundToInt().coerceIn(0, 255)
                }
                val bmp = Bitmap.createBitmap(out, THUMB, THUMB, Bitmap.Config.ARGB_8888).asImageBitmap()
                withContext(Dispatchers.Main) { thumbs[p.id] = bmp }
            }
        }.awaitAll()
        Log.i(TAG, "thumbs ${(System.nanoTime() - tStart) / 1_000_000}ms")
    }

    fun select(p: FilmProfile) {
        if (p.id == profile.id) return
        profile = p
        fx = p.fx
        adj = adj.copy(intensity = 1f)
        invalidate()
    }

    fun updateAdj(a: Adjustments) {
        adj = a
        invalidate()
    }

    fun updateFx(f: Effects) {
        fx = f
        invalidate()
    }

    fun resetAdjustments() = updateAdj(Adjustments(intensity = adj.intensity))
    fun resetEffects() = updateFx(profile.fx.copy(dateStamp = fx.dateStamp, frame = fx.frame))

    /** Develop at full resolution and save to the gallery. */
    suspend fun save(): Result<Uri> {
        saving = true
        val p = profile
        val a = adj
        val f = fx
        return try {
            val lut = withContext(Dispatchers.Default) { Engine.luts.get(p) }
            val maxEdge = Engine.renderer.onGl { maxTextureSize }
            var out: Bitmap? = null
            for (budget in longArrayOf(64_000_000L, 24_000_000L, 12_000_000L)) {
                try {
                    val full = withContext(Dispatchers.IO) { Images.decode(context, uri, maxEdge, budget) }
                    out = Engine.renderer.onGl { renderFull(full, lut, a, f, p.isMono) }
                    break
                } catch (oom: OutOfMemoryError) {
                    Log.w(TAG, "OOM at $budget px, retrying smaller")
                    System.gc()
                }
            }
            var result = out ?: error("Not enough memory to develop this photo")
            result = withContext(Dispatchers.Default) {
                if (f.dateStamp) Overlays.drawDateStamp(result, captureDate)
                val framed = Overlays.frame(result, f.frame, p.name)
                if (framed !== result) result.recycle()
                framed
            }
            Log.i(TAG, "developed ${result.width}x${result.height}")
            val saved = withContext(Dispatchers.IO) { Images.saveJpeg(context, result, uri, if (p.isIdentity) "Original" else p.name) }
            result.recycle()
            Result.success(saved)
        } catch (t: Throwable) {
            Log.e(TAG, "save failed", t)
            Result.failure(t)
        } finally {
            saving = false
        }
    }

    val hasFrame get() = fx.frame != FrameStyle.NONE

    companion object {
        private const val TAG = "Emulsion"
        const val PREVIEW_EDGE = 2048
        private const val THUMB = 200
    }
}
