package com.yumi.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class CompactLayoutTest {
    @Test fun minimumWindowKeepsTheRailAlignedAndItsEndControlsApartFromNavigation() {
        val directory = Files.createTempDirectory("yumi-compact-test-").toFile()
        seedDemo(directory)
        val model = DesktopModel(DemoBackend(), directory)
        try {
            runComposeUiTest {
                val windowHeight = mutableStateOf(600.dp)
                setContent {
                    CompositionLocalProvider(LocalAnimationsActive provides false, LocalBackdropDispatcher provides Dispatchers.Unconfined) {
                        Box(Modifier.requiredSize(680.dp, windowHeight.value)) { YumiApp(model) }
                    }
                }
                val rail = onNodeWithTag("sidebar").fetchSemanticsNode().boundsInRoot
                val page = onNodeWithTag("page-content").fetchSemanticsNode().boundsInRoot
                val logo = onNodeWithTag("app-mark").fetchSemanticsNode().boundsInRoot
                val first = onNodeWithTag("nav-Connect").fetchSemanticsNode().boundsInRoot
                val settings = onNodeWithTag("nav-Settings").fetchSemanticsNode().boundsInRoot
                val status = onNodeWithTag("tunnel-status").fetchSemanticsNode().boundsInRoot
                assertEquals(rail.top, page.top)
                val settingsIsland = onNodeWithTag("settings-island").fetchSemanticsNode().boundsInRoot
                assertEquals(settingsIsland.size, status.size)
                assertEquals(onNodeWithTag("flow-figure").fetchSemanticsNode().boundsInRoot.top,
                    onNodeWithTag("nav-panel").fetchSemanticsNode().boundsInRoot.top, 1f)
                assertTrue(first.top - logo.bottom >= 16f)
                assertTrue(status.top - settings.bottom >= 16f)
                onNodeWithTag("compact-connect").assertIsDisplayed()
                onNodeWithTag("connect-button").assertIsDisplayed()
                val map = onNodeWithTag("flow-figure").fetchSemanticsNode().boundsInRoot
                val control = onNodeWithTag("compact-control").fetchSemanticsNode().boundsInRoot
                assertEquals((map.width - 12f) / 2, control.width, 0.5f)
                onNodeWithTag("server-button").assertIsDisplayed().performClick()
                onNodeWithTag("server-list").assertIsDisplayed()
                model.busy.value = true
                model.pinging.value = true
                onNodeWithTag("ping-servers").assertIsEnabled()
                val search = onNodeWithTag("server-search").fetchSemanticsNode().boundsInRoot
                val ping = onNodeWithTag("ping-servers").fetchSemanticsNode().boundsInRoot
                assertEquals(search.height, ping.height, 0.5f)
                assertEquals(search.top, ping.top, 0.5f)
                windowHeight.value = 560.dp // A 600px native window also spends height on its title bar.
                onNodeWithTag("nav-Connect").performClick()
                val shortMap = onNodeWithTag("flow-figure").fetchSemanticsNode().boundsInRoot
                val shortNav = onNodeWithTag("nav-panel").fetchSemanticsNode().boundsInRoot
                val shortSettings = onNodeWithTag("settings-island").fetchSemanticsNode().boundsInRoot
                val shortStatus = onNodeWithTag("tunnel-status").fetchSemanticsNode().boundsInRoot
                assertEquals(shortMap.top, shortNav.top, 1f)
                assertEquals(shortSettings.size, shortStatus.size)
                assertTrue(shortStatus.top - shortSettings.bottom >= 11f)
            }
        } finally { runBlocking { model.close() }; directory.deleteRecursively() }
    }
    @Test fun widePagesShareTheHeaderAndAllThreeDockPositions() {
        val directory = Files.createTempDirectory("yumi-grid-layout-test-").toFile()
        seedDemo(directory)
        val model = DesktopModel(DemoBackend(), directory)
        try {
            runComposeUiTest {
                setContent {
                    CompositionLocalProvider(LocalAnimationsActive provides false, LocalBackdropDispatcher provides Dispatchers.Unconfined) {
                        Box(Modifier.requiredSize(1024.dp, 768.dp)) { YumiApp(model) }
                    }
                }
                val logo = onNodeWithTag("app-mark").fetchSemanticsNode().boundsInRoot
                val nav = onNodeWithTag("nav-panel").fetchSemanticsNode().boundsInRoot
                val cookie = onNodeWithTag("tunnel-status").fetchSemanticsNode().boundsInRoot
                val map = onNodeWithTag("flow-figure").fetchSemanticsNode().boundsInRoot
                assertEquals(map.top, nav.top, 1f)
                assertEquals(map.bottom, cookie.bottom, 1f)
                for (page in Page.entries) {
                    onNodeWithTag("nav-${page.name}").performClick()
                    onNodeWithTag("page-header").assertIsDisplayed()
                    assertEquals(logo.top, onNodeWithTag("page-header").fetchSemanticsNode().boundsInRoot.top, 1f)
                    assertEquals(logo, onNodeWithTag("app-mark").fetchSemanticsNode().boundsInRoot)
                    assertEquals(nav, onNodeWithTag("nav-panel").fetchSemanticsNode().boundsInRoot)
                    assertEquals(cookie, onNodeWithTag("tunnel-status").fetchSemanticsNode().boundsInRoot)
                }
            }
        } finally { runBlocking { model.close() }; directory.deleteRecursively() }
    }

}
