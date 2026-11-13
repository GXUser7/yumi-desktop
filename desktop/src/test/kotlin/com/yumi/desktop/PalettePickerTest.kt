package com.yumi.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.Image
import com.mydrop.vpn.data.JsonStore
import com.yumi.desktop.model.DesktopSettings
import com.yumi.desktop.model.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class PalettePickerTest {
    @Test fun fifteenNamedPalettesWrapInACompactWindowAndPersistTheSelection() {
        val directory = Files.createTempDirectory("yumi-palette-test-").toFile()
        seedDemo(directory)
        val model = DesktopModel(DemoBackend(), directory)
        try {
            runComposeUiTest {
                setContent {
                    CompositionLocalProvider(LocalAnimationsActive provides false, LocalBackdropDispatcher provides Dispatchers.Unconfined) {
                        Box(Modifier.requiredSize(680.dp, 600.dp)) { YumiApp(model, Page.Settings) }
                    }
                }
                onNodeWithTag("palette-Coffee").performScrollTo().assertIsDisplayed().performClick()
                waitUntil { model.desktop.value.palette == Palette.Coffee }
                onNodeWithTag("palette-Coffee").assertIsSelected()
                fun capture(name: String) {
                    val bitmap = onNodeWithTag("palette-picker").captureToImage()
                    try {
                        Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { image ->
                            checkNotNull(image.encodeToData()).use { data ->
                                File("build/reports/theme-picker/$name.png").also { it.parentFile.mkdirs() }.writeBytes(data.bytes)
                            }
                        }
                    } finally { bitmap.asSkiaBitmap().close() }
                }
                capture("coffee-compact")
                val picker = onNodeWithTag("palette-picker").fetchSemanticsNode().boundsInRoot
                for (palette in Palette.entries) {
                    val bounds = onNodeWithTag("palette-${palette.name}").fetchSemanticsNode().boundsInRoot
                    assertTrue(bounds.left >= picker.left && bounds.right <= picker.right + 1f)
                }
                onNodeWithTag("palette-Graphite").performScrollTo().performClick()
                waitUntil { model.desktop.value.palette == Palette.Graphite }
                onNodeWithTag("palette-Graphite").assertIsSelected()
                capture("graphite-compact")
                assertEquals(Palette.Graphite, JsonStore(File(directory, "desktop.json"), DesktopSettings.serializer(), DesktopSettings(), model.scope).value.palette)
            }
        } finally { runBlocking { model.close() }; directory.deleteRecursively() }
    }
}
