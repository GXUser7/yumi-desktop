package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.PingMode
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

class SettingsRepository(
    filesDir: File,
    scope: CoroutineScope,
    onWriteFailure: (Throwable) -> Unit = {},
) {

    private val store = JsonStore(
        file = File(filesDir, "settings.json"),
        serializer = AppSettings.serializer(),
        defaultValue = AppSettings(
            pingMode = PingMode.UrlTest,
            pingModeUrlTestDefaultApplied = true,
        ),
        scope = scope,
        onWriteFailure = onWriteFailure,
    )

    init {
        if (!store.value.pingModeUrlTestDefaultApplied || store.value.autoFailover) {
            store.update { current ->
                if (current.pingModeUrlTestDefaultApplied && !current.autoFailover) {
                    current
                } else {
                    current.copy(
                        pingMode = if (current.pingModeUrlTestDefaultApplied) current.pingMode else PingMode.UrlTest,
                        pingModeUrlTestDefaultApplied = true,
                        autoFailover = false,
                    )
                }
            }
        }
    }

    val settings: StateFlow<AppSettings> = store.state

    val value: AppSettings get() = store.value

    fun update(transform: (AppSettings) -> AppSettings) = store.update { current ->
        // Auto-failover / auto-replace is intentionally excluded from Yumi Desktop.
        transform(current).copy(autoFailover = false)
    }
}
