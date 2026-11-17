package com.yumi.desktop.update

import com.mydrop.vpn.core.model.Version
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

data class DesktopRelease(
    val version: String,
    val notes: String,
    val installerUrl: String,
    val checksumUrl: String,
    val size: Long,
    val signatureUrl: String? = null,
) {
    val fileName get() = assetName(version)
    val pageUrl get() = "https://github.com/GXUser7/yumi-desktop/releases/tag/v$version"
}

/**
 * The asset this build installs, spelled for the platform it runs on; a release carries all of
 * them. Linux picks between the two package formats by the system it finds itself on, the same
 * choice the elevated installer would make.
 */
fun assetName(version: String): String = when {
    System.getProperty("os.name").startsWith("Windows") -> "Yumi-Desktop-$version-windows-x64-setup.exe"
    System.getProperty("os.name") == "Mac OS X" -> "Yumi-Desktop-$version-macos-arm64.pkg"
    File("/etc/debian_version").isFile || File("/etc/alpine-release").isFile || File("/etc/lsb-release").isFile ->
        "Yumi-Desktop-$version-linux-amd64.deb"
    else -> "Yumi-Desktop-$version-linux-amd64.rpm"
}

data class VerifiedUpdate(val release: DesktopRelease, val file: File, val sha256: String) {
    fun verify() = file.isFile && file.length() == release.size && sha256(file).equals(sha256, ignoreCase = true)
}

interface UpdateSource {
    suspend fun latest(): DesktopRelease?
    suspend fun download(release: DesktopRelease, progress: (Long, Long) -> Unit): VerifiedUpdate
    fun purge() {}
}

class UpdateIntegrityException : IOException("Installer integrity check failed")

/** Injectable byte transport: tests never contact GitHub or start an installer. */
fun interface UpdateTransport {
    fun open(url: String): InputStream
}

class HttpsUpdateTransport(private val currentVersion: String) : UpdateTransport {
    override fun open(url: String): InputStream {
        var uri = URI(url)
        repeat(6) {
            require(uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443))
            require(uri.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"))
            val connection = uri.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "YumiDesktop/$currentVersion")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            val code = connection.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                require(!location.isNullOrBlank())
                uri = uri.resolve(location)
            } else {
                if (code != 200) {
                    connection.disconnect()
                    error("HTTP $code")
                }
                return object : java.io.FilterInputStream(connection.inputStream) {
                    override fun close() { try { super.close() } finally { connection.disconnect() } }
                }
            }
        }
        error("Too many redirects")
    }
}

class GitHubUpdates(
    private val directory: File,
    private val transport: UpdateTransport,
    private val publicKeyBase64: String = configuredPublicKey(),
) : UpdateSource {
    override suspend fun latest(): DesktopRelease? =
        parseReleases(transport.open(FEED).use { it.readLimited(2_000_000).decodeToString() }, publicKeyBase64)

    override fun purge() = purgeStale(keepName = null)

    override suspend fun download(release: DesktopRelease, progress: (Long, Long) -> Unit): VerifiedUpdate {
        require(release.size in 1..MAX_INSTALLER_SIZE)
        val checksumBytes = transport.open(release.checksumUrl).use { it.readLimited(4096) }
        if (publicKeyBase64.isNotBlank()) {
            val signatureUrl = release.signatureUrl ?: throw UpdateIntegrityException()
            val signatureBytes = transport.open(signatureUrl).use { it.readNBytes(65) }
            if (!verifyEd25519(publicKeyBase64, checksumBytes, signatureBytes)) throw UpdateIntegrityException()
        }
        val checksum = checksumBytes.decodeToString().trim()
        val expected = Regex("^([0-9a-fA-F]{64})\\s+\\*?${Regex.escape(release.fileName)}$")
            .matchEntire(checksum)?.groupValues?.get(1)?.lowercase() ?: throw UpdateIntegrityException()
        directory.mkdirs()
        val target = File(directory, release.fileName)
        val verified = VerifiedUpdate(release, target, expected)
        if (verified.verify()) {
            purgeStale(keepName = target.name)
            progress(release.size, release.size)
            return verified
        }
        purgeStale(keepName = null)
        val partial = File(directory, release.fileName + ".part")
        try {
            var count = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            transport.open(release.installerUrl).use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        count += read
                        if (count > release.size) throw UpdateIntegrityException()
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        progress(count, release.size)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            if (count != release.size || digest.digest().hex() != expected) throw UpdateIntegrityException()
            Files.move(partial.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            purgeStale(keepName = target.name)
            return verified
        } finally {
            partial.delete()
        }
    }

    private fun purgeStale(keepName: String?) {
        directory.listFiles()?.forEach { entry ->
            if (entry.name.startsWith("Yumi-Desktop-") && entry.name != keepName) {
                entry.deleteRecursively()
            }
        }
    }

    companion object {
        const val FEED = "https://api.github.com/repos/GXUser7/yumi-desktop/releases?per_page=30"
        const val RELEASES = "https://github.com/GXUser7/yumi-desktop/releases"
        const val EMBEDDED_UPDATE_PUBLIC_KEY = ""
        private const val MAX_INSTALLER_SIZE = 1_500_000_000L
        private val ED25519_SPKI_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

        fun configuredPublicKey(): String = System.getProperty("yumi.updatePublicKey", EMBEDDED_UPDATE_PUBLIC_KEY).trim()

        internal fun verifyEd25519(keyBase64: String, data: ByteArray, signature: ByteArray): Boolean {
            if (signature.size != 64) return false
            return runCatching {
                val raw = Base64.getDecoder().decode(keyBase64.trim())
                val spki = if (raw.size == 32) ED25519_SPKI_PREFIX + raw else raw
                val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(spki))
                Signature.getInstance("Ed25519").run {
                    initVerify(key)
                    update(data)
                    verify(signature)
                }
            }.getOrDefault(false)
        }

        /** Accept numeric stable releases and legacy beta tags; drafts and unrelated assets never qualify. */
        fun parseReleases(body: String, publicKeyBase64: String = configuredPublicKey()): DesktopRelease? {
            val requireSignature = publicKeyBase64.isNotBlank()
            val candidates = Json.parseToJsonElement(body).jsonArray.mapNotNull { item ->
                val release = item.jsonObject
                if (release["draft"]?.jsonPrimitive?.booleanOrNull != false) return@mapNotNull null
                val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                if (!tag.matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+"))) return@mapNotNull null
                val version = tag.drop(1)
                val name = assetName(version)
                val assets = release["assets"]?.jsonArray ?: return@mapNotNull null
                fun asset(file: String) = assets.map { it.jsonObject }.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == file }
                val installer = asset(name) ?: return@mapNotNull null
                val checksum = asset("$name.sha256") ?: return@mapNotNull null
                fun url(asset: JsonObject, file: String): String? {
                    val raw = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return null
                    return raw.takeIf { it == "https://github.com/GXUser7/yumi-desktop/releases/download/$tag/$file" }
                }
                val signatureUrl = asset("$name.sha256.sig")?.let { url(it, "$name.sha256.sig") }
                if (requireSignature && signatureUrl == null) return@mapNotNull null
                val size = installer["size"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                if (size !in 1..MAX_INSTALLER_SIZE) return@mapNotNull null
                DesktopRelease(
                    version = version,
                    notes = release["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    installerUrl = url(installer, name) ?: return@mapNotNull null,
                    checksumUrl = url(checksum, "$name.sha256") ?: return@mapNotNull null,
                    size = size,
                    signatureUrl = signatureUrl,
                )
            }
            return candidates.maxWithOrNull { a, b -> when {
                Version.isNewer(a.version, b.version) -> 1
                Version.isNewer(b.version, a.version) -> -1
                else -> 0
            } }
        }
    }
}

private fun InputStream.readLimited(limit: Int): ByteArray = readNBytes(limit + 1).also { check(it.size <= limit) { "Response too large" } }
private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
    }
    return digest.digest().hex()
}
