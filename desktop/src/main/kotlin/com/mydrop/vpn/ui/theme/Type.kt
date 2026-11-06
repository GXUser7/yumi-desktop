package com.mydrop.vpn.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/*
 * YouCloud's voice, on a desktop.
 *
 * Two faces. Unbounded for the display roles — page titles and the tunnel's headline: wide, round
 * and heavy, close in shape to the figures drifting behind the glass, with a Cyrillic drawn as its
 * own rather than added on. Onest for everything else: a calm grotesque, as good in Russian as in
 * English, that leaves the figure and the numbers the attention.
 *
 * The scale is YouCloud's (design/DESIGN.md, "Типографика"): display roles in Unbounded Bold at
 * 46/36/29, headlines and large titles in Onest ExtraBold, body at spec weight and tracking. The
 * opinion — heavy display and headline roles — lives here once instead of at fifty call sites.
 *
 * Static instances rather than the variable files: pinning the weight axis at build time leaves
 * Skia nothing to interpret, so the weight asked for is the weight drawn on every machine.
 */

private val Display = FontFamily(Font("font/unbounded_bold.ttf", FontWeight.Bold))

private val Sans = FontFamily(
    Font("font/onest_regular.ttf", FontWeight.Normal),
    Font("font/onest_medium.ttf", FontWeight.Medium),
    Font("font/onest_semibold.ttf", FontWeight.SemiBold),
    Font("font/onest_bold.ttf", FontWeight.Bold),
    Font("font/onest_extrabold.ttf", FontWeight.ExtraBold),
)

/**
 * Pictures, which Windows draws inconsistently and in one case not at all.
 *
 * Twemoji's COLR build, brought along because the platform cannot be relied on for the emoji a
 * subscription writes its server names in. Named explicitly wherever those names are set — see
 * [emojiText][com.mydrop.vpn.ui.components.emojiText] — and never installed as a fallback for
 * body text, so nothing here depends on how a particular Skia build resolves a codepoint.
 */
val EmojiFamily = FontFamily(Font("font/twemoji.ttf"))

@Suppress("DEPRECATION")
private val TightPlatform = PlatformTextStyle(null, null)

/** Display type set tight: the font's own ascent padding is trimmed so titles sit on the line. */
private val TrimBoth = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.Both,
)

private fun display(size: Int, line: Int, tracking: Float) = TextStyle(
    fontFamily = Display,
    fontWeight = FontWeight.Bold,
    fontSynthesis = FontSynthesis.None,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
    platformStyle = TightPlatform,
    lineHeightStyle = TrimBoth,
)

private fun sans(size: Int, line: Int, weight: FontWeight, tracking: Float = 0f) = TextStyle(
    fontFamily = Sans,
    fontWeight = weight,
    fontSynthesis = FontSynthesis.None,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
    platformStyle = TightPlatform,
)

val MyDropTypography = Typography(
    displayLarge = display(46, 54, -0.75f),
    displayMedium = display(36, 44, -0.5f),
    // The page title (`PageTitle`): every screen opens with one.
    displaySmall = display(29, 38, -0.25f),

    headlineLarge = sans(32, 40, FontWeight.ExtraBold, -0.75f),
    headlineMedium = sans(28, 36, FontWeight.ExtraBold, -0.5f),
    headlineSmall = sans(24, 32, FontWeight.ExtraBold, -0.25f),

    // The section title on a panel (`SectionTitle`).
    titleLarge = sans(22, 28, FontWeight.ExtraBold, -0.25f),
    titleMedium = sans(16, 24, FontWeight.Bold),
    titleSmall = sans(14, 20, FontWeight.SemiBold, 0.1f),

    bodyLarge = sans(16, 24, FontWeight.Normal),
    bodyMedium = sans(14, 20, FontWeight.Normal),
    bodySmall = sans(12, 16, FontWeight.Normal),

    labelLarge = sans(14, 20, FontWeight.SemiBold),
    labelMedium = sans(12, 16, FontWeight.SemiBold),
    labelSmall = sans(11, 16, FontWeight.Medium),
)

/**
 * The spaced capitals over a block — "СЕССИЯ", "ПРИЛОЖЕНИЯ · 3". labelMedium, ExtraBold, tracked
 * out to 1.2 sp, as YouCloud sets its kickers.
 */
val KickerStyle = sans(12, 16, FontWeight.ExtraBold, 1.2f)

/**
 * Figures that have to line up or tick without jitter: latency, throughput, the session timer.
 * Onest's own tabular numerals, so the numbers are in the same voice as the words around them.
 */
val MonoStyle = TextStyle(
    fontFamily = Sans,
    fontWeight = FontWeight.SemiBold,
    fontFeatureSettings = "tnum",
    letterSpacing = 0.em,
    platformStyle = TightPlatform,
)

/** The display face with tabular figures, for a poster that is a number — the timer. */
val DisplayFigures = display(46, 54, -0.75f).copy(fontFeatureSettings = "tnum")
