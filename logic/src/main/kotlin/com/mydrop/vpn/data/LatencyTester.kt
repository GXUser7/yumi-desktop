package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.LatencyResult
import com.mydrop.vpn.core.model.NodeGroup
import com.mydrop.vpn.core.model.PingMode
import com.mydrop.vpn.core.model.ProxyNode
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedTrustManager
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Reachability probes against a server's endpoint.
 *
 * None of these say anything about whether the credentials work — that would need the core
 * running and a separate outbound per node. What they do is separate live endpoints from blocked
 * or dead ones and give a number worth sorting by, which is the job the list actually needs.
 *
 * The transport dictates the probe: TCP protocols get a handshake, QUIC ones (Hysteria2, TUIC)
 * get a UDP probe, because they have no TCP port to connect to and used to be reported as
 * unmeasurable for that reason.
 */
class LatencyTester(private val maxConcurrency: Int = 16) {
    private var resolver: (String) -> InetAddress = InetAddress::getByName
    private var timeoutMillis: Int = LatencyResult.TIMEOUT_MILLIS

    internal constructor(
        resolver: (String) -> InetAddress,
        timeoutMillis: Int = LatencyResult.TIMEOUT_MILLIS,
        maxConcurrency: Int = 16,
    ) : this(maxConcurrency) {
        this.resolver = resolver
        this.timeoutMillis = timeoutMillis
    }

    private val groupProbes = Semaphore(maxConcurrency)
    private val probeExecutor = Dispatchers.IO.asExecutor()

    suspend fun measure(node: ProxyNode, mode: PingMode = PingMode.Tcp): LatencyResult {
        val group = node.group
        if (group == null || group.members.isEmpty()) return measureOne(node, mode)
        val results = coroutineScope {
            group.members.map { member -> async { groupProbes.withPermit { measureOne(member, mode) } } }.awaitAll()
        }
        val delays = results.withIndex().filter { !it.value.failed }
            .associate { it.index to it.value.millis.coerceAtLeast(1) }
        val chosen = group.pick(delays)
        return LatencyResult(node.id, chosen?.let(delays::get) ?: 0, System.currentTimeMillis(), failed = chosen == null)
    }

    private suspend fun measureOne(node: ProxyNode, mode: PingMode): LatencyResult =
        withContext(Dispatchers.IO) {
            val samples = if (mode == PingMode.Median) MEDIAN_SAMPLES else 1
            val measurements = ArrayList<Int>(samples)

            repeat(samples) {
                ensureActive()
                probe(node, mode)?.let(measurements::add)
            }

            // A median needs a majority of successes to mean anything; one lucky probe out of
            // three says the endpoint is flaky, not fast.
            val ok = measurements.size > samples / 2
            LatencyResult(
                nodeId = node.id,
                millis = if (ok) measurements.sorted()[measurements.size / 2] else 0,
                measuredAtEpochMillis = System.currentTimeMillis(),
                failed = !ok,
            )
        }

    /** One probe. Returns the round trip in milliseconds, or null if it did not answer. */
    private suspend fun probe(node: ProxyNode, mode: PingMode): Int? {
        val start = System.nanoTime()
        val ok = when {
            node.protocol.isQuicBased -> quicProbe(node)
            mode == PingMode.Tls -> tlsHandshake(node)
            else -> tcpHandshake(node)
        }
        return if (ok) ((System.nanoTime() - start) / 1_000_000).toInt() else null
    }

    private suspend fun tcpHandshake(node: ProxyNode): Boolean = probeSocket(::Socket) { socket ->
        val host = node.server.trim().removeSurrounding("[", "]")
        val address = resolver(host)
        socket.connect(InetSocketAddress(address, node.port), timeoutMillis)
    }

    /**
     * Runs blocking DNS resolution and socket I/O off the structured coroutine so cancellation or
     * [withTimeoutOrNull] returns immediately even when `getaddrinfo` blocks.
     */
    private suspend fun <S : Closeable> probeSocket(
        createSocket: () -> S,
        block: (S) -> Unit,
    ): Boolean {
        val socketRef = AtomicReference<S?>(null)
        val cancelled = AtomicBoolean(false)
        return withTimeoutOrNull(timeoutMillis.toLong()) {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation {
                    cancelled.set(true)
                    socketRef.getAndSet(null)?.let { runCatching { it.close() } }
                }
                probeExecutor.execute {
                    var socket: S? = null
                    val success = try {
                        if (cancelled.get() || !continuation.isActive) {
                            false
                        } else {
                            socket = createSocket()
                            socketRef.set(socket)
                            if (cancelled.get() || !continuation.isActive) {
                                runCatching { socket.close() }
                                false
                            } else {
                                block(socket)
                                true
                            }
                        }
                    } catch (_: Throwable) {
                        false
                    } finally {
                        cancelled.set(true)
                        socketRef.set(null)
                        socket?.let { runCatching { it.close() } }
                    }
                    if (continuation.isActive) {
                        continuation.resume(success)
                    }
                }
            }
        } ?: false
    }

    /**
     * Connect plus a full TLS handshake. Costs two or three round trips instead of one, and in
     * exchange proves the endpoint really terminates TLS — which for a REALITY or masquerading
     * node is the difference between "the port is open" and "the disguise is up".
     *
     * Certificates are not validated: see [probeSocketFactory] for why that is the only setting
     * under which this mode measures anything at all.
     */
    private suspend fun tlsHandshake(node: ProxyNode): Boolean = probeSocket(::Socket) { raw ->
        val host = node.server.trim().removeSurrounding("[", "]")
        val address = resolver(host)
        raw.connect(InetSocketAddress(address, node.port), timeoutMillis)
        val name = (node.tls?.serverName?.takeIf(String::isNotBlank) ?: node.server)
            .trim()
            .removeSurrounding("[", "]")
        val tls = probeSocketFactory.createSocket(raw, name, node.port, false) as SSLSocket
        tls.soTimeout = timeoutMillis
        tls.startHandshake()
        // Intentionally do not call tls.close(): closing `raw` in probeSocket avoids hanging on close_notify.
    }

    /**
     * A factory that trusts every chain, for probes only.
     *
     * This used to be `SSLSocketFactory.getDefault()`, whose trust managers validate the chain
     * inside `startHandshake()` — the exact opposite of what this probe needs. A REALITY node
     * serves a forged certificate by design and a self-signed Hysteria2 or Trojan node chains to
     * no system root, so the handshake threw for all of them and every such server was recorded
     * as unreachable. That reading was not merely cosmetic: [FailoverWatchdog] treats a failed
     * probe as a dead server, so TLS ping mode moved the tunnel off servers that were working.
     *
     * Trusting everything is safe here in a way it never is on a data path: the socket carries no
     * traffic and no credentials, it is closed the moment the handshake completes, and the core
     * does its own TLS with its own settings and its own trust store.
     */
    private val probeSocketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS")
            .apply { init(null, arrayOf<TrustManager>(TrustEveryChain), null) }
            .socketFactory
    }

    /**
     * QUIC endpoints answer an unknown version with a Version Negotiation packet — RFC 9000 §6
     * requires it. So we send a long-header packet carrying a reserved version and wait for any
     * reply; anything coming back means a QUIC server is listening.
     *
     * Two honest limits: a datagram carrying an Initial must be padded to 1200 bytes or servers
     * drop it, and endpoints with obfuscation (Hysteria2's salamander, say) will not answer at
     * all, so those still read as unreachable.
     */
    private suspend fun quicProbe(node: ProxyNode): Boolean = probeSocket(::DatagramSocket) { socket ->
        socket.soTimeout = timeoutMillis
        val host = node.server.trim().removeSurrounding("[", "]")
        val address = resolver(host)
        val packet = versionNegotiationProbe()
        socket.send(DatagramPacket(packet, packet.size, address, node.port))
        socket.receive(DatagramPacket(ByteArray(1500), 1500))
    }

    private fun versionNegotiationProbe(): ByteArray {
        val packet = ByteArray(QUIC_DATAGRAM_SIZE)
        // Long header with the fixed bit set; the low bits are packet-type and are ignored by a
        // server that has already decided it does not know the version.
        packet[0] = 0xC0.toByte()
        // A reserved "greasing" version no server implements, which is what forces the answer.
        packet[1] = 0x0A; packet[2] = 0x0A; packet[3] = 0x0A; packet[4] = 0x0A
        packet[5] = 8                                   // destination connection id length
        Random.nextBytes(packet, 6, 14)
        packet[14] = 8                                  // source connection id length
        Random.nextBytes(packet, 15, 23)
        // The remainder stays zero: token length, length and payload all read as empty, and the
        // padding to 1200 bytes is exactly what the anti-amplification rule wants to see.
        return packet
    }

    /** Probes many servers at once, capped so a large subscription cannot exhaust sockets. */
    suspend fun measureAll(
        nodes: List<ProxyNode>,
        mode: PingMode = PingMode.Tcp,
        onResult: (LatencyResult) -> Unit = {},
    ): List<LatencyResult> = coroutineScope {
        val gate = Semaphore(maxConcurrency)
        nodes.map { node ->
            async { gate.withPermit { measure(node, mode).also(onResult) } }
        }.awaitAll()
    }

    private companion object {
        const val TIMEOUT = LatencyResult.TIMEOUT_MILLIS
        const val MEDIAN_SAMPLES = 3
        const val QUIC_DATAGRAM_SIZE = 1200
    }
}

/**
 * Accepts any certificate. Reachable only through [LatencyTester.probeSocketFactory], which
 * documents why a probe must not validate; nothing else in the app may use it.
 */
private object TrustEveryChain : X509ExtendedTrustManager() {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        socket: Socket?,
    ) = Unit

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        socket: Socket?,
    ) = Unit

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        engine: SSLEngine?,
    ) = Unit

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        engine: SSLEngine?,
    ) = Unit

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
