package com.yumi.desktop.platform

import com.yumi.desktop.model.ServiceRequest
import kotlinx.coroutines.*
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.*

class UnixTunnelBackendTest {
    @Before fun unixOnly() { assumeFalse(System.getProperty("os.name").startsWith("Windows")) }
    private fun fixture(handler: (SocketChannel) -> Unit, test: (UnixTunnelBackend) -> Unit) {
        val directory = Files.createTempDirectory("yumi-unix-ipc-")
        val socket = directory.resolve("t.sock")
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
            server.bind(UnixDomainSocketAddress.of(socket))
            val worker = thread(isDaemon = true) { runCatching { server.accept().use(handler) } }
            try { test(UnixTunnelBackend(socket, 1500)) }
            finally { server.close(); worker.join(1500); Files.deleteIfExists(socket); Files.deleteIfExists(directory) }
        }
    }
    @Test fun readsFragmentedUtf8FramesAndIgnoresFutureFields() {
        fixture({ channel ->
            val input = DataInputStream(Channels.newInputStream(channel))
            input.readNBytes(input.readInt())
            val body = """{"version":1,"state":"connected","detail":"служба","future":true}""".toByteArray()
            val data = ByteBuffer.allocate(4 + body.size).putInt(body.size).put(body).array()
            for (byte in data) channel.write(ByteBuffer.wrap(byteArrayOf(byte)))
        }) { backend -> runBlocking {
            val status = backend.request(ServiceRequest(command = "status"))
            assertEquals("connected", status.state)
            assertEquals("служба", status.detail)
        } }
    }
    @Test fun stalledServiceHasATotalDeadline() {
        fixture({ channel -> val buffer = ByteBuffer.allocate(1024); while (channel.read(buffer) >= 0) buffer.clear() }) { backend -> runBlocking {
            withTimeout(2000) { assertEquals("unavailable", backend.request(ServiceRequest(command = "status")).state) }
        } }
    }
    @Test fun invalidFrameSizeCannotAllocateAnUnboundedReply() {
        fixture({ channel -> channel.write(ByteBuffer.allocate(4).putInt(Int.MAX_VALUE).flip()) }) { backend -> runBlocking {
            assertEquals("unavailable", backend.request(ServiceRequest(command = "status")).state)
        } }
    }
    @Test fun cancellingTheCallerInterruptsAnInFlightSocketRead() {
        val accepted = CountDownLatch(1)
        fixture({ channel -> accepted.countDown(); val b=ByteBuffer.allocate(1024); while(channel.read(b)>=0)b.clear() }) { backend -> runBlocking {
            val job = launch(Dispatchers.Default) { backend.request(ServiceRequest(command = "status")) }
            assertTrue(accepted.await(2, TimeUnit.SECONDS))
            withTimeout(2000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        } }
    }
}
