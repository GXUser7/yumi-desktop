package com.yumi.desktop

import com.mydrop.vpn.core.model.*
import com.mydrop.vpn.data.SettingsRepository
import com.yumi.desktop.model.*
import com.yumi.desktop.platform.TunnelBackend
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionSettingsTest {
    private class Backend : TunnelBackend {
        val requests = mutableListOf<ServiceRequest>()
        var immediate = true
        var status = ServiceStatus(state = "disconnected", geoReady = true)
        override suspend fun request(request: ServiceRequest): ServiceStatus {
            requests += request
            when (request.command) {
                "connect" -> status = status.copy(state = if (immediate) "connected" else "connecting", nodeId = request.nodeId, connectedAt = 42)
                "disconnect" -> status = status.copy(state = "disconnected", nodeId = null, killSwitch = false)
            }
            return status
        }
        val connects get() = requests.filter { it.command == "connect" }
    }

    private class Fixture(scope: CoroutineScope) {
        val directory = Files.createTempDirectory("yumi-settings-test-").toFile()
        val backend = Backend()
        val model: DesktopModel
        init {
            SettingsRepository(directory, scope).update { it.copy(deviceId = "test", mtu = 1500, subscriptionAutoUpdate = false) }
            model = DesktopModel(backend, directory, scope,
                measureLatency = { node, _ -> LatencyResult(node.id, 10, 1) }, checkTunnel = { true })
            model.profiles.addNodes(listOf(ProxyNode("", "Test", "192.0.2.1", 443,
                ProxySettings.Vless("11111111-2222-3333-4444-555555555555")).identified()))
        }
        suspend fun close() { model.close(); directory.deleteRecursively() }
    }

    @Test fun dnsIsPresentOnTheVeryFirstConnectionWithoutSavingSettings() = runTest {
        val f = Fixture(backgroundScope)
        try {
            runCurrent()
            f.model.toggleConnection().join()
            assertEquals("https://1.1.1.1/dns-query", f.backend.connects.single().options!!.dns)
        } finally { f.close() }
    }

    @Test fun allTunnelOptionsApplyWithoutACallerRememberingTheReconnectFlag() = runTest {
        val f = Fixture(backgroundScope)
        try {
            runCurrent(); f.model.toggleConnection().join()
            val sharedChanges: List<(AppSettings) -> AppSettings> = listOf(
                { it.copy(remoteDns = "9.9.9.9") }, { it.copy(mtu = 1400) },
                { it.copy(enableIpv6 = true) }, { it.copy(bypassLan = false) },
            )
            sharedChanges.forEach { change ->
                val count = f.backend.connects.size
                f.model.changeSettings(change).join()
                assertEquals(count + 1, f.backend.connects.size)
                assertEquals(ConnectRequestFactory.options(f.model.settings.value, f.model.desktop.value), f.backend.connects.last().options)
            }
            val desktopChanges: List<(DesktopSettings) -> DesktopSettings> = listOf(
                { it.copy(splitMode = SplitMode.OnlySelected) }, { it.copy(applications = listOf("browser.exe")) },
                { it.copy(domains = listOf("example.com")) }, { it.copy(networks = listOf("192.0.2.0/24")) },
                { it.copy(killSwitch = true) }, { it.copy(matchServerLocation = true) },
                { it.copy(bypassRussia = false) }, { it.copy(outboundAdapter = "Wi-Fi") },
            )
            desktopChanges.forEach { change ->
                val count = f.backend.connects.size
                f.model.changeDesktop(change(f.model.desktop.value)).join()
                assertEquals(count + 1, f.backend.connects.size)
                assertEquals(ConnectRequestFactory.options(f.model.settings.value, f.model.desktop.value), f.backend.connects.last().options)
            }
            f.model.changeSettings({ it.copy(themeMode = ThemeMode.Light) }).join()
            f.model.changeDesktop(f.model.desktop.value.copy(connectOnLaunch = true)).join()
            assertEquals(13, f.backend.connects.size, "Appearance and launch preferences must not rebuild a tunnel")
        } finally { f.close() }
    }

    @Test fun changesDuringStartupAreAppliedOnceWithTheLatestValues() = runTest {
        val f = Fixture(backgroundScope)
        try {
            runCurrent(); f.backend.immediate = false; f.model.toggleConnection().join()
            f.model.changeSettings({ it.copy(remoteDns = "9.9.9.9") }, true).join()
            f.model.changeSettings({ it.copy(remoteDns = "1.0.0.1", mtu = 1400) }).join()
            f.model.changeDesktop(f.model.desktop.value.copy(bypassRussia = false)).join()
            assertEquals(1, f.backend.connects.size)
            f.backend.immediate = true
            f.backend.status = f.backend.status.copy(state = "connected")
            advanceTimeBy(1001); runCurrent()
            assertEquals(2, f.backend.connects.size)
            assertEquals("1.0.0.1", f.backend.connects.last().options!!.dns)
            assertEquals(1400, f.backend.connects.last().options!!.mtu)
            assertFalse(f.backend.connects.last().options!!.bypassRussia)
            advanceTimeBy(3000); runCurrent()
            assertEquals(2, f.backend.connects.size)
        } finally { f.close() }
    }

    @Test fun turningOffAKillSwitchStillReleasesAFailedTunnel() = runTest {
        val f = Fixture(backgroundScope)
        try {
            runCurrent()
            f.model.changeDesktop(f.model.desktop.value.copy(killSwitch = true)).join()
            f.backend.status = f.backend.status.copy(state = "failed", killSwitch = true)
            advanceTimeBy(1001); runCurrent()
            f.model.changeDesktop(f.model.desktop.value.copy(killSwitch = false)).join()
            assertEquals(1, f.backend.requests.count { it.command == "disconnect" })
            assertFalse(f.model.status.value.killSwitch)
        } finally { f.close() }
    }
}
