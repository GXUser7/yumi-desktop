package com.yumi.desktop.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.io.File
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import kotlin.test.*

class GitHubUpdatesTest {
    private val payload = ByteArray(150_000) { (it % 253).toByte() }
    private val hash get() = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
    private val release = DesktopRelease("0.3.0", "Notes", "https://github.com/installer", "https://github.com/checksum", payload.size.toLong())

    private fun releaseJson(
        version: String,
        draft: Boolean = false,
        checksum: Boolean = true,
        signature: Boolean = false,
        malicious: Boolean = false,
    ): JsonObject = buildJsonObject {
        put("tag_name", "v$version"); put("draft", draft); put("prerelease", true); put("body", "What's new")
        putJsonArray("assets") {
            // The asset the running platform selects, the one parseReleases looks for.
            val name = assetName(version)
            val files = buildList {
                add(name)
                if (checksum) add("$name.sha256")
                if (signature) add("$name.sha256.sig")
            }
            for (file in files) addJsonObject {
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

    @Test fun configuredPublicKeyRequiresSignatureAssetAndVerifiesSpkiAndRawKeys() = runTest {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val spkiEncoded = pair.public.encoded
        assertEquals(44, spkiEncoded.size)
        val raw32 = spkiEncoded.copyOfRange(12, 44)
        val spkiBase64 = Base64.getEncoder().encodeToString(spkiEncoded)
        val raw32Base64 = Base64.getEncoder().encodeToString(raw32)

        val jsonMissingSig = JsonArray(listOf(releaseJson("0.4.0", signature = false)))
        assertNull(GitHubUpdates.parseReleases(jsonMissingSig.toString(), spkiBase64))
        assertNotNull(GitHubUpdates.parseReleases(jsonMissingSig.toString(), ""))

        val jsonWithSig = JsonArray(listOf(releaseJson("0.4.0", signature = true)))
        val parsed = assertNotNull(GitHubUpdates.parseReleases(jsonWithSig.toString(), spkiBase64))
        assertNotNull(parsed.signatureUrl)

        // Signed with trailing newline just like sha256sum/CI output.
        val checksumBytes = "$hash  ${parsed.fileName}\n".toByteArray()
        val sig = Signature.getInstance("Ed25519").run {
            initSign(pair.private)
            update(checksumBytes)
            sign()
        }
        assertEquals(64, sig.size)

        for (keyBase64 in listOf(spkiBase64, raw32Base64)) {
            val directory = Files.createTempDirectory("yumi-update-sig-test-").toFile()
            try {
                val transport = UpdateTransport { url ->
                    when (url) {
                        parsed.checksumUrl -> ByteArrayInputStream(checksumBytes)
                        parsed.signatureUrl -> ByteArrayInputStream(sig)
                        parsed.installerUrl -> ByteArrayInputStream(payload)
                        else -> error("Unexpected URL: $url")
                    }
                }
                val verified = GitHubUpdates(directory, transport, keyBase64).download(parsed) { _, _ -> }
                assertTrue(verified.verify())
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test fun rejectsBadOrMissingOrWrongSizedSignatureWhenKeyConfigured() = runTest {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val spkiBase64 = Base64.getEncoder().encodeToString(pair.public.encoded)
        val signedRelease = DesktopRelease(
            "0.3.0", "Notes", "https://github.com/installer", "https://github.com/checksum",
            payload.size.toLong(), "https://github.com/sig",
        )
        val checksumBytes = "$hash  ${signedRelease.fileName}\n".toByteArray()
        val validSig = Signature.getInstance("Ed25519").run {
            initSign(pair.private); update(checksumBytes); sign()
        }
        val badSig = validSig.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
        val shortSig = validSig.copyOf(63)
        val longSig = validSig + byteArrayOf(0x00)

        for (candidateSig in listOf(badSig, shortSig, longSig)) {
            val directory = Files.createTempDirectory("yumi-update-badsig-").toFile()
            try {
                val transport = UpdateTransport { url ->
                    when (url) {
                        signedRelease.checksumUrl -> ByteArrayInputStream(checksumBytes)
                        signedRelease.signatureUrl -> ByteArrayInputStream(candidateSig)
                        signedRelease.installerUrl -> ByteArrayInputStream(payload)
                        else -> error("Unexpected URL: $url")
                    }
                }
                assertFailsWith<UpdateIntegrityException> {
                    GitHubUpdates(directory, transport, spkiBase64).download(signedRelease) { _, _ -> }
                }
                assertTrue(directory.listFiles().orEmpty().isEmpty())
            } finally {
                directory.deleteRecursively()
            }
        }

        // Release without signatureUrl must also fail when public key is configured.
        val directory = Files.createTempDirectory("yumi-update-nosig-").toFile()
        try {
            val transport = UpdateTransport { url ->
                ByteArrayInputStream(if (url == release.checksumUrl) checksumBytes else payload)
            }
            assertFailsWith<UpdateIntegrityException> {
                GitHubUpdates(directory, transport, spkiBase64).download(release) { _, _ -> }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun readsPublicKeyFromSystemProperty() {
        val previous = System.getProperty("yumi.updatePublicKey")
        try {
            System.setProperty("yumi.updatePublicKey", "  test-prop-key  ")
            assertEquals("test-prop-key", GitHubUpdates.configuredPublicKey())
        } finally {
            if (previous == null) System.clearProperty("yumi.updatePublicKey")
            else System.setProperty("yumi.updatePublicKey", previous)
        }
    }

    @Test fun purgesStaleInstallersDuringDownload() = runTest {
        withSource { source, directory ->
            val staleExe = File(directory, "Yumi-Desktop-0.1.0-windows-x64-setup.exe").apply { writeText("old") }
            val stalePart = File(directory, "Yumi-Desktop-0.2.0-windows-x64-setup.exe.part").apply { writeText("part") }
            val unrelated = File(directory, "keep-me.txt").apply { writeText("keep") }
            val verified = source.download(release) { _, _ -> }
            assertTrue(verified.file.isFile)
            assertFalse(staleExe.exists())
            assertFalse(stalePart.exists())
            assertTrue(unrelated.isFile)
        }
    }
}
