package com.mydrop.vpn.core.parse

import com.mydrop.vpn.core.model.ProxySettings
import com.mydrop.vpn.core.model.TransportOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClashParserTest {

    private val document = """
        mixed-port: 7890
        allow-lan: false
        mode: rule
        proxies:
          - name: "🇳🇱 Нидерланды"
            type: vless
            server: nl.example.com
            port: 443
            uuid: 11111111-2222-3333-4444-555555555555
            tls: true
            servername: www.microsoft.com
            client-fingerprint: chrome
            flow: xtls-rprx-vision
            reality-opts:
              public-key: PUBKEY
              short-id: a1b2
          - name: "Токио WS"
            type: vmess
            server: jp.example.com
            port: 8443
            uuid: uuid-2
            alterId: 0
            cipher: auto
            tls: true
            network: ws
            ws-opts:
              path: /ws
              headers:
                Host: cdn.example.com
          - {name: inline, type: trojan, server: fi.example.com, port: 443, password: pw, tls: true}
        proxy-groups:
          - name: auto
            type: url-test
            proxies: ["🇳🇱 Нидерланды", "Токио WS"]
        rules:
          - MATCH,auto
    """.trimIndent()

    @Test
    fun `every proxy in the section becomes a node`() {
        val nodes = ClashParser.parse(document, "sub-1")
        assertEquals(listOf("🇳🇱 Нидерланды", "Токио WS", "inline"), nodes.map { it.name })
    }

    @Test
    fun `reality options come out of the nested block`() {
        val node = ClashParser.parse(document, null).first()
        assertEquals("nl.example.com", node.server)
        assertEquals(443, node.port)
        assertEquals("xtls-rprx-vision", (node.settings as ProxySettings.Vless).flow)
        assertEquals("www.microsoft.com", node.tls?.serverName)
        assertEquals("chrome", node.tls?.fingerprint)
        assertEquals("PUBKEY", node.tls?.reality?.publicKey)
        assertEquals("a1b2", node.tls?.reality?.shortId)
    }

    @Test
    fun `websocket options come out of two levels of nesting`() {
        val node = ClashParser.parse(document, null)[1]
        val transport = node.transport as TransportOptions.WebSocket
        assertEquals("/ws", transport.path)
        assertEquals("cdn.example.com", transport.headers["Host"])
    }

    @Test
    fun `an inline mapping is read like any other entry`() {
        val node = ClashParser.parse(document, null)[2]
        assertEquals("fi.example.com", node.server)
        assertEquals("pw", (node.settings as ProxySettings.Trojan).password)
    }

    @Test
    fun `proxy-groups are not servers`() {
        // The group section names its members, and a parser that ran past the end of `proxies:`
        // would turn "auto" into a server that dials nothing.
        val nodes = ClashParser.parse(document, null)
        assertNull(nodes.firstOrNull { it.name == "auto" })
    }

    @Test
    fun `a document without proxies yields nothing`() {
        assertTrue(ClashParser.parse("mode: rule\nrules:\n  - MATCH,DIRECT", null).isEmpty())
        assertTrue(ClashParser.parse("", null).isEmpty())
    }

    @Test
    fun `inlineMap handles quotes, brackets, trailing comments, depth cap, and normalizes server and reality key`() {
        val deep = "{a: ".repeat(14) + "x: 1" + "}".repeat(14)
        val doc = """
            proxies:
              - {name: "a,b{c}#keep", type: vless, server: "[2001:db8::1].", port: 443, uuid: u1, tls: true, public-key: ab+cd/ef=, alpn: [h2, http/1.1], ws-opts: {path: "/ws,1", headers: {Host: cdn.example.com}}} # trailing comment
              - {name: deep, type: trojan, server: пример.рф., port: 443, password: pw, extra: $deep}
        """.trimIndent()
        val nodes = ClashParser.parse(doc, "sub")
        assertEquals(2, nodes.size)
        val first = nodes[0]
        assertEquals("a,b{c}#keep", first.name)
        assertEquals("2001:db8::1", first.server)
        assertEquals("ab-cd_ef", first.tls?.reality?.publicKey)
        assertEquals(listOf("h2", "http/1.1"), first.tls?.alpn)

        val second = nodes[1]
        assertEquals("xn--e1afmkfd.xn--p1ai", second.server)
    }
}
