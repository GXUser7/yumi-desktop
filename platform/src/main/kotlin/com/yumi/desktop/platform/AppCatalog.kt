package com.yumi.desktop.platform

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.awt.image.BufferedImage
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.filechooser.FileSystemView

/**
 * A program the user might want to send through the tunnel or around it.
 *
 * [process] is what the rule is written with and what the core matches: the executable's file name,
 * spelt as it is on disk. [name] is only for reading — "Google Chrome" rather than `chrome.exe`.
 */
data class DesktopApp(
    val process: String,
    val name: String,
    val path: String?,
    val running: Boolean,
)

/**
 * Finds the programs on this machine, so that choosing one is a search and a click rather than a
 * trip through Program Files with a file dialog.
 *
 * Four places on Windows, each covering what the others miss:
 *
 * - **running processes** — whatever is open right now, which is usually what someone is looking
 *   at when they decide it should go around the tunnel;
 * - **Start menu shortcuts** — the names people know programs by, and the path behind each;
 * - **App Paths** in the registry — the executables Windows itself resolves by name;
 * - **installed programs** in the registry — their display names and the icon each one declares,
 *   which is nearly always the main executable.
 *
 * On Linux the same answer comes from `.desktop` entries — the free-desktop's own answer to a
 * Start menu — beside whatever is running; on macOS from the applications folder and each bundle's
 * Info.plist. Icons are read only where they are plain image files, which is most of Linux; a
 * bundle's `.icns` is converted once through the system's own `sips`, and where none of that
 * answers, the UI falls back to a letter and nobody is the worse.
 *
 * Nothing here is privileged and nothing is written: it reads what the signed-in user can read.
 * Each source fails on its own — a shortcut that will not parse or a registry key that is gone
 * costs that entry, not the list.
 */
object AppCatalog {

    fun discover(): List<DesktopApp> = if (!DesktopPlatform.isWindows) unixDiscover() else windowsDiscover()

    private fun unixDiscover(): List<DesktopApp> {
        val byProcess = LinkedHashMap<String, DesktopApp>()
        fun offer(app: DesktopApp) {
            val key = app.process.lowercase()
            val known = byProcess[key]
            byProcess[key] = when {
                known == null -> app
                // The friendlier name wins, and "running" is sticky: it is the most useful fact.
                else -> known.copy(
                    name = if (known.name.equals(known.process, true)) app.name else known.name,
                    path = known.path ?: app.path,
                    running = known.running || app.running,
                )
            }
        }
        runCatching { unixInstalledApps().forEach(::offer) }
        runCatching { runningProcesses().forEach(::offer) }
        return byProcess.values
            .filterNot { isNoise(it) }
            .sortedWith(compareByDescending<DesktopApp> { it.running }.thenBy { it.name.lowercase() })
    }

    private fun windowsDiscover(): List<DesktopApp> {
        val byProcess = LinkedHashMap<String, DesktopApp>()
        fun offer(app: DesktopApp) {
            val key = app.process.lowercase()
            val known = byProcess[key]
            byProcess[key] = when {
                known == null -> app
                // The friendlier name wins, and "running" is sticky: it is the most useful fact.
                else -> known.copy(
                    name = if (known.name.equals(known.process.removeSuffix(".exe"), true)) app.name else known.name,
                    path = known.path ?: app.path,
                    running = known.running || app.running,
                )
            }
        }
        runCatching { startMenu().forEach(::offer) }
        runCatching { installedPrograms().forEach(::offer) }
        runCatching { appPaths().forEach(::offer) }
        runCatching { runningProcesses().forEach(::offer) }
        return byProcess.values
            .filterNot { isNoise(it) }
            .sortedWith(compareByDescending<DesktopApp> { it.running }.thenBy { it.name.lowercase() })
    }

    /** What is open right now. Processes of other accounts report no path and are left out. */
    fun runningProcesses(): List<DesktopApp> = ProcessHandle.allProcesses().use { stream ->
        stream.toList().mapNotNull { process ->
            val command = runCatching { process.info().command().orElse(null) }.getOrNull() ?: return@mapNotNull null
            val file = File(command)
            if (DesktopPlatform.isWindows) {
                if (!file.name.endsWith(".exe", true)) return@mapNotNull null
                if (isSystemPath(file.path)) return@mapNotNull null
            } else if (isUnixNoise(file.name)) {
                return@mapNotNull null
            }
            DesktopApp(file.name, prettify(file.name), file.path, running = true)
        }.distinctBy { it.process.lowercase() }
    }

    private fun unixInstalledApps(): List<DesktopApp> =
        if (DesktopPlatform.isMac) macInstalledApps() else linuxInstalledApps()

    private fun linuxInstalledApps(): List<DesktopApp> {
        val roots = listOf(
            File("/usr/share/applications"),
            File("/usr/local/share/applications"),
            File("/var/lib/flatpak/exports/share/applications"),
            File(System.getProperty("user.home"), ".local/share/applications"),
            File(System.getProperty("user.home"), ".local/share/flatpak/exports/share/applications"),
        )
        return roots.flatMap { root ->
            root.listFiles { f -> f.isFile && f.name.endsWith(".desktop") }?.toList() ?: emptyList()
        }.mapNotNull(::parseDesktopEntry)
    }

    private fun parseDesktopEntry(file: File): DesktopApp? {
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        if (Regex("^NoDisplay=true", RegexOption.MULTILINE).containsMatchIn(text)) return null
        val name = Regex("^Name=(.*)$", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)?.trim()
        val exec = Regex("^Exec=(.*)$", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)?.trim() ?: return null
        // Exec carries field codes (%f, %u) and the occasional quoting; what the core matches is
        // the program the line runs. A token with a path in it is more specific than `flatpak`.
        val tokens = exec.split(' ').map { it.trim().trim('"') }.filter { it.isNotEmpty() && !it.startsWith("%") }
        val binary = tokens.firstOrNull { it.contains('/') } ?: tokens.firstOrNull() ?: return null
        val process = File(binary).name
        if (process.isEmpty()) return null
        return DesktopApp(process, name ?: prettify(process), desktopIcon(text) ?: binary, running = false)
    }

    /** The entry's icon resolved to a file ImageIO can read, or null where the theme does not. */
    private fun desktopIcon(entry: String): String? {
        val icon = Regex("^Icon=(.*)$", RegexOption.MULTILINE).find(entry)?.groupValues?.get(1)?.trim() ?: return null
        if (File(icon).isFile) return icon
        val sizes = listOf("256x256", "128x128", "64x64", "48x48", "32x32")
        val roots = listOf("/usr/share/icons/hicolor", "/usr/local/share/icons/hicolor")
        for (root in roots) for (size in sizes) {
            val candidate = File("$root/$size/apps/$icon.png")
            if (candidate.isFile) return candidate.path
        }
        val pixmap = File("/usr/share/pixmaps/$icon.png")
        return pixmap.takeIf { it.isFile }?.path
    }

    private fun macInstalledApps(): List<DesktopApp> {
        val roots = listOf(File("/Applications"), File("/System/Applications"))
        return roots.flatMap { root -> root.listFiles()?.filter { it.isDirectory && it.name.endsWith(".app") } ?: emptyList() }
            .mapNotNull { bundle ->
                val plist = File(bundle, "Contents/Info.plist")
                val text = runCatching { plist.readText() }.getOrNull() ?: return@mapNotNull null
                fun value(key: String) = Regex("<key>$key</key>\\s*<string>(.*?)</string>").find(text)?.groupValues?.get(1)?.trim()
                val name = value("CFBundleName") ?: value("CFBundleDisplayName") ?: bundle.name.removeSuffix(".app")
                val executable = value("CFBundleExecutable") ?: name
                if (executable.isEmpty()) return@mapNotNull null
                DesktopApp(executable, name, macIcon(bundle, value("CFBundleIconFile") ?: value("CFBundleIconName")) ?: bundle.path, running = false)
            }
    }

    /** A bundle's `.icns` turned into a png once, by the system's own converter, into our cache. */
    private fun macIcon(bundle: File, iconFile: String?): String? {
        if (iconFile == null) return null
        val spelled = if (iconFile.endsWith(".icns")) iconFile else "$iconFile.icns"
        val icns = File(bundle, "Contents/Resources/$spelled")
        if (!icns.isFile) return null
        val out = File(DesktopPlatform.dataDirectory(), "icons/${bundle.nameWithoutExtension}.png")
        return runCatching {
            out.parentFile.mkdirs()
            val process = ProcessBuilder("sips", "-s", "format", "png", icns.path, "--out", out.path).start()
            if (process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) out.path else null
        }.getOrNull()
    }

    // The parts of the desktop nobody routes, and the ones that would otherwise own the list.
    private val unixNoise = listOf(
        "systemd", "dbus", "pipewire", "wireplumber", "pulseaudio", "gvfs", "gnome-", "gnome_",
        "org.gnome", "plasma", "kded", "kwin", "xwayland", "xorg", "snapd", "sshd", "cupsd",
        "polkit", "rtkit", "udisks", "upower", "wpa_", "networkmanager", "packagekit", "tracker",
        "at-spi", "gjs", "dconf", "xdg", "ibus", "fcitx", "systemsettings", "gnome-shell",
    )

    private fun isUnixNoise(name: String): Boolean {
        val lower = name.lowercase()
        return unixNoise.any { lower.startsWith(it) || lower == it }
    }

    private fun startMenu(): List<DesktopApp> {
        val roots = listOfNotNull(System.getenv("ProgramData"), System.getenv("APPDATA"))
            .map { File(it, "Microsoft\\Windows\\Start Menu\\Programs") }
        return roots.flatMap { root ->
            root.walkTopDown().maxDepth(4)
                .filter { it.isFile && it.name.endsWith(".lnk", true) }
                .mapNotNull { link ->
                    val target = runCatching { ShellLink.target(link) }.getOrNull() ?: return@mapNotNull null
                    if (!target.endsWith(".exe", true)) return@mapNotNull null
                    val name = link.name.removeSuffix(".lnk").removeSuffix(".LNK")
                    DesktopApp(File(target).name, name, target, running = false)
                }
                .toList()
        }
    }

    private fun appPaths(): List<DesktopApp> = listOf(WinReg.HKEY_LOCAL_MACHINE, WinReg.HKEY_CURRENT_USER).flatMap { hive ->
        val key = "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths"
        val names = runCatching { Advapi32Util.registryGetKeys(hive, key).toList() }.getOrDefault(emptyList())
        names.filter { it.endsWith(".exe", true) }.mapNotNull { exe ->
            val path = runCatching { Advapi32Util.registryGetStringValue(hive, "$key\\$exe", "") }.getOrNull()
                ?.trim()?.trim('"')
            DesktopApp(path?.let { File(it).name } ?: exe, prettify(exe), path, running = false)
        }
    }

    private fun installedPrograms(): List<DesktopApp> {
        val key = "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall"
        val places = listOf(
            WinReg.HKEY_LOCAL_MACHINE to key,
            WinReg.HKEY_LOCAL_MACHINE to "SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
            WinReg.HKEY_CURRENT_USER to key,
        )
        return places.flatMap { (hive, path) ->
            val entries = runCatching { Advapi32Util.registryGetKeys(hive, path).toList() }.getOrDefault(emptyList())
            entries.mapNotNull { entry ->
                val values = runCatching { Advapi32Util.registryGetValues(hive, "$path\\$entry") }.getOrNull() ?: return@mapNotNull null
                val name = values["DisplayName"] as? String ?: return@mapNotNull null
                // `DisplayIcon` is "path,index" and nearly always the program's own executable.
                val icon = (values["DisplayIcon"] as? String)?.substringBeforeLast(',')?.trim()?.trim('"') ?: return@mapNotNull null
                if (!icon.endsWith(".exe", true) || isUninstaller(icon)) return@mapNotNull null
                DesktopApp(File(icon).name, name, icon, running = false)
            }
        }
    }

    private fun isSystemPath(path: String): Boolean {
        val windows = (System.getenv("SystemRoot") ?: "C:\\Windows").lowercase()
        return path.lowercase().startsWith(windows)
    }

    private fun isUninstaller(path: String): Boolean {
        val name = File(path).name.lowercase()
        return name.startsWith("unins") || "uninstall" in name || name == "setup.exe" || name.startsWith("update")
    }

    private fun isNoise(app: DesktopApp): Boolean {
        val process = app.process.lowercase()
        if (isUninstaller(process)) return true
        // Ourselves, and the parts of the system nobody routes: the shell and its helpers.
        if (DesktopPlatform.isWindows) {
            if (process in setOf("yumi.exe", "yumi-service.exe", "java.exe", "javaw.exe")) return true
        } else {
            if (process in setOf("yumi", "yumi-service", "java", "jar") || isUnixNoise(app.process)) return true
        }
        val name = app.name.lowercase()
        return listOf("uninstall", "удал", "readme", "help", "documentation", "справка").any { it in name }
    }

    /** `telegram.exe` → `Telegram`; the best a bare file name can do. */
    fun prettify(process: String): String =
        process.removeSuffix(".exe").removeSuffix(".EXE").replace('_', ' ').replace('-', ' ')
            .split(' ').filter { it.isNotEmpty() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
            .ifEmpty { process }

    private val icons = HashMap<String, BufferedImage?>()

    /**
     * The program's own icon, as Explorer draws it, or null where there is none to be had.
     * Cached by path: the list is scrolled and searched far more often than it changes.
     */
    @Synchronized fun icon(path: String?, size: Int = 32): BufferedImage? {
        if (path == null) return null
        return icons.getOrPut("$path@$size") {
            runCatching {
                val file = File(path).takeIf { it.isFile } ?: return@runCatching null
                // On unix the paths the catalog hands out are plain image files already resolved
                // at discover time, so the shell is not asked for anything.
                if (!DesktopPlatform.isWindows) return@runCatching readImageFile(file, size)
                val icon: Icon = FileSystemView.getFileSystemView().getSystemIcon(file, size, size) ?: return@runCatching null
                val image = BufferedImage(icon.iconWidth.coerceAtLeast(1), icon.iconHeight.coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
                val graphics = image.createGraphics()
                try {
                    if (icon is ImageIcon) graphics.drawImage(icon.image, 0, 0, null) else icon.paintIcon(null, graphics, 0, 0)
                } finally {
                    graphics.dispose()
                }
                image
            }.getOrNull()
        }
    }

    private fun readImageFile(file: File, size: Int): BufferedImage? {
        val source = ImageIO.read(file) ?: return null
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.drawImage(source, 0, 0, size, size, null)
        } finally {
            graphics.dispose()
        }
        return image
    }
}

/**
 * The target of a Windows shortcut, read straight from the file.
 *
 * The documented way is the IShellLink COM interface, which is a long way round from the JVM for
 * one path. The format is documented too ([MS-SHLLINK]), and the part needed here is short: the
 * header, an optional item-ID list to step over, and the link-info block that holds the local path.
 */
internal object ShellLink {
    private const val HAS_ID_LIST = 0x1
    private const val HAS_LINK_INFO = 0x2
    private const val VOLUME_ID_AND_LOCAL_BASE_PATH = 0x1

    fun target(file: File): String? {
        if (file.length() !in 0x4C..1_048_576) return null
        val bytes = RandomAccessFile(file, "r").use { raf -> ByteArray(raf.length().toInt()).also { raf.readFully(it) } }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.getInt(0) != 0x4C) return null
        val flags = buffer.getInt(0x14)
        var offset = 0x4C
        if (flags and HAS_ID_LIST != 0) offset += 2 + (buffer.getShort(offset).toInt() and 0xFFFF)
        if (flags and HAS_LINK_INFO == 0 || offset + 0x1C > bytes.size) return null
        val info = offset
        val headerSize = buffer.getInt(info + 4)
        val infoFlags = buffer.getInt(info + 8)
        if (infoFlags and VOLUME_ID_AND_LOCAL_BASE_PATH == 0) return null
        // A Unicode path where the header is long enough to carry one; the ANSI one otherwise.
        if (headerSize >= 0x24) {
            val unicode = buffer.getInt(info + 0x1C)
            if (unicode > 0) return readUtf16(bytes, info + unicode)
        }
        val ansi = buffer.getInt(info + 0x10)
        return readAnsi(bytes, info + ansi)
    }

    private fun readAnsi(bytes: ByteArray, start: Int): String? {
        if (start !in bytes.indices) return null
        var end = start
        while (end < bytes.size && bytes[end] != 0.toByte()) end++
        // The ANSI path is in the machine's own code page — 1251 on a Russian Windows — which the
        // JVM reports as its native encoding even where its default is UTF-8.
        val codePage = runCatching { charset(System.getProperty("native.encoding") ?: "windows-1252") }.getOrDefault(Charsets.ISO_8859_1)
        return String(bytes, start, end - start, codePage).takeIf { it.isNotBlank() }
    }

    private fun readUtf16(bytes: ByteArray, start: Int): String? {
        if (start !in bytes.indices) return null
        var end = start
        while (end + 1 < bytes.size && (bytes[end] != 0.toByte() || bytes[end + 1] != 0.toByte())) end += 2
        return String(bytes, start, end - start, Charsets.UTF_16LE).takeIf { it.isNotBlank() }
    }
}
