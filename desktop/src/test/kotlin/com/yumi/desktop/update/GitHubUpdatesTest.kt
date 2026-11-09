package com.yumi.desktop.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.io.File
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.*

class GitHubUpdatesTest {
    private val payload = ByteArray(150_000) { (it % 253).toByte() }
    private val hash get() = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
    private val release = DesktopRelease("0.3.0", "Notes", "https://github.com/installer", "https://github.com/checksum", payload.size.toLong())

    private fun releaseJson(version: String, draft: Boolean = false, checksum: Boolean = true, malicious: Boolean = false): JsonObject = buildJsonObject {
        put("tag_name", "v$version"); put("draft", draft); put("prerelease", true); put("body", "What's new")
        putJsonArray("assets") {
            val name = "Yumi-Desktop-$version-windows-x64-setup.exe"
            for (file in if (checksum) listOf(name, "$name.sha256") else listOf(name)) addJsonObject {
                put("name", file); put("size", payload.size)
                put("browser_download_url", if (malicious) "https://example.com/$file" else "https://github.com/GXUser7/yumi-desktop/releases/download/v$version/$file")
            }
        }
    }

    @Test fun selectsNewestNumericReleaseIncludingPublishedBetas() {
        val json = JsonArray(listOf(releaseJson("0.9.0"), releaseJson("0.11.0"), releaseJson("99.0.0", draft = true), releaseJson("9.0.0", checksum = false), releaseJson("8.0.0", malicious = true)))
        assertEquals("0.11.0", GitHubUpdates.parseReleases(json.toString())?.version)
    }

    @Test fun ignoresUnrelatedOrUnverifiedAssets() {
        assertNull(GitHubUpdates.parseReleases(JsonArray(listOf(releaseJson("0.3.0", checksum = false), releaseJson("0.4.0", malicious = true))).toString()))
    }

    private suspend fun withSource(checksum: String = "$hash  ${release.fileName}", bytes: ByteArray = payload, block: suspend (GitHubUpdates, File) -> Unit) {
        val directory = Files.createTempDirectory("yumi-update-test-").toFile()
        val transport = UpdateTransport { url -> ByteArrayInputStream(if (url == release.checksumUrl) checksum.toByteArray() else bytes) }
        try { block(GitHubUpdates(directory, transport), directory) }
        finally {
            check(directory.parentFile.canonicalFile == File(System.getProperty("java.io.tmpdir")).canonicalFile && directory.name.startsWith("yumi-update-test-"))
            directory.deleteRecursively()
        }
    }

    @Test fun verifiesWholeDownloadAndReusesVerifiedCache() = runTest {
        withSource { source, _ ->
            val progress = mutableListOf<Long>()
            val update = source.download(release) { done, _ -> progress.add(done) }
            assertContentEquals(payload, update.file.readBytes())
            assertTrue(update.verify())
            assertEquals(payload.size.toLong(), progress.last())
            source.download(release) { _, _ -> }
            update.file.appendText("changed")
            assertFalse(update.verify(), "A modified installer must never be launched")
        }
    }

    @Test fun rejectsBadChecksumAndLeavesNoExecutableOrPartialFile() = runTest {
        withSource(checksum = "${"0".repeat(64)}  ${release.fileName}") { source, directory ->
            assertFailsWith<UpdateIntegrityException> { source.download(release) { _, _ -> } }
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun rejectsChecksumForAnotherInstaller() = runTest {
        withSource(checksum = "$hash  Another.exe") { source, directory ->
            assertFailsWith<UpdateIntegrityException> { source.download(release) { _, _ -> } }
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun rejectsTruncatedDownload() = runTest {
        withSource(bytes = payload.copyOf(1000)) { source, directory ->
            assertFailsWith<UpdateIntegrityException> { source.download(release) { _, _ -> } }
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun cancelledDownloadRemovesPartialAndCanBeRetried() = runTest {
        withSource { source, directory ->
            assertFailsWith<CancellationException> { source.download(release) { _, _ -> throw CancellationException("cancel") } }
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            assertTrue(source.download(release) { _, _ -> }.verify())
        }
    }
}
