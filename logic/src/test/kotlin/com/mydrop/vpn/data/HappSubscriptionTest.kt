package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.*
import com.yumi.desktop.model.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*
import java.net.InetSocketAddress
import java.util.Base64

class HappSubscriptionTest {
    @Test fun panelDocumentsReplaceOnlyTheMatchingAutoServerAndKeepAllItsMembers() = runBlocking {
        val uuid = "11111111-2222-3333-4444-555555555555"
        fun outbound(tag: String, host: String) = """{"tag":"$tag","protocol":"vless","settings":{"vnext":[{"address":"$host","port":443,"users":[{"id":"$uuid","encryption":"none"}]}]}}"""
        val documents = """[{"remarks":"LTE Auto","outbounds":[${outbound("candidate-a", "first.example.com")},${outbound("candidate-b", "relay.example.com")},${outbound("fallback", "fallback.example.com")}],
            "routing":{"balancers":[{"tag":"auto","selector":["candidate-"],"fallbackTag":"fallback","strategy":{"type":"leastLoad","settings":{"maxRTT":"2s","costs":[{"match":"candidate-a","value":100000},{"match":"candidate-b","value":0}]}}}]}}]"""
        val links = "vless://$uuid@fallback.example.com:443#LTE%20Auto\nvless://$uuid@plain.example.com:443#Plain"
        val requests = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/fixturetoken123") { exchange ->
            requests += exchange.requestURI.path
            val body = if (exchange.requestURI.path.endsWith("/json")) documents else Base64.getEncoder().encodeToString(links.toByteArray())
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
            exchange.close()
        }
        server.start()
        try {
            val result = SubscriptionService(LogRepository(), Strings()).fetch(Subscription("fixture", "Fixture", "http://127.0.0.1:${server.address.port}/fixturetoken123"))
            assertTrue(result is SubscriptionUpdate.Success)
            val nodes = (result as SubscriptionUpdate.Success).nodes
            assertEquals(listOf("LTE Auto", "Plain"), nodes.map { it.name })
            val group = nodes.first().group!!
            assertEquals(listOf("relay.example.com", "first.example.com", "fallback.example.com"), group.members.map { it.server })
            assertEquals(2000, group.maxDelayMillis)
            assertEquals(NodeGroup.STRATEGY_COST, group.strategy)
            val request = ConnectRequestFactory.create(nodes.first(), AppSettings(mtu = 1500), DesktopSettings())
            assertEquals(3, request.group!!.members.size)
            assertEquals("plain.example.com", nodes.last().server)
            assertEquals(listOf("/fixturetoken123", "/fixturetoken123/json"), requests)
        } finally { server.stop(0) }
    }
}
