package com.yumi.desktop

import com.mydrop.vpn.core.model.ProbeEndpoint
import com.mydrop.vpn.core.model.ProbeTargets
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Checks traffic through the selected outbound, rather than just the local core's process. */
internal class TunnelHealthProbe(
    private val timeoutMillis: Long = 5_000,
    private val host: String = ProbeTargets.TUNNEL,
    private val tlsFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
) {
    init { require(timeoutMillis in 1..Int.MAX_VALUE.toLong()) }

    suspend fun reachable(probe: ProbeEndpoint): Boolean = probe(probe) != null

    /** Measures the post-TLS 1-RTT HTTPS round-trip in milliseconds, or null if unreachable. */
    suspend fun probe(probe: ProbeEndpoint): Int? = withTimeoutOrNull(timeoutMillis) {
        val raw = Socket()
        coroutineScope {
            // Closing the underlying socket immediately on cancellation/timeout unblocks connect,
            // TLS handshake, and reads/writes so the deadline covers the whole exchange.
            val cancelWatcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    runCatching { raw.close() }
                }
            }
            try {
                runInterruptible(Dispatchers.IO) {
                    executeProbe(raw, probe)
                }
            } finally {
                cancelWatcher.cancel()
            }
        }
    }

    private fun executeProbe(raw: Socket, probe: ProbeEndpoint): Int? = try {
        raw.use {
            raw.soTimeout = timeoutMillis.toInt()
            raw.connect(InetSocketAddress("127.0.0.1", probe.port), timeoutMillis.toInt())
            val credentials = Base64.getEncoder().encodeToString(
                "${probe.username}:${probe.password}".toByteArray(Charsets.UTF_8),
            )
            raw.outputStream.write((
                "CONNECT $host:443 HTTP/1.1\r\n" +
                    "Host: $host:443\r\n" +
                    "Proxy-Authorization: Basic $credentials\r\n\r\n"
                ).toByteArray(Charsets.US_ASCII))
            raw.outputStream.flush()
            if (readStatus(raw.inputStream) != 200) return@use null

            val tls = tlsFactory.createSocket(raw, host, 443, false) as SSLSocket
            tls.soTimeout = timeoutMillis.toInt()
            tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            tls.startHandshake()
            val started = System.nanoTime()
            tls.outputStream.write((
                "GET /generate_204 HTTP/1.1\r\nHost: $host\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(Charsets.US_ASCII))
            tls.outputStream.flush()
            // A listening endpoint or successful CONNECT is insufficient: the
            // remote HTTPS endpoint must actually return its expected response.
            if (readStatus(tls.inputStream) == 204) {
                ((System.nanoTime() - started) / 1_000_000).toInt().coerceAtLeast(1)
            } else null
        }
    } catch (_: Exception) {
        null
    }

    private fun readStatus(input: InputStream): Int? {
        val header = StringBuilder()
        repeat(16 * 1024) {
            val byte = input.read()
            if (byte < 0) return null
            header.append(byte.toChar())
            if (header.endsWith("\r\n\r\n")) {
                val line = header.toString().substringBefore("\r\n")
                return Regex("HTTP/1\\.[01] ([0-9]{3})(?:[ \\t].*)?")
                    .matchEntire(line)?.groupValues?.get(1)?.toIntOrNull()
            }
        }
        return null
    }
}
