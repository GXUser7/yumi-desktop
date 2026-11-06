package com.yumi.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yumi.desktop.model.RuleValidation
import com.yumi.desktop.platform.AppCatalog
import com.yumi.desktop.platform.DesktopApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/*
 * Choosing applications for split tunnelling, the way Clash lets you: type a name, or pick it.
 *
 * The list is everything Yumi could find on the machine — what is running now first, then what is
 * installed — with the program's own icon, the name people know it by and the process name the
 * rule is written with. Typing filters it; typing something it does not have offers to add exactly
 * what was typed as a process name, so a program that is not installed yet, or lives somewhere no
 * list reaches, is one Enter away. The file dialog is still there for the rare case, as the last
 * resort it should always have been.
 */

@Composable
fun AppPickerDialog(m: DesktopModel, selected: List<String>, onDone: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var chosen by remember { mutableStateOf(selected) }
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<DesktopApp>?>(null) }
    LaunchedEffect(Unit) { apps = m.discoverApps() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val chosenKeys = chosen.map { it.lowercase() }.toSet()

    val visible = remember(apps, query) {
        val all = apps.orEmpty()
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) all else all.filter { needle in it.name.lowercase() || needle in it.process.lowercase() }
    }
    // What typing would add: the text as a process name, unless the list already has it.
    val typed = remember(query, apps) {
        runCatching { RuleValidation.application(query) }.getOrNull()
            ?.takeIf { query.isNotBlank() && apps.orEmpty().none { a -> a.process.equals(it, true) } }
    }
    fun toggle(process: String) {
        chosen = if (process.lowercase() in chosenKeys) chosen.filterNot { it.equals(process, true) } else chosen + process
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.width(620.dp).heightIn(max = 720.dp),
            shape = RoundedCornerShape(Radii.extraLargeIncreased),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Kicker(m.text("Раздельное туннелирование", "Split tunnelling"))
                    Text(m.text("Приложения", "Applications"), style = MaterialTheme.typography.displaySmall)
                }
                Box(Modifier.onPreviewKeyEvent { event ->
                    // Enter adds what was typed, the way a command palette would.
                    if (event.key == Key.Enter && typed != null) { toggle(typed); query = ""; true } else false
                }) {
                    GlassField(
                        query,
                        { query = it },
                        Modifier.fillMaxWidth().focusRequester(focus),
                        placeholder = m.text("Найти или вписать имя, например chrome.exe", "Search, or type a name like chrome.exe"),
                        leading = Icons.Rounded.Search,
                    )
                }
                if (typed != null) PickerRow(
                    icon = null,
                    title = m.text("Добавить «$typed»", "Add “$typed”"),
                    subtitle = m.text("Правило по имени процесса — как в Clash", "A rule by process name, as in Clash"),
                    checked = typed.lowercase() in chosenKeys,
                    leading = Icons.Rounded.Add,
                ) { toggle(typed); query = "" }

                Box(Modifier.weight(1f, fill = false).heightIn(min = 120.dp)) {
                    val list = rememberLazyListState()
                    when (val found = apps) {
                        null -> Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                            LoadingIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                        else -> LazyColumn(state = list, modifier = Modifier.fillMaxWidth().padding(end = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            // What was chosen but is nowhere on the list — typed by hand, or not
                            // installed right now — stays visible so it can be taken off again.
                            val loose = chosen.filter { c -> found.none { it.process.equals(c, true) } }
                            if (query.isBlank() && loose.isNotEmpty()) {
                                item { Kicker(m.text("Добавлено вручную", "Added by hand"), Modifier.padding(top = 4.dp, bottom = 4.dp)) }
                                items(loose, key = { "loose-$it" }) { process ->
                                    PickerRow(null, AppCatalog.prettify(process), process, checked = true, leading = Icons.Rounded.Terminal) { toggle(process) }
                                }
                            }
                            val running = visible.filter { it.running }
                            val installed = visible.filter { !it.running }
                            if (running.isNotEmpty()) {
                                item { Kicker(m.text("Запущены сейчас", "Running now"), Modifier.padding(top = 8.dp, bottom = 4.dp)) }
                                items(running, key = { "run-" + it.process.lowercase() }) { app ->
                                    PickerRow(rememberAppIcon(app.path), app.name, app.process, app.process.lowercase() in chosenKeys) { toggle(app.process) }
                                }
                            }
                            if (installed.isNotEmpty()) {
                                item { Kicker(m.text("Установлены", "Installed"), Modifier.padding(top = 8.dp, bottom = 4.dp)) }
                                items(installed, key = { "inst-" + it.process.lowercase() }) { app ->
                                    PickerRow(rememberAppIcon(app.path), app.name, app.process, app.process.lowercase() in chosenKeys) { toggle(app.process) }
                                }
                            }
                            if (visible.isEmpty() && typed == null) item {
                                Text(
                                    m.text("Ничего не нашлось. Впиши имя процесса — например, game.exe.", "Nothing found. Type a process name — game.exe, for example."),
                                    Modifier.padding(16.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = {
                        val chooser = JFileChooser()
                        chooser.fileFilter = FileNameExtensionFilter(m.text("Программы Windows (*.exe)", "Windows programs (*.exe)"), "exe")
                        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                            runCatching { RuleValidation.application(chooser.selectedFile.name) }.getOrNull()?.let { name ->
                                if (name.lowercase() !in chosenKeys) chosen = chosen + name
                            }
                        }
                    }) {
                        Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(m.text("Обзор…", "Browse…"))
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        m.text("Выбрано: ${chosen.size}", "Chosen: ${chosen.size}"),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onDismiss) { Text(m.text("Отмена", "Cancel")) }
                    AccentButton(m.text("Готово", "Done")) { onDone(chosen) }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(
    icon: ImageBitmap?,
    title: String,
    subtitle: String,
    checked: Boolean,
    leading: androidx.compose.ui.graphics.vector.ImageVector = Icons.Rounded.Apps,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val container by animateColorAsState(
        if (checked) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
        colorTween(),
        label = "picker-row",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .pressMorph(interaction)
            .clip(RowShape)
            .background(container)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AppIcon(icon, leading)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Checkbox(checked, { onClick() })
    }
}

/** The program's icon on a soft plate, or a generic one where the program has none to give. */
@Composable
fun AppIcon(icon: ImageBitmap?, fallback: androidx.compose.ui.graphics.vector.ImageVector = Icons.Rounded.Apps) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Image(icon, null, Modifier.size(28.dp))
        else Icon(fallback, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The icon Explorer shows for [path], loaded off the UI thread and remembered. */
@Composable
fun rememberAppIcon(path: String?): ImageBitmap? {
    val icon by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { AppCatalog.icon(path, 32)?.toComposeImageBitmap() }
    }
    return icon
}
