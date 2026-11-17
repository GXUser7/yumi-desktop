package com.yumi.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.MenuComposable
import androidx.compose.ui.window.MenuScope
import androidx.compose.ui.window.setContent
import com.mydrop.vpn.data.ProfileState
import com.mydrop.vpn.data.Strings
import com.mydrop.vpn.shared.R
import java.awt.Frame
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

/** Compose's Tray action needs a double-click on Windows; opening Yumi also accepts a single click. */
@Composable
@ComposableOpenTarget(-1)
internal fun YumiTray(
    icon: Painter,
    onOpen: () -> Unit,
    onTrayFailed: () -> Unit = {},
    menu: @Composable @MenuComposable MenuScope.() -> Unit,
) {
    val currentOnOpen by rememberUpdatedState(onOpen)
    val currentOnTrayFailed by rememberUpdatedState(onTrayFailed)
    val currentMenu by rememberUpdatedState(menu)
    val systemTray = remember { runCatching { SystemTray.getSystemTray() }.getOrNull() }
    if (systemTray == null) {
        LaunchedEffect(Unit) { currentOnTrayFailed() }
        return
    }
    val image = remember(icon) {
        runCatching {
            val size = systemTray.trayIconSize
            icon.toAwtImage(Density(1f), LayoutDirection.Ltr, Size(size.width.toFloat(), size.height.toFloat()))
        }.getOrNull()
    }
    if (image == null) {
        LaunchedEffect(Unit) { currentOnTrayFailed() }
        return
    }
    val tray = remember {
        runCatching {
            TrayIcon(image, "Yumi").apply {
                isImageAutoSize = true
                addActionListener { currentOnOpen() }
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(event: MouseEvent) {
                        if (event.button == MouseEvent.BUTTON1 && event.clickCount == 1) currentOnOpen()
                    }
                })
            }
        }.getOrNull()
    }
    if (tray == null) {
        LaunchedEffect(Unit) { currentOnTrayFailed() }
        return
    }
    val parent = rememberCompositionContext()
    SideEffect { if (tray.image != image) tray.image = image }
    DisposableEffect(tray) {
        val popup = PopupMenu()
        tray.popupMenu = popup
        val composition = popup.setContent(parent) { currentMenu() }
        var added = false
        try {
            systemTray.add(tray)
            added = true
        } catch (_: Exception) {
            composition.dispose()
            tray.popupMenu = null
            currentOnTrayFailed()
            return@DisposableEffect onDispose {}
        }
        onDispose {
            if (added) runCatching { systemTray.remove(tray) }
            composition.dispose()
            tray.popupMenu = null
        }
    }
}

/** Preserve maximization while clearing the iconified bit, then focus the now-visible window. */
internal fun restoreApplicationWindow(window: Frame) {
    window.isVisible = true
    window.extendedState = window.extendedState and Frame.ICONIFIED.inv()
    window.toFront()
    window.requestFocus()
}

private const val MAX_TRAY_SERVERS = 40

@Composable
@MenuComposable
internal fun MenuScope.TrayServers(
    profiles: ProfileState,
    strings: Strings,
    pinging: Boolean,
    switching: Boolean,
    onSelect: (String) -> Unit,
    onPing: () -> Unit,
) = TrayServers(
    profiles = profiles,
    strings = strings,
    pinging = pinging,
    switching = switching,
    activeNodeId = null,
    onSelect = onSelect,
    onOpen = null,
    onPing = onPing,
)

@Composable
@MenuComposable
internal fun MenuScope.TrayServers(
    profiles: ProfileState,
    strings: Strings,
    pinging: Boolean,
    switching: Boolean,
    activeNodeId: String?,
    onSelect: (String) -> Unit,
    onOpen: (() -> Unit)? = null,
    onPing: () -> Unit,
) {
    val nodes = profiles.visibleNodes
    val currentId = activeNodeId ?: profiles.selectedNodeId
    val displayed = if (nodes.size <= MAX_TRAY_SERVERS) nodes else {
        val head = nodes.take(MAX_TRAY_SERVERS)
        val active = currentId?.let { id -> nodes.firstOrNull { it.id == id } }
        if (active != null && head.none { it.id == active.id }) nodes.take(MAX_TRAY_SERVERS - 1) + active else head
    }
    Menu(strings.text("Серверы", "Servers")) {
        if (displayed.isEmpty()) {
            Item(strings.text("Нет серверов", "No servers"), enabled = false, onClick = {})
        } else {
            // Native AWT menus have no radio items. Keep the current selection fixed.
            for (node in displayed) key(node.id) {
                val latency = profiles.latencies[node.id]
                val ping = when {
                    latency == null -> "—"
                    latency.failed -> strings.get(R.string.servers_latency_failed)
                    else -> strings.get(R.string.servers_latency_value, latency.millis)
                }
                val selected = node.id == currentId
                CheckboxItem(text = "${node.name} · $ping", checked = selected,
                    enabled = !selected && !switching, onCheckedChange = { if (it) onSelect(node.id) })
            }
            if (nodes.size > displayed.size) {
                Item(strings.text("Открыть Yumi…", "Open Yumi…"), onClick = { onOpen?.invoke() })
            }
            Separator()
            Item(strings.text(
                if (pinging) "Проверить задержку заново" else "Проверить задержку",
                if (pinging) "Restart latency check" else "Measure latency",
            ), onClick = onPing)
        }
    }
}
