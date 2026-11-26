package com.yumi.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.mydrop.vpn.core.model.AppLanguage
import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.CountryFilterMode
import com.mydrop.vpn.core.model.LatencyResult
import com.mydrop.vpn.core.model.PingMode
import com.mydrop.vpn.core.model.Protocol
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.ProxySettings
import com.mydrop.vpn.core.model.ServerSort
import com.mydrop.vpn.core.model.SettingsMode
import com.mydrop.vpn.core.model.Subscription
import com.mydrop.vpn.core.model.WorldMap
import com.mydrop.vpn.core.model.identified
import com.mydrop.vpn.data.ProfileRepository
import com.mydrop.vpn.data.SettingsRepository
import com.mydrop.vpn.shared.R
import com.yumi.desktop.model.ProbeResultItem
import com.yumi.desktop.model.ServiceRequest
import com.yumi.desktop.model.ServiceStatus
import com.yumi.desktop.platform.TunnelBackend
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FeaturesAndSettingsTest {

    private class FeatureBackend : TunnelBackend {
        var state = "disconnected"
        var nodeId: String? = null
        val probeRequests = mutableListOf<ServiceRequest>()
        var logText = "worker line 1\nworker line 2"
        override suspend fun request(request: ServiceRequest): ServiceStatus = when (request.command) {
            "connect" -> {
                state = "connected"
                nodeId = request.nodeId
                ServiceStatus(state = state, nodeId = nodeId, connectedAt = 10L, geoReady = true)
            }
            "disconnect" -> {
                state = "disconnected"
                nodeId = null
                ServiceStatus(state = state, geoReady = true)
            }
            "probe" -> {
                probeRequests += request
                val items = request.probe?.targets.orEmpty().mapIndexed { idx, target ->
                    ProbeResultItem(id = target.id, millis = (idx + 1) * 25)
                }
                ServiceStatus(state = state, nodeId = nodeId, geoReady = true, probeResults = items)
            }
            "logs" -> ServiceStatus(state = state, nodeId = nodeId, geoReady = true, logs = logText)
            else -> ServiceStatus(state = state, nodeId = nodeId, geoReady = true)
        }
    }

    @Test fun serverFilterSortFastestCoreProbeAndLogsWorkTogether() = runBlocking {
        val dir = Files.createTempDirectory("yumi-features-test-").toFile()
        val modelScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            SettingsRepository(dir, modelScope).update {
                it.copy(deviceId = "test", subscriptionAutoUpdate = false, pingMode = PingMode.UrlTest)
            }
            val deVless = ProxyNode(
                "", "${WorldMap.flagOf("DE")} Berlin", "de.example.com", 443,
                ProxySettings.Vless("11111111-2222-3333-4444-555555555555"),
                subscriptionId = "s1",
            ).identified()
            val nlTrojan = ProxyNode(
                "", "${WorldMap.flagOf("NL")} Amsterdam", "nl.example.com", 443,
                ProxySettings.Trojan("pass"),
                subscriptionId = "s1",
            ).identified()
            val profiles = ProfileRepository(dir, modelScope)
            profiles.addSubscription(Subscription("s1", "Sub 1", "https://example.com/sub"))
            profiles.addNodes(listOf(deVless, nlTrojan))

            val backend = FeatureBackend()
            val model = DesktopModel(backend, dir, modelScope)
            try {
                model.ping()
                withTimeout(5_000) {
                    while (model.profiles.state.value.latencies.size < 2 || model.pinging.value) {
                        delay(10)
                    }
                }
                assertEquals(1, backend.probeRequests.size)
                assertEquals(25, model.profiles.state.value.latencies[deVless.id]?.millis)
                assertEquals(50, model.profiles.state.value.latencies[nlTrojan.id]?.millis)

                model.setServerSort(ServerSort.Latency)
                model.profiles.selectNode(nlTrojan.id)
                model.selectFastestServer()
                withTimeout(2_000) {
                    while (model.profiles.state.value.selectedNodeId != deVless.id) {
                        delay(10)
                    }
                }
                assertEquals(deVless.id, model.profiles.state.value.selectedNodeId)

                model.toggleProtocolFilter(Protocol.TROJAN)
                assertEquals(listOf(nlTrojan.id), model.matchingServers().map { it.id })
                model.resetServerFilters()
                model.setCountryFilterMode(CountryFilterMode.Exclude)
                model.toggleCountryFilter("NL")
                assertEquals(listOf(deVless.id), model.matchingServers().map { it.id })
                model.resetServerFilters()

                model.refreshServiceLogs().join()
                assertTrue(model.serviceLogs.value.contains("worker line 1"))

                model.toggleConnection().join()
                assertEquals("connected", model.status.value.state)
                model.setSubscriptionEnabled("s1", false).join()
                assertEquals("disconnected", model.status.value.state)
                assertTrue(model.profiles.nodes.isEmpty())
            } finally {
                model.close()
                modelScope.cancel()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun settingsModeSwitchAndCustomRulesUiRenderProperlyWithPalettePickerPreserved() {
        val dir = Files.createTempDirectory("yumi-ui-features-").toFile()
        seedDemo(dir)
        val model = DesktopModel(DemoBackend(), dir)
        model.settings.update { it.copy(language = AppLanguage.English) }
        try {
            runComposeUiTest {
                setContent {
                    CompositionLocalProvider(LocalAnimationsActive provides false, LocalBackdropDispatcher provides Dispatchers.Unconfined) {
                        Box(Modifier.requiredSize(1024.dp, 768.dp)) { YumiApp(model, Page.Settings) }
                    }
                }
                assertEquals(SettingsMode.Advanced, model.settings.value.settingsMode)
                onNodeWithTag("settings-mode-panel").assertIsDisplayed()
                onNodeWithTag("settings-tls-fragment-panel").performScrollTo().assertIsDisplayed()
                onNodeWithTag("settings-latency-panel").performScrollTo().assertIsDisplayed()
                onNodeWithTag("palette-picker").performScrollTo().assertIsDisplayed()

                onNodeWithTag("settings-mode-panel").performScrollTo()
                onNodeWithText(model.strings.get(R.string.settings_mode_simple)).performClick()
                waitUntil { model.settings.value.settingsMode == SettingsMode.Simple }
                onNodeWithTag("palette-picker").performScrollTo().assertIsDisplayed()
                onNodeWithTag("settings-simple-more").performScrollTo().assertIsDisplayed()

                onNodeWithTag("nav-Routing").performClick()
                onNodeWithTag("custom-rules-panel").performScrollTo().assertIsDisplayed()
                onNodeWithTag("custom-rule-input").performScrollTo()
                onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("custom-rule-input")))
                    .performTextInput("blocked.example.com")
                onNodeWithTag("custom-rule-add").performScrollTo().performClick()
                waitUntil { "domain:blocked.example.com" in model.settings.value.customRules.proxy }
                assertFalse(model.settings.value.customRules.isEmpty)
                assertNotNull(model.settings.value.customRules.proxy.firstOrNull())
            }
        } finally {
            runBlocking { model.close() }
            dir.deleteRecursively()
        }
    }
}
