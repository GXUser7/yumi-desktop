package com.yumi.desktop

import com.mydrop.vpn.core.model.ProbeEndpoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.measureTime

class TunnelHealthProbeTest {
    @Test fun authenticatedTunnelMustCarryARealHttpsResponse() = runBlocking {
        val connect = CompletableDeferred<String>()
        val request = CompletableDeferred<String>()
        Fixture { socket ->
            connect.complete(readHead(socket.inputStream))
            socket.outputStream.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
            serverTls(socket).use { tls ->
                request.complete(readHead(tls.inputStream))
                tls.outputStream.write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray())
                tls.outputStream.flush()
            }
        }.use { fixture ->
            assertTrue(probe().reachable(fixture.endpoint))
            val head = connect.await()
            assertTrue(head.startsWith("CONNECT cp.cloudflare.com:443 HTTP/1.1\r\n"))
            val basic = Base64.getEncoder().encodeToString("probe-user:probe-password".toByteArray())
            assertTrue(head.contains("Proxy-Authorization: Basic $basic\r\n"))
            val get = request.await()
            assertTrue(get.startsWith("GET /generate_204 HTTP/1.1\r\nHost: cp.cloudflare.com\r\n"))
            assertFalse(get.contains("Proxy-Authorization"), "Local proxy credentials must not reach the remote server")
            fixture.await()
        }
    }

    @Test fun aListeningProxyThatRefusesTheTunnelIsNotReachable() = runBlocking {
        Fixture { socket ->
            readHead(socket.inputStream)
            socket.outputStream.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
        }.use { fixture ->
            assertFalse(probe().reachable(fixture.endpoint))
            fixture.await()
        }
    }

    @Test fun aCaptiveOrFailedResponseIsNotAHealthyTunnel() = runBlocking {
        Fixture { socket ->
            readHead(socket.inputStream)
            socket.outputStream.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
            serverTls(socket).use { tls ->
                readHead(tls.inputStream)
                tls.outputStream.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
                tls.outputStream.flush()
            }
        }.use { fixture ->
            assertFalse(probe().reachable(fixture.endpoint))
            fixture.await()
        }
    }

    @Test fun tlsVerifiesTheRemoteHostname() = runBlocking {
        Fixture { socket ->
            readHead(socket.inputStream)
            socket.outputStream.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
            try { serverTls(socket).close() } catch (_: java.io.IOException) { /* Client rejects this host. */ }
        }.use { fixture ->
            assertFalse(TunnelHealthProbe(host = "wrong.example", tlsFactory = trustedContext.socketFactory).reachable(fixture.endpoint))
            fixture.await()
        }
    }

    @Test fun anUntrustedCertificateIsRejected() = runBlocking {
        Fixture { socket ->
            readHead(socket.inputStream)
            socket.outputStream.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
            try { serverTls(socket).close() } catch (_: java.io.IOException) { /* Client rejects the test CA. */ }
        }.use { fixture ->
            assertFalse(TunnelHealthProbe().reachable(fixture.endpoint))
            fixture.await()
        }
    }

    @Test fun aTrickledHeaderCannotExtendTheTotalDeadline() = runBlocking {
        Fixture { socket ->
            readHead(socket.inputStream)
            try {
                repeat(200) {
                    socket.outputStream.write('H'.code)
                    socket.outputStream.flush()
                    Thread.sleep(30)
                }
            } catch (_: java.io.IOException) { /* Deadline closes the socket. */ }
        }.use { fixture ->
            val elapsed = measureTime { assertFalse(probe(250).reachable(fixture.endpoint)) }
            assertTrue(elapsed.inWholeMilliseconds < 2_000, "The deadline must cover the entire response, not each byte: $elapsed")
            fixture.await()
        }
    }

    @Test fun cancellationImmediatelyClosesTheInFlightProxySocket() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val eof = CompletableDeferred<Int>()
        Fixture { socket ->
            readHead(socket.inputStream)
            started.complete(Unit)
            eof.complete(socket.inputStream.read())
        }.use { fixture ->
            val pending = async { probe().reachable(fixture.endpoint) }
            withTimeout(2_000) { started.await() }
            withTimeout(2_000) { pending.cancelAndJoin() }
            assertEquals(-1, withTimeout(2_000) { eof.await() })
            assertTrue(pending.isCancelled)
            fixture.await()
        }
    }

    @Test fun oversizedOrMalformedProxyHeadersAreRejected() = runBlocking {
        for (head in listOf("HTTP/1.1 2000 Not a status\r\n\r\n", "HTTP/1.1 200 OK\r\nX-Fill: " + "x".repeat(16 * 1024))) {
            Fixture { socket ->
                readHead(socket.inputStream)
                try { socket.outputStream.write(head.toByteArray()) }
                catch (_: java.io.IOException) { /* Client may reject the oversized header mid-write. */ }
            }.use { fixture ->
                assertFalse(probe().reachable(fixture.endpoint))
                fixture.await()
            }
        }
    }

    @Test fun probeMeasuresPostTlsRequestLatencyWithoutProxyConnectSetupTime() = runBlocking {
        Fixture { socket ->
            readHead(socket.inputStream)
            Thread.sleep(250)
            socket.outputStream.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
            serverTls(socket).use { tls ->
                readHead(tls.inputStream)
                tls.outputStream.write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray())
                tls.outputStream.flush()
            }
        }.use { fixture ->
            val rtt = probe().probe(fixture.endpoint)
            assertTrue(rtt != null && rtt in 1..150, "Expected 1-RTT HTTP latency excluding 250ms proxy setup, got $rtt ms")
            fixture.await()
        }
    }

    @Test fun probeSupportsCustomHttpAndHttpsUrlsWithoutBreakingDefaultHttps204Contract() = runBlocking {
        val customHttpReq = CompletableDeferred<String>()
        Fixture { socket ->
            readHead(socket.inputStream)
            socket.outputStream.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
            customHttpReq.complete(readHead(socket.inputStream))
            socket.outputStream.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            socket.outputStream.flush()
        }.use { fixture ->
            val rtt = TunnelHealthProbe(probeUrl = "http://custom.example:8080/healthz?ok=1").probe(fixture.endpoint)
            assertTrue(rtt != null && rtt >= 1)
            val get = customHttpReq.await()
            assertTrue(get.startsWith("GET /healthz?ok=1 HTTP/1.1\r\nHost: custom.example:8080\r\n"))
            fixture.await()
        }

        val customHttpsReq = CompletableDeferred<String>()
        Fixture { socket ->
            readHead(socket.inputStream)
            socket.outputStream.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
            serverTls(socket).use { tls ->
                customHttpsReq.complete(readHead(tls.inputStream))
                tls.outputStream.write("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n".toByteArray())
                tls.outputStream.flush()
            }
        }.use { fixture ->
            val rtt = TunnelHealthProbe(
                tlsFactory = trustedContext.socketFactory,
                probeUrl = "https://cp.cloudflare.com/ping",
            ).probe(fixture.endpoint)
            assertTrue(rtt != null && rtt >= 1)
            assertTrue(customHttpsReq.await().startsWith("GET /ping HTTP/1.1\r\nHost: cp.cloudflare.com\r\n"))
            fixture.await()
        }
    }

    private fun probe(timeout: Long = 5_000) = TunnelHealthProbe(timeoutMillis = timeout, tlsFactory = trustedContext.socketFactory)

    private class Fixture(handler: (Socket) -> Unit) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val peer = AtomicReference<Socket?>()
        private val closing = AtomicBoolean()
        private val completed = CompletableDeferred<Unit>()
        val endpoint = ProbeEndpoint(server.localPort, "probe-user", "probe-password")
        private val worker = thread(isDaemon = true, name = "tunnel-health-proxy-test") {
            try {
                server.accept().use { socket ->
                    peer.set(socket)
                    socket.soTimeout = 5_000
                    handler(socket)
                }
                completed.complete(Unit)
            } catch (failure: Throwable) {
                if (closing.get()) completed.complete(Unit) else completed.completeExceptionally(failure)
            }
        }
        suspend fun await() = withTimeout(5_000) { completed.await() }
        override fun close() {
            closing.set(true)
            peer.get()?.close()
            server.close()
            worker.join(2_000)
            assertFalse(worker.isAlive, "The local proxy fixture must not leave a blocked thread")
        }
    }

    private companion object {
        // This PKCS12 file is a public, test-only self-signed certificate and private key. Its
        // trust is confined to this fixture: production uses the platform's default trust store.
        val keyStore: KeyStore by lazy {
            KeyStore.getInstance("PKCS12").apply {
                TunnelHealthProbeTest::class.java.getResourceAsStream("/tunnel-probe-test.p12")!!.use { load(it, "test-fixture".toCharArray()) }
            }
        }
        val trustedContext: SSLContext by lazy {
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keyStore) }
            SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
        }
        val serverContext: SSLContext by lazy {
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, "test-fixture".toCharArray()) }
            SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
        }
        fun serverTls(raw: Socket): SSLSocket = (serverContext.socketFactory.createSocket(raw, "cp.cloudflare.com", 443, false) as SSLSocket).apply {
            useClientMode = false
            soTimeout = 5_000
            startHandshake()
        }
        fun readHead(input: InputStream): String {
            val text = StringBuilder()
            while (!text.endsWith("\r\n\r\n")) {
                val byte = input.read()
                check(byte >= 0 && text.length < 16 * 1024)
                text.append(byte.toChar())
            }
            return text.toString()
        }
    }
}
