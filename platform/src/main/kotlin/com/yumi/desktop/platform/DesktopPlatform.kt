package com.yumi.desktop.platform

import com.yumi.desktop.model.*
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.AccCtrl
import com.sun.jna.platform.win32.Advapi32
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Shell32
import com.sun.jna.platform.win32.ShellAPI
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinError
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
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

class WindowsTunnelBackend internal constructor(
    private val pipeName: String = DesktopPlatform.PIPE_NAME,
    private val timeoutMillis: Long = 15_000L,
    private val expectedOwnerSid: String = DesktopPlatform.LOCAL_SYSTEM_SID,
) : TunnelBackend {
    constructor() : this(DesktopPlatform.PIPE_NAME, 15_000L, DesktopPlatform.LOCAL_SYSTEM_SID)

    override suspend fun request(request: ServiceRequest): ServiceStatus {
        if (!DesktopPlatform.isWindows) {
            return ServiceStatus(state = "unavailable", error = "platform_not_supported")
        }
        val handleLock = Any()
        var pipeHandle: WinNT.HANDLE? = null
        val cancelled = AtomicBoolean(false)
        val ioStarted = AtomicBoolean(false)
        val ioFinished = AtomicBoolean(false)
        val lastOpenError = AtomicInteger(0)

        fun abortPipe() {
            synchronized(handleLock) {
                val handle = pipeHandle
                if (handle != null && handle != WinBase.INVALID_HANDLE_VALUE) {
                    Kernel32Extra.INSTANCE.CancelIoEx(handle, null)
                }
            }
        }

        val reply = withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation {
                    cancelled.set(true)
                    abortPipe()
                    // Cover any narrow race between storing pipeHandle and entering ReadFile/WriteFile.
                    if (ioStarted.get() && !ioFinished.get()) {
                        thread(isDaemon = true, name = "yumi-pipe-cancel") {
                            var attempts = 0
                            while (!ioFinished.get() && attempts++ < 50) {
                                abortPipe()
                                try {
                                    Thread.sleep(10)
                                } catch (_: InterruptedException) {
                                    break
                                }
                            }
                        }
                    }
                }
                Dispatchers.IO.dispatch(EmptyCoroutineContext) {
                    ioStarted.set(true)
                    try {
                        if (cancelled.get()) {
                            throw CancellationException("Windows pipe request cancelled")
                        }
                        val handle = DesktopPlatform.openVerifiedPipe(pipeName, expectedOwnerSid, lastOpenError)
                        synchronized(handleLock) {
                            if (cancelled.get()) {
                                Kernel32.INSTANCE.CloseHandle(handle)
                                throw CancellationException("Windows pipe request cancelled")
                            }
                            pipeHandle = handle
                        }
                        val bytes = tunnelJson.encodeToString(request).toByteArray(Charsets.UTF_8)
                        require(bytes.size in 1..MAX_FRAME)
                        val frame = ByteBuffer.allocate(4 + bytes.size).putInt(bytes.size).put(bytes).array()
                        writeAll(handle, frame, cancelled)
                        val head = readExact(handle, 4, cancelled)
                        val size = ByteBuffer.wrap(head).int
                        require(size in 1..MAX_FRAME)
                        val body = readExact(handle, size, cancelled)
                        val status = decodeReply(body)
                        if (cont.isActive) cont.resume(status)
                    } catch (cancelledError: CancellationException) {
                        if (cont.isActive) cont.resumeWithException(cancelledError)
                    } catch (_: SecurityException) {
                        if (cont.isActive) cont.resume(ServiceStatus(state = "unavailable", error = "service_denied"))
                    } catch (_: Exception) {
                        if (cont.isActive) {
                            val openErr = lastOpenError.get()
                            val code = if (openErr != 0) {
                                DesktopPlatform.classifyWindowsPipeOpenError(openErr)
                            } else {
                                DesktopPlatform.diagnoseWindowsPipe(pipeName, expectedOwnerSid)
                            }
                            cont.resume(ServiceStatus(state = "unavailable", error = code))
                        }
                    } catch (fatal: Throwable) {
                        if (cont.isActive) cont.resumeWithException(fatal)
                    } finally {
                        ioFinished.set(true)
                        val toClose = synchronized(handleLock) {
                            val handle = pipeHandle
                            pipeHandle = null
                            handle
                        }
                        if (toClose != null && toClose != WinBase.INVALID_HANDLE_VALUE) {
                            Kernel32.INSTANCE.CloseHandle(toClose)
                        }
                    }
                }
            }
        }
        return reply ?: ServiceStatus(state = "unavailable", error = "service_unavailable")
    }

    private fun writeAll(handle: WinNT.HANDLE, data: ByteArray, cancelled: AtomicBoolean) {
        var offset = 0
        val written = IntByReference()
        while (offset < data.size) {
            if (cancelled.get()) throw CancellationException("Windows pipe write cancelled")
            val chunk = if (offset == 0) data else data.copyOfRange(offset, data.size)
            val ok = Kernel32.INSTANCE.WriteFile(handle, chunk, chunk.size, written, null)
            if (!ok) {
                val err = Kernel32.INSTANCE.GetLastError()
                if (cancelled.get() || err == WinError.ERROR_OPERATION_ABORTED) {
                    throw CancellationException("Windows pipe write aborted ($err)")
                }
                throw java.io.IOException("WriteFile failed ($err)")
            }
            val n = written.value
            if (n <= 0) throw java.io.IOException("WriteFile wrote 0 bytes")
            offset += n
        }
    }

    private fun readExact(handle: WinNT.HANDLE, length: Int, cancelled: AtomicBoolean): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        val bytesRead = IntByReference()
        val scratch = ByteArray(minOf(length, 65_536))
        while (offset < length) {
            if (cancelled.get()) throw CancellationException("Windows pipe read cancelled")
            val want = minOf(scratch.size, length - offset)
            val ok = Kernel32.INSTANCE.ReadFile(handle, scratch, want, bytesRead, null)
            if (!ok) {
                val err = Kernel32.INSTANCE.GetLastError()
                if (cancelled.get() || err == WinError.ERROR_OPERATION_ABORTED) {
                    throw CancellationException("Windows pipe read aborted ($err)")
                }
                throw java.io.IOException("ReadFile failed ($err)")
            }
            val n = bytesRead.value
            if (n <= 0) throw java.io.IOException("EOF on Windows pipe")
            System.arraycopy(scratch, 0, result, offset, n)
            offset += n
        }
        return result
    }
}

class UnixTunnelBackend(
    private val socketPath: Path = Path.of(DesktopPlatform.SOCKET_PATH),
    private val timeoutMillis: Long = 15_000,
) : TunnelBackend {
    override suspend fun request(request: ServiceRequest): ServiceStatus = withTimeoutOrNull(timeoutMillis) {
        val job = coroutineContext[Job]
        runInterruptible(Dispatchers.IO) {
            try {
                val address = UnixDomainSocketAddress.of(socketPath)
                if (!Files.exists(address.path)) {
                    return@runInterruptible ServiceStatus(state = "unavailable", error = "service_missing")
                }
                if (!Files.isReadable(address.path) || !Files.isWritable(address.path)) {
                    return@runInterruptible ServiceStatus(state = "unavailable", error = "service_denied")
                }
                SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                    channel.connect(address)
                    val bytes = tunnelJson.encodeToString(request).toByteArray(Charsets.UTF_8)
                    require(bytes.size in 1..MAX_FRAME)
                    val frame = frameOf(bytes)
                    while (frame.hasRemaining()) channel.write(frame)
                    val reply = readFrame { buffer -> channel.read(buffer) }
                    decodeReply(reply)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (interrupted: ClosedByInterruptException) {
                if (job?.isActive == false) throw CancellationException("Unix tunnel request cancelled", interrupted)
                ServiceStatus(state = "unavailable", error = DesktopPlatform.diagnoseUnixSocket(socketPath))
            } catch (error: Exception) {
                // The socket either is not there or cannot be opened; inspect POSIX state without locale strings.
                val statusError = if (error is java.nio.file.AccessDeniedException) {
                    "service_denied"
                } else {
                    DesktopPlatform.diagnoseUnixSocket(socketPath)
                }
                ServiceStatus(state = "unavailable", error = statusError)
            }
        }
    } ?: ServiceStatus(state = "unavailable", error = "service_unavailable")
}

data class LocalAddress(val label: String, val address: Inet4Address)

/** What came of offering to re-register the tunnel service; see [DesktopPlatform.repairService]. */
enum class ServiceRepair { Done, Declined, Unavailable }

object DesktopPlatform {
    val isWindows = System.getProperty("os.name").startsWith("Windows")
    val isMac = System.getProperty("os.name") == "Mac OS X"

    fun dataDirectory(): File {
        System.getProperty("yumi.dataDir")?.let { return ensurePrivateDirectory(File(it)) }
        val home = System.getProperty("user.home")
        val base = when {
            isWindows -> System.getenv("LOCALAPPDATA") ?: File(home, "AppData/Local").path
            // macOS convention instead of the XDG default; Linux honours XDG_DATA_HOME.
            isMac -> File(home, "Library/Application Support").path
            else -> System.getenv("XDG_DATA_HOME") ?: File(home, ".local/share").path
        }
        return ensurePrivateDirectory(File(base, "YumiDesktop"))
    }

    internal fun ensurePrivateDirectory(directory: File): File {
        directory.mkdirs()
        if (!isWindows) {
            runCatching {
                val path = directory.toPath()
                if (Files.getFileStore(path).supportsFileAttributeView(PosixFileAttributeView::class.java)) {
                    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
                }
            }
        }
        return directory
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
        file.writeText(linuxAutostartEntry(launcher.absolutePath))
    }

    internal fun linuxAutostartEntry(executablePath: String): String {
        require('\n' !in executablePath && '\r' !in executablePath && '\u0000' !in executablePath) {
            "Invalid control character in executable path"
        }
        return "[Desktop Entry]\n" +
            "Type=Application\n" +
            "Name=Yumi\n" +
            "Exec=${escapeDesktopExecArg(executablePath)} --tray\n" +
            "X-GNOME-Autostart-enabled=true\n"
    }

    internal fun escapeDesktopExecArg(value: String): String = buildString(value.length + 2) {
        append('"')
        for (ch in value) {
            when (ch) {
                '\\', '"', '$', '`' -> {
                    append('\\')
                    append(ch)
                }
                else -> append(ch)
            }
        }
        append('"')
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
        file.writeText(macLaunchAgentPlist(launcher.absolutePath))
    }

    internal fun macLaunchAgentPlist(executablePath: String): String {
        require('\n' !in executablePath && '\r' !in executablePath && '\u0000' !in executablePath) {
            "Invalid control character in executable path"
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n" +
            "<plist version=\"1.0\">\n<dict>\n" +
            "    <key>Label</key><string>app.yumi.desktop</string>\n" +
            "    <key>ProgramArguments</key>\n    <array>\n" +
            "        <string>${escapeXml(executablePath)}</string>\n        <string>--tray</string>\n    </array>\n" +
            "    <key>RunAtLoad</key><true/>\n" +
            "</dict>\n</plist>\n"
    }

    internal fun escapeXml(value: String): String = buildString(value.length) {
        for (ch in value) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
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
            User32.INSTANCE.PostMessage(window, 0x0086, WinDef.WPARAM(0), WinDef.LPARAM(0))
            User32.INSTANCE.PostMessage(window, 0x0086, WinDef.WPARAM(1), WinDef.LPARAM(0))
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
    internal fun diagnoseUnixSocket(socket: Path): String = when {
        !Files.exists(socket) -> "service_missing"
        !Files.isReadable(socket) || !Files.isWritable(socket) -> "service_denied"
        else -> "service_unavailable"
    }

    internal fun classifyWindowsPipeOpenError(lastError: Int): String = when (lastError) {
        ERROR_FILE_NOT_FOUND, ERROR_PATH_NOT_FOUND -> "service_missing"
        ERROR_ACCESS_DENIED -> "service_denied"
        else -> "service_unavailable"
    }

    internal fun readObjectOwnerSid(handle: WinNT.HANDLE): String? {
        val ownerRef = PointerByReference()
        val descriptorRef = PointerByReference()
        val status = Advapi32.INSTANCE.GetSecurityInfo(
            handle,
            AccCtrl.SE_OBJECT_TYPE.SE_KERNEL_OBJECT,
            WinNT.OWNER_SECURITY_INFORMATION,
            ownerRef,
            null,
            null,
            null,
            descriptorRef,
        )
        return try {
            if (status != WinError.ERROR_SUCCESS) return null
            val ownerPtr = ownerRef.value ?: return null
            runCatching { Advapi32Util.convertSidToStringSid(WinNT.PSID(ownerPtr)) }.getOrNull()
        } finally {
            descriptorRef.value?.let { Kernel32.INSTANCE.LocalFree(it) }
        }
    }

    internal fun openVerifiedPipe(
        pipeName: String,
        expectedOwnerSid: String = LOCAL_SYSTEM_SID,
        lastErrorOut: AtomicInteger? = null,
    ): WinNT.HANDLE {
        val handle = Kernel32.INSTANCE.CreateFile(
            pipeName,
            WinNT.GENERIC_READ or WinNT.GENERIC_WRITE,
            0,
            null,
            WinNT.OPEN_EXISTING,
            SECURITY_SQOS_PRESENT or SECURITY_ANONYMOUS,
            null,
        )
        if (handle == null || handle == WinBase.INVALID_HANDLE_VALUE) {
            val err = Kernel32.INSTANCE.GetLastError()
            lastErrorOut?.set(err)
            throw java.io.IOException("CreateFile failed for pipe ($err)")
        }
        val ownerSid = readObjectOwnerSid(handle)
        if (ownerSid != expectedOwnerSid) {
            Kernel32.INSTANCE.CloseHandle(handle)
            throw SecurityException("Untrusted pipe owner SID: ${ownerSid ?: "unknown"}")
        }
        return handle
    }

    internal fun diagnoseWindowsPipe(pipeName: String, expectedOwnerSid: String = LOCAL_SYSTEM_SID): String {
        val lastError = AtomicInteger(0)
        return try {
            val handle = openVerifiedPipe(pipeName, expectedOwnerSid, lastError)
            Kernel32.INSTANCE.CloseHandle(handle)
            // It opens now and did not a moment ago: the service is there and something else went
            // wrong in the exchange. Nothing here can say what, and pretending otherwise would be
            // worse than the general answer.
            "service_unavailable"
        } catch (_: SecurityException) {
            "service_denied"
        } catch (_: Exception) {
            classifyWindowsPipeOpenError(lastError.get())
        }
    }

    fun diagnoseService(): String {
        if (!isWindows) return diagnoseUnixSocket(Path.of(SOCKET_PATH))
        return diagnoseWindowsPipe(PIPE_NAME, LOCAL_SYSTEM_SID)
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

    private val sha256Pattern = Regex("^[0-9a-fA-F]{64}$")

    /**
     * Installs a downloaded update, on every platform by handing the verified package to the
     * system's own installer with one elevation prompt.
     *
     * On Windows the file is held open exclusively for reading while hashing and launching so
     * another process cannot swap it in between (TOCTOU). On unix the package is copied into a
     * root-owned `0700` staging directory, hashed there if [expectedSha256] is provided, and a
     * detached root worker waits for this JVM to exit before installing.
     */
    fun installUpdate(file: File, expectedSha256: String = "", relaunch: Boolean = true): Boolean {
        if (!file.isFile) return false
        if (expectedSha256.isNotBlank() && !sha256Pattern.matches(expectedSha256)) return false
        return when {
            isWindows -> installUpdateWindows(file, expectedSha256, relaunch)
            file.name.endsWith(".deb") -> launchUpdate(file, expectedSha256) { pkg -> "dpkg -i $pkg" }
            file.name.endsWith(".rpm") -> launchUpdate(file, expectedSha256) { pkg -> "rpm -U $pkg" }
            file.name.endsWith(".pkg") -> launchUpdate(file, expectedSha256) { pkg -> "installer -pkg $pkg -target /" }
            else -> false
        }
    }

    internal fun openLockedUpdateFile(file: File): WinNT.HANDLE? {
        val handle = Kernel32.INSTANCE.CreateFile(
            file.absolutePath,
            WinNT.GENERIC_READ,
            WinNT.FILE_SHARE_READ,
            null,
            WinNT.OPEN_EXISTING,
            WinNT.FILE_ATTRIBUTE_NORMAL,
            null,
        )
        return handle?.takeIf { it != WinBase.INVALID_HANDLE_VALUE }
    }

    internal fun sha256FromHandle(handle: WinNT.HANDLE): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        val bytesRead = IntByReference()
        while (true) {
            if (!Kernel32.INSTANCE.ReadFile(handle, buffer, buffer.size, bytesRead, null)) {
                throw java.io.IOException("ReadFile failed (${Kernel32.INSTANCE.GetLastError()})")
            }
            val n = bytesRead.value
            if (n <= 0) break
            digest.update(buffer, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    internal inline fun withLockedWindowsUpdateFile(
        file: File,
        expectedSha256: String,
        action: () -> Boolean,
    ): Boolean {
        val handle = openLockedUpdateFile(file) ?: return false
        return try {
            if (expectedSha256.isNotBlank()) {
                val actual = sha256FromHandle(handle)
                if (!actual.equals(expectedSha256, ignoreCase = true)) return false
            }
            action()
        } catch (_: Exception) {
            false
        } finally {
            Kernel32.INSTANCE.CloseHandle(handle)
        }
    }

    internal fun windowsUpdateParameters(directory: File, relaunch: Boolean, pid: Long = ProcessHandle.current().pid()): String {
        val restartArg = if (relaunch) " /YUMIRESTART=1" else ""
        val targetDir = directory.absolutePath.trimEnd('\\')
        return "/SILENT /NORESTART /YUMIUPDATE=1$restartArg /YUMIPID=$pid /DIR=\"$targetDir\""
    }

    private fun installUpdateWindows(file: File, expectedSha256: String, relaunch: Boolean): Boolean {
        val directory = installedDirectory() ?: return false
        return withLockedWindowsUpdateFile(file, expectedSha256) {
            val request = ShellAPI.SHELLEXECUTEINFO()
            request.lpVerb = "open"
            request.lpFile = file.absolutePath
            request.lpParameters = windowsUpdateParameters(directory, relaunch)
            request.nShow = WinUser.SW_SHOWNORMAL
            request.fMask = SEE_MASK_NOCLOSEPROCESS
            if (!Shell32.INSTANCE.ShellExecuteEx(request)) return@withLockedWindowsUpdateFile false
            request.hProcess?.let { Kernel32.INSTANCE.CloseHandle(it) }
            true
        }
    }

    internal fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    private fun quote(file: File) = shellQuote(file.absolutePath)

    internal fun buildUnixUpdateScript(
        file: File,
        expectedSha256: String,
        pid: Long,
        owner: String,
        currentServicePath: String,
        macOs: Boolean,
        installCommandForStagePath: (String) -> String,
    ): String {
        val ext = file.name.substringAfterLast('.', "").lowercase()
        val pkgName = if (ext.isNotEmpty() && ext.all(Char::isLetterOrDigit)) "update.$ext" else "update.pkg"
        val stagedExpr = "\"\$stage/$pkgName\""
        val copyLine = "cp -- ${shellQuote(file.absolutePath)} $stagedExpr && chmod 600 $stagedExpr || { rm -rf \"\$stage\"; exit 1; }"
        val hashLine = if (expectedSha256.isNotBlank()) {
            val verifier = if (macOs) "shasum -a 256 -c -" else "sha256sum -c -"
            "printf '%s  %s\\n' ${shellQuote(expectedSha256.lowercase())} $stagedExpr | $verifier >/dev/null 2>&1 || { rm -rf \"\$stage\"; exit 2; }"
        } else {
            ""
        }
        val installServiceStep = if (macOs && file.name.endsWith(".pkg")) {
            val installedSvc = shellQuote(MAC_POST_INSTALL_SERVICE)
            val fallbackSvc = shellQuote(currentServicePath)
            "svc=$installedSvc; [ -x \"\$svc\" ] || svc=$fallbackSvc; \"\$svc\" --install --owner $owner"
        } else {
            "${shellQuote(currentServicePath)} --install --owner $owner"
        }
        val installPkgStep = installCommandForStagePath(stagedExpr)
        val worker = "( trap 'rm -rf \"\$stage\"' EXIT; trap '' HUP; while kill -0 $pid 2>/dev/null; do sleep 1; done; $installPkgStep && { $installServiceStep; } ) >/dev/null 2>&1 </dev/null & exit 0"
        return buildList {
            add("stage=$(mktemp -d /tmp/yumi-update.XXXXXX) || exit 1")
            add("chmod 700 \"\$stage\" || { rm -rf \"\$stage\"; exit 1; }")
            add(copyLine)
            if (hashLine.isNotEmpty()) add(hashLine)
            add(worker)
        }.joinToString("; ")
    }

    // Stages the package as root synchronously while the prompt is open, then detaches the
    // install worker so it waits for this JVM to exit before replacing installed files.
    private fun launchUpdate(
        file: File,
        expectedSha256: String,
        installCommandForStagePath: (String) -> String,
    ): Boolean = runCatching {
        val currentService = serviceExecutable()
        val macPostInstall = File(MAC_POST_INSTALL_SERVICE).takeIf { isMac && file.name.endsWith(".pkg") && it.isFile }
        val ownerExe = currentService ?: macPostInstall ?: return false
        val owner = currentUserOwner(ownerExe) ?: return false
        if (!owner.all(Char::isDigit)) return false
        val serviceFallback = currentService?.absolutePath ?: MAC_POST_INSTALL_SERVICE
        val script = buildUnixUpdateScript(
            file = file,
            expectedSha256 = expectedSha256,
            pid = ProcessHandle.current().pid(),
            owner = owner,
            currentServicePath = serviceFallback,
            macOs = isMac,
            installCommandForStagePath = installCommandForStagePath,
        )
        elevatedRun(script) == ServiceRepair.Done
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
            ProcessBuilder("osascript", "-e", appleScript).redirectErrorStream(true).start()
        } else {
            ProcessBuilder("pkexec", "sh", "-c", command).redirectErrorStream(true).start()
        }
        val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().use { it.readText() } }
        val done = process.waitFor(10, TimeUnit.MINUTES)
        if (!done) {
            process.destroyForcibly()
            return ServiceRepair.Unavailable
        }
        val diagnostic = output.get(5, TimeUnit.SECONDS)
        val cancelled = process.exitValue() == 126 || diagnostic.contains("User canceled", ignoreCase = true)
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
    fun currentUserOwner(): String? = serviceExecutable()?.let(::currentUserOwner)

    private fun currentUserOwner(exe: File): String? = runCatching {
        val process = ProcessBuilder(exe.absolutePath, "--print-owner").start()
        if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); return@runCatching null }
        if (process.exitValue() != 0) return@runCatching null
        val text = process.inputStream.bufferedReader().use { it.readText() }.trim()
        when {
            isWindows -> text.takeIf { it.startsWith("S-") && it.length in 8..184 }
            else -> text.takeIf { it.isNotEmpty() && it.all(Char::isDigit) && it.length <= 10 }
        }
    }.getOrNull()

    internal fun waitForWindowsProcessSuccess(handle: WinNT.HANDLE?, timeoutMillis: Int = 60_000): ServiceRepair {
        val process = handle ?: return ServiceRepair.Unavailable
        return try {
            if (Kernel32.INSTANCE.WaitForSingleObject(process, timeoutMillis) != WinBase.WAIT_OBJECT_0) {
                return ServiceRepair.Unavailable
            }
            val exitCode = IntByReference()
            if (!Kernel32.INSTANCE.GetExitCodeProcess(process, exitCode)) {
                return ServiceRepair.Unavailable
            }
            if (exitCode.value == 0) ServiceRepair.Done else ServiceRepair.Unavailable
        } finally {
            Kernel32.INSTANCE.CloseHandle(process)
        }
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
        val owner = currentUserOwner(exe) ?: return ServiceRepair.Unavailable
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
            waitForWindowsProcessSuccess(request.hProcess, 60_000)
        }.getOrDefault(ServiceRepair.Unavailable)
    }

    internal const val PIPE_NAME = "\\\\.\\pipe\\YumiTunnel-v1"
    internal const val LOCAL_SYSTEM_SID = "S-1-5-18"
    private const val MAC_POST_INSTALL_SERVICE = "/Applications/Yumi.app/Contents/Resources/service/yumi-service"
    internal val SOCKET_PATH: String =
        if (System.getProperty("os.name") == "Mac OS X") "/var/run/yumi/tunnel.sock" else "/run/yumi/tunnel.sock"

    private const val ERROR_FILE_NOT_FOUND = 2
    private const val ERROR_PATH_NOT_FOUND = 3
    private const val ERROR_ACCESS_DENIED = 5
    private const val ERROR_CANCELLED = 1223
    private const val SECURITY_SQOS_PRESENT = 0x00100000
    private const val SECURITY_ANONYMOUS = 0x00000000

    /** SEE_MASK_NOCLOSEPROCESS: keeps the process handle alive so the wait above has something. */
    private const val SEE_MASK_NOCLOSEPROCESS = 0x00000040
}

private interface Kernel32Extra : StdCallLibrary {
    fun CancelIoEx(hFile: WinNT.HANDLE, lpOverlapped: Pointer?): Boolean

    companion object {
        val INSTANCE: Kernel32Extra = Native.load("kernel32", Kernel32Extra::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }
}

private interface Dwm : Library {
    fun DwmSetWindowAttribute(window: WinDef.HWND, attribute: Int, value: IntArray, size: Int): Int

    companion object {
        val INSTANCE: Dwm = Native.load("dwmapi", Dwm::class.java)
    }
}
