package com.mydrop.vpn.data

import com.mydrop.vpn.shared.R
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.Subscription
import com.mydrop.vpn.core.model.SubscriptionUpdate
import com.mydrop.vpn.core.net.isNumericAddress
import com.mydrop.vpn.core.parse.SubscriptionBody
import com.mydrop.vpn.core.parse.ProxyUriParser
import com.mydrop.vpn.core.xray.XrayConfigFactory
import com.mydrop.vpn.core.parse.SubscriptionParser
import com.mydrop.vpn.core.model.ProbeEndpoint
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import java.util.Base64
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What a panel with device binding wants to know about the client asking for the list.
 *
 * [hwid] is generated once per installation and random. Panels of the Remnawave family count
 * devices by this value, so it has to be stable — but deriving it from anything real (the Android
 * ID, the advertising ID, the IMEI) would hand every panel the user ever subscribes to a handle
 * that follows them across apps and reinstalls. A random identifier counts devices just as well
 * and identifies nothing else.
 */
data class DeviceIdentity(
    val hwid: String,
    val os: String,
    val osVersion: String,
    val model: String,
)

/**
 * Fetches and parses subscription documents.
 *
 * Panels key their behaviour off the User-Agent: several only emit the base64 share-link format
 * (rather than a Clash config) when they recognise a proxy client, so a browser-looking agent
 * would get a document this app cannot use.
 *
 * Some go further and refuse to serve anything until the client identifies its device. They do not
 * fail the request when it does not — they answer 200 with a handful of unusable placeholder
 * servers whose *names* carry the instructions, which is how "🚫 Отсутствует передача HWID" ended
 * up in the server list looking like six real servers. Hence both halves of the handling here: the
 * headers go out on every request, and a reply made of placeholders is reported as the refusal it
 * is instead of being imported.
 */
class SubscriptionService(
    private val logs: LogRepository,
    private val strings: Strings,
    private val userAgent: String =
        "Yumi/1.0.2 (" + (System.getProperty("os.name")?.replace(' ', '_') ?: "unknown") + ")",
    private val identity: () -> DeviceIdentity? = { null },
    /**
     * How to reach the running tunnel from inside this process, or null when there is none.
     *
     * A lambda rather than the value: the endpoint changes with every tunnel and this class
     * outlives all of them.
     */
    private val probeEndpoint: () -> ProbeEndpoint? = { null },
) {

    private companion object {
        /** The tunnel publishes its inbound here and nowhere else. */
        private const val LOOPBACK = "127.0.0.1"

        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 30_000
        const val PROXY_CONNECT_TIMEOUT_MILLIS = 12_000
        const val PROXY_READ_TIMEOUT_MILLIS = 15_000
        const val MAX_REDIRECTS = 5
        const val MAX_HEADER_BYTES = 32 * 1024
        const val MAX_BODY_BYTES = 8L * 1024 * 1024
        const val MAX_WEB_PAGE_URL_LENGTH = 2048

        /** A panel's subscription link: one opaque id as the whole path, as Remnawave writes them. */
        val PANEL_LINK_PATH = Regex("^/[A-Za-z0-9_-]{8,64}/?$")

        /**
         * Appended to this app's own agent when asking for Xray documents; see [autoSelectVariant].
         * The name of a client panels already hand documents to, said the way browsers have long
         * said `Mozilla/5.0 (compatible; …)`: this app still names itself first.
         */
        const val DOCUMENTS_AGENT_SUFFIX = " (compatible; Happ)"
    }

    suspend fun fetch(subscription: Subscription): SubscriptionUpdate = withContext(Dispatchers.IO) {
        runCatching { fetchInternal(subscription) }
            .getOrElse { error ->
                val message = error.message
                    ?: error::class.simpleName
                    ?: strings.get(R.string.error_network)
                logs.warn(R.string.log_subscription_message, subscription.name, message)
                SubscriptionUpdate.Failure(subscription.id, message)
            }
    }

    private fun fetchInternal(subscription: Subscription): SubscriptionUpdate {
        // Direct first, through the tunnel second, and the order is not arbitrary.
        //
        // The app excludes itself from its own tunnel, which is what makes its probes describe the
        // phone's real network rather than the proxy's. The cost of that shows up under an
        // allow-list regime, where only approved domains resolve and connect: the tunnel is up and
        // carrying the user's traffic while the app itself cannot reach its own provider, so the
        // subscription silently stops updating exactly when it matters most. Observed on a
        // Russian carrier: `Failed to connect to sub.example.com` with a healthy tunnel alongside.
        //
        // So a refusal is retried through the loopback inbound the tunnel already publishes. Only
        // on failure — the direct road is faster, needs no tunnel, and keeps working when the
        // provider is reachable and the proxy is not.
        val response = fetch(subscription.url, subscription)

        if (response.code !in 200..299) {
            return SubscriptionUpdate.Failure(
                subscription.id,
                strings.get(R.string.error_http, response.code),
            )
        }

        return when (val body = SubscriptionParser.parse(response.body, subscription.id)) {
            is SubscriptionBody.Nodes -> {
                // A list made entirely of placeholders is a refusal dressed as a list. Importing it
                // fills the server list with six unusable entries whose names are an instruction.
                if (body.nodes.isNotEmpty() && body.nodes.all { it.isPlaceholder() }) {
                    // The panel writes its own explanation into the names — device limit reached,
                    // binding not enabled, subscription expired — so it is quoted rather than
                    // replaced by a guess at which of those it was. The rest of the names carry
                    // the instructions and go to the journal.
                    val notice = body.nodes.first().name.trim()
                    body.nodes.forEach {
                        logs.warn(R.string.log_subscription_message, subscription.name, it.name)
                    }
                    RuntimeLog.w(
                        "YumiSub",
                        "panel refused: hwidHeaders=${response.hwidRequired} — " +
                            body.nodes.joinToString(" | ") { it.name },
                    )
                    // The panel is explicit about this one in a header, and it is the case a user
                    // can actually do something about — so it is named rather than quoted.
                    if (response.deviceLimitReached) {
                        return SubscriptionUpdate.Failure(
                            subscription.id,
                            strings.get(R.string.error_device_limit),
                        )
                    }
                    return SubscriptionUpdate.Failure(
                        subscription.id,
                        strings.get(R.string.error_panel_notice, notice),
                    )
                }

                // The same subscription as Xray documents, when this list flattened the provider's
                // auto-select servers into their last resort; see autoSelectVariant.
                val nodes = autoSelectVariant(subscription, body.nodes) ?: body.nodes

                // Refused here rather than left to fail at connect time. A protocol the core
                // cannot speak makes a row that looks like every other row, pings like every other
                // row and carries nothing — and the user is left comparing servers to work out
                // which of them is the broken one. A group keeps whichever members it can carry.
                val carried = nodes.mapNotNull(XrayConfigFactory::carriable)
                val refused = nodes
                    .filter { XrayConfigFactory.carriable(it) == null }
                    .mapNotNull(XrayConfigFactory::unsupported)

                logs.info(
                    R.string.log_subscription_received,
                    subscription.name,
                    strings.plural(R.plurals.servers, carried.size),
                )

                if (refused.isNotEmpty()) {
                    logs.warn(
                        R.string.log_skipped_unsupported_protocol,
                        refused.size,
                        refused.distinct().sorted().joinToString(", "),
                    )
                }

                // Named rather than hidden. A server the provider lists and the app refuses is
                // worth a line: without one the count simply comes up short, which reads as a
                // parsing failure rather than as the deliberate refusal it is.
                ProxyUriParser.unsupportedTransports(response.body)
                    .takeIf { it.isNotEmpty() }
                    ?.let { skipped ->
                        logs.warn(
                            R.string.log_skipped_unsupported_transport,
                            skipped.values.sum(),
                            skipped.keys.joinToString(", "),
                        )
                    }

                SubscriptionUpdate.Success(
                    subscription = subscription.copy(
                        userInfo = SubscriptionParser.parseUserInfo(response.userInfoHeader)
                            ?: subscription.userInfo,
                        remoteTitle = response.profileTitle ?: subscription.remoteTitle,
                        webPageUrl = response.webPageUrl ?: subscription.webPageUrl,
                        lastUpdatedEpochMillis = System.currentTimeMillis(),
                        lastError = null,
                    ),
                    nodes = carried,
                    addedCount = 0,
                    removedCount = 0,
                )
            }

            is SubscriptionBody.UnsupportedFormat ->
                SubscriptionUpdate.Failure(
                    subscription.id,
                    strings.get(R.string.error_unsupported_format, body.format),
                )

            is SubscriptionBody.Empty ->
                SubscriptionUpdate.Failure(subscription.id, strings.get(body.reason.messageRes))
        }
    }

    /**
     * The subscription again, asked for as Xray documents, when the list it answered with cannot
     * hold what the provider actually offers — and the auto-select servers from that answer put in
     * the places their flattened links held.
     *
     * A share link is one server. A provider's auto-select server is several — a balancer over
     * candidates, see [com.mydrop.vpn.core.model.NodeGroup] — and a panel asked for links flattens
     * it into one of them, the balancer's last resort. Which format a panel answers with is decided
     * by the User-Agent, and this app's is not one a panel knows; the provider's own client is
     * handed the documents and this app was handed the flattened list. The journal showed the
     * result: "LTE Авто" servers that carried nothing here and worked there.
     *
     * Two ways of asking are tried, in order. Remnawave serves a named format under a suffix of a
     * link shaped like `https://host/<id>`, and `json` is Xray's. Not every panel behind such a link
     * does: Quattro's answered the suffix with a 404 and chose by the agent alone, handing documents
     * to any agent that mentions Happ, Streisand, INCY or v2raytun — Remnawave's own default
     * response rules — and links to everyone else, this app included. So the second request is the
     * link itself with [DOCUMENTS_AGENT_SUFFIX] after this app's name, unless the user set an agent
     * of their own, which is theirs to choose.
     *
     * Only the groups are taken from the documents, never the list. The same answer wrote Quattro's
     * Hysteria2 servers as Xray `hysteria` outbounds, which this parser does not read; taking the
     * documents whole would have quietly removed the servers a friend's phone was riding. A group
     * replaces the link of the same name, which keeps its row, its place and — through the name —
     * the selection; a group no link is named after is added at the end. A document without a
     * group, a request that fails, or an answer that is not documents changes nothing.
     */
    private fun autoSelectVariant(subscription: Subscription, links: List<ProxyNode>): List<ProxyNode>? {
        if (links.any { it.group != null }) return null
        val url = runCatching { URL(subscription.url) }.getOrNull() ?: return null

        val variants = buildList {
            if (url.query == null && PANEL_LINK_PATH.matches(url.path.orEmpty())) {
                add(Triple("suffix", subscription.url.trimEnd('/') + "/json", subscription))
            }
            if (subscription.userAgentOverride == null) {
                val asking = subscription.copy(userAgentOverride = userAgent + DOCUMENTS_AGENT_SUFFIX)
                add(Triple("agent", subscription.url, asking))
            }
        }
        for ((how, variantUrl, asking) in variants) {
            val response = runCatching { fetch(variantUrl, asking) }.getOrNull()
            val body = response?.takeIf { it.code in 200..299 }
                ?.let { SubscriptionParser.parse(it.body, subscription.id) }
            val groups = (body as? SubscriptionBody.Nodes)?.nodes.orEmpty().filter { it.group != null }
            logs.trace(
                "YumiSub",
                "${subscription.name}: documents by $how: " + when {
                    response == null -> "no answer"
                    response.code !in 200..299 -> "HTTP ${response.code}"
                    body !is SubscriptionBody.Nodes -> "not a server list"
                    else -> "${body.nodes.size} servers, ${groups.size} auto-select"
                },
            )
            if (groups.isNotEmpty()) return withGroups(links, groups)
        }
        return null
    }

    /** [links] with each of [groups] in the place of the link named after it; see [autoSelectVariant]. */
    private fun withGroups(links: List<ProxyNode>, groups: List<ProxyNode>): List<ProxyNode> {
        val byName = groups.associateBy { it.name }
        val names = links.mapTo(HashSet()) { it.name }
        return links.map { link -> byName[link.name] ?: link } + groups.filter { it.name !in names }
    }

    internal data class Response(
        val code: Int,
        val body: String,
        val userInfoHeader: String?,
        val profileTitle: String?,
        val webPageUrl: String?,
        /** The panel announced device binding through one of its `x-hwid-*` headers. */
        val hwidRequired: Boolean = false,
        /** It also said the account has no free device slots left. */
        val deviceLimitReached: Boolean = false,
    )

    /**
     * A server that cannot be dialled: the all-zero UUID at `0.0.0.0:1` that panels use to carry a
     * message in the name field. Matched on the address rather than the text, which is written by
     * whoever runs the panel and comes in any language.
     */
    private fun ProxyNode.isPlaceholder(): Boolean =
        server == "0.0.0.0" || server.isBlank() || port <= 1

    /**
     * Same host, same scheme, same port — the test for whether this request may still carry the
     * subscription's credentials.
     *
     * Redirects are walked by hand here, and every hop used to be given the `Authorization` header
     * built from the link's userinfo along with the `x-hwid` identity, whatever host it pointed
     * at. One `302` from a panel — compromised, or simply hostile — was enough to hand a login and
     * a device identifier to a third party. A downgrade from https to http counts as a different
     * origin for the same reason: the credentials would go out in the clear.
     */
    private fun sameOrigin(a: URL, b: URL): Boolean =
        a.host.equals(b.host, ignoreCase = true) &&
            effectivePort(a) == effectivePort(b) &&
            notDowngraded(a, b)

    /**
     * The scheme test, which used to be plain equality and cost more than it defended.
     *
     * Refusing a downgrade is the whole point: https → http would put the login and the device id
     * on the wire in the clear. The reverse is the opposite of a risk, and equality refused it
     * anyway — so a subscription written `http://` that redirects to `https://`, which is what a
     * panel that has since fixed its certificate does, silently lost its credentials and started
     * answering 401.
     */
    private fun notDowngraded(from: URL, to: URL): Boolean =
        to.protocol.equals("https", ignoreCase = true) ||
            from.protocol.equals(to.protocol, ignoreCase = true)

    private fun effectivePort(url: URL): Int =
        if (url.port != -1) url.port else url.defaultPort

    /**
     * The subscription body, from wherever it can be had.
     *
     * @throws java.io.IOException when neither road works; the caller turns that into the message
     *   the user sees.
     */
    private fun fetch(url: String, subscription: Subscription): Response = try {
        getDirect(url, subscription)
    } catch (direct: IOException) {
        val probe = probeEndpoint()
        if (probe == null) throw direct
        logs.debug(R.string.log_subscription_via_tunnel, subscription.name)
        try {
            getViaHttpProxy(url, subscription, probe)
        } catch (viaTunnel: IOException) {
            // The direct failure is the one worth reporting: it names the provider rather than a
            // loopback port the user has never heard of.
            direct.addSuppressed(viaTunnel)
            throw direct
        }
    }

    private fun validateHttpUrl(raw: String): URL {
        require(raw.none { it.isISOControl() }) { "Invalid URL characters" }
        val url = runCatching { URI(raw).toURL() }.getOrElse { URL(raw) }
        val scheme = url.protocol.lowercase()
        require(scheme in setOf("http", "https")) { "Unsupported URL scheme: $scheme" }
        require(url.host.removeSurrounding("[", "]").isNotBlank()) { "Missing URL host" }
        return url
    }

    private fun isPrivateOrigin(url: URL): Boolean {
        val host = url.host.removeSurrounding("[", "]")
        if (!isNumericAddress(host) && !host.equals("localhost", ignoreCase = true)) return false
        return runCatching { InetAddress.getAllByName(host).any(::isPrivateOrSpecial) }
            .getOrDefault(false)
    }

    private fun validateRedirect(
        originIsPrivate: Boolean,
        current: String,
        location: String,
        allowUnresolvedHostname: Boolean,
    ): URL {
        val cleanLocation = location.trim()
        if (cleanLocation.any { it.isISOControl() }) {
            throw SecurityException("Invalid redirect Location")
        }
        val next = runCatching { URI(current).resolve(cleanLocation).toURL() }
            .getOrElse { URL(URL(current), cleanLocation) }
        val scheme = next.protocol.lowercase()
        if (scheme !in setOf("http", "https")) {
            throw SecurityException("Refused redirect to disallowed scheme: $scheme")
        }
        val host = next.host.removeSurrounding("[", "]")
        if (host.isBlank()) {
            throw SecurityException("Refused redirect with empty host")
        }
        val addresses = try {
            InetAddress.getAllByName(host)
        } catch (unresolved: UnknownHostException) {
            if (allowUnresolvedHostname && !isNumericAddress(host) && !host.equals("localhost", ignoreCase = true)) {
                return next
            }
            throw unresolved
        }
        for (addr in addresses) {
            if (addr.isAnyLocalAddress || addr.isLinkLocalAddress || addr.isMulticastAddress) {
                throw SecurityException("Refused redirect to restricted address")
            }
            if (!originIsPrivate && isPrivateOrSpecial(addr)) {
                throw SecurityException("Refused redirect to private or loopback address")
            }
        }
        return next
    }

    private fun isPrivateOrSpecial(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress ||
            addr.isAnyLocalAddress ||
            addr.isLinkLocalAddress ||
            addr.isMulticastAddress ||
            addr.isSiteLocalAddress
        ) {
            return true
        }
        val bytes = addr.address
        if (bytes.size == 4) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            return b0 == 0 ||
                b0 == 10 ||
                (b0 == 100 && (b1 and 0xC0) == 64) ||
                b0 == 127 ||
                (b0 == 169 && b1 == 254) ||
                (b0 == 172 && b1 in 16..31) ||
                (b0 == 192 && b1 == 168)
        }
        if (bytes.size == 16) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if ((b0 and 0xFE) == 0xFC) return true // fc00::/7 ULA
            if (b0 == 0xFE && (b1 and 0xC0) == 0x80) return true // fe80::/10 link-local
            if (b0 == 0xFF) return true // ff00::/8 multicast
            val isV4Mapped = (0..9).all { bytes[it].toInt() == 0 } &&
                bytes[10].toInt() == -1 && bytes[11].toInt() == -1
            if (isV4Mapped) {
                return isPrivateOrSpecial(InetAddress.getByAddress(bytes.copyOfRange(12, 16)))
            }
        }
        return false
    }

    private fun getDirect(url: String, subscription: Subscription): Response {
        val origin = validateHttpUrl(url)
        val originIsPrivate = isPrivateOrigin(origin)
        var current = origin.toString()
        repeat(MAX_REDIRECTS) {
            val target = validateHttpUrl(current)
            val trusted = sameOrigin(origin, target)
            val connection = (target.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                // Cross-protocol redirects (http→https) are not followed automatically, so
                // redirects are walked by hand below.
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", subscription.userAgentOverride ?: userAgent)

                // Credentials written into the link. HttpURLConnection parses them out of the URL
                // and then never sends them, which reads as a subscription that returns 401 for
                // no reason at all.
                target.userInfo?.takeIf { it.isNotEmpty() && trusted }?.let { credentials ->
                    // The shared decoder, not URLDecoder: a password with a `+` in it is a
                    // password with a `+` in it, not one with a space.
                    val decoded = com.mydrop.vpn.core.parse.urlDecode(credentials)
                    val encoded = Base64.getEncoder().encodeToString(decoded.toByteArray())
                    setRequestProperty("Authorization", "Basic $encoded")
                }

                // Last, so a provider's own header wins over anything assumed above. These are
                // where a panel behind a gateway keeps its token, so they travel under the same
                // rule as the rest of the credentials.
                if (trusted) {
                    subscription.headers.forEach { (name, value) ->
                        if (isSafeHeader(name, value)) {
                            runCatching { setRequestProperty(name, value) }
                        }
                    }
                }
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Encoding", "gzip")
                // Subscriptions sit behind caching front-ends that key on the URL and the agent
                // and ignore everything else. One refusal — a device that was not yet bound, a
                // limit that has since been raised — then keeps being replayed to a client whose
                // request has long since become correct.
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Pragma", "no-cache")
                // Panels that do not do device binding ignore these outright; the ones that do
                // refuse to serve a list without them.
                identity()?.takeIf { trusted }?.let {
                    setRequestProperty("x-hwid", it.hwid)
                    setRequestProperty("x-device-os", it.os)
                    setRequestProperty("x-ver-os", it.osVersion)
                    setRequestProperty("x-device-model", it.model)
                }
            }

            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: return Response(code, "", null, null, null)
                    current = validateRedirect(
                        originIsPrivate = originIsPrivate,
                        current = current,
                        location = location,
                        allowUnresolvedHostname = false,
                    ).toString()
                    return@repeat
                }

                val rawStream = (connection.errorStream ?: if (code < 400) connection.inputStream else null)
                    ?: ByteArrayInputStream(ByteArray(0))
                val stream = if (code < 400 && isGzipEncoding(connection.contentEncoding)) {
                    GZIPInputStream(rawStream)
                } else {
                    rawStream
                }
                // One byte past the ceiling, so a body that is merely at the limit still reads
                // whole while an oversized one is caught. Silently keeping the first 8 MB turned a
                // truncated document into a valid-looking short list, and every server past the
                // cut looked like one the provider had removed.
                val raw = stream.use { input -> input.readAtMost(MAX_BODY_BYTES + 1) }
                if (raw.size > MAX_BODY_BYTES) {
                    throw IllegalStateException(strings.get(R.string.error_body_too_large))
                }
                val body = String(raw, Charsets.UTF_8)

                return Response(
                    code = code,
                    body = body,
                    userInfoHeader = connection.getHeaderField("subscription-userinfo"),
                    profileTitle = connection.getHeaderField("profile-title")
                        ?.let(::decodeProfileTitle),
                    webPageUrl = sanitizeWebPageUrl(connection.getHeaderField("profile-web-page-url")),
                    hwidRequired = connection.getHeaderField("x-hwid-active") != null ||
                        connection.getHeaderField("x-hwid-not-supported") != null,
                    deviceLimitReached =
                        connection.getHeaderField("x-hwid-max-devices-reached") != null,
                )
            } finally {
                connection.disconnect()
            }
        }
        throw IllegalStateException(strings.get(R.string.error_too_many_redirects))
    }

    internal fun getViaHttpProxy(
        url: String,
        subscription: Subscription,
        through: ProbeEndpoint,
    ): Response {
        val origin = validateHttpUrl(url)
        val originIsPrivate = isPrivateOrigin(origin)
        var current = origin.toString()
        val proxyCredentials = Base64.getEncoder()
            .encodeToString("${through.username}:${through.password}".toByteArray(Charsets.UTF_8))

        repeat(MAX_REDIRECTS) {
            val target = validateHttpUrl(current)
            val trusted = sameOrigin(origin, target)
            val bareHost = target.host.removeSurrounding("[", "]")
            val connectHost = if (':' in bareHost) "[$bareHost]" else bareHost
            val port = effectivePort(target)
            val isHttps = target.protocol.equals("https", ignoreCase = true)

            val raw = Socket()
            try {
                raw.soTimeout = PROXY_READ_TIMEOUT_MILLIS
                raw.connect(
                    InetSocketAddress(InetAddress.getByName(LOOPBACK), through.port),
                    PROXY_CONNECT_TIMEOUT_MILLIS,
                )
                val connectReq = buildString {
                    append("CONNECT $connectHost:$port HTTP/1.1\r\n")
                    append("Host: $connectHost:$port\r\n")
                    append("Proxy-Authorization: Basic $proxyCredentials\r\n\r\n")
                }
                raw.getOutputStream().apply {
                    write(connectReq.toByteArray(Charsets.UTF_8))
                    flush()
                }
                val connectHead = readHttpHead(raw.getInputStream())
                val connectCode = parseStatusCode(connectHead)
                if (connectCode != 200) {
                    throw IOException("HTTP CONNECT failed with status $connectCode")
                }

                val ioSocket: Socket = if (isHttps) {
                    val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                    (factory.createSocket(raw, bareHost, port, true) as SSLSocket).apply {
                        soTimeout = PROXY_READ_TIMEOUT_MILLIS
                        sslParameters = sslParameters.apply {
                            endpointIdentificationAlgorithm = "HTTPS"
                        }
                        startHandshake()
                    }
                } else {
                    raw
                }

                ioSocket.use { sock ->
                    val pathAndQuery = target.file.let {
                        when {
                            it.isEmpty() -> "/"
                            it.startsWith('/') -> it
                            else -> "/$it"
                        }
                    }
                    val hostHeader = if (target.port != -1 && target.port != target.defaultPort) {
                        "$connectHost:${target.port}"
                    } else {
                        connectHost
                    }
                    val agent = sanitizeHeaderValue(subscription.userAgentOverride ?: userAgent)
                    val requestText = buildString {
                        append("GET $pathAndQuery HTTP/1.1\r\n")
                        append("Host: $hostHeader\r\n")
                        append("User-Agent: $agent\r\n")
                        target.userInfo?.takeIf { it.isNotEmpty() && trusted }?.let { credentials ->
                            val decoded = com.mydrop.vpn.core.parse.urlDecode(credentials)
                            val encoded = Base64.getEncoder().encodeToString(decoded.toByteArray())
                            append("Authorization: Basic $encoded\r\n")
                        }
                        if (trusted) {
                            subscription.headers.forEach { (name, value) ->
                                if (isSafeHeader(name, value) &&
                                    !name.equals("connection", ignoreCase = true) &&
                                    !name.equals("host", ignoreCase = true)
                                ) {
                                    append("$name: $value\r\n")
                                }
                            }
                        }
                        append("Accept: */*\r\n")
                        append("Accept-Encoding: gzip\r\n")
                        append("Cache-Control: no-cache\r\n")
                        append("Pragma: no-cache\r\n")
                        identity()?.takeIf { trusted }?.let {
                            append("x-hwid: ${sanitizeHeaderValue(it.hwid)}\r\n")
                            append("x-device-os: ${sanitizeHeaderValue(it.os)}\r\n")
                            append("x-ver-os: ${sanitizeHeaderValue(it.osVersion)}\r\n")
                            append("x-device-model: ${sanitizeHeaderValue(it.model)}\r\n")
                        }
                        append("Connection: close\r\n\r\n")
                    }
                    sock.getOutputStream().apply {
                        write(requestText.toByteArray(Charsets.UTF_8))
                        flush()
                    }

                    val input = sock.getInputStream()
                    val head = readHttpHead(input)
                    val code = parseStatusCode(head)
                        ?: throw IOException("Malformed HTTP status line")
                    val headers = parseHeaders(head)

                    if (code in 300..399) {
                        val location = headers["location"]?.firstOrNull()
                            ?: return Response(code, "", null, null, null)
                        current = validateRedirect(
                            originIsPrivate = originIsPrivate,
                            current = current,
                            location = location,
                            allowUnresolvedHostname = true,
                        ).toString()
                        return@repeat
                    }

                    val isChunked = headers["transfer-encoding"].orEmpty()
                        .flatMap { it.split(',') }
                        .any { it.trim().equals("chunked", ignoreCase = true) }
                    val contentLength = headers["content-length"]?.firstOrNull()?.trim()?.toLongOrNull()
                    if (contentLength != null && contentLength < 0L) {
                        throw IOException("Invalid Content-Length")
                    }

                    val bodyStream: InputStream = when {
                        isChunked -> ChunkedInputStream(input)
                        contentLength != null -> BoundedInputStream(input, contentLength)
                        else -> input
                    }
                    val rawWireBounded = BoundedInputStream(bodyStream, MAX_BODY_BYTES + 1)
                    val contentEncoding = headers["content-encoding"]?.firstOrNull()
                    val decodedStream = if (code < 400 && isGzipEncoding(contentEncoding) && contentLength != 0L) {
                        GZIPInputStream(rawWireBounded)
                    } else {
                        rawWireBounded
                    }
                    val rawBytes = decodedStream.use { it.readAtMost(MAX_BODY_BYTES + 1) }
                    if (rawBytes.size > MAX_BODY_BYTES) {
                        throw IllegalStateException(strings.get(R.string.error_body_too_large))
                    }
                    val body = String(rawBytes, Charsets.UTF_8)

                    return Response(
                        code = code,
                        body = body,
                        userInfoHeader = headers["subscription-userinfo"]?.firstOrNull(),
                        profileTitle = headers["profile-title"]?.firstOrNull()?.let(::decodeProfileTitle),
                        webPageUrl = sanitizeWebPageUrl(headers["profile-web-page-url"]?.firstOrNull()),
                        hwidRequired = headers.containsKey("x-hwid-active") ||
                            headers.containsKey("x-hwid-not-supported"),
                        deviceLimitReached = headers.containsKey("x-hwid-max-devices-reached"),
                    )
                }
            } finally {
                runCatching { raw.close() }
            }
        }
        throw IllegalStateException(strings.get(R.string.error_too_many_redirects))
    }

    private fun readHttpHead(stream: InputStream): String {
        val bytes = ByteArrayOutputStream()
        var newlines = 0
        while (newlines < 2) {
            val byte = stream.read()
            if (byte < 0) break
            if (bytes.size() >= MAX_HEADER_BYTES) {
                throw IOException("Response headers exceeded $MAX_HEADER_BYTES bytes")
            }
            bytes.write(byte)
            when (byte) {
                '\n'.code -> newlines++
                '\r'.code -> Unit
                else -> newlines = 0
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private fun parseStatusCode(head: String): Int? {
        val statusLine = head.lineSequence().firstOrNull()?.trim().orEmpty()
        if (!statusLine.startsWith("HTTP/", ignoreCase = true)) return null
        return statusLine.split(' ', limit = 3).getOrNull(1)?.toIntOrNull()
    }

    private fun parseHeaders(head: String): Map<String, List<String>> {
        val map = linkedMapOf<String, MutableList<String>>()
        head.lineSequence().drop(1).forEach { line ->
            if (line.isBlank()) return@forEach
            val colon = line.indexOf(':')
            if (colon <= 0) return@forEach
            val key = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            map.getOrPut(key) { mutableListOf() }.add(value)
        }
        return map
    }

    private fun isGzipEncoding(value: String?): Boolean =
        value.orEmpty()
            .split(',')
            .any { it.trim().equals("gzip", ignoreCase = true) }

    private fun isSafeHeader(name: String, value: String): Boolean =
        name.isNotBlank() &&
            ':' !in name &&
            name.none { it.isISOControl() || it.isWhitespace() } &&
            value.none { it == '\r' || it == '\n' || it == '\u0000' }

    private fun sanitizeHeaderValue(value: String): String =
        value.filterNot { it == '\r' || it == '\n' || it == '\u0000' }

    internal fun sanitizeWebPageUrl(raw: String?): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_WEB_PAGE_URL_LENGTH }
            ?: return null
        if (trimmed.any { it.isISOControl() }) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme !in setOf("http", "https")) return null
        if (uri.host.isNullOrBlank()) return null
        return trimmed
    }

    private class BoundedInputStream(
        private val source: InputStream,
        private var remaining: Long,
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0L) return -1
            val b = source.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0L) return -1
            val n = source.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }
    }

    private class ChunkedInputStream(private val source: InputStream) : InputStream() {
        private var remainingInChunk = 0L
        private var finished = false

        override fun read(): Int {
            val single = ByteArray(1)
            val n = read(single, 0, 1)
            return if (n <= 0) -1 else single[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (finished || len == 0) return if (finished) -1 else 0
            if (remainingInChunk == 0L && !nextChunk()) return -1
            val toRead = minOf(len.toLong(), remainingInChunk).toInt()
            val n = source.read(b, off, toRead)
            if (n < 0) throw EOFException("Unexpected EOF inside chunked body")
            remainingInChunk -= n
            if (remainingInChunk == 0L) {
                val line = readLineAscii()
                if (line != null && line.isNotEmpty()) {
                    throw IOException("Expected CRLF after chunk")
                }
            }
            return n
        }

        private fun nextChunk(): Boolean {
            val sizeLine = readLineAscii() ?: throw EOFException("Unexpected EOF reading chunk size")
            val sizeHex = sizeLine.substringBefore(';').trim()
            val size = sizeHex.toLongOrNull(16)?.takeIf { it >= 0L }
                ?: throw IOException("Invalid chunk size: $sizeHex")
            if (size == 0L) {
                while (true) {
                    val trailer = readLineAscii() ?: break
                    if (trailer.isEmpty()) break
                }
                finished = true
                return false
            }
            remainingInChunk = size
            return true
        }

        private fun readLineAscii(): String? {
            val sb = StringBuilder()
            while (true) {
                val ch = source.read()
                if (ch < 0) return if (sb.isEmpty()) null else sb.toString()
                if (ch == '\n'.code) break
                if (ch != '\r'.code) sb.append(ch.toChar())
                if (sb.length > 8192) throw IOException("Chunk line too long")
            }
            return sb.toString()
        }
    }

    /**
     * Reads up to [limit] bytes, by hand.
     *
     * `InputStream.readNBytes` is the obvious way to write this and it is an API 33 method, while
     * the app runs from API 26 — so on Android 8 through 12 every subscription refresh ended in a
     * `NoSuchMethodError` rather than a server list. It went unnoticed because nothing built or
     * ran the app below 33; Android lint is what named it.
     */
    private fun InputStream.readAtMost(limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var remaining = limit
        while (remaining > 0) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            out.write(buffer, 0, read)
            remaining -= read
        }
        return out.toByteArray()
    }

    /** `profile-title` is often sent as `base64:<...>` to survive non-ASCII provider names. */
    private fun decodeProfileTitle(raw: String): String {
        val prefix = "base64:"
        if (!raw.startsWith(prefix)) return raw
        return com.mydrop.vpn.core.parse.base64DecodeOrNull(raw.removePrefix(prefix)) ?: raw
    }
}
