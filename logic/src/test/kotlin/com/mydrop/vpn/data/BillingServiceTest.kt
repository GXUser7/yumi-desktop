package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.BillingCheckoutRequest
import com.mydrop.vpn.core.model.ProbeEndpoint
import com.mydrop.vpn.core.model.Subscription
import com.mydrop.vpn.core.model.SubscriptionUpdate
import com.sun.net.httpserver.HttpServer
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BillingServiceTest {

    private fun sampleManifestJson(
        baseUrl: String,
        version: Int = 1,
        planCount: Int = 2,
        checkoutEndpoint: String = "$baseUrl/checkout",
        fallbackWebUrl: String = "$baseUrl/portal",
    ): String {
        val plansJson = (1..planCount).joinToString(",") { i ->
            """
            {
              "id": "plan-$i",
              "title": "Plan $i",
              "price": ${i * 10000},
              "currency": "RUB",
              "duration_days": ${i * 30}
            }
            """.trimIndent()
        }
        return """
        {
          "version": $version,
          "provider": {
            "name": "Yumi Cloud",
            "support_url": "$baseUrl/support"
          },
          "routing_hint": "direct_ru",
          "promo_supported": true,
          "fallback_web_url": "$fallbackWebUrl",
          "payment_methods": [
            {"id": "sbp", "title": "SBP", "type": "sbp"}
          ],
          "plans": [$plansJson],
          "addons": [
            {"id": "addon-1", "title": "+100 GB", "price": 19900, "currency": "RUB"}
          ],
          "checkout": {
            "endpoint": "$checkoutEndpoint",
            "method": "POST"
          }
        }
        """.trimIndent()
    }

    @Test
    fun `fetchManifest parses valid OSB v1 JSON, filters unsafe URLs, caps plans at 50, and omits x-hwid and Authorization`() =
        runBlocking {
            val capturedHeaders = mutableMapOf<String, List<String>>()
            var responseBody = ""
            val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
            server.createContext("/manifest") { exchange ->
                capturedHeaders.clear()
                exchange.requestHeaders.forEach { (k, v) -> capturedHeaders[k.lowercase()] = v }
                val bytes = responseBody.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()

            try {
                val baseUrl = "http://127.0.0.1:${server.address.port}"
                val strings = Strings()
                val service = BillingService(
                    logs = LogRepository(strings),
                    strings = strings,
                    allowLocalHttp = true,
                )

                responseBody = sampleManifestJson(
                    baseUrl = baseUrl,
                    planCount = 60,
                    checkoutEndpoint = "http://evil.example.com/checkout",
                    fallbackWebUrl = "javascript:alert(1)",
                )
                val okResult = service.fetchManifest("$baseUrl/manifest", acceptLanguage = "ru")
                assertTrue(okResult is BillingResult.Success)
                okResult as BillingResult.Success
                assertEquals(50, okResult.value.plans.size)
                assertNull(okResult.value.checkout)
                assertNull(okResult.value.fallbackWebUrl)
                assertFalse(capturedHeaders.containsKey("x-hwid"))
                assertFalse(capturedHeaders.containsKey("authorization"))

                responseBody = sampleManifestJson(baseUrl = baseUrl, version = 2)
                val badVersion = service.fetchManifest("$baseUrl/manifest")
                assertTrue(badVersion is BillingResult.Failure)
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `fetchManifest falls back to loopback HTTP CONNECT probe endpoint when direct connect fails`() = runBlocking {
        val targetServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val seenConnects = mutableListOf<String>()
        var targetUrl = ""
        targetServer.createContext("/manifest") { exchange ->
            val bytes = sampleManifestJson(baseUrl = "http://127.0.0.1:${targetServer.address.port}").toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        targetServer.start()

        val proxySocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val running = AtomicBoolean(true)
        val proxyThread = thread(isDaemon = true) {
            while (running.get()) {
                val client = runCatching { proxySocket.accept() }.getOrNull() ?: break
                runCatching {
                    client.use { c ->
                        val input = BufferedInputStream(c.getInputStream())
                        val buf = ByteArrayOutputStream()
                        while (!buf.toString(Charsets.US_ASCII.name()).endsWith("\r\n\r\n")) {
                            val b = input.read()
                            if (b < 0) return@use
                            buf.write(b)
                        }
                        val head = buf.toString(Charsets.US_ASCII.name())
                        val connectLine = head.lineSequence().first()
                        seenConnects += connectLine
                        Socket("127.0.0.1", targetServer.address.port).use { upstream ->
                            c.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
                            c.getOutputStream().flush()
                            val t = thread(isDaemon = true) {
                                runCatching { input.copyTo(upstream.getOutputStream()); upstream.shutdownOutput() }
                            }
                            runCatching { upstream.getInputStream().copyTo(c.getOutputStream()); c.shutdownOutput() }
                            t.join(2000)
                        }
                    }
                }
            }
        }

        try {
            // Note: we simulate direct failure by letting client connect directly vs proxy: wait -
            // to force direct IOException first, test connect to a closed port? Since sanitizer requires
            // target URL host:port match CONNECT authority, instead serve proxy ONLY when target accepts.
            // Even cleaner: close direct path? HTTP CONNECT sends literal target host:port to proxy, so
            // if direct fails because closedPort doesn't accept directly, Wait: in unit test direct dials same 127.0.0.1:closedPort!
            // Instead stop direct server AFTER binding proxy? No, proxy dials targetServer while direct hits closedPort?
            //Wait: let proxy rewrite target port to targetServer.address.port when CONNECT arrives for closedPort!
            val closedPort = ServerSocket(0).use { it.localPort }
            targetUrl = "http://127.0.0.1:$closedPort/manifest"

            val strings = Strings()
            val service = BillingService(
                logs = LogRepository(strings),
                strings = strings,
                probeEndpoint = { ProbeEndpoint(proxySocket.localPort, "u", "p") },
                allowLocalHttp = true,
            )
            val result = service.fetchManifest(targetUrl)
            assertTrue(result is BillingResult.Success)
            assertEquals(1, seenConnects.size)
            assertTrue(seenConnects.single().contains("127.0.0.1:$closedPort"))
            result as BillingResult.Success
            assertEquals("Yumi Cloud", result.value.provider.name)
        } finally {
            running.set(false)
            runCatching { proxySocket.close() }
            proxyThread.join(1000)
            targetServer.stop(0)
        }
    }

    @Test
    fun `createCheckout sends POST JSON, parses structured 400 error, and rejects unsafe payment_url`() =
        runBlocking {
            var nextStatus = 200
            var nextResponse = ""
            var capturedMethod = ""
            var capturedBody = ""

            val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
            server.createContext("/checkout") { exchange ->
                capturedMethod = exchange.requestMethod
                capturedBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                val bytes = nextResponse.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(nextStatus, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()

            try {
                val baseUrl = "http://127.0.0.1:${server.address.port}"
                val strings = Strings()
                val service = BillingService(
                    logs = LogRepository(strings),
                    strings = strings,
                    allowLocalHttp = true,
                )

                nextStatus = 200
                nextResponse = """
                {
                  "order_id": "ord_123",
                  "amount": 170100,
                  "currency": "RUB",
                  "payment_url": "$baseUrl/pay/ord_123"
                }
                """.trimIndent()

                val ok = service.createCheckout(
                    endpoint = "$baseUrl/checkout",
                    request = BillingCheckoutRequest(
                        planId = "pro-3m",
                        paymentMethod = "sbp",
                        promoCode = "YUMI10",
                        returnUrl = "yumi://billing/return?sub_id=s1",
                    ),
                )
                assertEquals("POST", capturedMethod)
                assertTrue(capturedBody.contains("\"plan_id\":\"pro-3m\""))
                assertTrue(capturedBody.contains("\"promo_code\":\"YUMI10\""))
                assertTrue(ok is BillingResult.Success)
                ok as BillingResult.Success
                assertEquals("$baseUrl/pay/ord_123", ok.value.paymentUrl)

                nextStatus = 400
                nextResponse = """{"error":"invalid_promo","message":"Promo code not valid"}"""
                val promoErr = service.createCheckout(
                    endpoint = "$baseUrl/checkout",
                    request = BillingCheckoutRequest(planId = "pro-3m", paymentMethod = "sbp", promoCode = "BAD"),
                )
                assertTrue(promoErr is BillingResult.Failure)
                promoErr as BillingResult.Failure
                assertEquals("invalid_promo", promoErr.errorCode)
                assertEquals("Promo code not valid", promoErr.message)

                nextStatus = 200
                nextResponse = """{"order_id":"ord_x","payment_url":"http://evil.example.com/pay"}"""
                val unsafePayUrl = service.createCheckout(
                    endpoint = "$baseUrl/checkout",
                    request = BillingCheckoutRequest(planId = "pro-3m", paymentMethod = "sbp"),
                )
                assertTrue(unsafePayUrl is BillingResult.Failure)
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `SubscriptionService extracts billing URLs with header precedence and preserves metadata on failure`() =
        runBlocking {
            val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
            var baseUrl = ""
            server.createContext("/sub/ok") { exchange ->
                val bodyPlain = """
                    #billing-manifest-url: $baseUrl/api/from-comment
                    #payment-url: $baseUrl/pay/from-comment
                    vless://11111111-1111-1111-1111-111111111111@203.0.113.10:443?security=tls#DE1
                """.trimIndent()
                val encoded = Base64.getEncoder().encode(bodyPlain.toByteArray(Charsets.UTF_8))
                exchange.responseHeaders.add("billing-manifest-url", "$baseUrl/api/from-header")
                exchange.responseHeaders.add("profile-web-page-url", "$baseUrl/portal")
                exchange.responseHeaders.add("support-url", "$baseUrl/support")
                exchange.sendResponseHeaders(200, encoded.size.toLong())
                exchange.responseBody.use { it.write(encoded) }
            }
            server.createContext("/sub/expired") { exchange ->
                val bodyPlain = "vless://00000000-0000-0000-0000-000000000000@0.0.0.0:1?security=none#Expired"
                val encoded = Base64.getEncoder().encode(bodyPlain.toByteArray(Charsets.UTF_8))
                exchange.responseHeaders.add("billing-manifest-url", "$baseUrl/api/billing")
                exchange.responseHeaders.add("sub-expire-button-link", "$baseUrl/pay/expire-btn")
                exchange.responseHeaders.add("support-url", "$baseUrl/support")
                exchange.sendResponseHeaders(200, encoded.size.toLong())
                exchange.responseBody.use { it.write(encoded) }
            }
            server.createContext("/sub/hwid") { exchange ->
                val bodyPlain = "vless://00000000-0000-0000-0000-000000000000@0.0.0.0:1?security=none#Device+Limit"
                val encoded = Base64.getEncoder().encode(bodyPlain.toByteArray(Charsets.UTF_8))
                exchange.responseHeaders.add("x-hwid-max-devices-reached", "true")
                exchange.responseHeaders.add("billing-manifest-url", "$baseUrl/api/billing")
                exchange.responseHeaders.add("payment-url", "$baseUrl/pay/quick")
                exchange.responseHeaders.add("profile-web-page-url", "$baseUrl/portal")
                exchange.sendResponseHeaders(200, encoded.size.toLong())
                exchange.responseBody.use { it.write(encoded) }
            }
            server.start()

            try {
                baseUrl = "http://127.0.0.1:${server.address.port}"
                val strings = Strings()
                val service = SubscriptionService(
                    logs = LogRepository(strings),
                    strings = strings,
                    allowLocalBillingHttp = true,
                )

                val ok = service.fetch(Subscription(id = "sub-1", name = "Ok", url = "$baseUrl/sub/ok"))
                assertTrue(ok is SubscriptionUpdate.Success)
                ok as SubscriptionUpdate.Success
                assertEquals("$baseUrl/api/from-header", ok.subscription.billingManifestUrl)
                assertEquals("$baseUrl/pay/from-comment", ok.subscription.paymentUrl)
                assertEquals("$baseUrl/portal", ok.subscription.webPageUrl)
                assertEquals("$baseUrl/support", ok.subscription.supportUrl)

                val expired = service.fetch(Subscription(id = "sub-2", name = "Expired", url = "$baseUrl/sub/expired"))
                assertTrue(expired is SubscriptionUpdate.Failure)
                expired as SubscriptionUpdate.Failure
                assertEquals("$baseUrl/api/billing", expired.metadata?.billingManifestUrl)
                assertEquals("$baseUrl/pay/expire-btn", expired.metadata?.paymentUrl)
                assertEquals("$baseUrl/support", expired.metadata?.supportUrl)

                val hwid = service.fetch(Subscription(id = "sub-3", name = "HWID", url = "$baseUrl/sub/hwid"))
                assertTrue(hwid is SubscriptionUpdate.Failure)
                hwid as SubscriptionUpdate.Failure
                assertEquals(strings.get(com.mydrop.vpn.shared.R.string.error_device_limit), hwid.message)
                assertEquals("$baseUrl/api/billing", hwid.metadata?.billingManifestUrl)
                assertEquals("$baseUrl/pay/quick", hwid.metadata?.paymentUrl)
                assertEquals("$baseUrl/portal", hwid.metadata?.webPageUrl)
            } finally {
                server.stop(0)
            }
        }
}
