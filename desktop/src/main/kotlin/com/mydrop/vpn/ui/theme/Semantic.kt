package com.mydrop.vpn.ui.theme

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The phone keeps this beside `MyDropTheme`; here the theme lives in the application package, so
 * the composition local sits with the colours it carries and [com.yumi.desktop.YumiTheme] provides
 * it. Anything reading tunnel health or latency quality reads it from here rather than from a
 * constant of its own — see [toSemanticColors].
 */
val LocalSemanticColors: ProvidableCompositionLocal<MyDropSemanticColors> =
    staticCompositionLocalOf { MyDropDarkColors.toSemanticColors(dark = true) }
