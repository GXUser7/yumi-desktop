package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.*
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class LatencyCancellationTest {
    @Test fun cancellingATlsProbeClosesTheSocketWithoutWaitingForTheHandshakeTimeout() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val hello = CompletableDeferred<Unit>()
            var peer: Socket? = null
            val accepted = async(Dispatchers.IO) {
                server.accept().use { socket ->
                    peer = socket
                    assertTrue(socket.getInputStream().read() >= 0)
                    hello.complete(Unit)
                    // The fake endpoint never completes TLS; it observes the client's close.
                    socket.getInputStream().readBytes()
                }
            }
            val node = ProxyNode("tls", "TLS", "127.0.0.1", server.localPort, ProxySettings.Trojan("test"))
            val probe = async { LatencyTester().measure(node, PingMode.Tls) }
            try {
                withTimeout(5000) { hello.await() }
                withTimeout(1000) { probe.cancelAndJoin(); accepted.await() }
                assertTrue(probe.isCancelled)
            } finally { peer?.close(); probe.cancel(); accepted.cancel() }
        }
    }

    @Test fun blockedDnsReturnsImmediatelyUnderWithTimeoutOrNull() = runBlocking {
        val blockingTester = LatencyTester(
            resolver = {
                Thread.sleep(10_000)
                InetAddress.getByName("127.0.0.1")
            },
            timeoutMillis = 2_000,
        )
        val node = ProxyNode("dns-block", "Blocked DNS", "blocked.example.invalid", 443, ProxySettings.Trojan("test"))
        val started = System.nanoTime()
        val result = withTimeoutOrNull(120) {
            blockingTester.measure(node, PingMode.Tcp)
        }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertNull(result)
        assertTrue("Expected immediate return on blocked DNS, took ${elapsedMillis}ms", elapsedMillis < 1_500)
    }

    @Test fun internalProbeTimeoutReturnsFailedWithoutWaitingForStuckGetaddrinfo() = runBlocking {
        val blockingTester = LatencyTester(
            resolver = {
                Thread.sleep(10_000)
                InetAddress.getByName("127.0.0.1")
            },
            timeoutMillis = 120,
        )
        val node = ProxyNode("dns-timeout", "Blocked DNS", "blocked.example.invalid", 443, ProxySettings.Trojan("test"))
        val started = System.nanoTime()
        val result = blockingTester.measure(node, PingMode.Tcp)
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue(result.failed)
        assertTrue("Expected timeout at ~120ms, took ${elapsedMillis}ms", elapsedMillis < 1_500)
    }

    @Test fun tlsProbeStripsBracketsFromIpv6HostAndAbortsQuicklyWhenPeerStalls() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val accepted = async(Dispatchers.IO) {
                server.accept().use { socket ->
                    // Read ClientHello and hold open without sending ServerHello or close_notify.
                    socket.getInputStream().read()
                    delay(5_000)
                }
            }
            val node = ProxyNode(
                id = "tls-bracket",
                name = "TLS",
                server = "[127.0.0.1]",
                port = server.localPort,
                settings = ProxySettings.Trojan("test"),
                tls = TlsOptions(serverName = "[127.0.0.1]"),
            )
            val started = System.nanoTime()
            val result = LatencyTester(resolver = InetAddress::getByName, timeoutMillis = 150)
                .measure(node, PingMode.Tls)
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000
            accepted.cancel()
            assertTrue(result.failed)
            assertTrue("Expected fast TLS failure without close_notify hang, took ${elapsedMillis}ms", elapsedMillis < 1_500)
        }
    }
}
