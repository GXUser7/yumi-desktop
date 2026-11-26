package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.BillingCheckoutRequest
import com.mydrop.vpn.core.model.BillingCheckoutResponse
import com.mydrop.vpn.core.model.BillingErrorBody
import com.mydrop.vpn.core.model.BillingManifest
import com.mydrop.vpn.core.model.ProbeEndpoint
import com.mydrop.vpn.core.net.resolveSafeDirectRedirect
import com.mydrop.vpn.core.net.sanitizeBillingUrl
import com.mydrop.vpn.core.net.sanitizePortalUrl
import com.mydrop.vpn.shared.R
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Base64
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

sealed interface BillingResult<out T> {
    data class Success<T>(val value: T) : BillingResult<T>
    data class Failure(val message: String, val errorCode: String? = null) : BillingResult<Nothing>
}

/**
 * Fetches Open Subscription Billing manifests and initiates checkout orders.
 *
 * Unlike [SubscriptionService], this client NEVER attaches `x-hwid`, `x-device-*`, or subscription
 * `Authorization` credentials: billing endpoints frequently live on a shop bot's domain that is
 * separate from the proxy panel itself.
 */
class BillingService(
    private val logs: LogRepository,
    private val strings: Strings,
    private val userAgent: String =
        "Yumi/1.1.0 (" + (System.getProperty("os.name")?.replace(' ', '_') ?: "unknown") + ")",
    private val probeEndpoint: () -> ProbeEndpoint? = { null },
    private val allowLocalHttp: Boolean = false,
) {

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 20_000
        const val MAX_REDIRECTS = 5
        const val MAX_MANIFEST_BYTES = 512L * 1024
        const val MAX_HEADER_BYTES = 64 * 1024
        const val MAX_ITEMS = 50
        const val SUPPORTED_VERSION = 1
        const val LOOPBACK = "127.0.0.1"

        val lenientJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }

    suspend fun fetchManifest(
        manifestUrl: String,
        acceptLanguage: String = "en",
    ): BillingResult<BillingManifest> = withContext(Dispatchers.IO) {
        val safeUrl = sanitizeBillingUrl(manifestUrl, allowLocalHttp)
            ?: return@withContext BillingResult.Failure(strings.get(R.string.billing_error_unsafe_url))

        val http = runCatching {
            requestWithFallback(
                directCall = { getDirect(safeUrl, acceptLanguage) },
                proxyCall = { proxy -> getViaProxy(safeUrl, acceptLanguage, proxy) },
            )
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            val msg = error.message ?: error::class.simpleName ?: strings.get(R.string.error_network)
            logs.warn(R.string.log_subscription_message, "Billing", msg)
            return@withContext BillingResult.Failure(msg)
        }

        if (http.code !in 200..299) {
            return@withContext failureFromHttp(http.code, http.body)
        }

        val parsed = runCatching {
            lenientJson.decodeFromString<BillingManifest>(http.body)
        }.getOrElse { error ->
            return@withContext BillingResult.Failure(
                error.message ?: strings.get(R.string.billing_error_unsupported_version),
            )
        }

        if (parsed.version != SUPPORTED_VERSION) {
            return@withContext BillingResult.Failure(
                strings.get(R.string.billing_error_unsupported_version),
            )
        }

        val sanitizedCheckout = parsed.checkout?.let { cfg ->
            val safeEndpoint = sanitizeBillingUrl(cfg.endpoint, allowLocalHttp)
            safeEndpoint?.let { cfg.copy(endpoint = it) }
        }
        val sanitized = parsed.copy(
            provider = parsed.provider.copy(
                supportUrl = sanitizePortalUrl(parsed.provider.supportUrl, allowLocalHttp),
            ),
            fallbackWebUrl = sanitizePortalUrl(parsed.fallbackWebUrl, allowLocalHttp),
            plans = parsed.plans.take(MAX_ITEMS),
            addons = parsed.addons.take(MAX_ITEMS),
            paymentMethods = parsed.paymentMethods.take(MAX_ITEMS),
            checkout = sanitizedCheckout,
        )
        BillingResult.Success(sanitized)
    }

    suspend fun createCheckout(
        endpoint: String,
        request: BillingCheckoutRequest,
        acceptLanguage: String = "en",
    ): BillingResult<BillingCheckoutResponse> = withContext(Dispatchers.IO) {
        val safeEndpoint = sanitizeBillingUrl(endpoint, allowLocalHttp)
            ?: return@withContext BillingResult.Failure(strings.get(R.string.billing_error_unsafe_url))

        val payload = lenientJson.encodeToString(BillingCheckoutRequest.serializer(), request)
        val http = runCatching {
            requestWithFallback(
                directCall = { postDirect(safeEndpoint, payload, acceptLanguage) },
                proxyCall = { proxy -> postViaProxy(safeEndpoint, payload, acceptLanguage, proxy) },
            )
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            val msg = error.message ?: error::class.simpleName ?: strings.get(R.string.error_network)
            logs.warn(R.string.log_subscription_message, "Billing", msg)
            return@withContext BillingResult.Failure(msg)
        }

        if (http.code !in 200..299) {
            return@withContext failureFromHttp(http.code, http.body)
        }

        val decoded = runCatching {
            lenientJson.decodeFromString<BillingCheckoutResponse>(http.body)
        }.getOrElse { error ->
            return@withContext BillingResult.Failure(
                error.message ?: strings.get(R.string.billing_error_no_checkout),
            )
        }

        val safePayUrl = sanitizeBillingUrl(decoded.paymentUrl, allowLocalHttp)
            ?: return@withContext BillingResult.Failure(strings.get(R.string.billing_error_unsafe_url))

        BillingResult.Success(decoded.copy(paymentUrl = safePayUrl))
    }

    private fun failureFromHttp(code: Int, body: String): BillingResult.Failure {
        val parsed = runCatching {
            lenientJson.decodeFromString<BillingErrorBody>(body)
        }.getOrNull()
        val message = parsed?.message?.takeIf { it.isNotBlank() }
            ?: strings.get(R.string.error_http, code)
        return BillingResult.Failure(message = message, errorCode = parsed?.error)
    }

    private data class HttpTextResponse(val code: Int, val body: String)

    private inline fun requestWithFallback(
        directCall: () -> HttpTextResponse,
        proxyCall: (ProbeEndpoint) -> HttpTextResponse,
    ): HttpTextResponse = try {
        directCall()
    } catch (direct: IOException) {
        val proxy = probeEndpoint() ?: throw direct
        try {
            proxyCall(proxy)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (viaProxy: Exception) {
            direct.addSuppressed(viaProxy)
            throw direct
        }
    }

    private fun getDirect(url: String, acceptLanguage: String): HttpTextResponse {
        var current = url
        repeat(MAX_REDIRECTS) {
            val target = URL(current)
            val connection = (target.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Language", acceptLanguage)
                setRequestProperty("Accept-Encoding", "gzip")
                setRequestProperty("Cache-Control", "no-cache")
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: return HttpTextResponse(code, "")
                    val next = resolveSafeDirectRedirect(target, location).toString()
                    current = sanitizeBillingUrl(next, allowLocalHttp)
                        ?: throw IllegalStateException(strings.get(R.string.billing_error_unsafe_url))
                    return@repeat
                }
                val stream = (connection.errorStream ?: connection.inputStream)
                    .let { if (connection.contentEncoding == "gzip") GZIPInputStream(it) else it }
                val raw = stream.use { it.readAtMost(MAX_MANIFEST_BYTES + 1) }
                if (raw.size > MAX_MANIFEST_BYTES) {
                    throw IllegalStateException(strings.get(R.string.error_body_too_large))
                }
                return HttpTextResponse(code, String(raw, Charsets.UTF_8))
            } finally {
                connection.disconnect()
            }
        }
        throw IllegalStateException(strings.get(R.string.error_too_many_redirects))
    }

    private fun postDirect(
        endpoint: String,
        bodyJson: String,
        acceptLanguage: String,
    ): HttpTextResponse {
        val target = URL(endpoint)
        val connection = (target.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Language", acceptLanguage)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept-Encoding", "gzip")
        }
        return try {
            val bytes = bodyJson.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            val stream = (connection.errorStream ?: connection.inputStream)
                .let { if (connection.contentEncoding == "gzip") GZIPInputStream(it) else it }
            val raw = stream.use { it.readAtMost(MAX_MANIFEST_BYTES + 1) }
            if (raw.size > MAX_MANIFEST_BYTES) {
                throw IllegalStateException(strings.get(R.string.error_body_too_large))
            }
            HttpTextResponse(code, String(raw, Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }

    private fun getViaProxy(
        url: String,
        acceptLanguage: String,
        through: ProbeEndpoint,
    ): HttpTextResponse {
        var current = url
        repeat(MAX_REDIRECTS) {
            val target = URL(current)
            val result = executeOverHttpProxy(
                target = target,
                through = through,
                method = "GET",
                acceptLanguage = acceptLanguage,
                bodyBytes = null,
            )
            if (result.code in 300..399) {
                val location = result.location
                    ?: return HttpTextResponse(result.code, "")
                val next = resolveSafeDirectRedirect(target, location).toString()
                current = sanitizeBillingUrl(next, allowLocalHttp)
                    ?: throw IllegalStateException(strings.get(R.string.billing_error_unsafe_url))
                return@repeat
            }
            return HttpTextResponse(result.code, result.body)
        }
        throw IllegalStateException(strings.get(R.string.error_too_many_redirects))
    }

    private fun postViaProxy(
        endpoint: String,
        bodyJson: String,
        acceptLanguage: String,
        through: ProbeEndpoint,
    ): HttpTextResponse {
        val target = URL(endpoint)
        val result = executeOverHttpProxy(
            target = target,
            through = through,
            method = "POST",
            acceptLanguage = acceptLanguage,
            bodyBytes = bodyJson.toByteArray(Charsets.UTF_8),
        )
        return HttpTextResponse(result.code, result.body)
    }

    private data class ProxyHttpResult(val code: Int, val location: String?, val body: String)

    private fun executeOverHttpProxy(
        target: URL,
        through: ProbeEndpoint,
        method: String,
        acceptLanguage: String,
        bodyBytes: ByteArray?,
    ): ProxyHttpResult {
        val proxyCredentials = Base64.getEncoder().encodeToString(
            "${through.username}:${through.password}".toByteArray(Charsets.UTF_8),
        )
        val bareHost = target.host.removeSurrounding("[", "]")
        val connectHost = if (':' in bareHost) "[$bareHost]" else bareHost
        val port = if (target.port != -1) target.port else target.defaultPort
        val isHttps = target.protocol.equals("https", ignoreCase = true)

        val raw = Socket()
        return try {
            raw.soTimeout = READ_TIMEOUT_MILLIS
            raw.connect(
                InetSocketAddress(InetAddress.getByName(LOOPBACK), through.port),
                CONNECT_TIMEOUT_MILLIS,
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
                    soTimeout = READ_TIMEOUT_MILLIS
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
                val headText = buildString {
                    append("$method $pathAndQuery HTTP/1.1\r\n")
                    append("Host: $hostHeader\r\n")
                    append("User-Agent: ${sanitizeHeaderValue(userAgent)}\r\n")
                    append("Accept: application/json\r\n")
                    append("Accept-Language: ${sanitizeHeaderValue(acceptLanguage)}\r\n")
                    append("Accept-Encoding: gzip\r\n")
                    if (bodyBytes != null) {
                        append("Content-Type: application/json; charset=utf-8\r\n")
                        append("Content-Length: ${bodyBytes.size}\r\n")
                    } else {
                        append("Cache-Control: no-cache\r\n")
                    }
                    append("Connection: close\r\n\r\n")
                }
                sock.getOutputStream().apply {
                    write(headText.toByteArray(Charsets.UTF_8))
                    if (bodyBytes != null) write(bodyBytes)
                    flush()
                }

                val input = sock.getInputStream()
                val head = readHttpHead(input)
                val code = parseStatusCode(head) ?: throw IOException("Malformed HTTP status line")
                val headers = parseHeaders(head)
                if (code in 300..399) {
                    return ProxyHttpResult(code, headers["location"]?.firstOrNull(), "")
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
                val rawWireBounded = BoundedInputStream(bodyStream, MAX_MANIFEST_BYTES + 1)
                val contentEncoding = headers["content-encoding"]?.firstOrNull()
                val decodedStream = if (isGzipEncoding(contentEncoding) && contentLength != 0L) {
                    GZIPInputStream(rawWireBounded)
                } else {
                    rawWireBounded
                }
                val rawBytes = decodedStream.use { it.readAtMost(MAX_MANIFEST_BYTES + 1) }
                if (rawBytes.size > MAX_MANIFEST_BYTES) {
                    throw IllegalStateException(strings.get(R.string.error_body_too_large))
                }
                ProxyHttpResult(code, null, String(rawBytes, Charsets.UTF_8))
            }
        } finally {
            runCatching { raw.close() }
        }
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
        value.orEmpty().split(',').any { it.trim().equals("gzip", ignoreCase = true) }

    private fun sanitizeHeaderValue(value: String): String =
        value.filterNot { it == '\r' || it == '\n' || it == '\u0000' }

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

    private fun InputStream.readAtMost(limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var remaining = limit
        while (remaining > 0) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            out.write(buffer, 0, read)
            remaining -= read
        }
        return out.toByteArray()
    }
}
