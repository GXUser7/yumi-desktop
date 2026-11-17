package com.yumi.desktop.platform

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.CRC32
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AppCatalogTest {
    @Test fun unwrapsQuotedPathsEnvFlatpakSnapAndShellWrappers() {
        assertEquals(
            "/opt/My App/my-app",
            AppCatalog.unwrapDesktopExec("\"/opt/My App/my-app\" --flag /tmp/some/path %u"),
        )
        assertEquals(
            "/snap/bin/firefox",
            AppCatalog.unwrapDesktopExec("env BAMF_DESKTOP_FILE_HINT=/var/lib/snapd/desktop/applications/firefox_firefox.desktop /snap/bin/firefox %u"),
        )
        assertEquals(
            "/usr/bin/telegram-desktop",
            AppCatalog.unwrapDesktopExec("GDK_BACKEND=x11 WAYLAND_DISPLAY=wayland-0 /usr/bin/telegram-desktop -- %U"),
        )
        assertEquals(
            "telegram-desktop",
            AppCatalog.unwrapDesktopExec("/usr/bin/flatpak run --branch=stable --arch=x86_64 --command=telegram-desktop --file-forwarding org.telegram.desktop @@u %u @@"),
        )
        assertEquals(
            "org.mozilla.firefox",
            AppCatalog.unwrapDesktopExec("flatpak run --file-forwarding org.mozilla.firefox @@u %u @@"),
        )
        assertEquals(
            "telegram-desktop",
            AppCatalog.unwrapDesktopExec("snap run --shell telegram-desktop.telegram-desktop %U"),
        )
        assertEquals(
            "/opt/obsidian/obsidian",
            AppCatalog.unwrapDesktopExec("/bin/sh -c \"cd /opt/obsidian && exec /opt/obsidian/obsidian %U\""),
        )
        assertEquals(
            "/usr/bin/signal-desktop",
            AppCatalog.unwrapDesktopExec("env -u GTK_THEME FOO=1 /bin/sh -c 'env BAR=2 /usr/bin/signal-desktop --no-sandbox'"),
        )
    }

    @Test fun parsesDesktopEntryIgnoringActionsAndHiddenEntries() {
        val dir = Files.createTempDirectory("yumi-desktop-entry-").toFile().apply { deleteOnExit() }
        val valid = File(dir, "firefox.desktop").apply {
            writeText(
                """
                [Desktop Entry]
                Type=Application
                Name=Firefox
                Exec=env MOZ_ENABLE_WAYLAND=1 /usr/lib/firefox/firefox %u

                [Desktop Action new-window]
                Name=New Window
                Exec=/usr/bin/other-tool
                """.trimIndent(),
            )
            deleteOnExit()
        }
        val parsed = AppCatalog.parseDesktopEntry(valid)
        assertNotNull(parsed)
        assertEquals("firefox", parsed!!.process)
        assertEquals("Firefox", parsed.name)

        val hidden = File(dir, "hidden.desktop").apply {
            writeText(
                """
                [Desktop Entry]
                Type=Application
                Name=HiddenApp
                NoDisplay=true
                Exec=/usr/bin/hidden-app
                """.trimIndent(),
            )
            deleteOnExit()
        }
        assertNull(AppCatalog.parseDesktopEntry(hidden))
    }

    @Test fun rejectsMacIconPathTraversalAndAcceptsBundleResource() {
        val bundle = Files.createTempDirectory("yumi-bundle-").toFile().apply { deleteOnExit() }
        val resources = File(bundle, "Contents/Resources").apply { mkdirs() }
        val secretOutside = File(bundle, "Contents/secret.icns").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val valid = File(resources, "AppIcon.icns").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        assertNull(AppCatalog.resolveMacIconFile(bundle, "../secret"))
        assertNull(AppCatalog.resolveMacIconFile(bundle, "../secret.icns"))
        assertNull(AppCatalog.resolveMacIconFile(bundle, "sub/AppIcon"))
        assertNull(AppCatalog.resolveMacIconFile(bundle, "sub\\AppIcon"))
        assertNull(AppCatalog.resolveMacIconFile(bundle, "App\u0000Icon"))
        assertNull(AppCatalog.resolveMacIconFile(bundle, "missing"))
        assertEquals(valid.canonicalFile, AppCatalog.resolveMacIconFile(bundle, "AppIcon")?.canonicalFile)
        assertEquals(valid.canonicalFile, AppCatalog.resolveMacIconFile(bundle, "AppIcon.icns")?.canonicalFile)
        assertEquals(true, secretOutside.exists())
    }

    @Test fun guardsImageReadAgainstLargeFilesAndOversizedDimensions() {
        val dir = Files.createTempDirectory("yumi-img-").toFile().apply { deleteOnExit() }

        val validPng = File(dir, "ok.png").apply { deleteOnExit() }
        ImageIO.write(BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), "png", validPng)
        val loaded = AppCatalog.readImageFile(validPng, 32)
        assertNotNull(loaded)
        assertEquals(32, loaded!!.width)
        assertEquals(32, loaded.height)

        // Craft a PNG whose IHDR declares 2049x16 pixels without allocating pixel data.
        val raw = ByteArrayOutputStream().also { out ->
            ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png", out)
        }.toByteArray()
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(16, 2049)
        buf.putInt(20, 16)
        val crc = CRC32().apply { update(raw, 12, 17) }.value.toInt()
        buf.putInt(29, crc)
        val tooWidePng = File(dir, "wide.png").apply { writeBytes(raw); deleteOnExit() }
        assertNull(AppCatalog.readImageFile(tooWidePng, 32))

        // Reject files larger than 8 MiB before reading image headers.
        val tooBigFile = File(dir, "huge.png").apply { deleteOnExit() }
        RandomAccessFile(tooBigFile, "rw").use { it.setLength(8L * 1024L * 1024L + 1L) }
        assertNull(AppCatalog.readImageFile(tooBigFile, 32))
    }
}
