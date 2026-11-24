package com.yumi.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.yumi.desktop.model.ConnectRequestFactory
import com.yumi.desktop.model.SplitMode
import com.yumi.desktop.platform.DesktopApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import java.awt.Color as AwtColor
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class RoutingAppsTest {
    private fun solidBitmap(color: AwtColor): ImageBitmap {
        val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = color
            graphics.fillRect(0, 0, 32, 32)
        } finally {
            graphics.dispose()
        }
        return image.toComposeImageBitmap()
    }

    private fun centerPixel(node: SemanticsNodeInteraction): androidx.compose.ui.graphics.Color {
        val bitmap = node.captureToImage()
        try {
            val map = bitmap.toPixelMap()
            return map[map.width / 2, map.height / 2]
        } finally {
            bitmap.asSkiaBitmap().close()
        }
    }

    @Test fun removingSelectedApplicationDoesNotShiftIconsOntoRemainingRows() {
        AppIconCache.clear()
        val directory = Files.createTempDirectory("yumi-routing-icons-test-").toFile()
        seedDemo(directory)
        val model = DesktopModel(DemoBackend(), directory)
        val firstPath = "/fake/first.exe"
        val thirdPath = "/fake/third.exe"
        val fourthPath = "/fake/fourth.exe"
        AppIconCache.put(firstPath, solidBitmap(AwtColor(235, 30, 30)))
        AppIconCache.put(thirdPath, solidBitmap(AwtColor(30, 220, 40)))
        AppIconCache.put(fourthPath, solidBitmap(AwtColor(30, 80, 240)))
        model.knownApps.value = listOf(
            DesktopApp("first.exe", "First App", firstPath, running = true),
            DesktopApp("second.exe", "Second App", null, running = false),
            DesktopApp("third.exe", "Third App", thirdPath, running = true),
            DesktopApp("fourth.exe", "Fourth App", fourthPath, running = false),
        )
        model.desktop.update {
            it.copy(
                splitMode = SplitMode.OnlySelected,
                applications = listOf("first.exe", "second.exe", "third.exe", "fourth.exe"),
            )
        }
        try {
            runComposeUiTest {
                setContent {
                    CompositionLocalProvider(LocalAnimationsActive provides false, LocalBackdropDispatcher provides Dispatchers.Unconfined) {
                        Box(Modifier.requiredSize(960.dp, 720.dp)) { YumiApp(model, Page.Routing) }
                    }
                }
                onNodeWithTag("routing-app-first.exe").performScrollTo().assertIsDisplayed()
                onNodeWithTag("routing-app-third.exe").performScrollTo().assertIsDisplayed()
                onNodeWithTag("routing-app-fourth.exe").performScrollTo().assertIsDisplayed()

                val firstBefore = centerPixel(onNodeWithTag("routing-app-icon-first.exe"))
                val thirdBefore = centerPixel(onNodeWithTag("routing-app-icon-third.exe"))
                val fourthBefore = centerPixel(onNodeWithTag("routing-app-icon-fourth.exe"))
                assertTrue(firstBefore.red > 0.7f && firstBefore.green < 0.3f, "first.exe should render its red icon: $firstBefore")
                assertTrue(thirdBefore.green > 0.7f && thirdBefore.red < 0.3f, "third.exe should render its green icon: $thirdBefore")
                assertTrue(fourthBefore.blue > 0.7f && fourthBefore.red < 0.3f, "fourth.exe should render its blue icon: $fourthBefore")

                // Delete the first app (with cached red icon) and the second app (with null/fallback icon).
                onNodeWithTag("routing-app-remove-first.exe").performScrollTo().performClick()
                waitUntil { model.desktop.value.applications == listOf("second.exe", "third.exe", "fourth.exe") }
                onNodeWithTag("routing-app-first.exe").assertDoesNotExist()

                val thirdAfterFirst = centerPixel(onNodeWithTag("routing-app-icon-third.exe").performScrollTo())
                val fourthAfterFirst = centerPixel(onNodeWithTag("routing-app-icon-fourth.exe").performScrollTo())
                assertTrue(thirdAfterFirst.green > 0.7f && thirdAfterFirst.red < 0.3f, "third.exe kept green icon after deleting first.exe: $thirdAfterFirst")
                assertTrue(fourthAfterFirst.blue > 0.7f && fourthAfterFirst.green < 0.45f, "fourth.exe kept blue icon after deleting first.exe: $fourthAfterFirst")

                // Exact screenshot scenario: delete an app with fallback terminal icon right above an app with a real icon.
                onNodeWithTag("routing-app-remove-second.exe").performScrollTo().performClick()
                waitUntil { model.desktop.value.applications == listOf("third.exe", "fourth.exe") }
                onNodeWithTag("routing-app-second.exe").assertDoesNotExist()

                val thirdAfterSecond = centerPixel(onNodeWithTag("routing-app-icon-third.exe").performScrollTo())
                val fourthAfterSecond = centerPixel(onNodeWithTag("routing-app-icon-fourth.exe").performScrollTo())
                assertTrue(thirdAfterSecond.green > 0.7f && thirdAfterSecond.red < 0.3f, "third.exe kept green icon after deleting fallback-icon row: $thirdAfterSecond")
                assertTrue(fourthAfterSecond.blue > 0.7f && fourthAfterSecond.green < 0.45f, "fourth.exe kept blue icon after deleting fallback-icon row: $fourthAfterSecond")
            }
        } finally {
            AppIconCache.clear()
            runBlocking { model.close() }
            directory.deleteRecursively()
        }
    }

    @Test fun rememberAppIconTracksPathTransitionsAcrossCachedNullAndUncachedEntries() {
        AppIconCache.clear()
        val red = solidBitmap(AwtColor(235, 30, 30))
        val green = solidBitmap(AwtColor(30, 220, 40))
        val blue = solidBitmap(AwtColor(30, 80, 240))
        AppIconCache.put("/cached/a.exe", red)
        AppIconCache.put("/cached/b.exe", green)
        val gate = CompletableDeferred<ImageBitmap?>()
        try {
            runComposeUiTest {
                val path = mutableStateOf<String?>(null)
                var observed: ImageBitmap? = red
                setContent {
                    observed = rememberAppIcon(path.value) { target ->
                        if (target == "/uncached/c.exe") gate.await() else null
                    }
                }
                waitForIdle()
                assertNull(observed)

                // null -> cached A must update immediately, not stay stuck on null.
                path.value = "/cached/a.exe"
                waitForIdle()
                assertSame(red, observed)

                // cached A -> cached B must switch to B, not keep stale A.
                path.value = "/cached/b.exe"
                waitForIdle()
                assertSame(green, observed)

                // cached B -> uncached C must clear stale B immediately while loading, then publish C.
                path.value = "/uncached/c.exe"
                waitForIdle()
                assertNull(observed)
                gate.complete(blue)
                waitUntil { observed === blue }

                // cached/loaded C -> null must clear back to null.
                path.value = null
                waitForIdle()
                assertNull(observed)
            }
        } finally {
            AppIconCache.clear()
        }
    }

    @Test fun routingSwitchesToggleApplicationsAndSitesIndependentlyWhilePreservingLists() {
        val directory = Files.createTempDirectory("yumi-routing-switches-test-").toFile()
        seedDemo(directory)
        val model = DesktopModel(DemoBackend(), directory)
        model.knownApps.value = listOf(DesktopApp("chrome.exe", "Google Chrome", null, running = true))
        model.desktop.update {
            it.copy(
                splitMode = SplitMode.OnlySelected,
                applications = listOf("chrome.exe"),
                domains = listOf("example.com"),
                networks = listOf("192.0.2.0/24"),
            )
        }
        try {
            runComposeUiTest {
                setContent {
                    CompositionLocalProvider(LocalAnimationsActive provides false, LocalBackdropDispatcher provides Dispatchers.Unconfined) {
                        Box(Modifier.requiredSize(960.dp, 720.dp)) { YumiApp(model, Page.Routing) }
                    }
                }
                fun capture(name: String) {
                    val bitmap = onRoot().captureToImage()
                    try {
                        Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { image ->
                            checkNotNull(image.encodeToData()).use { data ->
                                File("build/reports/routing-test/$name.png").also { it.parentFile.mkdirs() }.writeBytes(data.bytes)
                            }
                        }
                    } finally {
                        bitmap.asSkiaBitmap().close()
                    }
                }

                onNodeWithTag("routing-apps-switch").performScrollTo().assertIsOn()
                onNodeWithTag("routing-sites-switch").performScrollTo().assertIsOn()

                onNodeWithTag("routing-apps-switch").performScrollTo().performClick()
                waitUntil { !model.desktop.value.applicationsEnabled }
                onNodeWithTag("routing-apps-switch").assertIsOff()
                onNodeWithTag("routing-app-chrome.exe").performScrollTo().assertIsDisplayed()
                val afterAppsOff = ConnectRequestFactory.options(model.settings.value, model.desktop.value)
                assertEquals(emptyList(), afterAppsOff.applications)
                assertEquals(listOf("example.com"), afterAppsOff.domains)
                assertEquals(listOf("192.0.2.0/24"), afterAppsOff.networks)

                onNodeWithTag("routing-sites-switch").performScrollTo().performClick()
                waitUntil { !model.desktop.value.sitesEnabled }
                onNodeWithTag("routing-sites-switch").assertIsOff()
                val afterBothOff = ConnectRequestFactory.options(model.settings.value, model.desktop.value)
                assertEquals(emptyList(), afterBothOff.applications)
                assertEquals(emptyList(), afterBothOff.domains)
                assertEquals(emptyList(), afterBothOff.networks)
                assertEquals(listOf("chrome.exe"), model.desktop.value.applications)
                assertEquals(listOf("example.com"), model.desktop.value.domains)
                assertEquals(listOf("192.0.2.0/24"), model.desktop.value.networks)
                capture("routing-switches-off")

                onNodeWithTag("routing-apps-switch").performScrollTo().performClick()
                onNodeWithTag("routing-sites-switch").performScrollTo().performClick()
                waitUntil { model.desktop.value.applicationsEnabled && model.desktop.value.sitesEnabled }
                val afterBothOn = ConnectRequestFactory.options(model.settings.value, model.desktop.value)
                assertEquals(listOf("chrome.exe"), afterBothOn.applications)
                assertEquals(listOf("example.com"), afterBothOn.domains)
                assertEquals(listOf("192.0.2.0/24"), afterBothOn.networks)
                capture("routing-switches-on")
            }
        } finally {
            runBlocking { model.close() }
            directory.deleteRecursively()
        }
    }
}
