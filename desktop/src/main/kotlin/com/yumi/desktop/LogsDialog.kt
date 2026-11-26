package com.yumi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.mydrop.vpn.shared.R
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

private enum class LogSource { App, Service }

@Composable
internal fun LogsDialog(m: DesktopModel, onClose: () -> Unit) {
    val s = m.strings
    val appEntries by m.logs.entries.collectAsState()
    val serviceLogs by m.serviceLogs.collectAsState()
    val refreshing by m.serviceLogsRefreshing.collectAsState()
    var source by remember { mutableStateOf(LogSource.Service) }
    var copiedNotice by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { m.refreshServiceLogs() }

    val lines = remember(source, appEntries, serviceLogs) {
        when (source) {
            LogSource.App -> appEntries
            LogSource.Service -> serviceLogs.lineSequence().filter { it.isNotBlank() }.toList()
        }
    }

    Dialog(onDismissRequest = onClose) {
        GlassPanel(
            Modifier
                .widthIn(min = 560.dp, max = 780.dp)
                .heightIn(min = 420.dp, max = 660.dp),
        ) {
            Column(
                Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        SectionTitle(s.get(R.string.logs_title))
                        Text(
                            s.get(R.string.settings_logs_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    QuietButton(
                        if (refreshing) "…" else s.get(R.string.logs_refresh),
                        icon = Icons.Rounded.Refresh,
                        enabled = !refreshing,
                    ) { m.refreshServiceLogs() }
                    QuietButton(
                        if (copiedNotice) m.text("Скопировано", "Copied") else s.get(R.string.action_copy_logs),
                        icon = Icons.Rounded.ContentCopy,
                        enabled = lines.isNotEmpty(),
                    ) {
                        val content = lines.joinToString("\n")
                        if (content.isNotEmpty()) {
                            runCatching {
                                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(content), null)
                                copiedNotice = true
                            }
                        }
                    }
                    PanelIconButton(Icons.Rounded.Close, s.get(R.string.action_close), size = 40.dp, onClick = onClose)
                }

                ChoiceRow(
                    label = null,
                    options = LogSource.entries,
                    selected = source,
                    text = {
                        when (it) {
                            LogSource.App -> s.get(R.string.logs_source_app)
                            LogSource.Service -> s.get(R.string.logs_source_service)
                        }
                    },
                    onSelect = {
                        source = it
                        copiedNotice = false
                        if (it == LogSource.Service) m.refreshServiceLogs()
                    },
                )

                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(380.dp)
                        .clip(RowShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                        .padding(14.dp),
                ) {
                    if (lines.isEmpty()) {
                        Text(
                            s.get(R.string.logs_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    } else {
                        SelectionContainer(Modifier.fillMaxSize()) {
                            LazyColumn(
                                Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                items(lines) { line ->
                                    Text(
                                        line,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
