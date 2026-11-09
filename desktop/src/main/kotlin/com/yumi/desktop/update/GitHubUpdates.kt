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
import java.security.MessageDigest

data class DesktopRelease(val version: String, val notes: String, val installerUrl: String, val checksumUrl: String, val size: Long) {
    val fileName get() = "Yumi-Desktop-$version-windows-x64-setup.exe"
    val pageUrl get() = "https://github.com/GXUser7/yumi-desktop/releases/tag/v$version"
}

data class VerifiedUpdate(val release: DesktopRelease, val file: File, val sha256: String) {
    fun verify() = file.isFile && file.length() == release.size && sha256(file).equals(sha256, ignoreCase = true)
}

interface UpdateSource {
    suspend fun latest(): DesktopRelease?
    suspend fun download(release: DesktopRelease, progress: (Long, Long) -> Unit): VerifiedUpdate
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

class GitHubUpdates(private val directory: File, private val transport: UpdateTransport) : UpdateSource {
    override suspend fun latest(): DesktopRelease? = parseReleases(transport.open(FEED).use { it.readLimited(2_000_000).decodeToString() })

    override suspend fun download(release: DesktopRelease, progress: (Long, Long) -> Unit): VerifiedUpdate {
        require(release.size in 1..MAX_INSTALLER_SIZE)
        val checksum = transport.open(release.checksumUrl).use { it.readLimited(4096).decodeToString().trim() }
        val expected = Regex("^([0-9a-fA-F]{64})\\s+\\*?${Regex.escape(release.fileName)}$")
            .matchEntire(checksum)?.groupValues?.get(1)?.lowercase() ?: throw UpdateIntegrityException()
        directory.mkdirs()
        val target = File(directory, release.fileName)
        val verified = VerifiedUpdate(release, target, expected)
        if (verified.verify()) { progress(release.size, release.size); return verified }
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
            return verified
        } finally {
            partial.delete()
        }
    }

    companion object {
        const val FEED = "https://api.github.com/repos/GXUser7/yumi-desktop/releases?per_page=30"
        const val RELEASES = "https://github.com/GXUser7/yumi-desktop/releases"
        private const val MAX_INSTALLER_SIZE = 1_500_000_000L

        /** Yumi currently publishes numeric beta tags; drafts and unrelated assets never qualify. */
        fun parseReleases(body: String): DesktopRelease? {
            val candidates = Json.parseToJsonElement(body).jsonArray.mapNotNull { item ->
                val release = item.jsonObject
                if (release["draft"]?.jsonPrimitive?.booleanOrNull != false) return@mapNotNull null
                val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                if (!tag.matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+"))) return@mapNotNull null
                val version = tag.drop(1)
                val name = "Yumi-Desktop-$version-windows-x64-setup.exe"
                val assets = release["assets"]?.jsonArray ?: return@mapNotNull null
                fun asset(file: String) = assets.map { it.jsonObject }.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == file }
                val installer = asset(name) ?: return@mapNotNull null
                val checksum = asset("$name.sha256") ?: return@mapNotNull null
                fun url(asset: JsonObject, file: String): String? {
                    val raw = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return null
                    return raw.takeIf { it == "https://github.com/GXUser7/yumi-desktop/releases/download/$tag/$file" }
                }
                val size = installer["size"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                if (size !in 1..MAX_INSTALLER_SIZE) return@mapNotNull null
                DesktopRelease(version, release["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    url(installer, name) ?: return@mapNotNull null, url(checksum, "$name.sha256") ?: return@mapNotNull null, size)
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
