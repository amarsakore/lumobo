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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
/** Light, soothing haptics: a soft tick per slider step, a firmer detent at defaults. */
class SoftHaptics(private val view: android.view.View, private val enabled: () -> Boolean) {
    fun tick() {
        if (!enabled()) return
        view.performHapticFeedback(
            if (android.os.Build.VERSION.SDK_INT >= 34) android.view.HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
            else android.view.HapticFeedbackConstants.CLOCK_TICK,
        )
    }
    fun detent() {
        if (!enabled()) return
        view.performHapticFeedback(
            if (android.os.Build.VERSION.SDK_INT >= 34) android.view.HapticFeedbackConstants.SEGMENT_TICK
            else android.view.HapticFeedbackConstants.KEYBOARD_TAP,
        )
    }
    fun confirm() {
        if (!enabled()) return
        view.performHapticFeedback(
            if (android.os.Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.CONFIRM
            else android.view.HapticFeedbackConstants.LONG_PRESS,
        )
    }
}

@Composable
fun rememberSoftHaptics(): SoftHaptics {
    val view = androidx.compose.ui.platform.LocalView.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val settings = remember { app.lumobo.data.AppSettings.get(ctx) }
    return remember(view) { SoftHaptics(view) { settings.haptics } }
}

/** Editor tool panel: [overflow] when its controls don't fit, [expanded] when pulled up. */
@Stable
class PanelSheet {
    var expanded by mutableStateOf(false)
    var overflow by mutableStateOf(false)
    val open get() = expanded && overflow
}

val LocalPanelSheet = staticCompositionLocalOf { PanelSheet() }

/** Vertical scroll for a tool panel's controls; tells the sheet whether they overflow. */
@Composable
fun Modifier.panelScroll(): Modifier {
    val st = rememberScrollState()
    val sheet = LocalPanelSheet.current
    val max = st.maxValue
    LaunchedEffect(max, sheet.open) {
        if (max != Int.MAX_VALUE && !sheet.open) sheet.overflow = max > 0
    }
    DisposableEffect(sheet) { onDispose { sheet.overflow = false } }
    return this.verticalScroll(st)
}

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
    val haptics = rememberSoftHaptics()
    val lastStep = remember { intArrayOf(Int.MIN_VALUE) }
    // Gesture handlers live across recompositions, so read the *latest* parameters through
    // updated state. Capturing them directly made drags apply stale settings (the "shift" bug).
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val lo by rememberUpdatedState(min)
    val hi by rememberUpdatedState(max)
    val def by rememberUpdatedState(default)
    val changed = abs(value - default) > 0.005f
    val valueColor by animateColorAsState(if (changed) Palette.Amber else Palette.TextFaint, label = "v")
    val alpha = if (enabled) 1f else 0.35f
    Column(modifier.fillMaxWidth().padding(horizontal = 18.dp).graphicsLayer { this.alpha = alpha }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label.uppercase(), style = Type.Label)
            Spacer(Modifier.weight(1f))
            Text(format(value), style = Type.Mono.copy(color = valueColor))
        }
        var widthPx by remember { mutableFloatStateOf(1f) }
        fun valueAt(x: Float) = (lo + (x / widthPx).coerceIn(0f, 1f) * (hi - lo))
        fun emit(v: Float) {
            var nv = v
            // Gentle detent at the default value.
            if (abs(nv - def) < (hi - lo) * 0.015f) nv = def
            if (nv == current) return
            val step = ((nv - lo) / (hi - lo) * 40f).toInt()
            if ((current - def) * (nv - def) < 0f || (nv == def && current != def)) haptics.detent()
            else if (step != lastStep[0]) haptics.tick()
            lastStep[0] = step
            change(nv)
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(24.dp)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onDoubleTap = { haptics.confirm(); change(def) },
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
                drawCircle(Palette.TextFaint, radius = 2.5.dp.toPx(), center = Offset(xd, cy + 8.dp.toPx()))
            }
            drawCircle(Palette.Thumb, radius = 6.5.dp.toPx(), center = Offset(x1, cy))
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
    val hold by rememberUpdatedState(onHold)
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
                            val job = launch { delay(160); hold(true) }
                            tryAwaitRelease()
                            job.cancel()
                            hold(false)
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
            .padding(horizontal = 11.dp, vertical = 4.dp),
    ) {
        Text(text, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = fg)
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
    val tint by animateColorAsState(if (selected) Palette.Amber else Palette.TextDim, label = "tab")
    Column(
        modifier
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(top = 6.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(19.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 10.5.sp, color = tint, maxLines = 1, textAlign = TextAlign.Center)
    }
}
