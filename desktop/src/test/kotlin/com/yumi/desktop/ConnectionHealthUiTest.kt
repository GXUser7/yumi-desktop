package com.yumi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.mydrop.vpn.core.model.AppSettings
import com.yumi.desktop.model.ServiceStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import java.nio.file.Files
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ConnectionHealthUiTest {
    @Test fun bothHeaderLayoutsAndShieldStopClaimingProtectionForADeadServer() {
        val directory = Files.createTempDirectory("yumi-health-ui-").toFile()
        seedDemo(directory)
        // Keep service polling outside this presentation test; health changes are explicit.
        val model = DesktopModel(DemoBackend(), directory, CoroutineScope(SupervisorJob() + StandardTestDispatcher()))
        try {
            runComposeUiTest {
                val health = mutableStateOf(ConnectionHealth.Checking)
                val compact = mutableStateOf(false)
                setContent {
                    CompositionLocalProvider(LocalAnimationsActive provides false) {
                        YumiTheme(AppSettings()) {
                            Column(Modifier.width(780.dp)) {
                                val status = ServiceStatus(state = "connected", nodeId = model.profiles.nodes.first().id)
                                Headline(model, status, model.profiles.nodes.first(), false, Modifier.fillMaxWidth(), compact.value, health.value)
                                TunnelCookie(model, status, health = health.value) {}
                            }
                        }
                    }
                }
                onNodeWithText("Проверяем").assertExists()
                onNodeWithText("Защищено").assertDoesNotExist()
                runOnIdle { health.value = ConnectionHealth.Unreachable }
                onNodeWithText("Сервер").assertExists()
                onNodeWithText("недоступен").assertExists()
                onNodeWithText("Защищено").assertDoesNotExist()
                onNodeWithTag("tunnel-status-error", useUnmergedTree = true).assertExists()
                onNodeWithTag("tunnel-status").assertContentDescriptionContains("Сервер недоступен", substring = true)
                runOnIdle { compact.value = true }
                onNodeWithText("Сервер недоступен").assertExists()
                runOnIdle { health.value = ConnectionHealth.Reachable }
                onNodeWithText("Защищено").assertExists()
                onNodeWithTag("tunnel-status-error", useUnmergedTree = true).assertDoesNotExist()
                onNodeWithTag("tunnel-status").assertContentDescriptionEquals("Защищено")
            }
        } finally { runBlocking { model.close() }; directory.deleteRecursively() }
    }
}
