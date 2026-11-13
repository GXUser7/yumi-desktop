package com.yumi.desktop

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.mydrop.vpn.ui.theme.MyDropDarkColors
import com.mydrop.vpn.ui.theme.MyDropLightColors
import com.yumi.desktop.model.Palette

/**
 * A [Palette] as a Material scheme, derived rather than tabulated.
 *
 * Every palette shares a tone ladder, with its own hue and chroma. New green/amber accents use
 * a darker light-mode tone to keep white button labels readable. The seven original options
 * retain their existing hues, chroma and lightnesses.
 *
 * They are pastel by construction. The accent sits at eighty per cent lightness and just over half
 * saturation in the dark scheme — bright enough to read as the one loud thing on a near-black
 * ground, soft enough that a window full of it is not tiring. The light scheme takes the same hue
 * down to thirty-eight per cent, because a pastel on paper has no contrast at all.
 *
 * **The neutrals move too, and that is the point.** Swapping only the accent leaves every surface
 * in the window the cold blue-grey the ice palette was built on, so choosing "rose" repaints a few
 * buttons and nothing else — the room stays blue and the accent sits in it like a sticker. The
 * whole ramp is rebuilt at the palette's hue instead: background, every container tone, the
 * outlines and the muted text. Read [DarkRamp] against the ice scheme's own values and they are
 * the same numbers — that scheme *is* this ramp taken at hue 218 — which is why doing it this way
 * changes the colour of the room without changing its design.
 *
 * [Palette.Glacier] is returned untouched all the same. It is the original, hand-tuned to the last
 * digit, and there is nothing to gain from regenerating it.
 */
fun Palette.scheme(dark: Boolean): ColorScheme {
    val base = if (dark) MyDropDarkColors else MyDropLightColors
    val accent = hue ?: return base
    fun tone(hue: Float, saturation: Float, lightness: Float) =
        Color.hsl(hue, (saturation * chroma).coerceIn(0f, 1f), lightness)
    // Analogous rather than a second choice: the secondary is a chip container and a "connecting"
    // state, so it has to sit beside the accent without being mistaken for it. Twenty-six degrees
    // and no further — at forty the rose palette's chips came out brown, which reads as a leftover
    // from another theme rather than as this one's quiet tone.
    val second = (accent + 26f) % 360f
    val partner = partnerHue
    val ramp = if (dark) DarkRamp else LightRamp

    val accents = if (dark) {
        base.copy(
            primary = tone(accent, 0.55f, 0.80f),
            onPrimary = tone(accent, 0.72f, 0.13f),
            primaryContainer = tone(accent, 0.30f, 0.25f),
            onPrimaryContainer = tone(accent, 0.62f, 0.90f),
            inversePrimary = tone(accent, 0.48f, 0.44f),
            surfaceTint = tone(accent, 0.55f, 0.80f),

            secondary = tone(second, 0.52f, 0.76f),
            onSecondary = tone(second, 0.80f, 0.13f),
            secondaryContainer = tone(second, 0.30f, 0.27f),
            onSecondaryContainer = tone(second, 0.70f, 0.88f),

            tertiary = tone(partner, 0.42f, 0.70f),
            onTertiary = tone(partner, 0.85f, 0.11f),
            tertiaryContainer = tone(partner, 0.60f, 0.17f),
            onTertiaryContainer = tone(partner, 0.66f, 0.80f),
        )
    } else {
        base.copy(
            primary = tone(accent, 0.50f, lightAccent),
            onPrimary = Color.White,
            primaryContainer = tone(accent, 0.58f, 0.90f),
            onPrimaryContainer = tone(accent, 0.70f, 0.15f),
            inversePrimary = tone(accent, 0.55f, 0.80f),
            surfaceTint = tone(accent, 0.50f, 0.38f),

            secondary = tone(second, 0.50f, 0.34f),
            onSecondary = Color.White,
            secondaryContainer = tone(second, 0.60f, 0.88f),
            onSecondaryContainer = tone(second, 0.70f, 0.12f),

            tertiary = tone(partner, 0.55f, 0.32f),
            onTertiary = Color.White,
            tertiaryContainer = tone(partner, 0.55f, 0.86f),
            onTertiaryContainer = tone(partner, 0.80f, 0.10f),
        )
    }
    return accents.withNeutrals(accent, ramp, chroma)
}

/**
 * One neutral: how saturated, and how light. Everything else about it comes from the palette.
 *
 * The saturations run a little above what the ice scheme uses. On a near-black ground a twentieth
 * of saturation is invisible, and the whole reason the ramp is rebuilt is that the choice should
 * be visible in the room and not only on the buttons.
 */
private class Tone(private val saturation: Float, private val lightness: Float) {
    fun at(hue: Float, chroma: Float) = Color.hsl(hue, (saturation * chroma).coerceIn(0f, 1f), lightness)
}

private class Ramp(
    val background: Tone,
    val lowest: Tone,
    val low: Tone,
    val container: Tone,
    val high: Tone,
    val highest: Tone,
    val variant: Tone,
    val bright: Tone,
    val outline: Tone,
    val outlineVariant: Tone,
    val onSurface: Tone,
    val onVariant: Tone,
    val inverse: Tone,
    val onInverse: Tone,
)

/** The ice scheme's own ladder, half a step warmer in saturation. */
private val DarkRamp = Ramp(
    background = Tone(0.34f, 0.055f),
    lowest = Tone(0.40f, 0.032f),
    low = Tone(0.32f, 0.085f),
    container = Tone(0.30f, 0.115f),
    high = Tone(0.28f, 0.150f),
    highest = Tone(0.26f, 0.190f),
    variant = Tone(0.26f, 0.210f),
    bright = Tone(0.24f, 0.230f),
    outline = Tone(0.18f, 0.380f),
    outlineVariant = Tone(0.24f, 0.220f),
    onSurface = Tone(0.40f, 0.930f),
    onVariant = Tone(0.20f, 0.680f),
    inverse = Tone(0.40f, 0.930f),
    onInverse = Tone(0.28f, 0.130f),
)

/** The same ladder upside down: what was a tone above the ground becomes a tone below it. */
private val LightRamp = Ramp(
    background = Tone(0.42f, 0.970f),
    lowest = Tone(0.60f, 0.995f),
    low = Tone(0.40f, 0.950f),
    container = Tone(0.36f, 0.930f),
    high = Tone(0.34f, 0.910f),
    highest = Tone(0.31f, 0.880f),
    variant = Tone(0.30f, 0.900f),
    bright = Tone(0.60f, 0.995f),
    outline = Tone(0.18f, 0.535f),
    outlineVariant = Tone(0.22f, 0.810f),
    onSurface = Tone(0.30f, 0.090f),
    onVariant = Tone(0.20f, 0.350f),
    inverse = Tone(0.28f, 0.130f),
    onInverse = Tone(0.35f, 0.950f),
)

private fun ColorScheme.withNeutrals(hue: Float, ramp: Ramp, chroma: Float): ColorScheme = copy(
    background = ramp.background.at(hue, chroma),
    onBackground = ramp.onSurface.at(hue, chroma),
    surface = ramp.background.at(hue, chroma),
    onSurface = ramp.onSurface.at(hue, chroma),
    surfaceDim = ramp.background.at(hue, chroma),
    surfaceBright = ramp.bright.at(hue, chroma),
    surfaceVariant = ramp.variant.at(hue, chroma),
    onSurfaceVariant = ramp.onVariant.at(hue, chroma),
    surfaceContainerLowest = ramp.lowest.at(hue, chroma),
    surfaceContainerLow = ramp.low.at(hue, chroma),
    surfaceContainer = ramp.container.at(hue, chroma),
    surfaceContainerHigh = ramp.high.at(hue, chroma),
    surfaceContainerHighest = ramp.highest.at(hue, chroma),
    outline = ramp.outline.at(hue, chroma),
    outlineVariant = ramp.outlineVariant.at(hue, chroma),
    inverseSurface = ramp.inverse.at(hue, chroma),
    inverseOnSurface = ramp.onInverse.at(hue, chroma),
)
