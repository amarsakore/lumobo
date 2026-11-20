package app.lumobo.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged

@Stable
class ZoomState {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    val zoomed get() = scale > 1.01f
    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }
}

/**
 * Pinch to zoom, pan while zoomed, double-tap to toggle. One-finger drags at 1x are left
 * alone so a parent pager can still swipe between photos.
 */
fun Modifier.zoomable(state: ZoomState, maxScale: Float = 6f): Modifier = this
    .pointerInput(state) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val event = awaitPointerEvent()
                val fingers = event.changes.count { it.pressed }
                if (fingers >= 2 || state.zoomed) {
                    val ns = (state.scale * event.calculateZoom()).coerceIn(1f, maxScale)
                    val pan = event.calculatePan()
                    val maxX = size.width * (ns - 1f) / 2f
                    val maxY = size.height * (ns - 1f) / 2f
                    state.scale = ns
                    state.offset = Offset(
                        (state.offset.x + pan.x).coerceIn(-maxX, maxX),
                        (state.offset.y + pan.y).coerceIn(-maxY, maxY),
                    )
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            } while (event.changes.any { it.pressed })
            if (state.scale < 1.02f) state.reset()
        }
    }
    .pointerInput(state) {
        detectTapGestures(onDoubleTap = { p ->
            if (state.zoomed) {
                state.reset()
            } else {
                val ns = 2.5f
                val maxX = size.width * (ns - 1f) / 2f
                val maxY = size.height * (ns - 1f) / 2f
                state.scale = ns
                state.offset = Offset(
                    ((size.width / 2f - p.x) * (ns - 1f)).coerceIn(-maxX, maxX),
                    ((size.height / 2f - p.y) * (ns - 1f)).coerceIn(-maxY, maxY),
                )
            }
        })
    }
    .graphicsLayer {
        scaleX = state.scale
        scaleY = state.scale
        translationX = state.offset.x
        translationY = state.offset.y
    }
