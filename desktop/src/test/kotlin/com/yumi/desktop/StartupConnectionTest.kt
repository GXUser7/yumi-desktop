package com.yumi.desktop

import com.mydrop.vpn.core.model.*
import com.mydrop.vpn.data.*
import com.yumi.desktop.model.*
import com.yumi.desktop.platform.TunnelBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class StartupConnectionTest {
    private class Backend(var state: String = "disconnected", var unavailablePolls: Int = 0) : TunnelBackend {
        val commands = mutableListOf<ServiceRequest>()
        override suspend fun request(request: ServiceRequest): ServiceStatus {
            commands.add(request)
            when (request.command) {
                "status" -> if (unavailablePolls-- > 0) return ServiceStatus(state = "unavailable")
                "connect" -> state = "connected"
                "disconnect" -> state = "disconnected"
            }
            return ServiceStatus(state = state, geoReady = true)
        }
        val connects get() = commands.filter { it.command == "connect" }
    }

    private class Fixture(scope: CoroutineScope, enabled: Boolean, selected: Boolean = true, val backend: Backend = Backend()) {
        val directory = Files.createTempDirectory("yumi-startup-test-").toFile()
        val node = ProxyNode("", "Chosen", "192.0.2.10", 443, ProxySettings.Vless("11111111-2222-3333-4444-555555555555")).identified()
        val model: DesktopModel
        init {
            SettingsRepository(directory, scope).update { it.copy(deviceId = "test", subscriptionAutoUpdate = false) }
            JsonStore(File(directory, "desktop.json"), DesktopSettings.serializer(), DesktopSettings(), scope).update {
                it.copy(connectOnLaunch = enabled, bypassRussia = false)
            }
            if (selected) ProfileRepository(directory, scope).addNodes(listOf(node))
            model = DesktopModel(backend, directory, scope)
        }
        suspend fun close() {
            model.close()
            check(directory.parentFile.canonicalFile == File(System.getProperty("java.io.tmpdir")).canonicalFile && directory.name.startsWith("yumi-startup-test-"))
            directory.deleteRecursively()
        }
    }

    @Test fun disabledByDefaultAndNoImplicitServerSelection() = runTest {
        assertFalse(DesktopSettings().connectOnLaunch)
        val fixture = Fixture(backgroundScope, enabled = false)
        try { runCurrent(); advanceTimeBy(5000); runCurrent(); assertTrue(fixture.backend.connects.isEmpty()) }
        finally { fixture.close() }
    }

    @Test fun waitsForServiceAndConnectsChosenServerOnlyOnce() = runTest {
        val fixture = Fixture(backgroundScope, true, backend = Backend(unavailablePolls = 2))
        try {
            runCurrent()
            assertTrue(fixture.backend.connects.isEmpty())
            advanceTimeBy(2001); runCurrent()
            assertEquals(listOf(fixture.node.id), fixture.backend.connects.map { it.nodeId })
            fixture.model.toggleConnection().join()
            advanceTimeBy(5000); runCurrent()
            assertEquals("disconnected", fixture.backend.state)
            assertEquals(1, fixture.backend.connects.size, "A manual disconnect must cancel startup connection")
        } finally { fixture.close() }
    }

    @Test fun noServersMeansNoStartupConnection() = runTest {
        val fixture = Fixture(backgroundScope, true, selected = false)
        try { runCurrent(); advanceTimeBy(3000); runCurrent(); assertTrue(fixture.backend.connects.isEmpty()); assertNull(fixture.model.message.value) }
        finally { fixture.close() }
    }

    @Test fun anExistingTunnelIsNotReplaced() = runTest {
        val fixture = Fixture(backgroundScope, true, backend = Backend(state = "connected"))
        try { runCurrent(); advanceTimeBy(3000); runCurrent(); assertTrue(fixture.backend.connects.isEmpty()) }
        finally { fixture.close() }
    }

    @Test fun failedStartupDoesNotRetryForever() = runTest {
        val fixture = Fixture(backgroundScope, true, backend = Backend(state = "failed"))
        try { runCurrent(); fixture.backend.state = "disconnected"; advanceTimeBy(3000); runCurrent(); assertTrue(fixture.backend.connects.isEmpty()) }
        finally { fixture.close() }
    }

    @Test fun turningTheSettingOnDoesNotConnectUntilTheNextLaunch() = runTest {
        val fixture = Fixture(backgroundScope, false)
        try {
            runCurrent()
            fixture.model.changeDesktop(fixture.model.desktop.value.copy(connectOnLaunch = true)).join()
            advanceTimeBy(3000); runCurrent()
            assertTrue(fixture.backend.connects.isEmpty())
            assertTrue(JsonStore(File(fixture.directory, "desktop.json"), DesktopSettings.serializer(), DesktopSettings(), backgroundScope).value.connectOnLaunch)
        } finally { fixture.close() }
    }
}
