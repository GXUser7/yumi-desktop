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
    private val probeUrl: String = ProbeTargets.DEFAULT_URL,
) {
    init { require(timeoutMillis in 1..Int.MAX_VALUE.toLong()) }

    suspend fun reachable(probe: ProbeEndpoint): Boolean = probe(probe) != null

    /** Measures the 1-RTT HTTP/HTTPS round-trip in milliseconds, or null if unreachable. */
    suspend fun probe(probe: ProbeEndpoint, targetUrl: String = probeUrl): Int? = withTimeoutOrNull(timeoutMillis) {
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
                    executeProbe(raw, probe, targetUrl)
                }
            } finally {
                cancelWatcher.cancel()
            }
        }
    }

    private fun executeProbe(raw: Socket, probe: ProbeEndpoint, targetUrl: String): Int? = try {
        val customUrl = host == ProbeTargets.TUNNEL && targetUrl != ProbeTargets.DEFAULT_URL
        val uri = if (customUrl) runCatching { java.net.URI(targetUrl.trim()) }.getOrNull() else null
        if (customUrl && (uri == null || uri.host.isNullOrBlank())) return null
        val targetHost = uri?.host ?: host
        val useTls = if (uri != null) uri.scheme.equals("https", ignoreCase = true) else true
        val targetPort = uri?.port?.takeIf { it > 0 } ?: if (useTls) 443 else 80
        val path = uri?.let {
            val p = it.rawPath?.takeIf(String::isNotEmpty) ?: "/"
            val q = it.rawQuery?.takeIf(String::isNotEmpty)?.let { query -> "?$query" }.orEmpty()
            p + q
        } ?: "/generate_204"
        val hostHeader = if ((useTls && targetPort == 443) || (!useTls && targetPort == 80)) {
            targetHost
        } else {
            "$targetHost:$targetPort"
        }

        raw.use {
            raw.soTimeout = timeoutMillis.toInt()
            raw.connect(InetSocketAddress("127.0.0.1", probe.port), timeoutMillis.toInt())
            val credentials = Base64.getEncoder().encodeToString(
                "${probe.username}:${probe.password}".toByteArray(Charsets.UTF_8),
            )
            val connectAuthority = if (':' in targetHost && !targetHost.startsWith("[")) {
                "[$targetHost]:$targetPort"
            } else {
                "$targetHost:$targetPort"
            }
            raw.outputStream.write((
                "CONNECT $connectAuthority HTTP/1.1\r\n" +
                    "Host: $connectAuthority\r\n" +
                    "Proxy-Authorization: Basic $credentials\r\n\r\n"
                ).toByteArray(Charsets.US_ASCII))
            raw.outputStream.flush()
            if (readStatus(raw.inputStream) != 200) return@use null

            val transport: Socket = if (useTls) {
                (tlsFactory.createSocket(raw, targetHost, targetPort, false) as SSLSocket).also { tls ->
                    tls.soTimeout = timeoutMillis.toInt()
                    tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    tls.startHandshake()
                }
            } else {
                raw
            }
            val started = System.nanoTime()
            transport.outputStream.write((
                "GET $path HTTP/1.1\r\nHost: $hostHeader\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(Charsets.US_ASCII))
            transport.outputStream.flush()
            val code = readStatus(transport.inputStream)
            // A listening endpoint or successful CONNECT is insufficient: the
            // remote endpoint must actually return its expected response.
            val healthy = if (customUrl) code != null && code in 200..299 else code == 204
            if (healthy) {
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
