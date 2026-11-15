package com.yumi.desktop

import com.mydrop.vpn.core.model.*
import com.mydrop.vpn.data.*
import com.yumi.desktop.model.*
import com.yumi.desktop.platform.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceInstallationTest {
    private class Backend : TunnelBackend {
        var installed = false
        var connects = 0
        override suspend fun request(request: ServiceRequest): ServiceStatus {
            if (!installed) return ServiceStatus(state = "unavailable", error = "service_missing")
            if (request.command == "connect") connects++
            return ServiceStatus(state = if (connects > 0 && request.command != "disconnect") "connected" else "disconnected",
                nodeId = request.nodeId, connectedAt = 1, geoReady = true)
        }
    }
    @Test fun explicitFirstConnectionInstallsThenConnectsButBackgroundPollingNeverPrompts() = runTest {
        val directory = Files.createTempDirectory("yumi-install-test-").toFile()
        SettingsRepository(directory, backgroundScope).update { it.copy(deviceId = "test", subscriptionAutoUpdate = false) }
        ProfileRepository(directory, backgroundScope).addNodes(listOf(ProxyNode("", "Chosen", "192.0.2.1", 443,
            ProxySettings.Vless("11111111-2222-3333-4444-555555555555")).identified()))
        val backend = Backend()
        var prompts = 0
        val model = DesktopModel(backend, directory, backgroundScope, serviceInstaller = {
            prompts++; backend.installed = true; ServiceRepair.Done
        }, measureLatency = { node, _ -> LatencyResult(node.id, 12, 1) })
        try {
            runCurrent(); advanceTimeBy(2001); runCurrent()
            assertEquals(0, prompts)
            assertTrue(model.canInstallService)
            model.toggleConnection().join(); runCurrent()
            assertEquals(1, prompts)
            assertEquals(1, backend.connects)
            assertFalse(model.serviceInstalling.value)
        } finally { model.close(); directory.deleteRecursively() }
    }
    @Test fun decliningInstallationDoesNotConnectOrRetryThePrompt() = runTest {
        val directory = Files.createTempDirectory("yumi-install-decline-").toFile()
        SettingsRepository(directory, backgroundScope).update { it.copy(deviceId = "test", subscriptionAutoUpdate = false) }
        val backend = Backend()
        var prompts = 0
        val model = DesktopModel(backend, directory, backgroundScope, serviceInstaller = { prompts++; ServiceRepair.Declined })
        try {
            runCurrent(); model.toggleConnection().join(); runCurrent()
            advanceTimeBy(2001); runCurrent()
            assertEquals(1, prompts)
            assertEquals(0, backend.connects)
            assertNull(model.message.value)
            assertFalse(model.serviceInstalling.value)
        } finally { model.close(); directory.deleteRecursively() }
    }
}
