package app.lumobo.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.lumobo.film.Geometry
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** A crop aspect. [ratio] is width / height in pixels; null for Original (image aspect) and Free. */
data class AspectPreset(val id: String, val label: String, val ratio: Float?)

object CropMath {
    val PRESETS = listOf(
        AspectPreset("original", "Original", null),
        AspectPreset("free", "Free", null),
        AspectPreset("1:1", "1:1", 1f),
        AspectPreset("4:5", "4:5", 4f / 5f),
        AspectPreset("5:4", "5:4", 5f / 4f),
        AspectPreset("3:4", "3:4", 3f / 4f),
        AspectPreset("4:3", "4:3", 4f / 3f),
        AspectPreset("2:3", "2:3", 2f / 3f),
        AspectPreset("3:2", "3:2", 3f / 2f),
        AspectPreset("9:16", "9:16", 9f / 16f),
        AspectPreset("16:9", "16:9", 16f / 9f),
        AspectPreset("xpan", "XPan", 65f / 24f),
    )

    fun rotatedSize(w: Int, h: Int, rotation: Int): Pair<Int, Int> = if (rotation % 2 == 1) h to w else w to h

    /** Pixel aspect the crop is locked to, or null when free. */
    fun lockedRatio(aspect: String, rw: Int, rh: Int): Float? = when (aspect) {
        "free" -> null
        "original" -> rw.toFloat() / rh
        else -> PRESETS.firstOrNull { it.id == aspect }?.ratio
    }

    /** Largest centred crop with [aspect] in an image of [rw]x[rh] (rotated size). */
    fun centered(rotation: Int, aspect: String, rw: Int, rh: Int, current: Geometry? = null): Geometry {
        val ratio = lockedRatio(aspect, rw, rh)
        val base = current ?: Geometry()
        if (ratio == null) {
            return base.copy(rotation = rotation, aspect = aspect)
        }
        val rn = ratio * rh / rw  // normalised width / height
        val (w, h) = if (rn >= 1f) 1f to 1f / rn else rn to 1f
        val l = (1f - w) / 2f
        val t = (1f - h) / 2f
        return base.copy(rotation = rotation, left = l, top = t, right = l + w, bottom = t + h, aspect = aspect)
    }

    private const val MIN = 0.08f

    /** Apply a drag of ([dx], [dy]) in normalised units to handle [mode]. */
    fun drag(g: Geometry, mode: Int, dx: Float, dy: Float, rn: Float?): Geometry {
        var l = g.left
        var t = g.top
        var r = g.right
        var b = g.bottom
        when (mode) {
            MOVE -> {
                val w = r - l
                val h = b - t
                l = (l + dx).coerceIn(0f, 1f - w); t = (t + dy).coerceIn(0f, 1f - h)
                r = l + w; b = t + h
            }
            TL, TR, BR, BL -> {
                val leftSide = mode == TL || mode == BL
                val topSide = mode == TL || mode == TR
                val ax = if (leftSide) r else l
                val ay = if (topSide) b else t
                val cx = ((if (leftSide) l else r) + dx).coerceIn(0f, 1f)
                val cy = ((if (topSide) t else b) + dy).coerceIn(0f, 1f)
                val maxW = if (leftSide) ax else 1f - ax
                val maxH = if (topSide) ay else 1f - ay
                var w = max(MIN, if (leftSide) ax - cx else cx - ax)
                var h = max(MIN, if (topSide) ay - cy else cy - ay)
                if (rn != null) {
                    if (w / h > rn) w = h * rn else h = w / rn
                    if (w > maxW) { w = maxW; h = w / rn }
                    if (h > maxH) { h = maxH; w = h * rn }
                    if (w < MIN) { w = MIN; h = w / rn }
                    if (h < MIN) { h = MIN; w = h * rn }
                } else {
                    w = w.coerceAtMost(maxW); h = h.coerceAtMost(maxH)
                }
                l = if (leftSide) ax - w else ax; r = l + w
                t = if (topSide) ay - h else ay; b = t + h
            }
            LEFT -> l = (l + dx).coerceIn(0f, r - MIN)
            TOP -> t = (t + dy).coerceIn(0f, b - MIN)
            RIGHT -> r = (r + dx).coerceIn(l + MIN, 1f)
            BOTTOM -> b = (b + dy).coerceIn(t + MIN, 1f)
        }
        return g.copy(left = l, top = t, right = r, bottom = b)
    }

    /** Pinch: shrink the crop by [factor] (zooming the photo in) and move it by ([dx], [dy]). */
    fun zoom(g: Geometry, factor: Float, dx: Float, dy: Float): Geometry {
        var w = g.right - g.left
        var h = g.bottom - g.top
        w /= factor; h /= factor
        val over = max(w, h)
        if (over > 1f) { w /= over; h /= over }
        val under = min(w, h)
        if (under < MIN) { w *= MIN / under; h *= MIN / under }
        val cx = (g.left + g.right) / 2f + dx
        val cy = (g.top + g.bottom) / 2f + dy
        val l = (cx - w / 2f).coerceIn(0f, 1f - w)
        val t = (cy - h / 2f).coerceIn(0f, 1f - h)
        return g.copy(left = l, top = t, right = l + w, bottom = t + h)
    }

    const val NONE = -1
    const val PINCH = 9
    const val MOVE = 0
    const val TL = 1
    const val TR = 2
    const val BR = 3
    const val BL = 4
    const val LEFT = 5
    const val TOP = 6
    const val RIGHT = 7
    const val BOTTOM = 8
}

/**
 * Interactive crop frame over [image] (the full, transformed frame). Drag corners to resize,
 * edges when the aspect is free, and inside to move. When a drag ends the view glides so the
 * crop fills the screen (Lightroom-style). Once cropped, the frame stays put: drag inside it to
 * slide the photo, pinch to zoom the photo in or out (a tighter or looser crop). Reports changes through [onChange].
 */
@Composable
fun CropOverlay(image: ImageBitmap, geo: Geometry, onChange: (Geometry) -> Unit, modifier: Modifier = Modifier, fineGrid: Boolean = false) {
    val density = LocalDensity.current
    val current by rememberUpdatedState(geo)
    val change by rememberUpdatedState(onChange)
    var mode by remember { mutableIntStateOf(CropMath.NONE) }
    val work = remember { arrayOf(geo) }
    val zoom = remember { androidx.compose.animation.core.Animatable(1f) }
    val cx = remember { androidx.compose.animation.core.Animatable(0.5f) }
    val cy = remember { androidx.compose.animation.core.Animatable(0.5f) }
    val scope = rememberCoroutineScope()
    BoxWithConstraints(modifier) {
        val pad = with(density) { 22.dp.toPx() }
        val bw = constraints.maxWidth.toFloat()
        val bh = constraints.maxHeight.toFloat()
        val s = min((bw - 2 * pad) / image.width, (bh - 2 * pad) / image.height)
        val dw = image.width * s
        val dh = image.height * s
        val cornerR = with(density) { 34.dp.toPx() }
        val edgeR = with(density) { 22.dp.toPx() }

        // Glide to frame the crop whenever the user isn't dragging.
        androidx.compose.runtime.LaunchedEffect(geo, mode == CropMath.NONE, dw, dh) {
            if (mode != CropMath.NONE) return@LaunchedEffect
            val target = min((bw - 2 * pad) / (geo.width * dw), (bh - 2 * pad) / (geo.height * dh)).coerceIn(1f, 12f)
            val tcx = if (target <= 1.001f) 0.5f else (geo.left + geo.right) / 2f
            val tcy = if (target <= 1.001f) 0.5f else (geo.top + geo.bottom) / 2f
            val spec = androidx.compose.animation.core.tween<Float>(320, easing = androidx.compose.animation.core.FastOutSlowInEasing)
            kotlinx.coroutines.coroutineScope {
                launch { zoom.animateTo(target, spec) }
                launch { cx.animateTo(tcx, spec) }
                launch { cy.animateTo(tcy, spec) }
            }
        }
        val z = zoom.value
        fun sx(nx: Float) = bw / 2f + (nx - cx.value) * dw * zoom.value
        fun sy(ny: Float) = bh / 2f + (ny - cy.value) * dh * zoom.value

        /** Keep the crop filling the view while pinching, so the photo zooms behind a fixed frame. */
        fun frame(g: Geometry) {
            val target = min((bw - 2 * pad) / (g.width * dw), (bh - 2 * pad) / (g.height * dh)).coerceIn(1f, 12f)
            scope.launch {
                zoom.snapTo(target)
                cx.snapTo(if (target <= 1.001f) 0.5f else (g.left + g.right) / 2f)
                cy.snapTo(if (target <= 1.001f) 0.5f else (g.top + g.bottom) / 2f)
            }
        }

        fun hit(p: Offset): Int {
            val g = current
            val l = sx(g.left)
            val t = sy(g.top)
            val r = sx(g.right)
            val b = sy(g.bottom)
            val corners = listOf(CropMath.TL to Offset(l, t), CropMath.TR to Offset(r, t), CropMath.BR to Offset(r, b), CropMath.BL to Offset(l, b))
            corners.minByOrNull { hypot(it.second.x - p.x, it.second.y - p.y) }?.let { (m, c) ->
                if (hypot(c.x - p.x, c.y - p.y) < cornerR) return m
            }
            val free = CropMath.lockedRatio(g.aspect, image.width, image.height) == null
            if (free) {
                if (abs(p.x - l) < edgeR && p.y in t..b) return CropMath.LEFT
                if (abs(p.x - r) < edgeR && p.y in t..b) return CropMath.RIGHT
                if (abs(p.y - t) < edgeR && p.x in l..r) return CropMath.TOP
                if (abs(p.y - b) < edgeR && p.x in l..r) return CropMath.BOTTOM
            }
            return if (p.x in l..r && p.y in t..b) CropMath.MOVE else CropMath.NONE
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .pointerInput(image.width, image.height, dw, dh) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val target = hit(down.position)
                        work[0] = current
                        var pinching = false
                        var moving = false
                        while (true) {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                // Two fingers: zoom the photo inside the frame (a tighter crop) and drag it.
                                if (!pinching) { pinching = true; mode = CropMath.PINCH; work[0] = current }
                                val k = zoom.value
                                val f = ev.calculateZoom()
                                val pan = ev.calculatePan()
                                // Zoom around the fingers: the photo point under them stays under them.
                                val p = ev.calculateCentroid()
                                val keep = 1f - 1f / f
                                val dx = (p.x - bw / 2f) / (dw * k) * keep - pan.x / (dw * k * f)
                                val dy = (p.y - bh / 2f) / (dh * k) * keep - pan.y / (dh * k * f)
                                val next = CropMath.zoom(work[0], f, dx, dy)
                                work[0] = next
                                change(next)
                                frame(next)
                                ev.changes.forEach { it.consume() }
                                continue
                            }
                            if (pinching) continue
                            val c = pressed[0]
                            if (!moving) {
                                if ((c.position - down.position).getDistance() < viewConfiguration.touchSlop) continue
                                moving = true
                                mode = target
                            }
                            c.consume()
                            val d = c.positionChange()
                            if (mode == CropMath.NONE) continue
                            val g = work[0]
                            val ratio = CropMath.lockedRatio(g.aspect, image.width, image.height)
                            val rn = ratio?.let { it * image.height / image.width }
                            val k = zoom.value
                            if (mode == CropMath.MOVE) {
                                // Inside the crop: slide the photo under the fixed frame.
                                val next = CropMath.drag(g, mode, -d.x / (dw * k), -d.y / (dh * k), rn)
                                work[0] = next
                                change(next)
                                frame(next)
                                continue
                            }
                            val next = CropMath.drag(g, mode, d.x / (dw * k), d.y / (dh * k), rn)
                            work[0] = next
                            change(next)
                        }
                        mode = CropMath.NONE
                    }
                },
        ) {
            val ox = sx(0f)
            val oy = sy(0f)
            val iw = dw * z
            val ih = dh * z
            drawImage(
                image,
                dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()),
                dstSize = IntSize(iw.roundToInt(), ih.roundToInt()),
                filterQuality = FilterQuality.High,
            )
            val g = current
            val l = sx(g.left)
            val t = sy(g.top)
            val r = sx(g.right)
            val b = sy(g.bottom)
            val shade = Color.Black.copy(alpha = 0.62f)
            drawRect(shade, Offset(0f, 0f), Size(size.width, t))
            drawRect(shade, Offset(0f, b), Size(size.width, size.height - b))
            drawRect(shade, Offset(0f, t), Size(l, b - t))
            drawRect(shade, Offset(r, t), Size(size.width - r, b - t))
            val thin = 1.dp.toPx()
            val grid = Color.White.copy(alpha = if (mode != CropMath.NONE || fineGrid) 0.55f else 0.3f)
            val lines = if (fineGrid) 9 else 3
            for (i in 1 until lines) {
                val x = l + (r - l) * i / lines.toFloat()
                val y = t + (b - t) * i / lines.toFloat()
                drawLine(grid, Offset(x, t), Offset(x, b), thin)
                drawLine(grid, Offset(l, y), Offset(r, y), thin)
            }
            drawRect(Color.White.copy(alpha = 0.9f), Offset(l, t), Size(r - l, b - t), style = Stroke(1.5.dp.toPx()))
            val arm = 20.dp.toPx()
            val thick = 3.5.dp.toPx()
            val handle = Color.White
            fun corner(x: Float, y: Float, sx: Float, sy: Float) {
                drawLine(handle, Offset(x, y), Offset(x + sx * arm, y), thick)
                drawLine(handle, Offset(x, y), Offset(x, y + sy * arm), thick)
            }
            corner(l, t, 1f, 1f); corner(r, t, -1f, 1f); corner(r, b, -1f, -1f); corner(l, b, 1f, -1f)
        }
    }
}

/**
 * Ruler dial in the style of Lightroom / Snapseed, dragged sideways. Used for straighten
 * (degrees, ±45) and perspective (±100). Double-tap resets. [onActive] reports dragging, so
 * the photo can show a fine levelling grid.
 */
@Composable
fun AngleDial(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onActive: (Boolean) -> Unit = {},
    range: Float = 45f,
    unitDp: Float = 9f,
    format: (Float) -> String = { v -> String.format(java.util.Locale.US, "%+.1f°", v).replace("+0.0°", "0°").replace("-0.0°", "0°") },
) {
    val haptics = rememberSoftHaptics()
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val active by rememberUpdatedState(onActive)
    val lim by rememberUpdatedState(range)
    val density = LocalDensity.current
    val pxPerUnit = with(density) { unitDp.dp.toPx() }
    val tick = if (range > 50f) 5 else 1
    val acc = remember { floatArrayOf(0f) }
    androidx.compose.foundation.layout.Column(modifier, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        // Centre the number itself on the needle; a unit (°) hangs off to the right.
        val label = format(value)
        val unit = if (label.endsWith("°")) "°" else ""
        val style = app.lumobo.ui.theme.Type.Mono.copy(
            color = if (abs(value) > 0.05f) app.lumobo.ui.theme.Palette.Amber else app.lumobo.ui.theme.Palette.Text,
            fontSize = androidx.compose.ui.unit.TextUnit(15f, androidx.compose.ui.unit.TextUnitType.Sp),
        )
        androidx.compose.foundation.layout.Row {
            androidx.compose.material3.Text(unit, style = style.copy(color = Color.Transparent))
            androidx.compose.material3.Text(label.removeSuffix(unit), style = style)
            androidx.compose.material3.Text(unit, style = style)
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        haptics.confirm()
                        change(0f)
                    })
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { acc[0] = current; active(true) },
                        onDragEnd = { active(false) },
                        onDragCancel = { active(false) },
                    ) { ch, dx ->
                        ch.consume()
                        val old = acc[0]
                        var next = (old - dx / pxPerUnit).coerceIn(-lim, lim)
                        if (abs(next) < 0.25f && abs(old) >= 0.25f) next = 0f // detent at level
                        if (next == 0f && old != 0f) haptics.detent()
                        else if (kotlin.math.floor(old / tick) != kotlin.math.floor(next / tick)) haptics.tick()
                        acc[0] = next
                        change((next * 10f).roundToInt() / 10f)
                    }
                },
        ) {
            val mid = size.width / 2f
            val base = size.height * 0.62f
            val r = range.toInt()
            val first = kotlin.math.ceil(current - mid / pxPerUnit).toInt().coerceAtLeast(-r)
            val last = kotlin.math.floor(current + mid / pxPerUnit).toInt().coerceAtMost(r)
            for (d in first..last) {
                if (d % tick != 0) continue
                val x = mid + (d - current) * pxPerUnit
                val major = d % (15 * tick) == 0
                val med = d % (5 * tick) == 0
                val h = when { major -> 18.dp.toPx(); med -> 12.dp.toPx(); else -> 7.dp.toPx() }
                val fade = 1f - (abs(x - mid) / mid).coerceIn(0f, 1f) * 0.75f
                val color = (if (d == 0) app.lumobo.ui.theme.Palette.Amber else Color.White).copy(alpha = (if (med) 0.9f else 0.45f) * fade)
                drawLine(color, Offset(x, base - h), Offset(x, base), if (major) 2.dp.toPx() else 1.dp.toPx())
            }
            drawLine(app.lumobo.ui.theme.Palette.Amber, Offset(mid, 0f), Offset(mid, size.height), 2.5.dp.toPx())
            drawCircle(app.lumobo.ui.theme.Palette.Amber, 3.dp.toPx(), Offset(mid, size.height - 3.dp.toPx()))
        }
    }
}
