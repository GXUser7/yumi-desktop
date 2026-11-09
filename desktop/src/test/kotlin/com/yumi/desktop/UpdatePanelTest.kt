package com.yumi.desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import com.mydrop.vpn.core.model.AppLanguage
import com.yumi.desktop.update.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class UpdatePanelTest {
    @Test fun checkDownloadCancelRetryAndRestartAreReachableFromSettings() {
        val directory = Files.createTempDirectory("yumi-update-ui-test-").toFile()
        seedDemo(directory)
        val model = DesktopModel(DemoBackend(), directory)
        model.desktop.update { it.copy(autoUpdate = false) }
        val release = DesktopRelease("0.3.0", "", "", "", 100)
        val finish = CompletableDeferred<Unit>()
        val updater = DesktopUpdater(model.scope, object : UpdateSource {
            override suspend fun latest() = release
            override suspend fun download(release: DesktopRelease, progress: (Long, Long) -> Unit): VerifiedUpdate {
                progress(50, 100)
                finish.await()
                return VerifiedUpdate(release, File(directory, "test-installer"), "not-executed")
            }
        }, model.desktop.state, AppVersion, installed = true, automatic = false)
        var installRequests = 0
        try {
            runComposeUiTest {
                setContent {
                    val settings by model.settings.settings.collectAsState()
                    val desktop by model.desktop.state.collectAsState()
                    YumiTheme(settings, desktop.palette) { UpdatePanel(model, desktop, { installRequests++ }, updater) }
                }
                onNodeWithText("Проверить обновления").performClick()
                waitUntil { updater.state.value is DesktopUpdateState.Available }
                onNodeWithText("Скачать обновление").performClick()
                waitUntil { updater.state.value is DesktopUpdateState.Downloading }
                onNodeWithText("Скачивается 0.3.0 · 50 %").assertExists()
                onNodeWithText("Отмена").performClick()
                waitUntil { updater.state.value is DesktopUpdateState.Available }
                onNodeWithText("Скачать обновление").performClick()
                waitUntil { updater.state.value is DesktopUpdateState.Downloading }
                finish.complete(Unit)
                waitUntil { updater.state.value is DesktopUpdateState.Ready }
                onNodeWithText("Перезапустить и обновить").assertIsDisplayed().performClick()
                assertEquals(1, installRequests)
                onNodeWithText("Обновлять автоматически").performClick()
                waitUntil { model.desktop.value.autoUpdate }
                runBlocking { model.changeSettings({ it.copy(language = AppLanguage.English) }).join() }
                onNodeWithText("Restart and update").assertExists()
                onNodeWithText("Update automatically").assertExists()
            }
        } finally {
            runBlocking { model.close() }
            check(directory.parentFile.canonicalFile == File(System.getProperty("java.io.tmpdir")).canonicalFile && directory.name.startsWith("yumi-update-ui-test-"))
            directory.deleteRecursively()
        }
    }
}
