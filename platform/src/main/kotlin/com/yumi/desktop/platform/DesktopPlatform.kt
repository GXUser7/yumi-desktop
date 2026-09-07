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
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface TunnelBackend {
    suspend fun request(request: ServiceRequest): ServiceStatus
}

class WindowsTunnelBackend : TunnelBackend {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    override suspend fun request(request: ServiceRequest): ServiceStatus = withContext(Dispatchers.IO) {
        if (!DesktopPlatform.isWindows) return@withContext ServiceStatus(state = "unavailable", error = "platform_not_supported")
        try {
            RandomAccessFile(DesktopPlatform.PIPE_NAME, "rw").use { pipe ->
                val bytes = json.encodeToString(request).toByteArray(Charsets.UTF_8)
                require(bytes.size in 1..1_048_576)
                pipe.writeInt(bytes.size); pipe.write(bytes)
                val size = pipe.readInt(); require(size in 1..1_048_576)
                val reply = ByteArray(size); pipe.readFully(reply)
                json.decodeFromString<ServiceStatus>(reply.decodeToString()).also { require(it.version == 1) }
            }
        } catch (_: Exception) {
            // Everything used to land here as one code, and the screen then advised installing a
            // service that was already installed and running. The reason is asked for now, once,
            // and only on the failing path.
            ServiceStatus(state = "unavailable", error = DesktopPlatform.diagnoseService())
        }
    }
}

data class LocalAddress(val label: String, val address: Inet4Address)

/** What came of offering to re-register the tunnel service; see [DesktopPlatform.repairService]. */
enum class ServiceRepair { Done, Declined, Unavailable }

object DesktopPlatform {
    val isWindows = System.getProperty("os.name").startsWith("Windows")
    fun dataDirectory(): File {
        System.getProperty("yumi.dataDir")?.let { return File(it).also(File::mkdirs) }
        val base = if (isWindows) System.getenv("LOCALAPPDATA") else null
        return File(base ?: File(System.getProperty("user.home"), ".local/share").path, "YumiDesktop").also(File::mkdirs)
    }
    fun localAddresses(): List<LocalAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter {
            it.isUp && !it.isLoopback && !Regex("tun|tap|vpn|wireguard|wintun|hyper-v|vmware|virtual", RegexOption.IGNORE_CASE).containsMatchIn(it.displayName.orEmpty())
        }.flatMap { network -> network.inetAddresses.toList().filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress && !it.isLoopbackAddress }
            .map { LocalAddress(network.displayName + " · " + it.hostAddress, it) } }
    }.getOrDefault(emptyList())

    fun setLaunchAtLogin(enabled: Boolean) {
        check(isWindows)
        val key = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        if (!enabled) {
            if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, key, "YumiDesktop"))
                Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, key, "YumiDesktop")
            return
        }
        val launcher = System.getProperty("jpackage.app-path")?.let(::File)
        require(launcher?.isFile == true) { "Install Yumi before enabling startup" }
        Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, key, "YumiDesktop", "\"${launcher.absolutePath}\" --tray")
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
     * Why the pipe would not open, asked only after it has already failed.
     *
     * `RandomAccessFile` throws `FileNotFoundException` both for a pipe that is not there and for
     * one that refused us, and tells the two apart only in the message — which Windows writes in
     * the user's own language. So the classification is done by asking Windows again, through an
     * API that answers in numbers.
     *
     * The distinction is the whole point. "Not there" means the service is not installed or not
     * running, and the honest advice is to install Yumi. "Refused" means it is running and belongs
     * to another account — advice to install it again is then not merely useless, it sends someone
     * to reinstall a thing that is working. See [repairService] for what is offered instead.
     */
    fun diagnoseService(): String {
        if (!isWindows) return "platform_not_supported"
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
        val launcher = System.getProperty("jpackage.app-path")?.let(::File) ?: return null
        return File(launcher.parentFile, "service\\yumi-service.exe").takeIf { it.isFile }
    }

    /**
     * This account's SID, asked of the service binary rather than worked out here.
     *
     * It is the same code path the installer uses and the same one the service compares against,
     * so there is exactly one definition of "who am I" in the product. Doing it again in Kotlin
     * would be a second definition, and the entire bug this repairs was two answers to that
     * question disagreeing.
     */
    fun currentUserSid(): String? {
        val exe = serviceExecutable() ?: return null
        return runCatching {
            val process = ProcessBuilder(exe.absolutePath, "--print-owner").start()
            val text = process.inputStream.bufferedReader().use { it.readText() }.trim()
            process.waitFor(5, TimeUnit.SECONDS)
            text.takeIf { it.startsWith("S-") && it.length in 8..184 }
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
        val sid = currentUserSid() ?: return ServiceRepair.Unavailable
        return runCatching {
            val request = ShellAPI.SHELLEXECUTEINFO()
            request.lpVerb = "runas"
            request.lpFile = exe.absolutePath
            request.lpParameters = "--install --owner $sid"
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
