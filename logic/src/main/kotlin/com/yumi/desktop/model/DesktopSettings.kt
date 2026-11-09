package com.yumi.desktop.model

import kotlinx.serialization.Serializable
import java.net.IDN
import com.mydrop.vpn.core.net.isNumericAddress

@Serializable enum class SplitMode { ExceptSelected, OnlySelected }
@Serializable data class DesktopSettings(
    val splitMode: SplitMode = SplitMode.ExceptSelected,
    /** Process names — `chrome.exe` — the way Clash keeps them. Older settings hold full paths,
     *  which [RuleValidation.application] turns into names as they are read. */
    val applications: List<String> = emptyList(),
    val domains: List<String> = emptyList(),
    val networks: List<String> = emptyList(),
    val launchAtLogin: Boolean = false,
    /** Kept here rather than in the shared [com.mydrop.vpn.core.model.AppSettings]: the phone
     *  takes its accent from the system wallpaper, so this is a window's answer to a question a
     *  phone does not ask. */
    val palette: Palette = Palette.Glacier,
    /** Block everything outside the tunnel until an explicit disconnect. Off by default: a kill
     *  switch nobody asked for looks exactly like a broken internet connection. */
    val killSwitch: Boolean = false,
    /** Russian sites and addresses around the tunnel, by the geo databases, above every
     *  application rule. Inert until the databases are downloaded. */
    val bypassRussia: Boolean = true,
    /** The physical adapter the core sends proxy traffic out of; empty follows the Windows
     *  default route. A name from the tunnel service's own adapter list. */
    val outboundAdapter: String = "",
    /** The shapes behind the glass drift. Off holds them still and costs nothing at all. */
    val liveBackdrop: Boolean = true,
)

object RuleValidation {
    fun domain(raw: String): String {
        val value = IDN.toASCII(raw.trim().removePrefix("*.").trimEnd('.')).lowercase()
        require(value.length in 1..253 && !isNumericAddress(value) && value.split('.').all {
            it.length in 1..63 && it.first().isLetterOrDigit() && it.last().isLetterOrDigit() && it.all { c -> c.isLetterOrDigit() || c == '-' }
        }) { "Invalid domain" }
        return value
    }
    fun network(raw: String): String {
        val parts = raw.trim().split('/')
        require(parts.size in 1..2 && isNumericAddress(parts[0])) { "Invalid IP address" }
        if (parts.size == 2) require(parts[1].toIntOrNull() in 0..(if (':' in parts[0]) 128 else 32)) { "Invalid prefix" }
        return raw.trim()
    }

    /**
     * An application as a process name: `Telegram.exe`.
     *
     * Accepts what a person types — `telegram`, `Telegram.exe`, a whole path pasted from Explorer —
     * and keeps only the file name, with `.exe` on the end so the list reads the way Task Manager
     * does. The case is kept as given: a name picked from the list is spelt as it is on disk, and
     * the service offers a hand-typed one in the spellings people use.
     */
    fun application(raw: String): String {
        val trimmed = raw.trim().trim('"')
        require(trimmed.none { it == '\n' || it == '\r' || it == '\u0000' }) { "Invalid application" }
        val name = trimmed.substringAfterLast('\\').substringAfterLast('/')
        require(name.isNotBlank() && name.trim('.').isNotEmpty() && name.length <= 255) { "Invalid application" }
        require(name.none { it in "<>:\"|?*" || it.code < 0x20 }) { "Invalid application" }
        return if (name.endsWith(".exe", ignoreCase = true)) name else "$name.exe"
    }
}
