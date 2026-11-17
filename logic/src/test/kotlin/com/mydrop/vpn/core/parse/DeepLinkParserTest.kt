package com.mydrop.vpn.core.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class DeepLinkParserTest {

    private fun b64url(value: String) =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())

    @Test
    fun `happ add link with base64 payload becomes a subscription`() {
        val target = "https://provider.example.com/sub/token123"
        val result = DeepLinkParser.parse("happ://add/${b64url(target)}")

        assertTrue(result is DeepLinkPayload.AddSubscription)
        assertEquals(target, (result as DeepLinkPayload.AddSubscription).url)
    }

    @Test
    fun `happ add link with an unencoded url is also accepted`() {
        val result = DeepLinkParser.parse("happ://add/https%3A%2F%2Fp.example.com%2Fsub")
        assertEquals(
            "https://p.example.com/sub",
            (result as DeepLinkPayload.AddSubscription).url,
        )
    }

    @Test
    fun `bare https link is treated as a subscription`() {
        val result = DeepLinkParser.parse("https://provider.example.com/sub/abc")
        assertTrue(result is DeepLinkPayload.AddSubscription)
    }

    @Test
    fun `single share link is imported as a server`() {
        val result = DeepLinkParser.parse("vless://uuid@a.example.com:443?security=tls#A")
        assertEquals(1, (result as DeepLinkPayload.AddNodes).nodes.size)
    }

    @Test
    fun `multi line share blob imports every server`() {
        val blob = """
            vless://uuid@a.example.com:443?security=tls#A
            trojan://pw@b.example.com:443#B
        """.trimIndent()

        val result = DeepLinkParser.parse(blob)
        assertEquals(2, (result as DeepLinkPayload.AddNodes).nodes.size)
    }

    @Test
    fun `base64 blob with no scheme is decoded before import`() {
        val blob = "vless://uuid@a.example.com:443?security=tls#A"
        val result = DeepLinkParser.parse(Base64.getEncoder().encodeToString(blob.toByteArray()))
        assertEquals(1, (result as DeepLinkPayload.AddNodes).nodes.size)
    }

    @Test
    fun `mydrop scheme behaves like happ`() {
        val result = DeepLinkParser.parse("mydrop://add/${b64url("https://p.example.com/s")}")
        assertTrue(result is DeepLinkPayload.AddSubscription)
    }

    @Test
    fun `name query parameter is carried through`() {
        val result = DeepLinkParser.parse(
            "happ://add/${b64url("https://p.example.com/s")}?name=Provider",
        )
        assertEquals("Provider", (result as DeepLinkPayload.AddSubscription).name)
    }

    @Test
    fun `unrecognised payload reports why instead of failing silently`() {
        val result = DeepLinkParser.parse("happ://add/????")
        assertTrue(result is DeepLinkPayload.Unsupported)
        // The code, not a sentence: the wording lives in resources and changes with the language,
        // while "this was not recognised as anything" is the fact worth pinning down.
        assertEquals(
            UnsupportedReason.NotRecognised,
            (result as DeepLinkPayload.Unsupported).reason,
        )
    }

    @Test
    fun `app link extracts fragment into name, preserves encoded token parameters, and handles case-insensitive scheme`() {
        val withFrag = DeepLinkParser.parse(
            "HAPP://add/https://panel.example.com/sub?token=a%26b%3Dc#My%20Provider",
        ) as DeepLinkPayload.AddSubscription
        assertEquals("https://panel.example.com/sub?token=a%26b%3Dc", withFrag.url)
        assertEquals("My Provider", withFrag.name)

        val encodedHead = DeepLinkParser.parse(
            "happ://add/https%3A%2F%2Fpanel.example.com%2Fsub%3Ftoken%3Dx#Named",
        ) as DeepLinkPayload.AddSubscription
        assertEquals("https://panel.example.com/sub?token=x", encodedHead.url)
        assertEquals("Named", encodedHead.name)
    }

    @Test
    fun `happ crypt and non-http schemes in subscription actions are rejected`() {
        val crypt = DeepLinkParser.parse("happ://crypt3/abcdef") as DeepLinkPayload.Unsupported
        assertEquals(UnsupportedReason.UnknownFormat, crypt.reason)

        val badScheme = DeepLinkParser.parse("happ://add/httpfake://panel.example.com/sub")
        assertTrue(badScheme is DeepLinkPayload.Unsupported)

        val fileScheme = DeepLinkParser.parse("happ://add/file:///etc/passwd")
        assertTrue(fileScheme is DeepLinkPayload.Unsupported)
    }

    @Test
    fun `unencoded proxy and dns app links preserve fragment name`() {
        val vless = DeepLinkParser.parse(
            "happ://add/vless://11111111-2222-3333-4444-555555555555@192.0.2.50:443?security=tls#DeepNode",
        ) as DeepLinkPayload.AddNodes
        assertEquals(1, vless.nodes.size)
        assertEquals("DeepNode", vless.nodes.single().name)

        val encodedFrag = DeepLinkParser.parse(
            "happ://add/vless://11111111-2222-3333-4444-555555555555@192.0.2.50:443?security=tls#My%20Deep%20Node",
        ) as DeepLinkPayload.AddNodes
        assertEquals("My Deep Node", encodedFrag.nodes.single().name)

        val dns = DeepLinkParser.parse("happ://add/tcp://1.1.1.1#MyTcpDns") as DeepLinkPayload.AddDns
        assertEquals("MyTcpDns", dns.profiles.single().name)
    }
}
