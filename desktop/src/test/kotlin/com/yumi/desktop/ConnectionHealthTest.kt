package com.yumi.desktop

import com.mydrop.vpn.core.model.*
import com.mydrop.vpn.data.ProfileRepository
import com.mydrop.vpn.data.SettingsRepository
import com.yumi.desktop.model.*
import com.yumi.desktop.platform.TunnelBackend
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionHealthTest {
    private class Backend : TunnelBackend {
        var state = "disconnected"
        var nodeId: String? = null
        var connects = 0
        var stableEpoch = false
        var connectGate: CompletableDeferred<Unit>? = null
        var connectStarted: CompletableDeferred<Unit>? = null
        override suspend fun request(request: ServiceRequest): ServiceStatus {
            when (request.command) {
                "connect" -> {
                    connectStarted?.complete(Unit)
                    connectGate?.await()
                    state = "connected"; nodeId = request.nodeId; connects++
                }
                "disconnect" -> { state = "disconnected"; nodeId = null }
            }
            return ServiceStatus(state = state, nodeId = nodeId, connectedAt = if (stableEpoch) 1L else connects.toLong(),
                upload = connects * 100L, download = connects * 200L,
                probePort = 12345, probeUser = "local", probePassword = "test", geoReady = true)
        }
    }

    private suspend fun TestScope.fixture(
        check: suspend (ProbeEndpoint) -> Boolean,
        test: suspend (DesktopModel, Backend) -> Unit,
    ) {
        val directory = Files.createTempDirectory("yumi-health-test-").toFile()
        SettingsRepository(directory, backgroundScope).update { it.copy(deviceId = "test", subscriptionAutoUpdate = false) }
        ProfileRepository(directory, backgroundScope).addNodes(List(2) { index ->
            ProxyNode("", "Server $index", "192.0.2.${index + 1}", 443,
                ProxySettings.Vless("11111111-2222-3333-4444-555555555555")).identified()
        })
        val backend = Backend()
        val model = DesktopModel(backend, directory, backgroundScope,
            measureLatency = { node, _ -> LatencyResult(node.id, 20, testScheduler.currentTime) }, checkTunnel = check)
        try { test(model, backend) }
        finally { model.close(); directory.deleteRecursively() }
    }

    @Test fun listeningServerIsNotProtectedUntilTrafficWorksAndHealthRecoversWithoutReconnect() = runTest {
        var reachable = false
        var checks = 0
        fixture({ checks++; reachable }) { model, backend ->
            runCurrent()
            model.toggleConnection().join(); runCurrent()
            assertEquals("connected", model.status.value.state)
            assertEquals(ConnectionHealth.Unreachable, model.connectionHealth.value)
            assertTrue(model.profiles.state.value.latencies.values.all { !it.failed })
            advanceTimeBy(2500); runCurrent()
            assertEquals(1, checks, "One-second service polling must not restart the health monitor")
            reachable = true
            advanceTimeBy(12_501); runCurrent()
            assertEquals(ConnectionHealth.Reachable, model.connectionHealth.value)
            assertNotNull(model.activeLatency.value)
            assertFalse(model.activeLatency.value!!.failed)
            assertTrue(model.activeLatency.value!!.millis > 0)
            assertEquals(1, backend.connects, "Health recovery must not silently reconnect or switch servers")
            reachable = false
            advanceTimeBy(15_001); runCurrent()
            assertEquals(ConnectionHealth.Unreachable, model.connectionHealth.value)
        }
    }

    @Test fun aStalledHealthCheckDoesNotHoldDisconnectAndItsResultCannotLeakIntoANewServer() = runTest {
        var checks = 0
        var cancelled = false
        fixture({
            if (++checks == 1) {
                try { awaitCancellation() }
                finally { cancelled = true }
            } else true
        }) { model, _ ->
            runCurrent()
            model.toggleConnection().join(); runCurrent()
            assertEquals(ConnectionHealth.Checking, model.connectionHealth.value)
            model.toggleConnection().join(); runCurrent()
            assertTrue(cancelled)
            assertEquals(ConnectionHealth.Unchecked, model.connectionHealth.value)
            model.selectNode(model.profiles.nodes.last().id).join()
            model.toggleConnection().join(); runCurrent()
            assertEquals(ConnectionHealth.Reachable, model.connectionHealth.value)
            assertEquals(model.profiles.nodes.last().id, model.status.value.nodeId)
        }
    }

    @Test fun changingServerCancelsThePreviousCheckAndChecksTheNewTunnel() = runTest {
        var checks = 0
        fixture({
            if (++checks == 1) withContext(NonCancellable) { delay(500); false } else true
        }) { model, backend ->
            runCurrent()
            model.toggleConnection().join(); runCurrent()
            model.selectNode(model.profiles.nodes.last().id).join(); runCurrent()
            assertEquals(ConnectionHealth.Reachable, model.connectionHealth.value)
            advanceTimeBy(501); runCurrent()
            assertEquals(ConnectionHealth.Reachable, model.connectionHealth.value, "A late failure from the old server must be discarded")
            assertEquals(2, backend.connects)
        }
    }

    @Test fun hotSwapKeepsConnectedStatusTimerAndCountersWhileWaitingForTheService() = runTest {
        fixture({ true }) { model, backend ->
            backend.stableEpoch = true
            runCurrent()
            model.toggleConnection().join(); runCurrent()
            advanceTimeBy(2001); runCurrent()
            val before = model.status.value
            val history = model.trafficHistory.value
            assertTrue(history.isNotEmpty())
            val rates = mutableListOf<TrafficStats>()
            val capture = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.traffic.collect { rates += it } }
            backend.connectGate = CompletableDeferred()
            backend.connectStarted = CompletableDeferred()
            val change = model.selectNode(model.profiles.nodes.last().id)
            backend.connectStarted!!.await()
            assertTrue(model.switching.value)
            assertEquals("connected", model.status.value.state)
            assertEquals(before.connectedAt, model.status.value.connectedAt)
            assertEquals(before.upload, model.status.value.upload)
            assertEquals(before.download, model.status.value.download)
            assertTrue(model.trafficHistory.value.size >= history.size, "A pending server switch must not clear the chart")
            backend.connectGate!!.complete(Unit)
            change.join(); runCurrent()
            assertFalse(model.switching.value)
            assertEquals("connected", model.status.value.state)
            assertEquals(before.connectedAt, model.status.value.connectedAt)
            assertEquals(200L, model.status.value.upload)
            assertEquals(400L, model.status.value.download)
            assertTrue(rates.any { it.uploadBytesPerSecond == 100L && it.downloadBytesPerSecond == 200L })
            capture.cancel()
        }
    }
}
