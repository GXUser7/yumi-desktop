package com.yumi.desktop.model

import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.NodeGroup
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.ProxySettings
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ConnectRequestTest {
    private fun server(host: String) = ProxyNode(
        id = host, name = host, server = host, port = 443,
        settings = ProxySettings.Vless(uuid = "11111111-2222-3333-4444-555555555555"),
    )

    @Test fun autoSelectGroupCarriesEveryMemberAndTheProvidersSelectionPolicy() {
        val members = listOf(server("first.example.com"), server("second.example.com"), server("last.example.com"))
        val group = members.first().copy(id = "group", name = "LTE Авто", group = NodeGroup(members))
        val request = ConnectRequestFactory.create(group, AppSettings(mtu = 1500), DesktopSettings())
        assertEquals("group", request.nodeId)
        val address = request.outbound!!.getValue("settings").jsonObject.getValue("vnext").jsonArray.first()
            .jsonObject.getValue("address").jsonPrimitive.content
        assertEquals("first.example.com", address)
        assertEquals(3, request.group!!.members.size)
        assertEquals(NodeGroup.STRATEGY_COST, request.group!!.strategy)
        assertEquals(listOf("first.example.com", "second.example.com", "last.example.com"), request.group!!.members.map {
            it.getValue("settings").jsonObject.getValue("vnext").jsonArray.first().jsonObject.getValue("address").jsonPrimitive.content
        })
    }

    @Test fun applicationPathsFromOlderSettingsBecomeNames() {
        val desktop = DesktopSettings(applications = listOf("C:/Program Files/Google/Chrome/Application/chrome.exe", "chrome.exe", "Telegram"))
        val request = ConnectRequestFactory.create(server("a.example.com"), AppSettings(mtu = 1500), desktop)
        // Names arrive as the platform spells them: a name that carries its extension keeps it,
        // a bare one gains it where processes have extensions.
        val windows = System.getProperty("os.name").startsWith("Windows")
        assertEquals(listOf("chrome.exe", if (windows) "Telegram.exe" else "Telegram"), request.options!!.applications)
    }

    @Test fun chosenAdapterTravelsWithTheRequest() {
        val request = ConnectRequestFactory.create(
            server("a.example.com"),
            AppSettings(mtu = 1500),
            DesktopSettings(outboundAdapter = "Беспроводная сеть"),
        )
        assertEquals("Беспроводная сеть", request.options!!.outboundAdapter)
    }

    @Test fun createRejectsDirectAndUnsupportedNodesAndClampsMtu() {
        assertThrows(IllegalArgumentException::class.java) {
            ConnectRequestFactory.create(
                ProxyNode("d", "Direct", "127.0.0.1", 80, ProxySettings.Direct),
                AppSettings(),
                DesktopSettings(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConnectRequestFactory.create(
                ProxyNode("s4", "Socks4", "example.com", 1080, ProxySettings.Socks(version = "4")),
                AppSettings(),
                DesktopSettings(),
            )
        }
        val lowMtu = ConnectRequestFactory.create(server("a.example.com"), AppSettings(mtu = 500), DesktopSettings())
        assertEquals(1280, lowMtu.options!!.mtu)
        val highMtu = ConnectRequestFactory.create(server("a.example.com"), AppSettings(mtu = 20_000), DesktopSettings())
        assertEquals(9000, highMtu.options!!.mtu)
    }
}
