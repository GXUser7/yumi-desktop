package com.yumi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.ThemeMode
import com.yumi.desktop.model.ServiceStatus
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class TunnelIndicatorTest {
    private fun withModel(block: (DesktopModel) -> Unit) {
        val directory = Files.createTempDirectory("yumi-indicator-test-").toFile()
        seedDemo(directory)
        val model = DesktopModel(DemoBackend(), directory)
        try { block(model) }
        finally {
            runBlocking { model.close() }
            check(directory.parentFile.canonicalFile == File(System.getProperty("java.io.tmpdir")).canonicalFile && directory.name.startsWith("yumi-indicator-test-"))
            directory.deleteRecursively()
        }
    }

    @Test fun stateChangesReplaceIconsAndKeepNavigationAndTheActualError() = withModel { model ->
        val status = mutableStateOf(ServiceStatus())
        var opens = 0
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalAnimationsActive provides false) {
                    YumiTheme(AppSettings(themeMode = ThemeMode.Dark)) {
                        Box(Modifier.padding(24.dp)) { TunnelCookie(model, status.value) { opens++ } }
                    }
                }
            }
            onNodeWithTag("tunnel-status-idle", useUnmergedTree = true).assertExists()
            onNodeWithTag("tunnel-status").assertContentDescriptionEquals("Не защищено")
            runOnIdle { status.value = ServiceStatus(state = "connecting") }
            onNodeWithTag("tunnel-status-busy", useUnmergedTree = true).assertExists()
            onNodeWithTag("tunnel-status").assertContentDescriptionEquals("Подключение…")
            runOnIdle { status.value = ServiceStatus(state = "connected", killSwitch = true) }
            onNodeWithTag("tunnel-status-connected", useUnmergedTree = true).assertExists()
            onNodeWithTag("tunnel-status-busy", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag("tunnel-status").assertContentDescriptionEquals("Защищено").performClick()
            assertEquals(1, opens)
            runOnIdle { status.value = ServiceStatus(state = "disconnecting") }
            onNodeWithTag("tunnel-status").assertContentDescriptionEquals("Отключение…")
            runOnIdle { status.value = ServiceStatus(state = "failed", error = "adapter_unavailable", detail = "TUN adapter could not open: 0x490", killSwitch = true) }
            onNodeWithTag("tunnel-status-error", useUnmergedTree = true).assertExists()
            onNodeWithTag("tunnel-status").assertContentDescriptionContains("TUN adapter could not open: 0x490", substring = true)
            onNodeWithTag("tunnel-status").assertContentDescriptionContains("Килл свитч удерживает интернет", substring = true)
            runOnIdle { status.value = ServiceStatus(state = "failed", error = "pipe read timed out") }
            onNodeWithTag("tunnel-status").assertContentDescriptionContains("pipe read timed out", substring = true)
            runOnIdle { status.value = ServiceStatus(state = "connected") }
            onNodeWithTag("tunnel-status-error", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag("tunnel-status").assertContentDescriptionEquals("Защищено")
        }
    }

    @Test fun hoverShowsTheServiceFailureAndCoreDetails() = withModel { model ->
        val detail = "Failed to create adapter: access denied"
        runComposeUiTest {
            setContent {
                YumiTheme(AppSettings(themeMode = ThemeMode.Dark)) {
                    Box(Modifier.fillMaxSize().padding(24.dp)) {
                        TunnelCookie(model, ServiceStatus(state = "unavailable", error = "service_denied", detail = detail)) {}
                    }
                }
            }
            onNodeWithTag("tunnel-status").performMouseInput { enter(center) }
            mainClock.advanceTimeBy(500)
            onNodeWithText(detail, substring = true).assertIsDisplayed()
            onNodeWithText("зарегистрирована на другую учётную запись", substring = true).assertExists()
        }
    }

    @Suppress("DEPRECATION")
    @Test fun settledIndicatorsStopRequestingFrames() = withModel { model ->
        val states = listOf(ServiceStatus(), ServiceStatus(state = "connecting"), ServiceStatus(state = "connected"), ServiceStatus(state = "failed", error = "worker_stopped"))
        val scene = ImageComposeScene(width = 340, height = 192) {
            Column(Modifier.width(340.dp)) {
                for (theme in listOf(ThemeMode.Dark, ThemeMode.Light)) {
                    YumiTheme(AppSettings(themeMode = theme)) {
                        Surface(color = MaterialTheme.colorScheme.background) {
                                Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                    states.forEach { status ->
                                        CompositionLocalProvider(LocalAnimationsActive provides (status.state != "connecting")) {
                                            TunnelCookie(model, status) {}
                                        }
                                    }
                                }
                        }
                    }
                }
            }
        }
        try {
            repeat(20) { scene.render(it * 16_666_667L).close() }
            assertFalse(scene.hasInvalidations(), "Settled status icons must not keep the frame clock alive")
            val output = File("build/reports/ui/tunnel-indicator.png").also { it.parentFile.mkdirs() }
            scene.render(21 * 16_666_667L).use { image ->
                image.encodeToData()!!.use { output.writeBytes(it.bytes) }
            }
        } finally { scene.close() }
    }
}
