package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.ProbeEndpoint
import com.mydrop.vpn.core.model.Subscription
import com.mydrop.vpn.core.model.SubscriptionUpdate
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Authenticator
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionServiceTest {

    private fun gzipBytes(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    private fun readHttpHeaders(input: InputStream): List<String> {
        val sb = StringBuilder()
        var newlines = 0
        while (newlines < 2) {
            val b = input.read()
            if (b < 0) break
            val c = b.toChar()
            sb.append(c)
            when (c) {
                '\n' -> newlines++
                '\r' -> Unit
                else -> newlines = 0
            }
        }
        return sb.toString().lines().map { it.trimEnd() }.filter { it.isNotEmpty() }
    }

    private inline fun withConnectProxy(
        expectedAuth: String = Base64.getEncoder().encodeToString("u:p".toByteArray()),
        crossinline handler: (connectTarget: String, requestHeaders: List<String>, output: OutputStream) -> Unit,
        block: (ProbeEndpoint, List<String>) -> Unit,
    ) {
        val connects = CopyOnWriteArrayList<String>()
        val pool = Executors.newCachedThreadPool()
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val acceptThread = Thread {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                pool.execute {
                    client.use { socket ->
                        socket.soTimeout = 5_000
                        val input = socket.getInputStream()
                        val output = socket.getOutputStream()
                        val connectHeaders = readHttpHeaders(input)
                        val requestLine = connectHeaders.firstOrNull().orEmpty()
                        val target = requestLine.removePrefix("CONNECT ").substringBefore(' ')
                        connects += target
                        val authHeader = connectHeaders.firstOrNull {
                            it.startsWith("Proxy-Authorization:", ignoreCase = true)
                        }
                        if (authHeader?.substringAfter(':')?.trim() != "Basic $expectedAuth") {
                            output.write("HTTP/1.1 407 Proxy Auth Required\r\n\r\n".toByteArray())
                            output.flush()
                            return@execute
                        }
                        output.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
                        output.flush()

                        val getHeaders = readHttpHeaders(input)
                        handler(target, getHeaders, output)
                        output.flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        try {
            block(ProbeEndpoint(server.localPort, "u", "p"), connects)
        } finally {
            runCatching { server.close() }
            pool.shutdownNow()
            acceptThread.join(1_000)
        }
    }

    @Test
    fun `does not install a global Authenticator when fetching through probe`() = runBlocking {
        Authenticator.setDefault(null)
        val closedPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val vless = "vless://11111111-2222-3333-4444-555555555555@example.com:443?security=tls#Node1"
        val bodyBytes = Base64.getEncoder().encodeToString(vless.toByteArray()).toByteArray()

        withConnectProxy(
            handler = { _, _, out ->
                out.write(
                    (
                        "HTTP/1.1 200 OK\r\n" +
                            "Content-Length: ${bodyBytes.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(),
                )
                out.write(bodyBytes)
            },
        ) { probe, connects ->
            val service = SubscriptionService(LogRepository(), Strings(), probeEndpoint = { probe })
            val result = service.fetch(
                Subscription(
                    id = "s1",
                    name = "Sub",
                    url = "http://127.0.0.1:$closedPort/sub",
                    userAgentOverride = "YumiTest/1.0",
                ),
            )
            assertTrue(result is SubscriptionUpdate.Success)
            assertNull(Authenticator.getDefault())
            assertEquals(listOf("127.0.0.1:$closedPort"), connects)
        }
    }

    @Test
    fun `getViaHttpProxy decodes chunked case-insensitive gzip and sanitizes webPageUrl`() {
        val vless = "vless://11111111-2222-3333-4444-555555555555@example.com:443?security=tls#Chunked"
        val gz = gzipBytes(vless)
        val split = gz.size / 2
        val part1 = gz.copyOfRange(0, split)
        val part2 = gz.copyOfRange(split, gz.size)

        withConnectProxy(
            handler = { _, _, out ->
                out.write(
                    (
                        "HTTP/1.1 200 OK\r\n" +
                            "Transfer-Encoding: chunked\r\n" +
                            "Content-Encoding: GZIP\r\n" +
                            "profile-web-page-url: https://panel.example.com/renew\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(),
                )
                out.write("${part1.size.toString(16)};ext=1\r\n".toByteArray())
                out.write(part1)
                out.write("\r\n".toByteArray())
                out.write("${part2.size.toString(16)}\r\n".toByteArray())
                out.write(part2)
                out.write("\r\n0\r\n\r\n".toByteArray())
            },
        ) { probe, _ ->
            val service = SubscriptionService(LogRepository(), Strings())
            val sub = Subscription("s1", "Sub", "http://93.184.216.34/sub")
            val response = service.getViaHttpProxy(sub.url, sub, probe)
            assertEquals(200, response.code)
            assertEquals(vless, response.body)
            assertEquals("https://panel.example.com/renew", response.webPageUrl)
        }
    }

    @Test
    fun `skips gzip decompression on HTTP 400 and above in proxy and direct paths`() = runBlocking {
        withConnectProxy(
            handler = { _, _, out ->
                out.write(
                    (
                        "HTTP/1.1 403 Forbidden\r\n" +
                            "Content-Encoding: GZIP\r\n" +
                            "Content-Length: 9\r\n" +
                            "Connection: close\r\n\r\n" +
                            "forbidden"
                        ).toByteArray(),
                )
            },
        ) { probe, _ ->
            val service = SubscriptionService(LogRepository(), Strings())
            val sub = Subscription("s1", "Sub", "http://93.184.216.34/sub")
            val response = service.getViaHttpProxy(sub.url, sub, probe)
            assertEquals(403, response.code)
            assertEquals("forbidden", response.body)
        }

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/sub") { exchange ->
            exchange.responseHeaders.add("Content-Encoding", "GZIP")
            val payload = "not-gzip-error".toByteArray()
            exchange.sendResponseHeaders(404, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
        try {
            val service = SubscriptionService(LogRepository(), Strings())
            val update = service.fetch(Subscription("s1", "Sub", "http://127.0.0.1:${server.address.port}/sub"))
            assertTrue(update is SubscriptionUpdate.Failure)
            assertTrue((update as SubscriptionUpdate.Failure).message.contains("404"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `getViaHttpProxy caps response headers at 32 KB`() {
        withConnectProxy(
            handler = { _, _, out ->
                out.write("HTTP/1.1 200 OK\r\n".toByteArray())
                val huge = "X-Pad: " + "a".repeat(1024) + "\r\n"
                repeat(35) { out.write(huge.toByteArray()) }
                out.write("\r\n".toByteArray())
            },
        ) { probe, _ ->
            val service = SubscriptionService(LogRepository(), Strings())
            val sub = Subscription("s1", "Sub", "http://93.184.216.34/sub")
            var thrown: Throwable? = null
            try {
                service.getViaHttpProxy(sub.url, sub, probe)
            } catch (e: IOException) {
                thrown = e
            }
            assertTrue("Expected IOException on >32KB headers", thrown is IOException)
        }
    }

    @Test
    fun `anti-SSRF blocks redirects from non-private origin to private, special, and non-http targets`() {
        val blockedTargets = listOf(
            "http://127.0.0.1:8080/secret",
            "http://localhost:8080/secret",
            "http://10.0.0.1/secret",
            "http://172.16.0.1/secret",
            "http://192.168.1.1/secret",
            "http://100.64.0.1/secret",
            "http://169.254.169.254/latest/meta-data/",
            "http://[::1]:8080/secret",
            "http://[fc00::1]/secret",
            "http://[fe80::1]/secret",
            "http://0.0.0.0/secret",
            "http://224.0.0.1/secret",
            "file:///etc/passwd",
            "ftp://example.com/file",
        )
        val service = SubscriptionService(LogRepository(), Strings())
        val sub = Subscription("s1", "Sub", "http://93.184.216.34/sub")

        blockedTargets.forEach { location ->
            withConnectProxy(
                handler = { _, _, out ->
                    out.write(
                        (
                            "HTTP/1.1 302 Found\r\n" +
                                "Location: $location\r\n" +
                                "Content-Length: 0\r\n" +
                                "Connection: close\r\n\r\n"
                            ).toByteArray(),
                    )
                },
            ) { probe, connects ->
                var rejected: Throwable? = null
                try {
                    service.getViaHttpProxy(sub.url, sub, probe)
                } catch (e: SecurityException) {
                    rejected = e
                }
                assertTrue("Expected SecurityException for redirect to $location", rejected is SecurityException)
                assertEquals("Proxy must never CONNECT to blocked redirect target $location", listOf("93.184.216.34:80"), connects)
            }
        }
    }

    @Test
    fun `sanitizeWebPageUrl accepts safe http and https URLs and drops dangerous ones`() {
        val service = SubscriptionService(LogRepository(), Strings())
        assertEquals("https://panel.example.com/sub?a=1", service.sanitizeWebPageUrl("  https://panel.example.com/sub?a=1  "))
        assertEquals("http://panel.example.com", service.sanitizeWebPageUrl("http://panel.example.com"))
        assertNull(service.sanitizeWebPageUrl("javascript:alert(1)"))
        assertNull(service.sanitizeWebPageUrl("file:///etc/passwd"))
        assertNull(service.sanitizeWebPageUrl("https:///no-host"))
        assertNull(service.sanitizeWebPageUrl("https://panel.example.com/\u0007bad"))
        assertNull(service.sanitizeWebPageUrl("https://panel.example.com/" + "a".repeat(2050)))
    }

    @Test
    fun `rejects non-http initial subscription URL`() = runBlocking {
        val service = SubscriptionService(LogRepository(), Strings())
        val result = service.fetch(Subscription("s1", "Sub", "file:///etc/passwd"))
        assertTrue(result is SubscriptionUpdate.Failure)
    }
}
