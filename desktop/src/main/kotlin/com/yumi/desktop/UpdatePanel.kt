package com.yumi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yumi.desktop.model.DesktopSettings
import com.yumi.desktop.update.*
import java.awt.Desktop
import java.net.URI

@Composable
fun UpdatePanel(m: DesktopModel, settings: DesktopSettings, onInstall: () -> Unit, updater: DesktopUpdater = m.updates) {
    val state by updater.state.collectAsState()
    val release = when (val current = state) {
        is DesktopUpdateState.Available -> current.release
        is DesktopUpdateState.Downloading -> current.release
        is DesktopUpdateState.Ready -> current.update.release
        is DesktopUpdateState.Failed -> current.release
        else -> null
    }
    GlassPanel(Modifier.fillMaxWidth().testTag("updates")) {
        SectionTitle(m.text("Обновления", "Updates"), "Yumi Desktop $AppVersion")
        Text(when (val current = state) {
            DesktopUpdateState.Idle -> m.text("Новые версии Yumi — прямо здесь", "New Yumi versions, right here")
            DesktopUpdateState.Checking -> m.text("Проверяю обновления…", "Checking for updates…")
            is DesktopUpdateState.Current -> m.text("Установлена последняя версия", "You're up to date")
            is DesktopUpdateState.Available -> m.text("Доступна версия ${current.release.version}", "Version ${current.release.version} is available")
            is DesktopUpdateState.Downloading -> {
                val percent = (current.downloaded * 100 / current.total.coerceAtLeast(1)).coerceIn(0, 100)
                m.text("Скачивается ${current.release.version} · $percent %", "Downloading ${current.release.version} · $percent %")
            }
            is DesktopUpdateState.Ready -> m.text("Версия ${current.update.release.version} готова к установке", "Version ${current.update.release.version} is ready to install")
            is DesktopUpdateState.Failed -> if (current.integrity)
                m.text("Файл обновления не прошёл проверку. Скачай его заново.", "The update file failed verification. Download it again.")
                else m.text("Не удалось обновиться. Проверь соединение и повтори.", "Update failed. Check your connection and retry.")
        }, style = MaterialTheme.typography.bodyLarge)
        if (updater.installed) SwitchRow(
            m.text("Обновлять автоматически", "Update automatically"), settings.autoUpdate,
            m.text("Проверять и скачивать в фоне. Готовое обновление установится при выходе из Yumi или по кнопке перезапуска. Windows запросит разрешение на установку.",
                "Check and download in the background. Install on exit or restart now. Windows will ask for permission to install."),
        ) { m.changeDesktop(settings.copy(autoUpdate = it)) }
        else Text(m.text("Автообновление работает в установленном приложении. Для этой копии новую версию можно скачать со страницы выпуска.",
            "Automatic updates work in the installed application. Download a new version for this copy from its release page."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state is DesktopUpdateState.Downloading) {
            val download = state as DesktopUpdateState.Downloading
            LinearProgressIndicator(progress = { (download.downloaded.toFloat() / download.total.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when (state) {
                is DesktopUpdateState.Ready -> AccentButton(m.text("Перезапустить и обновить", "Restart and update"), icon = Icons.Rounded.SystemUpdateAlt, onClick = onInstall)
                is DesktopUpdateState.Downloading -> QuietButton(m.text("Отмена", "Cancel")) { updater.cancelDownload() }
                is DesktopUpdateState.Available -> AccentButton(m.text("Скачать обновление", "Download update"), icon = Icons.Rounded.SystemUpdateAlt) {
                    if (updater.installed) updater.download() else release?.let { Desktop.getDesktop().browse(URI(it.pageUrl)) }
                }
                else -> QuietButton(m.text("Проверить обновления", "Check for updates"), icon = Icons.Rounded.Refresh, enabled = state !is DesktopUpdateState.Checking) {
                    if (release != null && updater.installed) updater.download() else updater.check()
                }
            }
            if (release != null) TextButton(onClick = { Desktop.getDesktop().browse(URI(release.pageUrl)) }) { Text(m.text("Что нового", "What's new")) }
        }
    }
}
