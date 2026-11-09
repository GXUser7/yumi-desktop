package com.yumi.desktop

import androidx.compose.runtime.staticCompositionLocalOf

/** Continuous animation sleeps while the window is minimized or hidden in the tray. */
val LocalAnimationsActive = staticCompositionLocalOf { true }
