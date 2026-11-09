package com.yumi.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.ThemeMode
import com.yumi.desktop.model.Palette

/** Real Compose/Skia draws: a moving foreground must not rebuild a frozen blurred scene. */
@Suppress("DEPRECATION")
class BackdropRenderingTest {
    @Test fun frozenBackdropRebuildsForPaletteAndWindowSizeAndSleepsWhileHidden() {
        val palette = mutableStateOf(Palette.Glacier)
        var backdrop: BackdropState? = null
        val scene = ImageComposeScene(width = 600, height = 400) {
            CompositionLocalProvider(LocalBackdropDispatcher provides Dispatchers.Unconfined, LocalAnimationsActive provides false) {
                YumiTheme(AppSettings(themeMode = ThemeMode.Dark), palette.value) {
                    ExpressiveBackdrop(animated = true) { backdrop = LocalBackdrop.current }
                }
            }
        }
        try {
            var time = 0L
            fun frames() { repeat(8) { Snapshot.sendApplyNotifications(); scene.render(time).close(); time += 16_666_667L } }
            frames()
            val first = checkNotNull(backdrop).frame
            assertTrue(first > 0)
            frames()
            assertEquals(first, checkNotNull(backdrop).frame)
            // Resume as a frozen visible window: size and palette edits must repaint its glass.
            scene.setContent {
                CompositionLocalProvider(LocalBackdropDispatcher provides Dispatchers.Unconfined) {
                    YumiTheme(AppSettings(themeMode = ThemeMode.Dark), palette.value) {
                        ExpressiveBackdrop(animated = false) { backdrop = LocalBackdrop.current }
                    }
                }
            }
            frames()
            val beforeResize = checkNotNull(backdrop).frame
            scene.constraints = Constraints.fixed(800, 600)
            frames()
            assertTrue(checkNotNull(backdrop).frame > beforeResize)
            assertEquals(200, checkNotNull(backdrop).frost?.width)
            val beforePalette = checkNotNull(backdrop).frame
            palette.value = Palette.entries.first { it != Palette.Glacier }
            frames()
            assertTrue(checkNotNull(backdrop).frame > beforePalette)
        } finally { scene.close() }
    }

    @Test fun frozenBackdropSettlesAndDoesNotFollowForegroundFrames() {
        val tick = mutableIntStateOf(0)
        var backdrop: BackdropState? = null
        var compositions = 0
        val scene = ImageComposeScene(width = 600, height = 400) {
            CompositionLocalProvider(LocalBackdropDispatcher provides Dispatchers.Unconfined) { MaterialTheme {
                ExpressiveBackdrop(animated = false) {
                    backdrop = LocalBackdrop.current
                    SideEffect { compositions++ }
                    Box(Modifier.fillMaxSize().glass(RoundedCornerShape(24.dp), Color.White.copy(alpha = 0.5f))) {
                        Box(Modifier.fillMaxSize().drawBehind {
                            drawRect(if (tick.intValue % 2 == 0) Color.Red else Color.Blue)
                        })
                    }
                }
            } }
        }
        try {
            var time = 0L
            repeat(5) { scene.render(time).close(); time += 16_666_667L }
            val before = Snapshot.withoutReadObservation { checkNotNull(backdrop).frame }
            assertTrue(before > 0, "The worker must publish an initial scene")
            val beforeCompositions = compositions
            repeat(20) {
                tick.intValue++
                Snapshot.sendApplyNotifications()
                scene.render(time).close()
                time += 16_666_667L
            }
            val extraRecordings = Snapshot.withoutReadObservation { checkNotNull(backdrop).frame } - before
            println("Frozen backdrop: $extraRecordings scene recordings for 20 foreground frames")
            assertEquals(0L, extraRecordings, "Foreground animation rebuilt the frozen backdrop")
            assertEquals(beforeCompositions, compositions, "Draws must not recompose the content")
            repeat(5) { scene.render(time).close(); time += 16_666_667L }
            assertFalse(scene.hasInvalidations(), "A frozen scene keeps scheduling frames")
        } finally {
            scene.close()
        }
    }
}
