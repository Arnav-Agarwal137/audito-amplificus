package com.arnavagarwal.sound.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.arnavagarwal.sound.R

/**
 * "Aurora" (his pick, 22 Sep 2026 - RULES.md): near-black, a glowing cloud whose three colours are the
 * three parts of the sound, frosted-glass cards, Inter Tight. A light version exists because the phone may
 * be in light mode; the cloud and the glass carry over, only the ground changes.
 */
data class Aurora(
    val bg: Color,
    val ink: Color,
    val dim: Color,
    val glass: Color,
    val edge: Color,
    val onPrimary: Color,
    val bass: Color = Color(0xFFFF7A2F),
    val voice: Color = Color(0xFFE0409B),
    val treble: Color = Color(0xFF35C9FF),
    val good: Color = Color(0xFF3DFC9A),
    val warn: Color = Color(0xFFFFC93D),
    val dark: Boolean,
)

private val DarkAurora = Aurora(
    bg = Color(0xFF07080D), ink = Color(0xFFF4F5FA), dim = Color(0x8CF4F5FA),
    glass = Color(0x0FFFFFFF), edge = Color(0x1FFFFFFF), onPrimary = Color(0xFF0B0C12), dark = true,
)
private val LightAurora = Aurora(
    bg = Color(0xFFF4F3F8), ink = Color(0xFF111218), dim = Color(0x99111218),
    glass = Color(0xB3FFFFFF), edge = Color(0x14000000), onPrimary = Color(0xFFFFFFFF),
    good = Color(0xFF12A564), warn = Color(0xFFD08A00), dark = false,
)

val LocalAurora = staticCompositionLocalOf { DarkAurora }

val InterTight = FontFamily(
    Font(R.font.inter_tight_light, FontWeight.Light),
    Font(R.font.inter_tight_regular, FontWeight.Normal),
    Font(R.font.inter_tight_semibold, FontWeight.SemiBold),
    Font(R.font.inter_tight_bold, FontWeight.Bold),
)

private fun Typography.withFamily(f: FontFamily): Typography {
    fun TextStyle.f() = copy(fontFamily = f)
    return copy(
        displayLarge = displayLarge.f(), displayMedium = displayMedium.f(), displaySmall = displaySmall.f(),
        headlineLarge = headlineLarge.f(), headlineMedium = headlineMedium.f(), headlineSmall = headlineSmall.f(),
        titleLarge = titleLarge.f(), titleMedium = titleMedium.f(), titleSmall = titleSmall.f(),
        bodyLarge = bodyLarge.f(), bodyMedium = bodyMedium.f(), bodySmall = bodySmall.f(),
        labelLarge = labelLarge.f(), labelMedium = labelMedium.f(), labelSmall = labelSmall.f(),
    )
}

@Composable
fun AuroraTheme(content: @Composable () -> Unit) {
    val a = if (isSystemInDarkTheme()) DarkAurora else LightAurora
    val scheme = if (a.dark) darkColorScheme(background = a.bg, surface = a.bg, onSurface = a.ink, primary = a.ink)
                 else lightColorScheme(background = a.bg, surface = a.bg, onSurface = a.ink, primary = a.ink)
    CompositionLocalProvider(LocalAurora provides a) {
        MaterialTheme(colorScheme = scheme, typography = Typography().withFamily(InterTight), content = content)
    }
}

/** The frosted card every block of the screen sits on. */
@Composable
fun Modifier.glass(radius: Dp = 24.dp): Modifier {
    val a = LocalAurora.current
    val shape = RoundedCornerShape(radius)
    return this.clip(shape).background(a.glass).border(1.dp, a.edge, shape)
}
