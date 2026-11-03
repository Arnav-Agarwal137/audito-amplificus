package com.arnavagarwal.sound.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A slider whose zero is in the MIDDLE and whose colour grows outward from it. Material's slider fills
 * from the left edge, so at 0 dB it looked half-full, as if bass were being added (seen on the emulator
 * 22 Sep). Values snap to 0.5 dB, and near zero snap to exactly zero so "back to normal" is easy to hit.
 */
@Composable
fun CenterSlider(value: Double, color: Color, onChange: (Double) -> Unit, range: Double = 8.0, anchor: Double? = null) {
    val a = LocalAurora.current
    val current by rememberUpdatedState(value)
    val emit by rememberUpdatedState(onChange)
    fun snap(v: Double): Double {
        // near the app's own setting, snap exactly onto it, so "back to what the app chose" is easy to hit
        if (anchor != null && abs(v - anchor) < 0.75) return anchor
        if (abs(v) < 0.75) return 0.0
        return (v.coerceIn(-range, range) * 2).roundToInt() / 2.0
    }
    fun fromX(x: Float, w: Float) = snap(((x / w) * 2 - 1) * range)

    Canvas(
        Modifier.fillMaxWidth().height(36.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(current.toFloat(), -range.toFloat()..range.toFloat(), 32)
                setProgress { v -> emit(snap(v.toDouble())); true }
            }
            .pointerInput(Unit) { detectTapGestures { emit(fromX(it.x, size.width.toFloat())) } }
            .pointerInput(Unit) { detectHorizontalDragGestures { ch, _ -> emit(fromX(ch.position.x, size.width.toFloat())) } }
    ) {
        val w = size.width; val cy = size.height / 2; val th = 8.dp.toPx(); val r = CornerRadius(th / 2)
        val mid = w / 2
        val x = (mid + (current / range).toFloat() * (w / 2 - 10.dp.toPx())).coerceIn(0f, w)
        drawRoundRect(a.edge, Offset(0f, cy - th / 2), Size(w, th), r)
        val left = minOf(mid, x); val right = maxOf(mid, x)
        if (right - left > 0.5f) drawRoundRect(color, Offset(left, cy - th / 2), Size(right - left, th), r)
        drawRect(a.dim, Offset(mid - 1.dp.toPx() / 2, cy - 7.dp.toPx()), Size(1.dp.toPx(), 14.dp.toPx())) // the zero mark
        if (anchor != null && abs(anchor) >= 0.25) { // where the app put it: a small ring under the handle's path
            val ax = (mid + (anchor.coerceIn(-range, range) / range).toFloat() * (w / 2 - 10.dp.toPx())).coerceIn(0f, w)
            drawCircle(a.ink.copy(alpha = .55f), radius = 5.dp.toPx(), center = Offset(ax, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
        }
        drawCircle(if (a.dark) Color.White else color, radius = 10.dp.toPx(), center = Offset(x, cy))
        drawCircle(color, radius = 6.dp.toPx(), center = Offset(x, cy))
    }
}
