package com.yumi.desktop.model

import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.NodeGroup
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.ProxySettings
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectRequestTest {
    private fun server(host: String) = ProxyNode(
        id = host, name = host, server = host, port = 443,
        settings = ProxySettings.Vless(uuid = "11111111-2222-3333-4444-555555555555"),
    )

    /** The window never switches servers on its own: a group is ridden as its first member. */
    @Test fun autoSelectGroupIsCarriedAsItsFirstChoice() {
        val members = listOf(server("first.example.com"), server("second.example.com"), server("last.example.com"))
        val group = members.first().copy(id = "group", name = "LTE Авто", group = NodeGroup(members))
        val request = ConnectRequestFactory.create(group, AppSettings(mtu = 1500), DesktopSettings())
        assertEquals("group", request.nodeId)
        val address = request.outbound!!.getValue("settings").jsonObject.getValue("vnext").jsonArray.first()
            .jsonObject.getValue("address").jsonPrimitive.content
        assertEquals("first.example.com", address)
    }

    @Test fun applicationPathsFromOlderSettingsBecomeNames() {
        val desktop = DesktopSettings(applications = listOf("C:/Program Files/Google/Chrome/Application/chrome.exe", "chrome.exe", "Telegram"))
        val request = ConnectRequestFactory.create(server("a.example.com"), AppSettings(mtu = 1500), desktop)
        assertEquals(listOf("chrome.exe", "Telegram.exe"), request.options!!.applications)
    }

    @Test fun chosenAdapterTravelsWithTheRequest() {
        val request = ConnectRequestFactory.create(
            server("a.example.com"),
            AppSettings(mtu = 1500),
            DesktopSettings(outboundAdapter = "Беспроводная сеть"),
        )
        assertEquals("Беспроводная сеть", request.options!!.outboundAdapter)
    }
}
