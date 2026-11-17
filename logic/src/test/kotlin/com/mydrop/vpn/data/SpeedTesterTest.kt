package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.ProbeEndpoint
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedTesterTest {

    @Test
    fun `readHead caps response headers at 16 KB`() {
        val tester = SpeedTester(LogRepository(), Strings())
        val oversized = ("HTTP/1.1 200 OK\r\n" + "X-Pad: " + "a".repeat(17_000) + "\r\n\r\n").toByteArray()
        var threw = false
        try {
            tester.readHead(ByteArrayInputStream(oversized))
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue("Expected readHead to reject >16KB headers", threw)
    }

    @Test
    fun `upload request size is bounded so HTTP status check runs well within 8s`() {
        assertTrue(SpeedTester.UPLOAD_BYTES in 1_000_000L..10_000_000L)
    }

    @Test
    fun `cancelling measure closes in-flight proxy socket immediately`(): Unit = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val connected = CompletableDeferred<Unit>()
            val accepted = async(Dispatchers.IO) {
                server.accept().use { socket ->
                    socket.getInputStream().read()
                    connected.complete(Unit)
                    socket.getInputStream().readBytes()
                }
            }
            val job = launch {
                SpeedTester(LogRepository(), Strings())
                    .measure(ProbeEndpoint(server.localPort, "u", "p"), "Test")
                    .collect()
            }
            try {
                withTimeout(5_000) { connected.await() }
                withTimeout(1_000) {
                    job.cancelAndJoin()
                    accepted.await()
                }
            } finally {
                job.cancel()
                accepted.cancel()
            }
        }
        Unit
    }
}
