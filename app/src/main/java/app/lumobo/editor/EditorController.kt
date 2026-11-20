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
import kotlin.math.pow
import kotlin.math.roundToInt

/** Process-wide rendering resources. */
object Engine {
    val renderer by lazy { GlRenderer() }
    val luts = LutCache(size = 64, capacity = 16)
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
    private var source: Bitmap? = null
    private var prefetchJob: Job? = null

    /** RGB histogram of the current render (64 bins each, normalised), when [wantHistogram]. */
    var histogram by mutableStateOf<FloatArray?>(null)
        private set
    @Volatile var wantHistogram = false
        set(v) { field = v; if (v) invalidate() }

    /** Category of the last film used, to open the film strip where the user left off. */
    val lastCategory get() = Profiles.byId(settings.lastFilmId).category
    private val buffers = arrayOfNulls<Bitmap>(3)
    private val frameBuffers = arrayOfNulls<Bitmap>(3)
    private var bufIdx = 0
    private val dirty = Channel<Unit>(Channel.CONFLATED)

    private val drafts = app.lumobo.data.Drafts.get(context)
    private val draftKey = (target ?: uri).toString()

    init {
        // Auto-saved edits win (they're the latest); else a developed photo's own recipe;
        // else the photo opens as shot.
        val start = drafts.load(draftKey) ?: initial
        if (start != null) {
            profile = Profiles.byId(start.filmId)
            adj = start.adj
            fx = start.fx
            geo = start.geo
        } else {
            profile = Profiles.ORIGINAL
            fx = settings.effectsFor(Profiles.ORIGINAL)
        }
        commit()
    }

    /** Auto-save: keep the current edit as this photo's draft (cleared when back to untouched). */
    private fun saveDraft() {
        val untouched = profile.isIdentity && adj == Adjustments() && geo == Geometry() &&
            fx == settings.effectsFor(Profiles.ORIGINAL) && initial == null
        if (untouched) drafts.clear(draftKey)
        else drafts.save(draftKey, Recipe(uri, profile.id, adj, fx, geo))
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
        // Every committed change (and undo / redo) is auto-saved.
        if (status == EditorStatus.Ready || historyIndex > 0) saveDraft()
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
            source = src
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
                val hist = if (wantHistogram) withContext(Dispatchers.Default) { histogramOf(target) } else null
                val shown = if (crop) target else withContext(Dispatchers.Default) {
                    if (f.dateStamp) Overlays.drawDateStamp(target, captureDate, settings.dateColor, settings.dateFormat)
                    Overlays.frame(target, f.frame, p.name, frameBuffers[idx]).also { if (it !== target) frameBuffers[idx] = it }
                }
                rendered = shown.asImageBitmap()
                if (hist != null) histogram = hist
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
        // Keep the photo's own shape (clamped), so film previews match what you'll get.
        val ar = (src.width.toFloat() / src.height).coerceIn(0.56f, 1.8f)
        val tw = if (ar >= 1f) THUMB else (THUMB * ar).roundToInt()
        val th = if (ar >= 1f) (THUMB / ar).roundToInt() else THUMB
        val sw = min(src.width, (src.height * ar).roundToInt())
        val sh = min(src.height, (src.width / ar).roundToInt())
        val crop = Bitmap.createBitmap(src, (src.width - sw) / 2, (src.height - sh) / 2, sw, sh)
        val small = Bitmap.createScaledBitmap(crop, tw, th, true)
        val px = IntArray(tw * th)
        small.getPixels(px, 0, tw, 0, 0, tw, th)
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
                val bmp = Bitmap.createBitmap(out, tw, th, Bitmap.Config.ARGB_8888).asImageBitmap()
                withContext(Dispatchers.Main) { thumbs[p.id] = bmp }
            }
        }.awaitAll()
    }

    private fun histogramOf(b: Bitmap): FloatArray {
        val bins = 64
        val h = FloatArray(bins * 3)
        val row = IntArray(b.width)
        val step = maxOf(1, b.height / 160)
        var y = 0
        while (y < b.height) {
            b.getPixels(row, 0, b.width, 0, y, b.width, 1)
            var x = 0
            while (x < row.size) {
                val c = row[x]
                h[((c shr 16) and 255) * bins / 256]++
                h[bins + ((c shr 8) and 255) * bins / 256]++
                h[2 * bins + (c and 255) * bins / 256]++
                x += 4
            }
            y += step
        }
        val max = h.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        for (i in h.indices) h[i] = kotlin.math.sqrt(h[i] / max)
        return h
    }

    /** Lightroom-style Auto: balance exposure, clipping and shadows from the original's histogram. */
    fun autoTone() {
        val src = source ?: return
        val lum = FloatArray(4096)
        var n = 0
        val weights = FloatArray(4096) { 1f }
        val stepX = maxOf(1, src.width / 64)
        val stepY = maxOf(1, src.height / 64)
        var yy = 0
        while (yy < src.height && n < lum.size) {
            var xx = 0
            while (xx < src.width && n < lum.size) {
                val c = src.getPixel(xx, yy)
                // Like a camera's centre-weighted meter: the middle counts up to twice as much.
                val dx = xx / (src.width - 1f) - 0.5f
                val dy = yy / (src.height - 1f) - 0.5f
                weights[n] = 1f + (1f - (dx * dx + dy * dy) * 4f).coerceIn(0f, 1f)
                lum[n++] = (0.2126f * ((c shr 16) and 255) + 0.7152f * ((c shr 8) and 255) + 0.0722f * (c and 255)) / 255f
                xx += stepX
            }
            yy += stepY
        }
        if (n < 16) return
        // Centre-weighted percentiles.
        val order = (0 until n).sortedBy { lum[it] }
        val sorted = FloatArray(n) { lum[order[it]] }
        val cum = FloatArray(n)
        var acc = 0f
        for (i in 0 until n) { acc += weights[order[i]]; cum[i] = acc }
        fun pct(p: Float): Float {
            val target = p * acc
            var lo = 0
            var hi = n - 1
            while (lo < hi) { val mid = (lo + hi) / 2; if (cum[mid] < target) lo = mid + 1 else hi = mid }
            return sorted[lo]
        }
        // Mid-tone key from the middle half of the histogram; correct only part of the way,
        // so backlit or night scenes keep their mood.
        val med = ((pct(0.3f) + pct(0.5f) + pct(0.7f)) / 3f).coerceIn(0.02f, 0.98f)
        val full = kotlin.math.ln(app.lumobo.film.ColorMath.srgbToLinear(0.44f) / app.lumobo.film.ColorMath.srgbToLinear(med)) / kotlin.math.ln(2f)
        val ev = (full * 0.55f).coerceIn(-0.6f, 0.6f)
        val gain = 2.0.pow(ev.toDouble()).toFloat()
        fun shifted(v: Float) = app.lumobo.film.ColorMath.linearToSrgb(app.lumobo.film.ColorMath.srgbToLinear(v) * gain).coerceIn(0f, 1f)
        val lo = shifted(pct(0.01f))
        val hi = shifted(pct(0.99f))
        val p10 = shifted(pct(0.1f))
        val p90 = shifted(pct(0.9f))
        val dark = sorted.take(n).count { shifted(it) < 0.18f } / n.toFloat()
        val bright = sorted.take(n).count { shifted(it) > 0.9f } / n.toFloat()
        updateAdj(adj.copy(
            exposure = ev / 2f,
            whites = if (hi < 0.93f) ((0.97f - hi) / 0.16f).coerceIn(0f, 0.6f) else 0f,
            blacks = if (lo > 0.04f) -((lo - 0.02f) / 0.10f).coerceIn(0f, 0.6f) else 0f,
            highlights = if (bright > 0.08f) -(bright * 3f).coerceIn(0.15f, 0.6f) else 0f,
            shadows = if (dark > 0.2f) (dark * 1.2f).coerceIn(0.1f, 0.5f) else 0f,
            contrast = if (p90 - p10 < 0.55f) 0.15f else 0f,
            vibrance = 0.12f,
        ))
    }

    /** Bake the LUTs of a category in the background so tapping a film is instant. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun prefetch(category: app.lumobo.film.Category?) {
        prefetchJob?.cancel()
        prefetchJob = scope.launch(Dispatchers.Default.limitedParallelism(2)) {
            for (p in Profiles.all.filter { it.category == category }) {
                if (Engine.luts.peek(p) == null) Engine.luts.get(p)
            }
        }
    }

    /** Star eraser: while on, dragging on the photo paints areas that get no stars. */
    var starBrush by mutableStateOf(false)
    /** Eraser size, 0..1 (about 2% to 16% of the photo's width). */
    var brushSize by mutableStateOf(0.35f)
    val brushRadius get() = 0.02f + brushSize * 0.14f

    /** Paint the star eraser at ([nx], [ny]) of the shown frame (0..1), [radius] of its width. */
    fun eraseStars(nx: Float, ny: Float, radius: Float) {
        val g = geo
        var u = g.left + nx * g.width
        var v = g.top + ny * g.height
        if (g.flipH) u = 1f - u
        if (g.flipV) v = 1f - v
        val (su, sv) = when (((g.rotation % 4) + 4) % 4) {
            1 -> v to 1f - u
            2 -> 1f - u to 1f - v
            3 -> 1f - v to u
            else -> u to v
        }
        val r = radius * (if (g.rotation % 2 == 1) g.height else g.width)
        val m = fx.starMask
        // Skip dabs that barely moved, so a long stroke stays light to store and draw.
        if (m.size >= 3 && kotlin.math.hypot(m[m.size - 3] - su, m[m.size - 2] - sv) < r * 0.3f) return
        updateFx(fx.copy(starMask = m + listOf(su, sv, r)))
    }

    fun clearStarMask() = updateFx(fx.copy(starMask = emptyList()))

    fun applyLook(look: app.lumobo.data.SavedLook) {
        profile = Profiles.byId(look.filmId)
        adj = look.adj
        fx = look.fx.copy(frame = fx.frame, dateStamp = fx.dateStamp)
        settings.lastFilmId = profile.id
        invalidate()
        changed()
    }

    fun resetAll() {
        profile = Profiles.ORIGINAL
        adj = Adjustments()
        fx = settings.effectsFor(Profiles.ORIGINAL)
        geo = Geometry()
        invalidate()
        changed()
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
        val reframed = g.rotation != geo.rotation || g.angle != geo.angle || g.flipH != geo.flipH || g.flipV != geo.flipV || g.perspV != geo.perspV || g.perspH != geo.perspH
        geo = g
        if (!cropMode || reframed) invalidate()
        changed()
    }

    fun setCropping(on: Boolean) {
        if (cropMode == on) return
        cropMode = on
        invalidate()
    }

    /** The film's own starting effects (texture) under the current settings. */
    val filmDefaults get() = settings.effectsFor(profile)

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
        private const val THUMB = 260
    }
}
