package com.yumi.desktop.platform

import com.yumi.desktop.model.*
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Shell32
import com.sun.jna.platform.win32.ShellAPI
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.platform.win32.WinUser
import java.io.File
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface TunnelBackend {
    suspend fun request(request: ServiceRequest): ServiceStatus
}

/*
Both backends speak the same wire: one UTF-8 JSON request frame, one reply frame, each headed by a
big-endian 32-bit length, one exchange per connection, a megabyte cap both ways. The service
enforces the same numbers on its side, so the two transports below differ only in how the bytes
travel — a named pipe on Windows, a unix socket on Linux and macOS.
*/
private val tunnelJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

private const val MAX_FRAME = 1_048_576

private fun frameOf(bytes: ByteArray): ByteBuffer {
    val frame = ByteBuffer.allocate(4 + bytes.size)
    frame.putInt(bytes.size)
    frame.put(bytes)
    frame.flip()
    return frame
}

private fun readFrame(read: (ByteBuffer) -> Int): ByteArray {
    val head = ByteBuffer.allocate(4)
    while (head.hasRemaining()) if (read(head) < 0) throw java.io.IOException("short frame header")
    val size = ByteBuffer.wrap(head.array()).int
    require(size in 1..MAX_FRAME)
    val body = ByteBuffer.allocate(size)
    while (body.hasRemaining()) if (read(body) < 0) throw java.io.IOException("short frame body")
    return body.array()
}

private fun decodeReply(reply: ByteArray): ServiceStatus =
    tunnelJson.decodeFromString<ServiceStatus>(reply.decodeToString()).also { require(it.version == 1) }

class WindowsTunnelBackend : TunnelBackend {
    override suspend fun request(request: ServiceRequest): ServiceStatus = withContext(Dispatchers.IO) {
        if (!DesktopPlatform.isWindows) return@withContext ServiceStatus(state = "unavailable", error = "platform_not_supported")
        try {
            RandomAccessFile(DesktopPlatform.PIPE_NAME, "rw").use { pipe ->
                val bytes = tunnelJson.encodeToString(request).toByteArray(Charsets.UTF_8)
                require(bytes.size in 1..MAX_FRAME)
                pipe.writeInt(bytes.size); pipe.write(bytes)
                val size = pipe.readInt(); require(size in 1..MAX_FRAME)
                val reply = ByteArray(size); pipe.readFully(reply)
                decodeReply(reply)
            }
        } catch (_: Exception) {
            // Everything used to land here as one code, and the screen then advised installing a
            // service that was already installed and running. The reason is asked for now, once,
            // and only on the failing path.
            ServiceStatus(state = "unavailable", error = DesktopPlatform.diagnoseService())
        }
    }
}

class UnixTunnelBackend : TunnelBackend {
    override suspend fun request(request: ServiceRequest): ServiceStatus = withContext(Dispatchers.IO) {
        try {
            val address = UnixDomainSocketAddress.of(DesktopPlatform.SOCKET_PATH)
            if (!Files.exists(address.path)) {
                return@withContext ServiceStatus(state = "unavailable", error = "service_missing")
            }
            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(address)
                val bytes = tunnelJson.encodeToString(request).toByteArray(Charsets.UTF_8)
                require(bytes.size in 1..MAX_FRAME)
                channel.write(frameOf(bytes))
                val reply = readFrame { buffer -> channel.read(buffer) }
                decodeReply(reply)
            }
        } catch (_: Exception) {
            // The socket either is not there (answered above), or the exchange failed for a reason
            // the file system cannot name. The generic answer keeps the screen honest.
            ServiceStatus(state = "unavailable", error = "service_unavailable")
        }
    }
}

data class LocalAddress(val label: String, val address: Inet4Address)

/** What came of offering to re-register the tunnel service; see [DesktopPlatform.repairService]. */
enum class ServiceRepair { Done, Declined, Unavailable }

object DesktopPlatform {
    val isWindows = System.getProperty("os.name").startsWith("Windows")
    val isMac = System.getProperty("os.name") == "Mac OS X"

    fun dataDirectory(): File {
        System.getProperty("yumi.dataDir")?.let { return File(it).also(File::mkdirs) }
        val home = System.getProperty("user.home")
        val base = when {
            isWindows -> System.getenv("LOCALAPPDATA") ?: File(home, "AppData/Local").path
            // macOS convention instead of the XDG default; Linux honours XDG_DATA_HOME.
            isMac -> File(home, "Library/Application Support").path
            else -> System.getenv("XDG_DATA_HOME") ?: File(home, ".local/share").path
        }
        return File(base, "YumiDesktop").also(File::mkdirs)
    }

    fun localAddresses(): List<LocalAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter {
            it.isUp && !it.isLoopback &&
                !Regex("tun|tap|vpn|wireguard|wintun|hyper-v|vmware|virtual|docker|veth|virbr|bridge", RegexOption.IGNORE_CASE)
                    .containsMatchIn(it.displayName.orEmpty())
        }.flatMap { network -> network.inetAddresses.toList().filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress && !it.isLoopbackAddress }
            .map { LocalAddress(network.displayName + " · " + it.hostAddress, it) } }
    }.getOrDefault(emptyList())

    fun setLaunchAtLogin(enabled: Boolean) {
        when {
            isWindows -> setLaunchAtLoginWindows(enabled)
            isMac -> setLaunchAtLoginMac(enabled)
            else -> setLaunchAtLoginLinux(enabled)
        }
    }

    private fun launcher(): File {
        val launcher = System.getProperty("jpackage.app-path")?.let(::File)
        require(launcher?.isFile == true) { "Install Yumi before enabling startup" }
        return launcher
    }

    private fun setLaunchAtLoginWindows(enabled: Boolean) {
        val key = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        if (!enabled) {
            if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, key, "YumiDesktop"))
                Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, key, "YumiDesktop")
            return
        }
        val launcher = launcher()
        Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, key, "YumiDesktop", "\"${launcher.absolutePath}\" --tray")
    }

    private fun setLaunchAtLoginLinux(enabled: Boolean) {
        val file = File(System.getenv("XDG_CONFIG_HOME") ?: File(System.getProperty("user.home"), ".config").path, "autostart/yumi-desktop.desktop")
        if (!enabled) {
            file.delete()
            return
        }
        val launcher = launcher()
        file.parentFile.mkdirs()
        file.writeText(
            "[Desktop Entry]\n" +
                "Type=Application\n" +
                "Name=Yumi\n" +
                "Exec=\"${launcher.absolutePath}\" --tray\n" +
                "X-GNOME-Autostart-enabled=true\n",
        )
    }

    private fun setLaunchAtLoginMac(enabled: Boolean) {
        val file = File(System.getProperty("user.home"), "Library/LaunchAgents/app.yumi.desktop.plist")
        if (!enabled) {
            file.delete()
            return
        }
        val launcher = launcher()
        file.parentFile.mkdirs()
        // LaunchAgent rather than the modern SMAppService: the JVM has no Objective-C bridge, and
        // a plist in the user's own domain needs no elevation and survives reboots all the same.
        file.writeText(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n" +
                "<plist version=\"1.0\">\n<dict>\n" +
                "    <key>Label</key><string>app.yumi.desktop</string>\n" +
                "    <key>ProgramArguments</key>\n    <array>\n" +
                "        <string>${launcher.absolutePath}</string>\n        <string>--tray</string>\n    </array>\n" +
                "    <key>RunAtLoad</key><true/>\n" +
                "</dict>\n</plist>\n",
        )
    }

    /**
     * Asks Windows to draw the title bar in the window's own colours.
     *
     * The bar is not ours to paint — it belongs to the desktop manager, outside the surface Compose
     * draws on — so a dark app was arriving under a white caption with black buttons, which is the
     * one part of the window that could not be designed. What *can* be done is to tell DWM what
     * kind of window this is, and it obliges to different extents depending on the build:
     *
     * On Windows 10 (1809 and up) only [DARK_MODE] is honoured, and that is most of the win: the
     * caption turns near-black with light buttons and stops fighting the app. Windows 11 also
     * accepts an explicit caption, border and text colour, so there the bar takes the palette
     * exactly. Every attribute is offered and every refusal ignored — an older build answers
     * `E_INVALIDARG` to the ones it does not know, which is a fine answer.
     *
     * Then the frame is made to repaint, and it takes three things to make it. Windows 10 accepts
     * the attribute — the call answers `S_OK` — and simply carries on drawing the caption it
     * already had, so switching back to the light theme left a black bar over a white window.
     * `SWP_FRAMECHANGED` did not move it and neither did `RedrawWindow`; what does is telling the
     * window its activation changed, because that is the event on which DWM re-reads the frame.
     * The pair of messages leaves it active, as it was.
     *
     * On Linux and macOS the title bar already belongs to the system, so this is a no-op.
     */
    fun applyWindowChrome(windowHandle: Long, dark: Boolean, captionArgb: Int, textArgb: Int) {
        if (!isWindows || windowHandle == 0L) return
        runCatching {
            val window = WinDef.HWND(Pointer(windowHandle))
            val caption = captionArgb.toColorRef()
            val text = textArgb.toColorRef()
            Dwm.INSTANCE.DwmSetWindowAttribute(window, DARK_MODE, intArrayOf(if (dark) 1 else 0), 4)
            Dwm.INSTANCE.DwmSetWindowAttribute(window, CAPTION_COLOR, intArrayOf(caption), 4)
            Dwm.INSTANCE.DwmSetWindowAttribute(window, BORDER_COLOR, intArrayOf(caption), 4)
            Dwm.INSTANCE.DwmSetWindowAttribute(window, TEXT_COLOR, intArrayOf(text), 4)
            User32.INSTANCE.SetWindowPos(window, null, 0, 0, 0, 0, REDRAW_FRAME)
            User32.INSTANCE.RedrawWindow(window, null, null, WinDef.DWORD(0x0001L or 0x0100L or 0x0400L))
            User32.INSTANCE.SendMessage(window, 0x0086, WinDef.WPARAM(0), WinDef.LPARAM(0))
            User32.INSTANCE.SendMessage(window, 0x0086, WinDef.WPARAM(1), WinDef.LPARAM(0))
        }
    }

    /** ARGB as a Windows COLORREF: no alpha, and the channels the other way round. */
    private fun Int.toColorRef(): Int {
        val red = (this shr 16) and 0xFF
        val green = (this shr 8) and 0xFF
        val blue = this and 0xFF
        return (blue shl 16) or (green shl 8) or red
    }

    /** Since Windows 10 build 18985. The older 17763..18984 spelling is 19, and is not offered. */
    private const val DARK_MODE = 20

    /** Windows 11 only; earlier builds answer E_INVALIDARG and keep the dark caption. */
    private const val CAPTION_COLOR = 35
    private const val BORDER_COLOR = 34
    private const val TEXT_COLOR = 36

    /** SWP_NOSIZE | SWP_NOMOVE | SWP_NOZORDER | SWP_FRAMECHANGED. */
    private const val REDRAW_FRAME = 0x0001 or 0x0002 or 0x0004 or 0x0020

    /**
     * Why the service would not answer, asked only after it has already failed.
     *
     * The distinction is the whole point. "Not there" means the service is not installed or not
     * running, and the honest advice is to install Yumi. "Refused" means it is running and belongs
     * to another account — advice to install it again is then not merely useless, it sends someone
     * to reinstall a thing that is working. See [repairService] for what is offered instead.
     *
     * On Windows the pipe answers FileNotFoundException for both cases in the user's own language,
     * so the classification is done by asking Windows again, through an API that answers in
     * numbers. On unix the socket file either exists or it does not, which is most of the answer
     * already; a file that is there while the connection fails is a busy or foreign socket.
     */
    fun diagnoseService(): String {
        if (!isWindows) {
            val socket = Path.of(SOCKET_PATH)
            return when {
                !Files.exists(socket) -> "service_missing"
                else -> "service_unavailable"
            }
        }
        val handle = runCatching {
            Kernel32.INSTANCE.CreateFile(
                PIPE_NAME,
                WinNT.GENERIC_READ or WinNT.GENERIC_WRITE,
                0,
                null,
                WinNT.OPEN_EXISTING,
                0,
                null,
            )
        }.getOrNull() ?: return "service_unavailable"
        if (handle != WinBase.INVALID_HANDLE_VALUE) {
            Kernel32.INSTANCE.CloseHandle(handle)
            // It opens now and did not a moment ago: the service is there and something else went
            // wrong in the exchange. Nothing here can say what, and pretending otherwise would be
            // worse than the general answer.
            return "service_unavailable"
        }
        return when (Kernel32.INSTANCE.GetLastError()) {
            ERROR_FILE_NOT_FOUND, ERROR_PATH_NOT_FOUND -> "service_missing"
            ERROR_ACCESS_DENIED -> "service_denied"
            else -> "service_unavailable"
        }
    }

    /** The tunnel service binary that shipped beside this application, if this is a real install. */
    fun serviceExecutable(): File? {
        if (isWindows) {
            val launcher = System.getProperty("jpackage.app-path")?.let(::File) ?: return null
            return File(launcher.parentFile, "service\\yumi-service.exe").takeIf { it.isFile }
        }
        // Compose puts the appResourcesRootDir content under app/resources in the image and names
        // it to the launcher through this property; the fallback walks the same layout by hand.
        val resources = System.getProperty("compose.application.resources.dir")?.let(::File)
            ?: System.getProperty("jpackage.app-path")?.let(::File)?.parentFile?.let { File(it, "app/resources") }
            ?: return null
        val exe = File(resources, "service/yumi-service").takeIf { it.isFile } ?: return null
        // The packaging decides the bit; if a transfer stripped it, restore it quietly rather than
        // fail every connect with a permission error.
        if (!exe.canExecute()) runCatching { exe.setExecutable(true, false) }
        return exe
    }

    fun installedDirectory(): File? {
        val launcher = System.getProperty("jpackage.app-path")?.let(::File) ?: return null
        if (!isWindows) return launcher.parentFile.takeIf { serviceExecutable() != null }
        return launcher.parentFile.takeIf { File(it, "unins000.exe").isFile && serviceExecutable() != null }
    }

    /**
     * Installs a downloaded update, on every platform by handing the verified package to the
     * system's own installer with one elevation prompt.
     *
     * On Windows the Inno installer waits for this process to exit and then relaunches the app.
     * The unix package managers have no such handshake, so there the elevated shell waits for this
     * process to exit — the files are only touched once this JVM is gone — installs, and leaves
     * the relaunch to the user. The prompt appears while the app is still up, which is when
     * someone is looking at it.
     */
    fun installUpdate(file: File): Boolean {
        if (!file.isFile) return false
        return when {
            isWindows -> installUpdateWindows(file)
            file.name.endsWith(".deb") -> launchUpdate("dpkg -i ${quote(file)}")
            file.name.endsWith(".rpm") -> launchUpdate("rpm -U ${quote(file)}")
            file.name.endsWith(".pkg") -> launchUpdate("installer -pkg ${quote(file)} -target /")
            else -> false
        }
    }

    private fun installUpdateWindows(file: File): Boolean {
        val directory = installedDirectory() ?: return false
        return runCatching {
            val request = ShellAPI.SHELLEXECUTEINFO()
            request.lpVerb = "open"
            request.lpFile = file.absolutePath
            request.lpParameters = "/SILENT /NORESTART /YUMIUPDATE=1 /YUMIPID=${ProcessHandle.current().pid()} /DIR=\"${directory.absolutePath}\""
            request.nShow = WinUser.SW_SHOWNORMAL
            request.fMask = SEE_MASK_NOCLOSEPROCESS
            if (!Shell32.INSTANCE.ShellExecuteEx(request)) return false
            request.hProcess?.let { Kernel32.INSTANCE.CloseHandle(it) }
            true
        }.getOrDefault(false)
    }

    private fun quote(file: File) = "\"" + file.absolutePath.replace("\"", "\\\"") + "\""

    // Starts the elevated installer and comes back at once: the shell itself waits for this
    // process to end, so the app can exit on its own schedule while the prompt is answered.
    private fun launchUpdate(command: String): Boolean = runCatching {
        val pid = ProcessHandle.current().pid()
        val script = "while kill -0 $pid 2>/dev/null; do sleep 1; done; $command"
        if (isMac) {
            val appleScript = "do shell script " + "\"" + script.replace("\\", "\\\\").replace("\"", "\\\"") + "\" with administrator privileges"
            ProcessBuilder("osascript", "-e", appleScript).start()
        } else {
            ProcessBuilder("pkexec", "sh", "-c", script).start()
        }
        true
    }.getOrDefault(false)

    /*
     * One elevation prompt on every non-Windows platform: polkit on Linux, the administrator
     * dialog on macOS. The command runs inside the elevated shell, so the answer comes back only
     * when the work is actually done. Declining the prompt is an answer, not a fault: pkexec
     * answers 126 and osascript reports the cancellation in its error text, and both become
     * [ServiceRepair.Declined].
     */
    private fun elevatedRun(command: String): ServiceRepair = runCatching {
        val process = if (isMac) {
            val appleScript = "do shell script " + "\"" + command.replace("\\", "\\\\").replace("\"", "\\\"") + "\" with administrator privileges"
            ProcessBuilder("osascript", "-e", appleScript).start()
        } else {
            ProcessBuilder("pkexec", "sh", "-c", command).start()
        }
        val stderr = StringBuilder()
        val reader = process.errorStream.bufferedReader()
        val done = process.waitFor(10, TimeUnit.MINUTES)
        if (!done) {
            process.destroyForcibly()
            return ServiceRepair.Unavailable
        }
        reader.use { runCatching { stderr.append(it.readText()) } }
        val cancelled = process.exitValue() == 126 || stderr.contains("User canceled", ignoreCase = true)
        when {
            process.exitValue() == 0 -> ServiceRepair.Done
            cancelled -> ServiceRepair.Declined
            else -> ServiceRepair.Unavailable
        }
    }.getOrDefault(ServiceRepair.Unavailable)

    /**
     * This account's owner id, asked of the service binary rather than worked out here.
     *
     * It is the same code path the installer uses and the same one the service compares against,
     * so there is exactly one definition of "who am I" in the product. Doing it again in Kotlin
     * would be a second definition, and the entire bug this repairs was two answers to that
     * question disagreeing. Windows spells the answer as a SID, unix as a decimal uid.
     */
    fun currentUserOwner(): String? {
        val exe = serviceExecutable() ?: return null
        return runCatching {
            val process = ProcessBuilder(exe.absolutePath, "--print-owner").start()
            val text = process.inputStream.bufferedReader().use { it.readText() }.trim()
            process.waitFor(5, TimeUnit.SECONDS)
            when {
                isWindows -> text.takeIf { it.startsWith("S-") && it.length in 8..184 }
                else -> text.takeIf { it.isNotEmpty() && it.all(Char::isDigit) && it.length <= 10 }
            }
        }.getOrNull()
    }

    /**
     * Registers the tunnel service to this account, over one elevation prompt.
     *
     * The service admits one account, chosen when it was installed, and that choice is made by
     * whoever answered the installer's own elevation prompt. Where that is a separate
     * administrator account — most work laptops, and any machine with a family admin — the service
     * ends up owned by somebody who never opens Yumi, and the person who does is refused by it.
     *
     * Returns false when there is nothing to run or the prompt was declined. It does not report
     * whether the service came back: the connect screen asks every second anyway, so the answer
     * arrives on its own and is the truthful one.
     */
    fun repairService(): ServiceRepair {
        val exe = serviceExecutable() ?: return ServiceRepair.Unavailable
        val owner = currentUserOwner() ?: return ServiceRepair.Unavailable
        if (!isWindows) {
            return elevatedRun("${quote(exe)} --install --owner $owner")
        }
        return runCatching {
            val request = ShellAPI.SHELLEXECUTEINFO()
            request.lpVerb = "runas"
            request.lpFile = exe.absolutePath
            request.lpParameters = "--install --owner $owner"
            request.nShow = WinUser.SW_HIDE
            request.fMask = SEE_MASK_NOCLOSEPROCESS
            if (!Shell32.INSTANCE.ShellExecuteEx(request)) {
                // Declining the elevation prompt is an answer, not a fault, and telling somebody
                // their repair failed when they chose not to run it is how an app earns distrust.
                return if (Kernel32.INSTANCE.GetLastError() == ERROR_CANCELLED) {
                    ServiceRepair.Declined
                } else {
                    ServiceRepair.Unavailable
                }
            }
            request.hProcess?.let {
                Kernel32.INSTANCE.WaitForSingleObject(it, 60_000)
                Kernel32.INSTANCE.CloseHandle(it)
            }
            ServiceRepair.Done
        }.getOrDefault(ServiceRepair.Unavailable)
    }

    internal const val PIPE_NAME = "\\\\.\\pipe\\YumiTunnel-v1"
    internal val SOCKET_PATH: String =
        if (System.getProperty("os.name") == "Mac OS X") "/var/run/yumi/tunnel.sock" else "/run/yumi/tunnel.sock"

    private const val ERROR_FILE_NOT_FOUND = 2
    private const val ERROR_PATH_NOT_FOUND = 3
    private const val ERROR_ACCESS_DENIED = 5
    private const val ERROR_CANCELLED = 1223

    /** SEE_MASK_NOCLOSEPROCESS: keeps the process handle alive so the wait above has something. */
    private const val SEE_MASK_NOCLOSEPROCESS = 0x00000040
}

private interface Dwm : Library {
    fun DwmSetWindowAttribute(window: WinDef.HWND, attribute: Int, value: IntArray, size: Int): Int

    companion object {
        val INSTANCE: Dwm = Native.load("dwmapi", Dwm::class.java)
    }
}
