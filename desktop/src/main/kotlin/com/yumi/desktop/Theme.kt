package com.yumi.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.ThemeMode
import com.mydrop.vpn.ui.theme.*
import com.yumi.desktop.model.Palette

/** Whether the window is dark right now, which is a question the palette swatches also ask. */
@Composable fun isDark(settings: AppSettings) = when (settings.themeMode) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Dark -> true
    ThemeMode.Light -> false
}

@Composable fun YumiTheme(settings: AppSettings, palette: Palette = Palette.Glacier, content: @Composable () -> Unit) {
    val dark = isDark(settings)
    var colors = palette.scheme(dark)
    if (dark && settings.amoled) colors = colors.toAmoled(palette.hue, palette.chroma)
    // The tunnel state, the traffic waves and the control's outline all read their colour from
    // here rather than from a constant beside them, so a palette change moves them with everything
    // else. The latency ramp is the deliberate exception — there the colour is the measurement.
    CompositionLocalProvider(LocalSemanticColors provides colors.toSemanticColors(dark)) {
        MaterialExpressiveTheme(colorScheme = colors, typography = MyDropTypography, motionScheme = MotionScheme.expressive(), content = content)
    }
}

/**
 * Collapses the elevation ramp onto true black.
 *
 * Container tones are kept slightly apart so cards and sheets remain distinguishable from the
 * background instead of merging into a void — and they keep the palette's hue while they do it.
 * Falling back to neutral greys here would mean that switching AMOLED on quietly cancelled the
 * palette, which is not what a switch about the backlight is for. A hue-less palette (the
 * original ice) keeps its own hand-picked greys.
 */
internal fun ColorScheme.toAmoled(hue: Float?, chroma: Float = 1f): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = hue?.let { Color.hsl(it, 0.30f * chroma, 0.042f) } ?: Color(0xFF0A0A0C),
    surfaceContainer = hue?.let { Color.hsl(it, 0.28f * chroma, 0.072f) } ?: Color(0xFF121215),
    surfaceContainerHigh = hue?.let { Color.hsl(it, 0.26f * chroma, 0.108f) } ?: Color(0xFF1B1B1F),
    surfaceContainerHighest = hue?.let { Color.hsl(it, 0.24f * chroma, 0.145f) } ?: Color(0xFF242429),
)
