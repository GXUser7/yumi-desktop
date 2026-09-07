package com.mydrop.vpn.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.platform.Font

/*
 * Three voices, two superfamilies.
 *
 * The poster voice is Roboto Flex instanced at wght 880 / wdth 90 / opsz 144: heavy, slightly
 * narrowed, optically sized for large settings. System Roboto cannot reach it — there is no
 * width axis and nothing above Black — which is why the faces are bundled rather than requested
 * from the platform.
 *
 * Static instances rather than the variable file: pinning the axes at build time drops every
 * delta table, so all five faces together come to ~170 KB instead of ~1.8 MB, and none of the
 * runtime variable-font API is involved.
 */

// The 144pt optical instance is the app's face and it stays the app's face here: at the sizes a
// window sets a poster headline — 32 px and up — it renders exactly as it does on the phone. It is
// only below that, where the counters of a 144pt instance start to close in Skia, that the UI
// optical instance takes over; see [heading] for where the line falls.
private val Display = FontFamily(Font("font/roboto_flex_display.ttf", FontWeight.Black))

private val Ui = FontFamily(
    Font("font/roboto_flex_regular.ttf", FontWeight.Normal),
    Font("font/roboto_flex_medium.ttf", FontWeight.Medium),
    Font("font/roboto_flex_semibold.ttf", FontWeight.SemiBold),
)

/** Digits that line up in columns: latency, throughput, the session timer. */
val MonoFamily = FontFamily(Font("font/roboto_mono_medium.ttf", FontWeight.Medium))

/**
 * Pictures, which Windows draws inconsistently and in one case not at all.
 *
 * Twemoji's COLR build, brought along because the platform cannot be relied on for the emoji a
 * subscription writes its server names in. Named explicitly wherever those names are set — see
 * [emojiText][com.mydrop.vpn.ui.components.emojiText] — and never installed as a fallback for
 * body text, so nothing here depends on how a particular Skia build resolves a codepoint.
 */
val EmojiFamily = FontFamily(Font("font/twemoji.ttf"))

/**
 * Trimmed leading. Poster type set at 0.86 line height only looks right once the font's own
 * ascent/descent padding is out of the way, and [LineHeightStyle.Trim] needs
 * `includeFontPadding = false` to have anything to trim.
 */
@Suppress("DEPRECATION")
private val TightPlatform = PlatformTextStyle(null, null)

private val TrimBoth = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.Both,
)

private fun poster(size: Int, tracking: Float = -0.04f) = TextStyle(
    fontFamily = Display,
    fontWeight = FontWeight.Black,
    fontSynthesis = FontSynthesis.None,
    fontSize = size.sp,
    lineHeight = (size * 0.86f).sp,
    letterSpacing = tracking.em,
    platformStyle = TightPlatform,
    lineHeightStyle = TrimBoth,
)

/**
 * The poster voice at section-title sizes, set in the UI optical instance instead.
 *
 * Same shape of style — tight leading, negative tracking, trimmed — so a section title still
 * belongs to the same family as the headline above it. What changes is the face: a display
 * instance is drawn to be seen at a poster's distance, and at 28 px on a monitor its counters fill
 * in and the word turns into a smudge.
 */
private fun heading(size: Int, tracking: Float = -0.03f) = TextStyle(
    fontFamily = Ui,
    fontWeight = FontWeight.SemiBold,
    fontSize = size.sp,
    lineHeight = (size * 0.94f).sp,
    letterSpacing = tracking.em,
    platformStyle = TightPlatform,
    lineHeightStyle = TrimBoth,
)

private fun ui(
    size: Int,
    weight: FontWeight = FontWeight.Normal,
    lineHeight: Float = 1.42f,
    tracking: Float = 0f,
) = TextStyle(
    fontFamily = Ui,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = (size * lineHeight).sp,
    letterSpacing = tracking.em,
    platformStyle = TightPlatform,
)

val MyDropTypography = Typography(
    // Screen headlines. displayLarge is the tunnel screen's state word; the smaller steps carry
    // the same voice onto screens whose titles are longer.
    displayLarge = poster(52),
    displayMedium = poster(44),
    displaySmall = poster(38),

    headlineLarge = poster(32, tracking = -0.03f),
    headlineMedium = heading(26),
    headlineSmall = heading(21, tracking = -0.025f),

    titleLarge = ui(20, FontWeight.SemiBold, lineHeight = 1.3f),
    titleMedium = ui(16, FontWeight.SemiBold, lineHeight = 1.35f),
    titleSmall = ui(14, FontWeight.SemiBold, lineHeight = 1.35f),

    bodyLarge = ui(16),
    bodyMedium = ui(14),
    bodySmall = ui(12, lineHeight = 1.35f),

    labelLarge = ui(14, FontWeight.Medium, lineHeight = 1.2f),
    labelMedium = ui(12, FontWeight.Medium, lineHeight = 1.2f, tracking = 0.02f),
    // Uppercase micro-labels ("ПРИЁМ", "МС") need the extra tracking to stay readable.
    labelSmall = ui(10, FontWeight.Medium, lineHeight = 1.2f, tracking = 0.08f),
)

/** Tabular figures for anything that has to line up or tick without jitter. */
val MonoStyle = TextStyle(
    fontFamily = MonoFamily,
    fontWeight = FontWeight.Medium,
    letterSpacing = (-0.02f).em,
    platformStyle = TightPlatform,
)
