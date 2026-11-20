package app.lumobo.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lumobo.ui.theme.Palette
import app.lumobo.ui.theme.Type
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Thin, precise slider in the style of a camera menu. Double-tap to reset.
 * Bipolar sliders (default inside the range) fill from the centre.
 */
@Composable
fun FilmSlider(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    min: Float = -1f,
    max: Float = 1f,
    default: Float = 0f,
    enabled: Boolean = true,
    format: (Float) -> String = { v -> val i = (v * 100).roundToInt(); if (min < 0f && i > 0) "+$i" else "$i" },
) {
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(value)
    val changed = abs(value - default) > 0.005f
    val valueColor by animateColorAsState(if (changed) Palette.Amber else Palette.TextFaint, label = "v")
    val alpha = if (enabled) 1f else 0.35f
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp).graphicsLayer { this.alpha = alpha }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label.uppercase(), style = Type.Label)
            Spacer(Modifier.weight(1f))
            Text(format(value), style = Type.Mono.copy(color = valueColor))
        }
        var widthPx by remember { mutableFloatStateOf(1f) }
        fun valueAt(x: Float) = (min + (x / widthPx).coerceIn(0f, 1f) * (max - min))
        fun emit(v: Float) {
            var nv = v
            // Gentle detent at the default value.
            if (abs(nv - default) < (max - min) * 0.015f) nv = default
            if ((current - default) * (nv - default) < 0f || (nv == default && current != default)) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            onChange(nv)
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(30.dp)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onDoubleTap = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onChange(default) },
                        onTap = { emit(valueAt(it.x)) },
                    )
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        emit(valueAt(change.position.x))
                    }
                },
        ) {
            widthPx = size.width
            val cy = size.height / 2
            val stroke = 2.dp.toPx()
            fun xOf(v: Float) = (v - min) / (max - min) * size.width
            drawLine(Palette.Surface3, Offset(0f, cy), Offset(size.width, cy), stroke, StrokeCap.Round)
            // tick marks
            for (i in 0..10) {
                val x = size.width * i / 10f
                val h = if (i == 5 || i == 0 || i == 10) 5.dp.toPx() else 2.5.dp.toPx()
                drawLine(Palette.Line, Offset(x, cy - h - 4.dp.toPx()), Offset(x, cy - 4.dp.toPx()), 1.dp.toPx())
            }
            // Bipolar controls fill from the centre, 0..1 controls fill from the left.
            val x0 = if (min < 0f && max > 0f) xOf(0f) else xOf(min)
            val x1 = xOf(value)
            drawLine(if (changed) Palette.Amber else Palette.TextDim, Offset(x0, cy), Offset(x1, cy), stroke, StrokeCap.Round)
            if (default > min && default < max && !(min < 0f && default == 0f)) {
                // Mark the film's own default so it is easy to return to.
                val xd = xOf(default)
                drawCircle(Palette.TextFaint, radius = 2.5.dp.toPx(), center = Offset(xd, cy + 9.dp.toPx()))
            }
            drawCircle(Color.White, radius = 7.dp.toPx(), center = Offset(x1, cy))
            if (changed) drawCircle(Palette.Amber, radius = 3.dp.toPx(), center = Offset(x1, cy))
        }
    }
}

/** Pinch-zoom / pan / double-tap image. Press-and-hold reports [onHold] for before/after compare. */
@Composable
fun ZoomableImage(
    bitmap: ImageBitmap,
    modifier: Modifier = Modifier,
    resetKey: Any? = null,
    onHold: (Boolean) -> Unit = {},
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(resetKey) { scale = 1f; offset = Offset.Zero }
    val animScale by animateFloatAsState(scale, label = "zoom")
    BoxWithConstraints(
        modifier
            .clipToBounds()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val ns = (scale * zoom).coerceIn(1f, 6f)
                    val maxX = size.width * (ns - 1f) / 2f
                    val maxY = size.height * (ns - 1f) / 2f
                    scale = ns
                    offset = Offset(
                        (offset.x + pan.x).coerceIn(-maxX, maxX),
                        (offset.y + pan.y).coerceIn(-maxY, maxY),
                    )
                }
            }
            .pointerInput(Unit) {
                coroutineScope {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1.05f) {
                                scale = 1f; offset = Offset.Zero
                            } else {
                                scale = 2.5f
                                offset = Offset((size.width / 2f - it.x) * 1.5f, (size.height / 2f - it.y) * 1.5f)
                            }
                        },
                        onPress = {
                            val job = launch { delay(160); onHold(true) }
                            tryAwaitRelease()
                            job.cancel()
                            onHold(false)
                        },
                    )
                }
            },
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.High,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = animScale; scaleY = animScale
                    translationX = offset.x; translationY = offset.y
                },
        )
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bg by animateColorAsState(if (selected) Palette.Text else Color.Transparent, label = "chip")
    val fg by animateColorAsState(if (selected) Palette.Bg else Palette.TextDim, label = "chipText")
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, if (selected) Color.Transparent else Palette.Line, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = fg)
    }
}

@Composable
fun SegmentedTabs(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Palette.Surface2)
            .padding(3.dp),
    ) {
        options.forEachIndexed { i, label ->
            val sel = i == selected
            val bg by animateColorAsState(if (sel) Palette.Surface3 else Color.Transparent, label = "seg")
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(bg)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (sel) Palette.Text else Palette.TextDim)
            }
        }
    }
}

@Composable
fun RoundIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Palette.Text, background: Color = Palette.Surface2) {
    Box(
        modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun BottomTab(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tint by animateColorAsState(if (selected) Palette.Amber else Palette.TextFaint, label = "tab")
    Column(
        modifier
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(label.uppercase(), style = Type.Label.copy(fontSize = 10.sp, color = tint), textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.width(14.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(if (selected) Palette.Amber else Color.Transparent))
    }
}
