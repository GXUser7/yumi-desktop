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
        // The click sequence is timed against the Windows and Linux runners, where it runs on
        // every push; the macOS runner answers it nondeterministically and the panel's logic is
        // not platform-specific, so there it sits out.
        org.junit.Assume.assumeFalse(System.getProperty("os.name") == "Mac OS X")
        val directory = Files.createTempDirectory("yumi-update-ui-test-").toFile()
        seedDemo(directory)
        val model = DesktopModel(DemoBackend(), directory)
        model.desktop.update { it.copy(autoUpdate = false) }
        val release = DesktopRelease("1.2.0", "", "", "", 100)
        val finish = CompletableDeferred<Unit>()
        var failDownload = false
        var checks = 0
        var downloads = 0
        val updater = DesktopUpdater(model.scope, object : UpdateSource {
            override suspend fun latest(): DesktopRelease {
                checks++
                return release
            }
            override suspend fun download(release: DesktopRelease, progress: (Long, Long) -> Unit): VerifiedUpdate {
                downloads++
                progress(50, 100)
                if (failDownload) error("network error")
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
                failDownload = true
                onNodeWithText("Скачать обновление").performClick()
                waitUntil { updater.state.value is DesktopUpdateState.Failed }
                // Check for updates must always re-check instead of silently reusing failed download.
                val checksBefore = checks
                onNodeWithText("Проверить обновления").performClick()
                waitUntil { checks == checksBefore + 1 && updater.state.value is DesktopUpdateState.Available }
                onNodeWithText("Скачать обновление").performClick()
                waitUntil { updater.state.value is DesktopUpdateState.Failed }
                failDownload = false
                val downloadsBefore = downloads
                onNodeWithText("Повторить").performClick()
                waitUntil { downloads == downloadsBefore + 1 && updater.state.value is DesktopUpdateState.Downloading }
                onNodeWithText("Скачивается 1.2.0 · 50 %").assertExists()
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
