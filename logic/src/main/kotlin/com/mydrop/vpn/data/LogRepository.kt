package com.mydrop.vpn.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Diagnostics stay in memory; subscription URLs, node names and exception bodies are not logged. */
class LogRepository(private val strings: Strings = Strings()) {
    private val lines = MutableStateFlow<List<String>>(emptyList())
    val entries = lines.asStateFlow()
    fun info(message: String) = append("Info", "Operation completed")
    fun info(id: Int, vararg args: Any) = append("Info", "Event $id")
    fun warn(id: Int, vararg args: Any) = append("Warning", "Event $id")
    fun debug(id: Int, vararg args: Any) = append("Debug", "Event $id")
    /** Diagnostic detail that the phone writes to its journal file; the window has no such file. */
    fun trace(tag: String, message: String) = RuntimeLog.i(tag, message)
    private fun append(level: String, message: String) = lines.update { (it + "$level: $message").takeLast(200) }
}

internal object RuntimeLog {
    fun i(tag: String, message: String) = Unit
    fun w(tag: String, message: String, error: Throwable? = null) = Unit
}
