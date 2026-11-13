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
}
