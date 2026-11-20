package app.lumobo.editor

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
import app.lumobo.data.AppSettings
import app.lumobo.film.Adjustments
import app.lumobo.film.Effects
import app.lumobo.film.FilmProfile
import app.lumobo.film.FrameStyle
import app.lumobo.film.Geometry
import app.lumobo.film.LutCache
import app.lumobo.film.Profiles
import app.lumobo.render.GlRenderer
import app.lumobo.render.Images
import app.lumobo.render.Overlays
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

/**
 * State and rendering for one editing session.
 * [uri] is the photo being developed; [initial] restores a saved recipe; [target] is a
 * developed photo to replace on save (re-editing from the Developed tab).
 */
class EditorController(
    private val context: Context,
    val uri: Uri,
    private val initial: Recipe? = null,
    val target: Uri? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settings = AppSettings.get(context)

    var status by mutableStateOf<EditorStatus>(EditorStatus.Loading)
        private set
    var profile by mutableStateOf(Profiles.ORIGINAL)
        private set
    var adj by mutableStateOf(Adjustments())
        private set
    var fx by mutableStateOf(Effects())
        private set
    var geo by mutableStateOf(Geometry())
        private set
    /** While cropping, the preview shows the whole (rotated) frame without frames or stamps. */
    var cropMode by mutableStateOf(false)
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

    /** Size of the preview source, for crop maths. */
    var sourceSize by mutableStateOf(1 to 1)
        private set

    // ── Undo / redo: snapshots committed once a change settles (one slider drag = one step).
    private data class Snapshot(val profile: FilmProfile, val adj: Adjustments, val fx: Effects, val geo: Geometry)
    private val history = ArrayList<Snapshot>()
    private var historyIndex = -1
    private var commitJob: Job? = null
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    private var captureDate: LocalDate = LocalDate.now()
    private val buffers = arrayOfNulls<Bitmap>(3)
    private val frameBuffers = arrayOfNulls<Bitmap>(3)
    private var bufIdx = 0
    private val dirty = Channel<Unit>(Channel.CONFLATED)

    init {
        if (initial != null) {
            profile = Profiles.byId(initial.filmId)
            adj = initial.adj
            fx = initial.fx
            geo = initial.geo
        } else {
            val start = if (settings.rememberLastFilm) Profiles.byId(settings.lastFilmId) else Profiles.ORIGINAL
            profile = start
            fx = settings.effectsFor(start)
        }
        commit()
    }

    private fun snapshot() = Snapshot(profile, adj, fx, geo)

    private fun changed() {
        canUndo = true
        canRedo = false
        commitJob?.cancel()
        commitJob = scope.launch {
            delay(450)
            commit()
        }
    }

    private fun commit() {
        val s = snapshot()
        if (historyIndex >= 0 && history[historyIndex] == s) return
        while (history.size > historyIndex + 1) history.removeAt(history.lastIndex)
        history += s
        historyIndex = history.lastIndex
        if (history.size > 120) {
            history.removeAt(0)
            historyIndex--
        }
        refreshHistory()
    }

    private fun refreshHistory() {
        canUndo = historyIndex > 0
        canRedo = historyIndex < history.lastIndex
    }

    private fun restore(s: Snapshot) {
        profile = s.profile
        adj = s.adj
        fx = s.fx
        geo = s.geo
        invalidate()
        refreshHistory()
    }

    fun undo() {
        commitJob?.cancel()
        commit()
        if (historyIndex > 0) {
            historyIndex--
            restore(history[historyIndex])
        }
    }

    fun redo() {
        commitJob?.cancel()
        if (historyIndex < history.lastIndex) {
            historyIndex++
            restore(history[historyIndex])
        }
    }

    fun start() {
        scope.launch { load() }
    }

    fun dispose() {
        scope.cancel()
    }

    private suspend fun load() {
        try {
            val src = withContext(Dispatchers.IO) { Images.decode(context, uri, PREVIEW_EDGE) }
            captureDate = withContext(Dispatchers.IO) { Images.captureDate(context, uri) }
            sourceSize = src.width to src.height
            original = src.asImageBitmap()
            Engine.renderer.onGl { setPreviewSource(src) }
            status = EditorStatus.Ready
            scope.launch { renderLoop() }
            invalidate()
            scope.launch { buildThumbs(src) }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "load failed", t)
            status = EditorStatus.Failed(t.message ?: "Could not open this image")
        }
    }

    private fun invalidate() {
        dirty.trySend(Unit)
    }

    private fun buffer(i: Int, w: Int, h: Int): Bitmap {
        val b = buffers[i]
        if (b != null && b.width == w && b.height == h) return b
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { buffers[i] = it }
    }

    private suspend fun renderLoop() {
        for (u in dirty) {
            val p = profile
            val a = adj
            val f = fx
            val crop = cropMode
            val g = if (crop) geo.uncropped() else geo
            busy = true
            try {
                val t0 = System.nanoTime()
                val lut = Engine.luts.peek(p) ?: withContext(Dispatchers.Default) { Engine.luts.get(p) }
                val idx = bufIdx
                bufIdx = (bufIdx + 1) % buffers.size
                val (sw, sh) = sourceSize
                val (w, h) = GlRenderer.outputSize(sw, sh, g)
                val target = buffer(idx, w, h)
                Engine.renderer.onGl { renderPreview(lut, a, f, p.isMono, g, target) }
                val shown = if (crop) target else withContext(Dispatchers.Default) {
                    if (f.dateStamp) Overlays.drawDateStamp(target, captureDate, settings.dateColor, settings.dateFormat)
                    Overlays.frame(target, f.frame, p.name, frameBuffers[idx]).also { if (it !== target) frameBuffers[idx] = it }
                }
                rendered = shown.asImageBitmap()
                lastRenderMs = (System.nanoTime() - t0) / 1_000_000
            } catch (c: CancellationException) {
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
        val s = min(src.width, src.height)
        val crop = Bitmap.createBitmap(src, (src.width - s) / 2, (src.height - s) / 2, s, s)
        val small = Bitmap.createScaledBitmap(crop, THUMB, THUMB, true)
        val px = IntArray(THUMB * THUMB)
        small.getPixels(px, 0, THUMB, 0, 0, THUMB, THUMB)
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
    }

    fun select(p: FilmProfile) {
        if (p.id == profile.id) return
        val next = settings.effectsFor(p)
        // Carry over frame / date only if the user changed them from the previous film's defaults.
        val prev = settings.effectsFor(profile)
        val userFrame = fx.frame != prev.frame
        val userDate = fx.dateStamp != prev.dateStamp
        fx = next.copy(
            frame = if (userFrame) fx.frame else next.frame,
            dateStamp = if (userDate) fx.dateStamp else next.dateStamp,
        ).withLensOf(fx)
        profile = p
        adj = adj.copy(intensity = 1f)
        settings.lastFilmId = p.id
        invalidate()
        changed()
    }

    fun updateAdj(a: Adjustments) {
        adj = a
        invalidate()
        changed()
    }

    fun updateFx(f: Effects) {
        fx = f
        invalidate()
        changed()
    }

    fun updateGeo(g: Geometry) {
        val reframed = g.rotation != geo.rotation || g.angle != geo.angle || g.flipH != geo.flipH || g.flipV != geo.flipV
        geo = g
        if (!cropMode || reframed) invalidate()
        changed()
    }

    fun setCropping(on: Boolean) {
        if (cropMode == on) return
        cropMode = on
        invalidate()
    }

    fun resetAdjustments() = updateAdj(Adjustments(intensity = adj.intensity))
    fun resetEffects() = updateFx(settings.effectsFor(profile).copy(dateStamp = fx.dateStamp, frame = fx.frame).withLensOf(fx))

    /** Develop at full resolution and save to the gallery (replacing [target] when re-editing). */
    suspend fun save(): Result<Uri> {
        saving = true
        val p = profile
        val a = adj
        val f = fx
        val g = geo
        return try {
            val lut = withContext(Dispatchers.Default) { Engine.luts.get(p) }
            val maxEdge = Engine.renderer.onGl { maxTextureSize }
            // Budget the decode so the cropped result lands near the export-size setting.
            val area = (g.width * g.height).coerceIn(0.05f, 1f)
            val budget = (settings.maxExportPixels / area).toLong().coerceAtMost(64_000_000L)
            var out: Bitmap? = null
            for (b in longArrayOf(budget, minOf(budget, 24_000_000L), 12_000_000L)) {
                try {
                    val full = withContext(Dispatchers.IO) { Images.decode(context, uri, maxEdge, b) }
                    out = Engine.renderer.onGl { renderFull(full, lut, a, f, p.isMono, g) }
                    break
                } catch (oom: OutOfMemoryError) {
                    Log.w(TAG, "OOM at $b px, retrying smaller")
                    System.gc()
                }
            }
            var result = out ?: error("Not enough memory to develop this photo")
            result = withContext(Dispatchers.Default) {
                if (f.dateStamp) Overlays.drawDateStamp(result, captureDate, settings.dateColor, settings.dateFormat)
                val framed = Overlays.frame(result, f.frame, p.name)
                if (framed !== result) result.recycle()
                framed
            }
            Log.i(TAG, "developed ${result.width}x${result.height}")
            val recipe = Recipe(uri, p.id, a, f, g)
            val options = Images.SaveOptions(settings.jpegQuality, settings.keepCameraInfo, settings.keepLocation)
            val name = if (p.isIdentity) "Original" else p.name
            val saved = withContext(Dispatchers.IO) { Images.saveJpeg(context, result, uri, name, recipe, options, target) }
            result.recycle()
            Result.success(saved)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "save failed", t)
            Result.failure(t)
        } finally {
            saving = false
        }
    }

    companion object {
        private const val TAG = "Lumobo"
        const val PREVIEW_EDGE = 2048
        private const val THUMB = 200
    }
}
