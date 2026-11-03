package com.arnavagarwal.sound.desktop

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.sin

/**
 * The glowing cloud behind the song. Three soft lights, one per part of the sound (orange = bass,
 * pink = voice, blue = treble); each grows with how much the app is lifting that part, and all three
 * drift slowly so the screen feels alive. Off or silent: the cloud shrinks and fades.
 * drift = false (window minimised or hidden) stops the endless animation, so nothing redraws unseen.
 */
@Composable
fun AuroraCloud(parts: Words.Parts, active: Boolean, modifier: Modifier = Modifier, drift: Boolean = true) {
    val a = LocalAurora.current
    fun scale(db: Double) = (1f + (db / 8.0).toFloat()).coerceIn(0.6f, 1.6f)
    val bass by animateFloatAsState(scale(parts.bass), tween(900), label = "bass")
    val voice by animateFloatAsState(scale(parts.voice), tween(900), label = "voice")
    val treble by animateFloatAsState(scale(parts.treble), tween(900), label = "treble")
    val glow by animateFloatAsState(if (active) 1f else 0.35f, tween(1200), label = "glow")
    val t = if (drift) rememberInfiniteTransition(label = "drift").animateFloat(
        0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Restart), label = "t").value
    else 0f

    Canvas(modifier) {
        val w = size.width; val h = size.height; val r0 = minOf(w, h) * 0.42f
        val alpha = (if (a.dark) 0.85f else 0.55f) * glow
        val mode = if (a.dark) BlendMode.Screen else BlendMode.Multiply
        fun light(c: Color, cx: Float, cy: Float, r: Float) = drawCircle(
            Brush.radialGradient(listOf(c.copy(alpha = alpha), c.copy(alpha = alpha * 0.35f), Color.Transparent), Offset(cx, cy), r),
            radius = r, center = Offset(cx, cy), blendMode = mode)
        light(a.bass, w * (0.34f + 0.04f * cos(t)), h * (0.62f + 0.03f * sin(t)), r0 * bass)
        light(a.voice, w * (0.52f + 0.04f * sin(t * 2)), h * (0.40f + 0.04f * cos(t)), r0 * 0.85f * voice)
        light(a.treble, w * (0.72f + 0.03f * cos(t * 2)), h * (0.60f + 0.04f * sin(t)), r0 * 0.7f * treble)
    }
}
