package app.lumobo.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
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

    const val NONE = -1
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
 * Interactive crop frame over [image] (the full, rotated frame). Drag corners to resize,
 * edges when the aspect is free, and inside to move. Reports changes through [onChange].
 */
@Composable
fun CropOverlay(image: ImageBitmap, geo: Geometry, onChange: (Geometry) -> Unit, modifier: Modifier = Modifier, fineGrid: Boolean = false) {
    val density = LocalDensity.current
    val current by rememberUpdatedState(geo)
    val change by rememberUpdatedState(onChange)
    var mode by remember { mutableIntStateOf(CropMath.NONE) }
    val work = remember { arrayOf(geo) }
    BoxWithConstraints(modifier) {
        val pad = with(density) { 22.dp.toPx() }
        val bw = constraints.maxWidth.toFloat()
        val bh = constraints.maxHeight.toFloat()
        val s = min((bw - 2 * pad) / image.width, (bh - 2 * pad) / image.height)
        val dw = image.width * s
        val dh = image.height * s
        val ox = (bw - dw) / 2f
        val oy = (bh - dh) / 2f
        val cornerR = with(density) { 34.dp.toPx() }
        val edgeR = with(density) { 22.dp.toPx() }

        fun hit(p: Offset): Int {
            val g = current
            val l = ox + g.left * dw
            val t = oy + g.top * dh
            val r = ox + g.right * dw
            val b = oy + g.bottom * dh
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
                .pointerInput(image.width, image.height, dw, dh) {
                    detectDragGestures(
                        onDragStart = { mode = hit(it); work[0] = current },
                        onDragEnd = { mode = CropMath.NONE },
                        onDragCancel = { mode = CropMath.NONE },
                        onDrag = { c, d ->
                            if (mode == CropMath.NONE) return@detectDragGestures
                            c.consume()
                            val g = work[0]
                            val ratio = CropMath.lockedRatio(g.aspect, image.width, image.height)
                            val rn = ratio?.let { it * image.height / image.width }
                            val next = CropMath.drag(g, mode, d.x / dw, d.y / dh, rn)
                            work[0] = next
                            change(next)
                        },
                    )
                },
        ) {
            drawImage(
                image,
                dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()),
                dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
                filterQuality = FilterQuality.High,
            )
            val g = current
            val l = ox + g.left * dw
            val t = oy + g.top * dh
            val r = ox + g.right * dw
            val b = oy + g.bottom * dh
            val shade = Color.Black.copy(alpha = 0.62f)
            drawRect(shade, Offset(ox, oy), Size(dw, t - oy))
            drawRect(shade, Offset(ox, b), Size(dw, oy + dh - b))
            drawRect(shade, Offset(ox, t), Size(l - ox, b - t))
            drawRect(shade, Offset(r, t), Size(ox + dw - r, b - t))
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
 * Straighten dial in the style of Lightroom / Snapseed: a tick ruler you drag sideways.
 * [value] is in degrees (-45..45). Double-tap resets. [onActive] reports dragging, so the
 * photo can show a fine levelling grid.
 */
@Composable
fun AngleDial(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onActive: (Boolean) -> Unit = {},
) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val current by rememberUpdatedState(value)
    val density = LocalDensity.current
    val pxPerDegree = with(density) { 9.dp.toPx() }
    val acc = remember { floatArrayOf(0f) }
    androidx.compose.foundation.layout.Column(modifier, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        androidx.compose.material3.Text(
            String.format(java.util.Locale.US, "%+.1f°", value).replace("+0.0°", "0°").replace("-0.0°", "0°"),
            style = app.lumobo.ui.theme.Type.Mono.copy(
                color = if (abs(value) > 0.05f) app.lumobo.ui.theme.Palette.Amber else app.lumobo.ui.theme.Palette.Text,
                fontSize = androidx.compose.ui.unit.TextUnit(15f, androidx.compose.ui.unit.TextUnitType.Sp),
            ),
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        onChange(0f)
                    })
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { acc[0] = current; onActive(true) },
                        onDragEnd = { onActive(false) },
                        onDragCancel = { onActive(false) },
                    ) { change, dx ->
                        change.consume()
                        val old = acc[0]
                        var next = (old - dx / pxPerDegree).coerceIn(-45f, 45f)
                        if (abs(next) < 0.25f && abs(old) >= 0.25f) next = 0f // detent at level
                        if (kotlin.math.floor(old) != kotlin.math.floor(next) || (next == 0f && old != 0f)) {
                            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        }
                        acc[0] = next
                        onChange((next * 10f).roundToInt() / 10f)
                    }
                },
        ) {
            val cx = size.width / 2f
            val base = size.height * 0.62f
            val first = kotlin.math.ceil(current - cx / pxPerDegree).toInt().coerceAtLeast(-45)
            val last = kotlin.math.floor(current + cx / pxPerDegree).toInt().coerceAtMost(45)
            for (d in first..last) {
                val x = cx + (d - current) * pxPerDegree
                val major = d % 15 == 0
                val mid = d % 5 == 0
                val h = when { major -> 18.dp.toPx(); mid -> 12.dp.toPx(); else -> 7.dp.toPx() }
                val fade = 1f - (abs(x - cx) / cx).coerceIn(0f, 1f) * 0.75f
                val color = (if (d == 0) app.lumobo.ui.theme.Palette.Amber else Color.White).copy(alpha = (if (mid) 0.9f else 0.45f) * fade)
                drawLine(color, Offset(x, base - h), Offset(x, base), if (major) 2.dp.toPx() else 1.dp.toPx())
            }
            // Centre needle
            drawLine(app.lumobo.ui.theme.Palette.Amber, Offset(cx, 0f), Offset(cx, size.height), 2.5.dp.toPx())
            drawCircle(app.lumobo.ui.theme.Palette.Amber, 3.dp.toPx(), Offset(cx, size.height - 3.dp.toPx()))
        }
    }
}
