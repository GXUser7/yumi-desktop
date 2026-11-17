package com.yumi.desktop

import com.mydrop.vpn.core.model.*
import com.mydrop.vpn.data.*
import com.yumi.desktop.model.*
import com.yumi.desktop.platform.TunnelBackend
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ActiveConnectionTest {
    private class Backend : TunnelBackend {
        var status = ServiceStatus(state = "disconnected", geoReady = true)
        val connects = mutableListOf<String?>()
        var rejectSwitch = false
        override suspend fun request(request: ServiceRequest): ServiceStatus {
            when (request.command) {
                "connect" -> {
                    connects += request.nodeId
                    if (rejectSwitch) return status.copy(error = "hot_swap_failed")
                    status = status.copy(state = "connected", nodeId = request.nodeId, connectedAt = 42)
                }
                "disconnect" -> status = status.copy(state = "disconnected", nodeId = null)
            }
            return status
        }
    }

    private fun node(host: String, id: String = "") = ProxyNode(id, "Belgium", host, 443,
        ProxySettings.Vless("11111111-2222-3333-4444-555555555555"), subscriptionId = "sub").let {
        if (id.isEmpty()) it.identified() else it
    }

    private fun TestScope.model(directory: File, backend: Backend): DesktopModel {
        val scope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        SettingsRepository(directory, scope).update { it.copy(deviceId = "test", subscriptionAutoUpdate = false) }
        return DesktopModel(backend, directory, scope,
            measureLatency = { node, _ -> LatencyResult(node.id, 10, 1) }, checkTunnel = { true })
    }

    @Test fun refreshKeepsTheActualServerAndSettingsReconnectItInsteadOfTheRotatedSelection() = runTest {
        val directory = Files.createTempDirectory("yumi-active-test-").toFile()
        val backend = Backend()
        val model = model(directory, backend)
        try {
            val original = node("192.0.2.1")
            val rotated = node("192.0.2.2")
            model.profiles.addSubscription(Subscription("sub", "Subscription", "https://example.com/sub"))
            model.profiles.addNodes(listOf(original))
            runCurrent()
            model.toggleConnection().join(); runCurrent()
            model.profiles.applySubscriptionUpdate("sub", listOf(rotated), null, null, null)
            advanceTimeBy(1001); runCurrent()
            assertEquals(rotated.id, model.profiles.state.value.selectedNodeId)
            assertFalse(model.profiles.allNodes.any { it.id == original.id })
            assertEquals(original, model.activeNode.value)
            model.changeSettings({ it.copy(mtu = 1400) }, reconnect = true).join(); runCurrent()
            assertEquals(listOf<String?>(original.id, original.id), backend.connects)
            assertEquals(original, model.activeNode.value)
            model.removeSubscription("sub").join()
            assertEquals("disconnected", backend.status.state, "Removing the active subscription must disconnect even after rotation")
        } finally { model.close(); directory.deleteRecursively() }
    }

    @Test fun anAcknowledgedSnapshotSurvivesWindowRestartAndNeverAttachesToAnotherSession() = runTest {
        val directory = Files.createTempDirectory("yumi-active-restart-").toFile()
        val backend = Backend()
        val original = node("192.0.2.1")
        val first = model(directory, backend)
        first.profiles.addNodes(listOf(original))
        runCurrent()
        first.toggleConnection().join(); runCurrent()
        first.profiles.removeNode(original.id)
        first.scope.cancel() // A crashed window leaves the service and its tunnel running.
        val second = model(directory, backend)
        try {
            runCurrent()
            assertEquals(original, second.activeNode.value)
            backend.status = backend.status.copy(connectedAt = 43)
            advanceTimeBy(1001); runCurrent()
            assertNull(second.activeNode.value, "A snapshot belongs to one acknowledged session")
        } finally { second.close(); directory.deleteRecursively() }
    }

    @Test fun anIdMigrationResolvesTheRunningServicesOldIdWithoutSwitchingIt() = runTest {
        val directory = Files.createTempDirectory("yumi-active-migration-").toFile()
        val old = node("192.0.2.1", "legacy-id")
        JsonStore(File(directory, "profiles.json"), ProfileState.serializer(), ProfileState(), backgroundScope)
            .update { it.copy(nodes = listOf(old), selectedNodeId = old.id) }
        val backend = Backend().also { it.status = it.status.copy(state = "connected", nodeId = old.id, connectedAt = 42) }
        val model = model(directory, backend)
        try {
            runCurrent()
            assertEquals(old.identified(), model.activeNode.value)
            assertEquals("legacy-id", model.status.value.nodeId)
            assertTrue(backend.connects.isEmpty())
        } finally { model.close(); directory.deleteRecursively() }
    }

    @Test fun rejectedSwitchKeepsTheOldHealthAndActiveServerAndSurfacesError() = runTest {
        val directory = Files.createTempDirectory("yumi-active-reject-").toFile()
        val backend = Backend()
        val model = model(directory, backend)
        try {
            val original = node("192.0.2.1")
            val other = node("192.0.2.2")
            model.profiles.addNodes(listOf(original, other))
            runCurrent()
            model.toggleConnection().join(); runCurrent()
            assertEquals(ConnectionHealth.Reachable, model.connectionHealth.value)
            backend.rejectSwitch = true
            model.selectNode(other.id).join(); runCurrent()
            assertEquals(original, model.activeNode.value)
            assertEquals(ConnectionHealth.Reachable, model.connectionHealth.value)
            assertNotNull(model.message.value)
            assertTrue(model.message.value!!.contains("Could not switch servers") || model.message.value!!.contains("Не удалось сменить сервер"))
        } finally { model.close(); directory.deleteRecursively() }
    }

    @Test fun selectingServerDuringConnectingStateSwitchesTargetServer() = runTest {
        val directory = Files.createTempDirectory("yumi-connecting-select-").toFile()
        val first = node("192.0.2.1")
        val second = node("192.0.2.2")
        val connectingBackend = object : TunnelBackend {
            val connects = mutableListOf<String?>()
            var current = ServiceStatus(state = "disconnected", geoReady = true)
            override suspend fun request(request: ServiceRequest): ServiceStatus {
                when (request.command) {
                    "connect" -> {
                        connects += request.nodeId
                        current = current.copy(state = "connecting", nodeId = request.nodeId)
                    }
                    "disconnect" -> current = current.copy(state = "disconnected", nodeId = null)
                }
                return current
            }
        }
        val model = DesktopModel(
            connectingBackend, directory,
            CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job])),
            measureLatency = { n, _ -> LatencyResult(n.id, 10, 1) },
            checkTunnel = { true },
        )
        try {
            model.settings.update { it.copy(deviceId = "test", subscriptionAutoUpdate = false) }
            model.profiles.addNodes(listOf(first, second))
            runCurrent()
            model.toggleConnection().join(); runCurrent()
            assertEquals("connecting", model.status.value.state)
            model.selectNode(second.id).join(); runCurrent()
            assertEquals(listOf<String?>(first.id, second.id), connectingBackend.connects)
        } finally { model.close(); directory.deleteRecursively() }
    }
}
