package com.yumi.desktop

import com.mydrop.vpn.core.model.*
import com.mydrop.vpn.data.*
import com.yumi.desktop.model.*
import com.yumi.desktop.platform.TunnelBackend
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PingTest {
    private class Backend : TunnelBackend {
        var state = "disconnected"
        var node: String? = null
        override suspend fun request(request: ServiceRequest): ServiceStatus {
            when (request.command) {
                "connect" -> { state = "connected"; node = request.nodeId }
                "disconnect" -> { state = "disconnected"; node = null }
            }
            return ServiceStatus(state = state, nodeId = node, connectedAt = 1L, geoReady = true)
        }
    }

    private suspend fun TestScope.fixture(probe: suspend (ProxyNode, PingMode) -> LatencyResult, test: suspend (DesktopModel) -> Unit) {
        val directory = Files.createTempDirectory("yumi-ping-test-").toFile()
        SettingsRepository(directory, backgroundScope).update { it.copy(deviceId = "test", subscriptionAutoUpdate = false) }
        ProfileRepository(directory, backgroundScope).addNodes(List(2) { index ->
            ProxyNode("", "Server $index", "192.0.2.${index + 1}", 443, ProxySettings.Vless("11111111-2222-3333-4444-555555555555")).identified()
        })
        val model = DesktopModel(Backend(), directory, backgroundScope, probe)
        try { test(model) } finally { model.close(); directory.deleteRecursively() }
    }

    @Test fun restartCancelsPreviousSweepAndCannotPublishItsUnfinishedResults() = runTest {
        var attempts = 0
        var cancellations = 0
        fixture({ node, _ ->
            val attempt = ++attempts
            try { delay(if (attempt <= 2) 10_000 else 20) }
            catch (cancelled: CancellationException) { cancellations++; throw cancelled }
            LatencyResult(node.id, if (attempt <= 2) 999 else 42, testScheduler.currentTime)
        }) { model ->
            runCurrent()
            model.busy.value = true // A subscription operation must not disable ping or lose its busy state.
            model.ping(); runCurrent()
            assertTrue(model.pinging.value)
            assertEquals(2, attempts)
            model.ping(); runCurrent()
            assertEquals(2, cancellations)
            advanceTimeBy(250); runCurrent()
            assertEquals(4, attempts)
            assertFalse(model.pinging.value)
            assertTrue(model.busy.value)
            assertEquals(listOf(42, 42), model.profiles.state.value.latencies.values.map { it.millis })
            advanceTimeBy(10_000); runCurrent()
            assertTrue(model.profiles.state.value.latencies.values.all { it.millis == 42 })
        }
    }

    @Test fun connectionProbesOnlyTheConnectedServerAndStatusPollsDoNotRepeatIt() = runTest {
        val measured = mutableListOf<String>()
        fixture({ node, _ ->
            measured += node.id
            LatencyResult(node.id, 37, testScheduler.currentTime)
        }) { model ->
            runCurrent()
            val nodes = model.profiles.nodes
            model.selectNode(nodes.last().id).join(); runCurrent()
            assertTrue(measured.isEmpty(), "Selecting an idle server must not connect or probe it")
            model.toggleConnection().join(); runCurrent()
            assertEquals(listOf(nodes.last().id), measured)
            assertEquals(37, model.profiles.state.value.latencies[nodes.last().id]?.millis)
            advanceTimeBy(2500); runCurrent()
            assertEquals(1, measured.size)
            model.selectNode(nodes.first().id).join(); runCurrent()
            assertEquals(listOf(nodes.last().id, nodes.first().id), measured)
        }
    }

    @Test fun slowAutomaticProbeDoesNotHoldConnectionCommandsAndIsCancelledOnDisconnect() = runTest {
        var cancelled = false
        var started = false
        fixture({ _, _ ->
            started = true
            try { awaitCancellation() } finally { cancelled = true }
        }) { model ->
            runCurrent()
            model.toggleConnection().join(); runCurrent()
            assertEquals("connected", model.status.value.state)
            assertTrue(started)
            model.toggleConnection().join(); runCurrent()
            assertEquals("disconnected", model.status.value.state)
            assertTrue(cancelled)
            assertTrue(model.profiles.state.value.latencies.isEmpty())
        }
    }
}
