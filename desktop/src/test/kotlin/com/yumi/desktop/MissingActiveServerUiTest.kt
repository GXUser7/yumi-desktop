package com.yumi.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.yumi.desktop.model.ServiceStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import java.nio.file.Files
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class MissingActiveServerUiTest {
    @Test fun aRunningTunnelWithAnUnknownServerNeverShowsFirstRunOrTheSelectedServersName() {
        val directory = Files.createTempDirectory("yumi-missing-active-ui-").toFile()
        seedDemo(directory)
        val model = DesktopModel(DemoBackend(), directory, CoroutineScope(SupervisorJob() + StandardTestDispatcher()))
        model.status.value = ServiceStatus(state = "connected", nodeId = "removed-by-refresh", connectedAt = System.currentTimeMillis())
        model.connectionHealth.value = ConnectionHealth.Reachable
        try {
            runComposeUiTest {
                val compact = mutableStateOf(false)
                setContent {
                    CompositionLocalProvider(LocalAnimationsActive provides false, LocalBackdropDispatcher provides Dispatchers.Unconfined) {
                        Box(Modifier.requiredSize(if (compact.value) 680.dp else 1280.dp, 820.dp)) { YumiApp(model) }
                    }
                }
                for (small in listOf(false, true)) {
                    runOnIdle { compact.value = small }
                    onNodeWithText("Активный · нет в списке").assertExists()
                    onNodeWithText("Защищено").assertExists()
                    onNodeWithText("Не выбран").assertDoesNotExist()
                    onNodeWithText("Первый запуск", ignoreCase = true).assertDoesNotExist()
                    onNodeWithText("Уже пользуешься Yumi?").assertDoesNotExist()
                    onNodeWithText(model.profiles.nodes.first().name).assertDoesNotExist()
                    onNodeWithTag("connect-button").assertIsEnabled()
                }
            }
        } finally { runBlocking { model.close() }; directory.deleteRecursively() }
    }
}
