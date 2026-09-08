package com.yumi.desktop

import com.mydrop.vpn.core.model.ProbeEndpoint
import com.mydrop.vpn.core.model.ProbeTargets
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.Base64

/**
 * What became of one connection, once it was actually used for something.
 *
 * [Passing] and [Unknown] are the screen's silence; the other three are the three different
 * things "connected, but nothing loads" turns out to mean.
 */
enum class TunnelVerdict { Unknown, Passing, ResolverHijacked, SystemBypassed, NodeSilent }

/**
 * Asks whether the tunnel that just came up is carrying this machine's traffic.
 *
 * The service reports `connected` the moment `Start()` returns, and that is an honest answer to a
 * narrower question than the user is asking: the adapter exists, the routes were accepted, the
 * core is running. Everything that decides whether a browser loads a page happens afterwards, and
 * a tunnel can be perfectly healthy while the machine around it sends every packet somewhere else.
 * That is not hypothetical — a second VPN client left behind a TUN adapter holding the resolver,
 * the tunnel came up beside it, and the connect screen said `connected` for as long as anyone
 * cared to look at it.
 *
 * Two requests, and the difference between them is the whole diagnosis:
 *
 * * through the probe inbound, which routing hands straight to the selected server. The hostname
 *   never meets a local resolver — the core passes it to the outbound and the server resolves it —
 *   so this asks only "is the server there and carrying traffic".
 * * through nothing at all, as any other program on the machine would. On the phone this would
 *   prove nothing, because the app excludes itself from its own tunnel; on Windows there is no
 *   such exclusion and the adapter captures this process along with everything else. That is what
 *   makes the plain request a fair witness for the browser that is about to fail.
 *
 * Server yes, machine no, is then read once more to say *why*, because the common cause names
 * itself: fake-ip clients hand out addresses from the benchmarking range, and a name that resolves
 * to one of those has been answered by somebody else's tunnel.
 */
object TunnelCheck {

    fun run(probe: ProbeEndpoint): TunnelVerdict {
        val credentials = Base64.getEncoder()
            .encodeToString("${probe.username}:${probe.password}".toByteArray())
        val viaTunnel = Proxy(Proxy.Type.HTTP, InetSocketAddress(LOOPBACK, probe.port))
        if (!answers(ProbeTargets.url(ProbeTargets.TUNNEL), viaTunnel, credentials)) return TunnelVerdict.NodeSilent
        // NO_PROXY rather than the default: a system proxy left set by another client would carry
        // this request and report success for a machine whose routing is still broken.
        if (answers(ProbeTargets.url(ProbeTargets.DNS), Proxy.NO_PROXY, null)) return TunnelVerdict.Passing
        return if (resolvesIntoBenchmarkSpace(ProbeTargets.DNS)) TunnelVerdict.ResolverHijacked
        else TunnelVerdict.SystemBypassed
    }

    /**
     * Exactly 204, which is the whole reason these endpoints exist.
     *
     * Accepting any success would accept the two answers that matter most here: a captive portal's
     * page, and a redirect to one. Both mean something between this machine and the internet
     * answered instead of the internet, which for a tunnel that is supposed to be carrying this
     * request is the failure, not a pass.
     */
    private fun answers(url: String, through: Proxy, credentials: String?): Boolean = runCatching {
        val connection = URL(url).openConnection(through) as HttpURLConnection
        connection.apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            instanceFollowRedirects = false
            // Set as a header rather than through `Authenticator`, which is process-wide: the one
            // installed for subscription refreshes answers for this same loopback port, and two
            // components taking turns at a global default is a race nobody needs.
            credentials?.let { setRequestProperty("Proxy-Authorization", "Basic $it") }
        }
        try { connection.responseCode == 204 } finally { connection.disconnect() }
    }.getOrDefault(false)

    /**
     * Whether the machine's own resolver answers with an address out of 198.18.0.0/15.
     *
     * RFC 2544 reserved that range for benchmarking equipment, which is precisely why every
     * fake-ip implementation hands it out: nothing on the internet lives there, so the address is
     * meaningless to anyone but the tunnel that invented it. Reaching one through *our* tunnel
     * means the packet leaves for a server that has never heard of it.
     */
    private fun resolvesIntoBenchmarkSpace(host: String): Boolean =
        runCatching { answeredFromBenchmarkSpace(InetAddress.getAllByName(host).asList()) }.getOrDefault(false)

    /** 198.18.0.0/15 — both halves, and nothing on either side of them. */
    internal fun answeredFromBenchmarkSpace(addresses: List<InetAddress>): Boolean = addresses.any { address ->
        val bytes = address.address
        bytes.size == 4 && (bytes[0].toInt() and 0xFF) == 198 && (bytes[1].toInt() and 0xFF) in 18..19
    }

    private const val LOOPBACK = "127.0.0.1"
    private const val CONNECT_TIMEOUT_MILLIS = 5_000
    private const val READ_TIMEOUT_MILLIS = 5_000
}
