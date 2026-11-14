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
    menu: @Composable @MenuComposable MenuScope.() -> Unit,
) {
    val currentOnOpen by rememberUpdatedState(onOpen)
    val currentMenu by rememberUpdatedState(menu)
    val systemTray = remember { SystemTray.getSystemTray() }
    val image = remember(icon) {
        val size = systemTray.trayIconSize
        icon.toAwtImage(Density(1f), LayoutDirection.Ltr, Size(size.width.toFloat(), size.height.toFloat()))
    }
    val tray = remember {
        TrayIcon(image, "Yumi").apply {
            isImageAutoSize = true
            addActionListener { currentOnOpen() }
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) {
                    if (event.button == MouseEvent.BUTTON1 && event.clickCount == 1) currentOnOpen()
                }
            })
        }
    }
    val parent = rememberCompositionContext()
    SideEffect { if (tray.image != image) tray.image = image }
    DisposableEffect(tray) {
        val popup = PopupMenu()
        tray.popupMenu = popup
        val composition = popup.setContent(parent) { currentMenu() }
        try { systemTray.add(tray) }
        catch (failure: Exception) {
            composition.dispose()
            tray.popupMenu = null
            throw failure
        }
        onDispose {
            systemTray.remove(tray)
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

@Composable
@MenuComposable
internal fun MenuScope.TrayServers(
    profiles: ProfileState,
    strings: Strings,
    pinging: Boolean,
    switching: Boolean,
    onSelect: (String) -> Unit,
    onPing: () -> Unit,
) {
    val nodes = profiles.visibleNodes
    Menu(strings.text("Серверы", "Servers")) {
        if (nodes.isEmpty()) {
            Item(strings.text("Нет серверов", "No servers"), enabled = false, onClick = {})
        } else {
            // Native AWT menus have no radio items. Keep the current selection fixed.
            for (node in nodes) key(node.id) {
                val latency = profiles.latencies[node.id]
                val ping = when {
                    latency == null -> "—"
                    latency.failed -> strings.get(R.string.servers_latency_failed)
                    else -> strings.get(R.string.servers_latency_value, latency.millis)
                }
                val selected = node.id == profiles.selectedNodeId
                CheckboxItem(text = "${node.name} · $ping", checked = selected,
                    enabled = !selected && !switching, onCheckedChange = { if (it) onSelect(node.id) })
            }
            Separator()
            Item(strings.text(
                if (pinging) "Проверить задержку заново" else "Проверить задержку",
                if (pinging) "Restart latency check" else "Measure latency",
            ), onClick = onPing)
        }
    }
}
