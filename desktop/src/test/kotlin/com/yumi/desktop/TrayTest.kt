package com.yumi.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.window.setContent
import com.mydrop.vpn.core.model.*
import com.mydrop.vpn.data.ProfileState
import com.mydrop.vpn.data.Strings
import java.awt.CheckboxMenuItem
import java.awt.EventQueue
import java.awt.Frame
import java.awt.Menu
import java.awt.PopupMenu
import java.awt.event.ActionEvent
import java.awt.event.ItemEvent
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class TrayTest {
    @Test fun nativeServerMenuTracksSelectionLatencyAndPingWithoutHiddenSubscriptions() {
        fun node(index: Int, subscriptionId: String? = null) = ProxyNode(
            "", "Server $index", "192.0.2.$index", 443,
            ProxySettings.Vless("11111111-2222-3333-4444-555555555555"), subscriptionId = subscriptionId,
        ).identified()
        val nodes = listOf(node(1), node(2), node(3), node(4, "off"))
        var profiles by mutableStateOf(ProfileState(
            nodes = nodes,
            subscriptions = listOf(Subscription("off", "Disabled", "https://example.com/sub", enabled = false)),
            selectedNodeId = nodes[0].id,
            latencies = mapOf(
                nodes[0].id to LatencyResult(nodes[0].id, 42, 1),
                nodes[1].id to LatencyResult(nodes[1].id, 5000, 1, failed = true),
            ),
        ))
        var pinging by mutableStateOf(false)
        var pingRequests = 0
        val strings = Strings { AppLanguage.English }
        val popup = PopupMenu()
        runComposeUiTest {
            setContent {
                val parent = rememberCompositionContext()
                DisposableEffect(popup) {
                    val composition = popup.setContent(parent) {
                        TrayServers(profiles, strings, pinging, false, { profiles = profiles.copy(selectedNodeId = it) }) {
                            pingRequests++
                            pinging = true
                        }
                    }
                    onDispose { composition.dispose() }
                }
            }
            runOnIdle {
                val servers = popup.getItem(0) as Menu
                assertEquals(5, servers.itemCount)
                assertEquals("Server 1 · 42 ms", servers.getItem(0).label)
                assertEquals("Server 2 · ${strings.get(com.mydrop.vpn.shared.R.string.servers_latency_failed)}", servers.getItem(1).label)
                assertEquals("Server 3 · —", servers.getItem(2).label)
                assertTrue((servers.getItem(0) as CheckboxMenuItem).state)
                assertFalse(servers.getItem(0).isEnabled)
                val target = servers.getItem(2) as CheckboxMenuItem
                target.state = true
                val event = ItemEvent(target, ItemEvent.ITEM_STATE_CHANGED, target.label, ItemEvent.SELECTED)
                target.itemListeners.forEach { it.itemStateChanged(event) }
                assertEquals(nodes[2].id, profiles.selectedNodeId)
                profiles = profiles.copy(latencies = profiles.latencies + (nodes[2].id to LatencyResult(nodes[2].id, 77, 2)))
            }
            runOnIdle {
                val servers = popup.getItem(0) as Menu
                assertFalse((servers.getItem(0) as CheckboxMenuItem).state)
                assertTrue((servers.getItem(2) as CheckboxMenuItem).state)
                assertEquals("Server 3 · 77 ms", servers.getItem(2).label)
                val ping = servers.getItem(4)
                ping.actionListeners.forEach { it.actionPerformed(ActionEvent(ping, ActionEvent.ACTION_PERFORMED, ping.label)) }
            }
            runOnIdle {
                assertEquals(1, pingRequests)
                assertEquals("Restart latency check", (popup.getItem(0) as Menu).getItem(4).label)
            }
        }
    }

    @Test fun openingFromTrayRestoresAHiddenWindow() {
        EventQueue.invokeAndWait {
            val window = Frame("Tray restore check")
            try {
                window.setSize(320, 240)
                window.extendedState = Frame.ICONIFIED
                assertFalse(window.isVisible)
                restoreApplicationWindow(window)
                assertTrue(window.isVisible)
                assertEquals(0, window.extendedState and Frame.ICONIFIED)
            } finally { window.dispose() }
        }
    }

    @Test fun trayMenuHighlightsActiveNodeAndCapsLargeListsWithOpenItem() {
        val nodes = (1..55).map { index ->
            ProxyNode("", "Server $index", "192.0.2.$index", 443,
                ProxySettings.Vless("11111111-2222-3333-4444-555555555555")).identified()
        }
        val active = nodes[49] // Beyond the 40-item cap; must still stay visible and checked.
        val profiles = ProfileState(nodes = nodes, selectedNodeId = nodes[0].id)
        val strings = Strings { AppLanguage.English }
        val popup = PopupMenu()
        var opened = 0
        runComposeUiTest {
            setContent {
                val parent = rememberCompositionContext()
                DisposableEffect(popup) {
                    val composition = popup.setContent(parent) {
                        TrayServers(profiles, strings, pinging = false, switching = false, activeNodeId = active.id, onSelect = {}, onOpen = { opened++ }, onPing = {})
                    }
                    onDispose { composition.dispose() }
                }
            }
            runOnIdle {
                val servers = popup.getItem(0) as Menu
                // 40 server items + "Open Yumi…" + separator + "Measure latency" = 43
                assertEquals(43, servers.itemCount)
                assertFalse((servers.getItem(0) as CheckboxMenuItem).state)
                assertTrue(servers.getItem(0).isEnabled)
                val activeItem = servers.getItem(39) as CheckboxMenuItem
                assertTrue(activeItem.label.startsWith("Server 50 "))
                assertTrue(activeItem.state)
                assertFalse(activeItem.isEnabled)
                val openMore = servers.getItem(40)
                assertEquals("Open Yumi…", openMore.label)
                openMore.actionListeners.forEach { it.actionPerformed(ActionEvent(openMore, ActionEvent.ACTION_PERFORMED, openMore.label)) }
                assertEquals(1, opened)
            }
        }
    }
}
