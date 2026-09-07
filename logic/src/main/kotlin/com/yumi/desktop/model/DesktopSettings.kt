package com.yumi.desktop.model

import kotlinx.serialization.Serializable
import java.net.IDN
import com.mydrop.vpn.core.net.isNumericAddress

@Serializable enum class SplitMode { ExceptSelected, OnlySelected }
@Serializable data class DesktopSettings(
    val splitMode: SplitMode = SplitMode.ExceptSelected,
    val applications: List<String> = emptyList(),
    val domains: List<String> = emptyList(),
    val networks: List<String> = emptyList(),
    val launchAtLogin: Boolean = false,
    /** Kept here rather than in the shared [com.mydrop.vpn.core.model.AppSettings]: the phone
     *  takes its accent from the system wallpaper, so this is a window's answer to a question a
     *  phone does not ask. */
    val palette: Palette = Palette.Glacier,
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
    fun application(raw: String): String {
        val path = raw.trim().replace('\\', '/')
        require(Regex("^[A-Za-z]:/.*\\.exe$", RegexOption.IGNORE_CASE).matches(path) && path.none { it == '\n' || it == '\r' || it == '\u0000' }) { "Choose an absolute .exe path" }
        return path
    }
}
