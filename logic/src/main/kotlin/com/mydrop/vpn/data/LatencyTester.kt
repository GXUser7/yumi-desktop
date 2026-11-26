package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.LatencyResult
import com.mydrop.vpn.core.model.NodeGroup
import com.mydrop.vpn.core.model.PingMode
import com.mydrop.vpn.core.model.Protocol
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.ProxySettings
import com.mydrop.vpn.core.xray.XrayConfigFactory
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedTrustManager
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Measures nodes through an ephemeral Xray core probe session and streams results back as
 * `(nodeId, millisOrNull)` as each node finishes.
 */
fun interface CoreProber {
    suspend fun probe(
        nodes: List<ProxyNode>,
        settings: AppSettings,
        timeoutMillis: Int,
        concurrency: Int,
        onResult: (nodeId: String, millis: Int?) -> Unit,
    )
}

/**
 * Reachability and real-delay probes against a server's endpoint.
 *
 * When a [CoreProber] is provided, [PingMode.UrlTest] measures every node end-to-end by fetching
 * the probe URL through an ephemeral Xray outbound, and fast socket modes ([PingMode.Tcp],
 * [PingMode.Tls], [PingMode.Median]) automatically upgrade QUIC, WireGuard, and fragmented TLS
 * nodes to the core as well. All modes optionally resolve server hostnames through DoH first so
 * operator DNS poisoning does not hide working nodes.
 */
class LatencyTester(
    private val maxConcurrency: Int = 16,
    private val timeoutProvider: () -> Int = { LatencyResult.TIMEOUT_MILLIS },
    private val settingsProvider: () -> AppSettings = { AppSettings() },
    private val coreProber: CoreProber? = null,
    private val isTunnelRunning: () -> Boolean = { false },
    private val resolveNode: (ProxyNode, String) -> ProxyNode = ServerAddressResolver::resolveNode,
) {
    private var resolver: (String) -> InetAddress = InetAddress::getByName

    internal constructor(
        resolver: (String) -> InetAddress,
        timeoutMillis: Int = LatencyResult.TIMEOUT_MILLIS,
        maxConcurrency: Int = 16,
    ) : this(maxConcurrency = maxConcurrency, timeoutProvider = { timeoutMillis }) {
        this.resolver = resolver
    }

    private val groupProbes = Semaphore(maxConcurrency)
    private val probeExecutor = Dispatchers.IO.asExecutor()

    suspend fun measure(node: ProxyNode, mode: PingMode = PingMode.UrlTest): LatencyResult {
        val settings = settingsProvider()
        if (needsCoreProbe(node, mode, settings)) {
            return measureAll(listOf(node), mode).single()
        }
        val resolved = withContext(Dispatchers.IO) { maybeResolve(node, settings) }
        val group = resolved.group
        if (group != null && group.members.isNotEmpty()) return measureGroup(resolved, group, mode)
        return measureOne(resolved, mode)
    }

    private suspend fun measureGroup(node: ProxyNode, group: NodeGroup, mode: PingMode): LatencyResult {
        val results = coroutineScope {
            group.members.map { member -> async { groupProbes.withPermit { measureOne(member, mode) } } }.awaitAll()
        }
        val delays = results.withIndex()
            .filter { (_, result) -> !result.failed }
            .associate { (index, result) -> index to result.millis.coerceAtLeast(1) }
        val chosen = group.pick(delays)
        return LatencyResult(
            nodeId = node.id,
            millis = chosen?.let(delays::get) ?: 0,
            measuredAtEpochMillis = System.currentTimeMillis(),
            failed = chosen == null,
        )
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
        val timeout = effectiveTimeoutMillis()
        val start = System.nanoTime()
        val hasTls = node.tls?.enabled == true || (node.tls == null && node.settings is ProxySettings.Trojan)
        val ok = when {
            node.protocol.isQuicBased -> quicProbe(node, timeout)
            mode == PingMode.Tls && hasTls -> tlsHandshake(node, timeout)
            else -> tcpHandshake(node, timeout)
        }
        return if (ok) ((System.nanoTime() - start) / 1_000_000).toInt() else null
    }

    private suspend fun tcpHandshake(node: ProxyNode, timeout: Int): Boolean = probeSocket(::Socket, timeout) { socket ->
        val host = node.server.trim().removeSurrounding("[", "]")
        val address = resolver(host)
        socket.connect(InetSocketAddress(address, node.port), timeout)
    }

    /**
     * Runs blocking DNS resolution and socket I/O off the structured coroutine so cancellation or
     * [withTimeoutOrNull] returns immediately even when `getaddrinfo` blocks.
     */
    private suspend fun <S : Closeable> probeSocket(
        createSocket: () -> S,
        timeout: Int,
        block: (S) -> Unit,
    ): Boolean {
        val socketRef = AtomicReference<S?>(null)
        val cancelled = AtomicBoolean(false)
        return withTimeoutOrNull(timeout.toLong()) {
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

    private suspend fun tlsHandshake(node: ProxyNode, timeout: Int): Boolean = probeSocket(::Socket, timeout) { raw ->
        val host = node.server.trim().removeSurrounding("[", "]")
        val address = resolver(host)
        raw.connect(InetSocketAddress(address, node.port), timeout)
        val name = (node.tls?.serverName?.takeIf(String::isNotBlank) ?: node.server)
            .trim()
            .removeSurrounding("[", "]")
        val tls = probeSocketFactory.createSocket(raw, name, node.port, false) as SSLSocket
        tls.soTimeout = timeout
        tls.startHandshake()
    }

    private val probeSocketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS")
            .apply { init(null, arrayOf<TrustManager>(TrustEveryChain), null) }
            .socketFactory
    }

    private suspend fun quicProbe(node: ProxyNode, timeout: Int): Boolean = probeSocket(::DatagramSocket, timeout) { socket ->
        val host = node.server.trim().removeSurrounding("[", "]")
        val address = resolver(host)
        socket.connect(address, node.port)
        val packet = versionNegotiationProbe()
        val reply = ByteArray(1500)
        val deadlineNanos = System.nanoTime() + timeout * 1_000_000L
        val retryGapMillis = minOf(QUIC_RETRY_GAP_MILLIS, timeout / 3).coerceAtLeast(100)
        var attempt = 0
        while (attempt < QUIC_MAX_ATTEMPTS) {
            val remainingMillis = ((deadlineNanos - System.nanoTime()) / 1_000_000L).toInt()
            if (remainingMillis <= 0) break
            val sliceMillis = if (attempt == 0 && remainingMillis > retryGapMillis + 150) {
                retryGapMillis
            } else {
                remainingMillis
            }
            socket.soTimeout = sliceMillis
            socket.send(DatagramPacket(packet, packet.size))
            try {
                socket.receive(DatagramPacket(reply, reply.size))
                return@probeSocket
            } catch (_: SocketTimeoutException) {
                attempt++
            }
        }
        throw SocketTimeoutException("QUIC probe timed out")
    }

    private fun versionNegotiationProbe(): ByteArray {
        val packet = ByteArray(QUIC_DATAGRAM_SIZE)
        packet[0] = 0xC0.toByte()
        packet[1] = 0x0A; packet[2] = 0x0A; packet[3] = 0x0A; packet[4] = 0x0A
        packet[5] = 8
        Random.nextBytes(packet, 6, 14)
        packet[14] = 8
        Random.nextBytes(packet, 15, 23)
        return packet
    }

    /** Probes many servers at once, preserving input order and streaming results as each completes. */
    suspend fun measureAll(
        nodes: List<ProxyNode>,
        mode: PingMode = PingMode.UrlTest,
        onResult: (LatencyResult) -> Unit = {},
    ): List<LatencyResult> {
        if (nodes.isEmpty()) return emptyList()
        val settings = settingsProvider()
        val timeout = effectiveTimeoutMillis()

        val coreItems = ArrayList<IndexedValue<ProxyNode>>()
        val socketItems = ArrayList<IndexedValue<ProxyNode>>()
        nodes.forEachIndexed { index, node ->
            if (needsCoreProbe(node, mode, settings)) {
                coreItems.add(IndexedValue(index, node))
            } else {
                socketItems.add(IndexedValue(index, node))
            }
        }

        val orderedResults = AtomicReferenceArray<LatencyResult>(nodes.size)
        coroutineScope {
            if (coreItems.isNotEmpty()) {
                launch {
                    measureWithCore(coreItems, settings, timeout, orderedResults, onResult)
                }
            }
            if (socketItems.isNotEmpty()) {
                val gate = Semaphore(maxConcurrency)
                socketItems.forEach { (index, node) ->
                    launch {
                        gate.withPermit {
                            val resolved = withContext(Dispatchers.IO) { maybeResolve(node, settings) }
                            val group = resolved.group
                            val result = if (group != null && group.members.isNotEmpty()) {
                                measureGroup(resolved, group, mode)
                            } else {
                                measureOne(resolved, mode)
                            }
                            orderedResults.set(index, result)
                            onResult(result)
                        }
                    }
                }
            }
        }

        return List(nodes.size) { index ->
            orderedResults.get(index) ?: LatencyResult(
                nodeId = nodes[index].id,
                millis = 0,
                measuredAtEpochMillis = System.currentTimeMillis(),
                failed = true,
            )
        }
    }

    private suspend fun measureWithCore(
        items: List<IndexedValue<ProxyNode>>,
        settings: AppSettings,
        timeout: Int,
        orderedResults: AtomicReferenceArray<LatencyResult>,
        onResult: (LatencyResult) -> Unit,
    ) {
        val prober = coreProber ?: return

        val uniqueMembers = LinkedHashMap<String, ProxyNode>()
        val memberToPlainTargets = HashMap<String, MutableList<IndexedValue<ProxyNode>>>()
        val memberToGroups = HashMap<String, MutableList<GroupTracker>>()
        val trackers = ArrayList<GroupTracker>()

        withContext(Dispatchers.IO) {
            for ((index, node) in items) {
                val group = node.group
                if (group != null && group.members.isNotEmpty()) {
                    val resolvedMembers = group.members.map { maybeResolve(it, settings) }
                    resolvedMembers.forEach { member -> uniqueMembers.putIfAbsent(member.id, member) }
                    val tracker = GroupTracker(index = index, node = node, group = group)
                    trackers.add(tracker)
                    resolvedMembers.mapTo(HashSet()) { it.id }.forEach { memberId ->
                        memberToGroups.getOrPut(memberId) { ArrayList(2) }.add(tracker)
                    }
                } else {
                    val resolved = maybeResolve(node, settings)
                    uniqueMembers.putIfAbsent(resolved.id, resolved)
                    memberToPlainTargets
                        .getOrPut(resolved.id) { ArrayList(1) }
                        .add(IndexedValue(index, node))
                }
            }
        }

        val lock = Any()
        val reportedIndices = HashSet<Int>(items.size)
        val concurrency = if (isTunnelRunning()) CORE_CONCURRENCY_CONNECTED else CORE_CONCURRENCY_IDLE
        val coreTimeout = timeout.coerceIn(
            AppSettings.MIN_PROBE_TIMEOUT_MILLIS,
            AppSettings.MAX_PROBE_TIMEOUT_MILLIS,
        )

        prober.probe(uniqueMembers.values.toList(), settings, coreTimeout, concurrency) { memberId, millis ->
            val readyToEmit = ArrayList<LatencyResult>(2)
            val now = System.currentTimeMillis()
            synchronized(lock) {
                memberToPlainTargets[memberId]?.forEach { (index, node) ->
                    if (reportedIndices.add(index)) {
                        val ok = millis != null && millis > 0
                        val lr = LatencyResult(
                            nodeId = node.id,
                            millis = if (ok) millis!! else 0,
                            measuredAtEpochMillis = now,
                            failed = !ok,
                        )
                        orderedResults.set(index, lr)
                        readyToEmit.add(lr)
                    }
                }
                memberToGroups[memberId]?.forEach { tracker ->
                    tracker.record(memberId, millis)
                    if (tracker.isComplete() && reportedIndices.add(tracker.index)) {
                        val lr = tracker.buildResult(now)
                        orderedResults.set(tracker.index, lr)
                        readyToEmit.add(lr)
                    }
                }
            }
            readyToEmit.forEach(onResult)
        }

        if (!coroutineContext.isActive) return

        val missing = ArrayList<LatencyResult>()
        val now = System.currentTimeMillis()
        synchronized(lock) {
            for (tracker in trackers) {
                if (reportedIndices.add(tracker.index)) {
                    val lr = tracker.buildResult(now)
                    orderedResults.set(tracker.index, lr)
                    missing.add(lr)
                }
            }
            for ((index, node) in items) {
                if (reportedIndices.add(index)) {
                    val lr = LatencyResult(
                        nodeId = node.id,
                        millis = 0,
                        measuredAtEpochMillis = now,
                        failed = true,
                    )
                    orderedResults.set(index, lr)
                    missing.add(lr)
                }
            }
        }
        missing.forEach(onResult)
    }

    internal fun needsCoreProbe(node: ProxyNode, mode: PingMode, settings: AppSettings): Boolean {
        if (coreProber == null) return false
        if (mode == PingMode.UrlTest) return true
        val members = node.group?.members?.takeIf { it.isNotEmpty() } ?: listOf(node)
        return members.any { member ->
            member.protocol.isQuicBased ||
                member.protocol == Protocol.WIREGUARD ||
                (mode == PingMode.Tls && XrayConfigFactory.usesFragment(member, settings))
        }
    }

    private fun maybeResolve(node: ProxyNode, settings: AppSettings): ProxyNode =
        if (settings.resolveServerAddress) {
            resolveNode(node, settings.resolveDohUrl)
        } else {
            node
        }

    private fun effectiveTimeoutMillis(): Int = timeoutProvider().coerceAtLeast(1)

    private class GroupTracker(
        val index: Int,
        val node: ProxyNode,
        val group: NodeGroup,
    ) {
        private val expectedMemberIds: Set<String> = group.members.mapTo(HashSet()) { it.id }
        private val memberDelays = HashMap<String, Int?>(expectedMemberIds.size)

        fun record(memberId: String, millis: Int?) {
            memberDelays[memberId] = millis
        }

        fun isComplete(): Boolean = memberDelays.size >= expectedMemberIds.size

        fun buildResult(now: Long): LatencyResult {
            val delays = HashMap<Int, Int>(group.members.size)
            group.members.forEachIndexed { idx, member ->
                val ms = memberDelays[member.id]
                if (ms != null && ms > 0) {
                    delays[idx] = ms.coerceAtLeast(1)
                }
            }
            val chosen = group.pick(delays)
            return LatencyResult(
                nodeId = node.id,
                millis = chosen?.let(delays::get) ?: 0,
                measuredAtEpochMillis = now,
                failed = chosen == null,
            )
        }
    }

    private companion object {
        const val MEDIAN_SAMPLES = 3
        const val QUIC_DATAGRAM_SIZE = 1200
        const val QUIC_MAX_ATTEMPTS = 2
        const val QUIC_RETRY_GAP_MILLIS = 600
        const val CORE_CONCURRENCY_IDLE = 12
        const val CORE_CONCURRENCY_CONNECTED = 8
    }
}

private object TrustEveryChain : X509ExtendedTrustManager() {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
