package com.yumi.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AltRoute
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mydrop.vpn.core.format.ValueAndUnit
import com.mydrop.vpn.core.format.daysUntil
import com.mydrop.vpn.core.format.formatDuration
import com.mydrop.vpn.core.model.*
import com.mydrop.vpn.pairing.*
import com.mydrop.vpn.shared.R
import com.mydrop.vpn.ui.components.*
import com.mydrop.vpn.ui.theme.LocalSemanticColors
import com.mydrop.vpn.ui.theme.MonoStyle
import com.yumi.desktop.model.*
import kotlinx.coroutines.delay

/*
 * The window, in YouCloud's material.
 *
 * Shapes drift behind everything and every surface is matte glass over them. Three things still
 * carry the family resemblance with the phone and the television: the poster headline the tunnel
 * screen opens with, the rounded figure the tunnel lives inside, and the one loud control beside
 * it. What the window adds — and the phone cannot — is a second column, so the numbers a phone
 * hides behind a drag gesture are simply on screen.
 */

/** Screen margin, and the gap between the dock, the main column and the aside. */
private val Inset = 18.dp

/** Inner padding of the connection headline tile. */
private val TileRim = 18.dp

/** Corner radius of the flow figure: the phone's, and the scale's extra-extra-large. */
private val FigureShape = RoundedCornerShape(Radii.extraExtraLarge)

private val DialogShape = RoundedCornerShape(Radii.extraLargeIncreased)

enum class Page(val ru: String, val en: String, val icon: ImageVector) {
    Connect("Подключение", "Connection", Icons.Rounded.Shield),
    Servers("Серверы", "Servers", Icons.Rounded.Dns),
    Subscriptions("Подписки", "Subscriptions", Icons.Rounded.Cloud),
    Routing("Туннелирование", "Split tunneling", Icons.AutoMirrored.Rounded.AltRoute),
    Speed("Тест скорости", "Speed test", Icons.Rounded.Speed),
    Settings("Настройки", "Settings", Icons.Rounded.Settings),
}

@Composable fun YumiApp(model: DesktopModel, initialPage: Page = Page.Connect, onInstallUpdate: () -> Unit = {}) {
    val settings by model.settings.settings.collectAsState()
    val desktop by model.desktop.state.collectAsState()
    val notice by model.message.collectAsState()
    var page by remember(initialPage) { mutableStateOf(initialPage) }
    var add by remember { mutableStateOf(false) }
    var pairing by remember { mutableStateOf(false) }
    val openPairing = { pairing = true; model.startPairing() }

    YumiTheme(settings, desktop.palette) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            ExpressiveBackdrop(animated = desktop.liveBackdrop) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val compact = maxHeight < 700.dp || maxWidth < 846.dp
                    val dense = compact && maxHeight < 600.dp
                    val targetInset = if (compact) 12.dp else Inset
                    val inset by animateDpAsState(targetInset, tween(220), label = "window-inset")
                    val compactTop = minOf(CompactContentTop,
                        (maxHeight - targetInset * 2 - 8.dp - if (dense) 356.dp else 396.dp).coerceAtLeast(160.dp))
                    Row(Modifier.fillMaxSize().padding(inset).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(inset)) {
                        CompositionLocalProvider(LocalCompactLayout provides compact, LocalDenseLayout provides dense, LocalCompactContentTop provides compactTop) {
                            Dock(model, page, compact) { page = it }
                            Box(Modifier.weight(1f).fillMaxHeight().testTag("page-content")) {
                                AnimatedContent(
                                    targetState = page,
                                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                                    label = "page",
                                ) { shown ->
                                    when (shown) {
                                        Page.Connect -> ConnectPage(model, onServers = { page = Page.Servers }, onPair = openPairing)
                                        Page.Servers -> ServersPage(model)
                                        Page.Subscriptions -> SubscriptionsPage(model, onAdd = { add = true }, onPair = openPairing)
                                        Page.Routing -> RoutingPage(model)
                                        Page.Speed -> SpeedPage(model)
                                        Page.Settings -> SettingsPage(model, onInstallUpdate)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (add) ImportDialog(model, onClose = { add = false })
            if (pairing) PairingDialog(model, onClose = { pairing = false; model.pairing.stop() })
            notice?.let {
                AlertDialog(
                    onDismissRequest = { model.message.value = null },
                    confirmButton = { TextButton(onClick = { model.message.value = null }) { Text("OK") } },
                    shape = DialogShape,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    text = { Text(it, style = MaterialTheme.typography.bodyLarge) },
                )
            }
        }
    }
}

/* ── The dock ─────────────────────────────────────────────────────────────────────────────── */

/** The three islands keep the same positions on every page, rather than following its content. */
@Composable private fun Dock(m: DesktopModel, page: Page, compact: Boolean, onSelect: (Page) -> Unit) {
    val status by m.status.collectAsState()
    val dense = LocalDenseLayout.current
    val button by animateDpAsState(if (dense) 40.dp else if (compact) 44.dp else 56.dp, tween(220), label = "dock-button-size")
    val endButton by animateDpAsState(if (dense) 40.dp else if (compact) 44.dp else 56.dp, tween(220), label = "dock-end-button")
    val padding by animateDpAsState(if (dense) 6.dp else 8.dp, tween(220), label = "dock-padding")
    val gap by animateDpAsState(if (dense) 12.dp else if (compact) 16.dp else 24.dp, tween(220), label = "dock-gap")
    val groupGap by animateDpAsState(if (dense) 8.dp else 12.dp, tween(220), label = "dock-group-gap")
    val itemGap by animateDpAsState(if (compact) 3.dp else 6.dp, tween(220), label = "dock-item-gap")
    val navigationTop by animateDpAsState(if (compact) LocalCompactContentTop.current else RegularPageHeaderHeight + 12.dp, tween(220), label = "dock-top")
    val island = endButton + 16.dp
    BoxWithConstraints(Modifier.width(island).fillMaxHeight().testTag("sidebar")) {
        Box(Modifier.align(Alignment.TopCenter).size(endButton).testTag("app-mark"), contentAlignment = Alignment.Center) {
            YumiMark(plate = MaterialTheme.colorScheme.primary, glyph = MaterialTheme.colorScheme.onPrimary, size = endButton)
        }
        val groupHeight = button * 5 + itemGap * 4 + padding * 2 + groupGap + island
        val top = navigationTop.coerceAtMost((maxHeight - groupHeight - island - gap).coerceAtLeast(endButton + gap))
        Column(Modifier.align(Alignment.TopCenter).offset(y = top), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                Modifier.width(island).testTag("nav-panel").glass(PillShape, glassTint()).padding(vertical = padding),
                verticalArrangement = Arrangement.spacedBy(itemGap), horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Page.entries.filter { it != Page.Settings }.forEach { item ->
                    DockButton(m.text(item.ru, item.en), item.icon, item == page, Modifier.testTag("nav-${item.name}"), button) { onSelect(item) }
                }
            }
            Spacer(Modifier.height(groupGap))
            Box(Modifier.testTag("settings-island").glass(CircleShape, glassTint()).padding(8.dp)) {
                DockButton(m.text(Page.Settings.ru, Page.Settings.en), Page.Settings.icon, page == Page.Settings, Modifier.testTag("nav-Settings"), endButton) {
                    onSelect(Page.Settings)
                }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter)) { TunnelCookie(m, status, size = island) { onSelect(Page.Connect) } }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun DockButton(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier = Modifier, size: Dp = 56.dp, onClick: () -> Unit) {
    TooltipArea(
        tooltip = { HoverLabel(label) },
        delayMillis = 350,
        tooltipPlacement = TooltipPlacement.ComponentRect(
            anchor = Alignment.CenterEnd,
            alignment = Alignment.CenterEnd,
            offset = DpOffset(14.dp, 0.dp),
        ),
    ) {
        // The page you are on is a filled circle — the dock is a column of circles, and a square
        // among them read as a different kind of thing rather than as the chosen one.
        PanelIconButton(icon, label, modifier, checked = selected, size = size, checkedCorner = 50, onClick = onClick)
    }
}

/** A name beside a button: a solid plate, because a popup has nothing behind it to frost. */
@Composable private fun HoverLabel(text: String) {
    Surface(shape = PillShape, color = MaterialTheme.colorScheme.inverseSurface, shadowElevation = 6.dp) {
        Text(
            text,
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.inverseOnSurface,
        )
    }
}

/**
 * The tunnel's state on every page: the expressive cookie while busy, rounded Material shields
 * when settled, and a warning triangle whose tooltip explains a failure.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun TunnelCookie(m: DesktopModel, status: ServiceStatus, size: Dp = 56.dp, onClick: () -> Unit) {
    val semantic = LocalSemanticColors.current
    val busy = status.state == "connecting" || status.state == "disconnecting"
    val connected = status.state == "connected"
    val blocked = status.killSwitch && !connected && !busy
    val failed = status.state == "failed" || status.state == "unavailable" || !status.error.isNullOrBlank() || blocked
    val color = when {
        failed -> MaterialTheme.colorScheme.error
        connected -> semantic.connected
        busy -> semantic.connecting
        else -> MaterialTheme.colorScheme.outline
    }
    val label = when {
        failed -> buildList {
            val error = status.error?.takeIf { it.isNotBlank() }
            add(when {
                error != null -> serviceError(m, error, includeUnknown = status.detail.isNullOrBlank())
                status.state == "unavailable" -> m.text("Служба туннеля недоступна.", "The tunnel service is unavailable.")
                status.state == "failed" -> m.text("Не удалось запустить туннель.", "Could not start the tunnel.")
                else -> m.text("VPN отключён.", "VPN is disconnected.")
            })
            status.detail?.trim()?.takeIf { it.isNotEmpty() && it !in this }?.let { add(it) }
            if (blocked) add(m.text("Килл свитч удерживает интернет. Открой подключение, чтобы снять блокировку.", "The kill switch is holding traffic. Open Connection to lift the block."))
        }.joinToString("\n\n")
        connected -> m.text("Защищено", "Protected")
        status.state == "connecting" -> m.text("Подключение…", "Connecting…")
        status.state == "disconnecting" -> m.text("Отключение…", "Disconnecting…")
        else -> m.text("Не защищено", "Unprotected")
    }
    val interaction = remember { MutableInteractionSource() }
    val angle = if (busy && !failed && LocalAnimationsActive.current) {
        val spin = rememberInfiniteTransition(label = "cookie")
        spin.animateFloat(0f, 360f, infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "cookie-angle")
    } else null
    TooltipArea(
        tooltip = {
            Surface(shape = RowShape, color = MaterialTheme.colorScheme.inverseSurface, shadowElevation = 6.dp) {
                Text(label, Modifier.widthIn(max = 360.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.inverseOnSurface)
            }
        },
        delayMillis = 250,
        tooltipPlacement = TooltipPlacement.ComponentRect(Alignment.CenterEnd, Alignment.CenterEnd, DpOffset(14.dp, 0.dp)),
    ) {
        Box(
            Modifier
                .size(size)
                .testTag("tunnel-status")
                .semantics { contentDescription = label }
                .pressMorph(interaction)
                .glass(CircleShape, glassTint())
                .clickable(interaction, indication = null, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            val animated by animateColorAsState(color, colorTween(), label = "cookie-color")
            if (busy && !failed) Box(
                Modifier
                    .size(24.dp)
                    .testTag("tunnel-status-busy")
                    .graphicsLayer { rotationZ = angle?.value ?: 0f }
                    .clip(MaterialShapes.Cookie9Sided.toShape())
                    .background(animated),
            )
            else Icon(
                imageVector = when { failed -> Icons.Rounded.Warning; connected -> Icons.Rounded.GppGood; else -> Icons.Rounded.GppBad },
                contentDescription = null,
                modifier = Modifier.size(26.dp).testTag(when { failed -> "tunnel-status-error"; connected -> "tunnel-status-connected"; else -> "tunnel-status-idle" }),
                tint = animated,
            )
        }
    }
}

/* ── Shared bits ──────────────────────────────────────────────────────────────────────────── */

/**
 * Latency, bucketed. Users act on "fast / usable / bad", and a continuous gradient would imply a
 * precision a handshake probe does not have.
 */
@Composable private fun LatencyLabel(m: DesktopModel, result: LatencyResult?) {
    val semantic = LocalSemanticColors.current
    val (text, color) = when {
        result == null -> "—" to semantic.latencyDead
        result.failed -> m.strings.get(R.string.servers_latency_failed) to semantic.latencySlow
        result.millis < 150 -> m.strings.get(R.string.servers_latency_value, result.millis) to semantic.latencyFast
        result.millis < 400 -> m.strings.get(R.string.servers_latency_value, result.millis) to semantic.latencyMedium
        else -> m.strings.get(R.string.servers_latency_value, result.millis) to semantic.latencySlow
    }
    Text(text, style = MonoStyle.copy(fontSize = MaterialTheme.typography.labelLarge.fontSize), color = color, maxLines = 1)
}

/** Badges worth showing on a desktop: "Auto ×N" says nothing here, where a group rides its first server. */
private val ProxyNode.desktopBadges get() = badges.filterNot { it.startsWith("Auto ×") }

@Composable private fun ProtocolPlate(protocol: Protocol) {
    Plate(protocol.label, loud = protocol == Protocol.VLESS || protocol == Protocol.HYSTERIA2)
}

@Composable private fun EmptyState(icon: ImageVector, title: String, body: String) {
    GlassPanel(Modifier.fillMaxWidth()) {
        Box(
            Modifier.size(52.dp).clip(MaterialShapes.Sunny.toShape()).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A scrolling page with the bar a pointer expects; the wheel works either way.
 *
 * The content stops at [ReadingWidth] rather than filling whatever the window is. A settings panel
 * stretched across a wide monitor puts its switch a foot away from the label it belongs to.
 */
@Composable private fun ScrollingColumn(content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 14.dp, bottom = 12.dp)) {
            Column(
                Modifier.widthIn(max = ReadingWidth),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

/** As wide as a column of prose and a row of controls should ever get. */
private val ReadingWidth = 860.dp

/* ── Connect ──────────────────────────────────────────────────────────────────────────────── */

@Composable private fun ConnectPage(m: DesktopModel, onServers: () -> Unit, onPair: () -> Unit) {
    val status by m.status.collectAsState()
    val traffic by m.traffic.collectAsState()
    val profiles by m.profiles.state.collectAsState()
    val node = profiles.nodes.firstOrNull { it.id == (status.nodeId ?: profiles.selectedNodeId) }

    val download = rememberRateHistory()
    val upload = rememberRateHistory()
    val live by rememberUpdatedState(traffic)
    // Sampled on a clock rather than on every change of the counters: two identical seconds — an
    // idle tunnel — would otherwise produce one sample, and the axis would stop being time.
    LaunchedEffect(status.state) {
        if (status.state != "connected") {
            download.clear(); upload.clear()
            return@LaunchedEffect
        }
        while (true) {
            download.push(live.downloadBytesPerSecond)
            upload.push(live.uploadBytesPerSecond)
            delay(1_000)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = LocalCompactLayout.current || maxWidth < 720.dp
        val tight = maxHeight < 640.dp
        val entry = remember { Animatable(1f) }
        val initialized = remember { booleanArrayOf(false) }
        LaunchedEffect(narrow) {
            if (initialized[0]) {
                entry.snapTo(0.7f)
                entry.animateTo(1f, tween(220))
            } else initialized[0] = true
        }
        // One layout at a time: two crossfading planets share a camera and fight over its size.
        // Drop the temporary layer after the transition so normal frames keep the direct path.
        Box(Modifier.fillMaxSize().then(if (entry.isRunning || entry.value < 1f) Modifier.graphicsLayer {
            alpha = entry.value
            translationY = (1f - entry.value) * 8.dp.toPx()
        } else Modifier)) {
            if (narrow) {
                CompactConnectPage(m, status, traffic, node, profiles.latencies[node?.id], onServers, onPair)
            } else {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(2f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.fillMaxWidth().height(pageHeaderHeight()).testTag("page-header").glass(PanelShape, glassTint())) {
                            Headline(m, status, node, tight, Modifier.padding(if (LocalCompactLayout.current) TileRim else TileRim + 4.dp))
                        }
                        FlowFigure(m, status, traffic, node, Modifier.weight(1f).fillMaxWidth())
                    }
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ServerRow(m, node, profiles.latencies[node?.id], onServers)
                        ControlTile(m, status, node != null, Modifier.weight(1f), m::toggleConnection)
                        when {
                            // A kill switch holding a dead tunnel is the one thing on this screen the user
                            // must not miss: their internet is off on purpose, and here is how to turn it on.
                            status.killSwitch && status.state != "connected" && status.state != "connecting" -> TroubleNote(
                                Icons.Rounded.GppMaybe,
                                m.text("Интернет закрыт килл свитчем", "The kill switch is holding traffic"),
                                m.text(
                                    "Туннель не работает, поэтому всё, что шло бы мимо него, заблокировано. Переподключись или сними блокировку.",
                                    "The tunnel is down, so everything that would leave around it is blocked. Reconnect, or lift the block.",
                                ),
                                Modifier.fillMaxWidth(),
                                action = m.text("Снять блокировку", "Lift the block"),
                                onAction = { m.releaseKillSwitch() },
                            )
                            status.state in listOf("failed", "unavailable") -> ConnectionProblem(m, status)
                            node == null -> TransferCard(m, onPair)
                            else -> SessionTile(m, traffic, download.values, upload.values)
                        }
                    }
                }
            }
        }
    }
}

/** A small window keeps the same hierarchy without squeezing two poster columns together. */
@Composable private fun CompactConnectPage(
    m: DesktopModel, status: ServiceStatus, traffic: TrafficStats, node: ProxyNode?, latency: LatencyResult?,
    onServers: () -> Unit, onPair: () -> Unit,
) {
    val groupHeight by animateDpAsState(LocalCompactContentTop.current - 12.dp, tween(220), label = "compact-connection-height")
    Column(Modifier.fillMaxSize().testTag("compact-connect"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().height(groupHeight), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f).fillMaxWidth().testTag("page-header").glass(PanelShape, glassTint())) {
                    Headline(m, status, node, true, Modifier.padding(12.dp), compact = true)
                }
                ServerButton(m, node, latency, Modifier.fillMaxWidth().height(64.dp), true, onServers)
            }
            Box(Modifier.weight(1f).fillMaxHeight().testTag("compact-control")) {
                ControlTile(m, status, node != null, Modifier.fillMaxSize(), m::toggleConnection)
            }
        }
        FlowFigure(m, status, traffic, node, Modifier.weight(1f).fillMaxWidth())
        if (status.killSwitch && status.state !in listOf("connected", "connecting")) {
            QuietButton(m.text("Снять блокировку интернета", "Lift the network block"), Modifier.fillMaxWidth(), Icons.Rounded.GppMaybe) { m.releaseKillSwitch() }
        } else if (status.state in listOf("failed", "unavailable")) {
            ConnectionProblem(m, status)
        } else if (node == null) {
            QuietButton(m.text("Перенести с телефона", "Transfer from phone"), Modifier.fillMaxWidth(), Icons.Rounded.QrCodeScanner, onClick = onPair)
        } else {
            val semantic = LocalSemanticColors.current
            Row(Modifier.fillMaxWidth().glass(TileShape, glassTint()).padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Box(Modifier.weight(1f)) { SessionRate(m.strings.get(R.string.connect_rate_download), m.strings.rate(traffic.downloadBytesPerSecond), semantic.download) }
                Box(Modifier.weight(1f)) { SessionRate(m.strings.get(R.string.connect_rate_upload), m.strings.rate(traffic.uploadBytesPerSecond), semantic.upload) }
            }
        }
    }
}

/**
 * The server, and the square that changes it: a tile with the latency on it that opens the server
 * list, and beside it the server that reading belongs to.
 */
@Composable private fun ServerRow(m: DesktopModel, node: ProxyNode?, latency: LatencyResult?, onServers: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 240.dp) ServerButton(m, node, latency, Modifier.fillMaxWidth().height(72.dp), true, onServers)
        else Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ServerButton(m, node, latency, Modifier.fillMaxHeight().aspectRatio(1f), false, onServers)
            Column(
                Modifier.weight(1f).fillMaxHeight().glass(TileShape, glassTint()).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Kicker(m.text("Сервер", "Server"))
                Text(
                    if (node != null) rememberEmojiText(node.name, 14.sp)
                    else AnnotatedString(m.text("Не выбран", "None chosen")),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (node != null) Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProtocolPlate(node.protocol)
                    node.desktopBadges.take(1).forEach { Plate(it) }
                }
            }
        }
    }
}

@Composable private fun ServerButton(
    m: DesktopModel,
    node: ProxyNode?,
    latency: LatencyResult?,
    modifier: Modifier,
    wide: Boolean,
    onServers: () -> Unit,
) {
    val semantic = LocalSemanticColors.current
    val value = latency?.takeIf { !it.failed }?.millis
    val ink = when {
        value == null -> MaterialTheme.colorScheme.onSurfaceVariant
        value < 80 -> semantic.latencyFast
        value < 180 -> semantic.latencyMedium
        else -> semantic.latencySlow
    }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val tint = glassTint(if (hovered) 0.9f else GlassAlpha)
    Box(
        modifier
            .testTag("server-button")
            .pressMorph(interaction)
            .glass(TileShape, tint)
            .hoverable(interaction)
            .clickable(interaction, indication = null, onClick = onServers),
    ) {
        if (wide) Row(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Rounded.SwapHoriz, m.text("Сменить сервер", "Choose server"), Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            if (node != null) Text(rememberEmojiText(node.name, 15.sp), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            else Text(m.text("Выбрать", "Choose"), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(value?.let { m.strings.get(R.string.servers_latency_value, it) } ?: "—", style = MonoStyle.copy(fontSize = MaterialTheme.typography.labelLarge.fontSize), color = ink, maxLines = 1)
        } else Column(
            Modifier.fillMaxSize().padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Rounded.SwapHoriz, m.text("Сменить сервер", "Choose server"), Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(3.dp))
            Text(value?.toString() ?: "—", style = MonoStyle.copy(fontSize = MaterialTheme.typography.titleLarge.fontSize), color = ink)
            Text(m.strings.get(R.string.unit_millis).uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun Headline(m: DesktopModel, status: ServiceStatus, node: ProxyNode?, tight: Boolean, modifier: Modifier, compact: Boolean = false) {
    val semantic = LocalSemanticColors.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status.state, status.connectedAt) {
        while (status.state == "connected") {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val connected = status.state == "connected"
    val primary: String
    val secondary: String
    when (status.state) {
        "connected" -> {
            primary = m.strings.get(R.string.connect_protected)
            secondary = formatDuration(now - status.connectedAt)
        }
        "connecting" -> {
            primary = m.strings.get(R.string.connect_raising)
            secondary = m.strings.get(R.string.connect_tunnel_word)
        }
        "disconnecting" -> {
            primary = m.strings.get(R.string.connect_stopping)
            secondary = m.strings.get(R.string.connect_tunnel_word)
        }
        "failed" -> {
            primary = m.strings.get(R.string.connect_failed_primary)
            secondary = m.strings.get(R.string.connect_failed_secondary)
        }
        "unavailable" -> {
            primary = m.text("Служба", "Service")
            secondary = m.text("недоступна", "unavailable")
        }
        else -> {
            primary = m.strings.get(R.string.connect_tunnel_primary)
            secondary = m.strings.get(R.string.connect_tunnel_secondary)
        }
    }
    val ink = if (connected) semantic.connected else MaterialTheme.colorScheme.onSurface
    val poster = pagePosterStyle(compact || LocalCompactLayout.current, tight)
    Column(modifier) {
        Kicker(
            when {
                connected && node != null -> m.text("Туннель · ${node.protocol.label}", "Tunnel · ${node.protocol.label}")
                else -> m.text("Туннель", "Tunnel")
            },
        )
        Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
        if (compact) {
            val label = when (status.state) {
                "connected" -> primary
                "connecting" -> m.text("Подключение", "Connecting")
                "disconnecting" -> m.text("Отключение", "Disconnecting")
                "failed" -> m.text("Ошибка подключения", "Connection failed")
                "unavailable" -> m.text("Служба недоступна", "Service unavailable")
                else -> m.text("VPN выключен", "VPN is off")
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, Modifier.weight(1f), style = poster, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (connected) Text(secondary, style = poster.copy(fontSize = 20.sp, lineHeight = 26.sp, fontFeatureSettings = "tnum"), color = ink, maxLines = 1)
            }
        } else {
        Text(primary, style = poster, color = ink, maxLines = 1)
        // Keyed on the state, never on the text: keying on the text would crossfade the whole line
        // once a second while the timer ticks. Tabular figures keep it from twitching sideways.
        @Suppress("UnusedContentLambdaTargetStateParameter")
        AnimatedContent(
            targetState = status.state,
            transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
            label = "headline-second-line",
        ) { _ ->
            Text(secondary, style = poster.copy(fontFeatureSettings = "tnum"), color = ink, maxLines = 1)
        }
        }
    }
}

/** Keep diagnostics outside the fixed poster, so the dock grid also holds for service errors. */
@Composable private fun ConnectionProblem(m: DesktopModel, status: ServiceStatus) {
    // Only what the tiles beside this one cannot say: a handshake that failed, a service that is not
    // installed or belongs to someone else.
    val subtitle = when {
        status.state == "unavailable" -> when (status.error) {
            "service_denied" -> m.text("Служба туннеля зарегистрирована на другую учётную запись Windows.", "The tunnel service is registered to a different Windows account.")
            "service_missing" -> m.text("Служба туннеля не установлена или остановлена.", "The tunnel service is not installed, or is not running.")
            else -> m.text("Установите Yumi вместе с VPN-службой, чтобы подключаться.", "Install Yumi with its tunnel service to connect.")
        }
        status.state == "failed" -> status.error?.let { serviceError(m, it) }
        else -> null
    }
    TroubleNote(
        Icons.Rounded.Warning,
        if (status.state == "failed") m.text("Не удалось подключиться", "Could not connect") else m.text("Служба недоступна", "Service unavailable"),
        subtitle,
        Modifier.fillMaxWidth(),
        action = if (status.state == "unavailable" && status.error == "service_denied") m.text("Переназначить службу на меня", "Register the service to me") else null,
        onAction = { m.repairService() },
        detail = status.detail?.takeIf { status.state == "failed" && it.isNotBlank() },
    )
}

@Composable private fun FlowFigure(m: DesktopModel, status: ServiceStatus, traffic: TrafficStats, node: ProxyNode?, modifier: Modifier) {
    val semantic = LocalSemanticColors.current
    val settings by m.settings.settings.collectAsState()
    val running = status.state == "connected"
    val warming = status.state == "connecting"
    // Matte, like every other surface: the figure is glass, the sea is a translucent wash over it
    // and the gaps between the cells are left open, so the shapes drifting behind the window show
    // through the ocean, blurred, and move under it when the window is dragged.
    val veil by animateFloatAsState(if (running) 0.62f else 0.5f, colorTween(), label = "figure-veil")
    Box(modifier.testTag("flow-figure").glass(FigureShape, glassTint(veil))) {
        when (settings.visualizer) {
            Visualizer.Waves -> TrafficWaves(
                downloadBytesPerSecond = traffic.downloadBytesPerSecond,
                uploadBytesPerSecond = traffic.uploadBytesPerSecond,
                downloadColor = semantic.download,
                uploadColor = semantic.upload,
                warming = warming,
            )
            Visualizer.Planet -> {
                val mask = WorldLandMask
                // The flag in the server's own name is its country code. Nothing is asked of the
                // network to find out where a server is.
                val place = remember(node?.name) { node?.name?.let(WorldMap::countryCodeOf)?.let(WorldMap::centroidOf) }
                PixelPlanet(
                    mask = mask,
                    latitude = place?.get(0),
                    longitude = place?.get(1),
                    connected = running,
                    warming = warming,
                    seaColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isLightTheme()) 0.42f else 0.38f),
                    landColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f),
                    gapColor = Color.Transparent,
                    markerColor = semantic.marker,
                )
            }
        }
        if (!running) Box(
            Modifier.align(Alignment.BottomStart).padding(18.dp)
                .glass(PillShape, glassTint(0.86f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                m.strings.get(if (warming) R.string.connect_stream_warming else R.string.connect_stream_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The control, standing up: the tallest thing on screen is the one thing that does something.
 * Loud — the accent — while the tunnel is down, because it is the button being asked for; once it
 * is up it settles into glass with the one ring on the screen, the ring of the active element.
 */
@Composable private fun ControlTile(m: DesktopModel, status: ServiceStatus, hasNode: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val semantic = LocalSemanticColors.current
    val connected = status.state == "connected"
    val busy = status.state == "connecting" || status.state == "disconnecting"
    val loud = !connected && !busy
    val container by animateColorAsState(
        if (busy) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.primary,
        colorTween(),
        label = "control-container",
    )
    val content by animateColorAsState(
        when {
            connected -> semantic.connected
            busy -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onPrimary
        },
        colorTween(),
        label = "control-content",
    )
    // Stopping never needs a server.
    val usable = when (status.state) {
        "unavailable", "disconnecting" -> false
        "connected", "connecting" -> true
        else -> hasNode
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Half the shorter side is a capsule whatever the column turns out to be; connected it
        // settles to a slab. Pressed, it rounds a little further and sinks.
        val capsule = minOf(maxWidth, maxHeight) / 2
        val corner by animateDpAsState(
            when {
                connected -> Radii.extraLargeIncreased
                pressed -> capsule
                else -> capsule * 0.92f
            },
            Springs.press(),
            label = "control-corner",
        )
        val shape = RoundedCornerShape(corner.coerceAtLeast(0.dp))
        val roomy = maxWidth >= 230.dp
        Box(
            Modifier
                .fillMaxSize()
                .testTag("connect-button")
                .pressMorph(interaction, usable)
                .then(if (connected) Modifier.glass(shape, glassTint()).border(1.5.dp, semantic.connected, shape) else Modifier.clip(shape).background(if (usable || !loud) container else container.copy(alpha = 0.4f)))
                .clickable(interaction, indication = null, enabled = usable, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when {
                    busy -> LoadingIndicator(color = content, modifier = Modifier.size(if (roomy) 64.dp else 48.dp))
                    connected -> Icon(Icons.Rounded.Pause, null, Modifier.size(if (roomy) 46.dp else 34.dp), tint = content)
                    status.state == "failed" -> Icon(Icons.Rounded.Refresh, null, Modifier.size(if (roomy) 42.dp else 32.dp), tint = content)
                    else -> Icon(Icons.Rounded.PowerSettingsNew, null, Modifier.size(if (roomy) 42.dp else 32.dp), tint = content)
                }
                Text(
                    m.strings.get(
                        when (status.state) {
                            "connected" -> R.string.connect_button_disconnect
                            "connecting" -> R.string.connect_button_connecting
                            "disconnecting" -> R.string.connect_button_disconnecting
                            "failed" -> R.string.action_retry
                            else -> R.string.connect_button_connect
                        },
                    ),
                    style = if (roomy) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                    color = if (connected) MaterialTheme.colorScheme.onSurface else content,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable private fun SessionTile(m: DesktopModel, traffic: TrafficStats, download: List<Long>, upload: List<Long>) {
    val semantic = LocalSemanticColors.current
    GlassPanel(Modifier.fillMaxWidth(), shape = TileShape, padding = PaddingValues(18.dp), spacing = 9.dp) {
        Kicker(m.strings.get(R.string.connect_session))
        SessionRate(m.strings.get(R.string.connect_rate_download), m.strings.rate(traffic.downloadBytesPerSecond), semantic.download)
        SessionRate(m.strings.get(R.string.connect_rate_upload), m.strings.rate(traffic.uploadBytesPerSecond), semantic.upload)
        // Only once there is a shape to draw. An empty framed box reads as a chart that failed to
        // load rather than as a tunnel that is not carrying anything.
        if (download.size >= 2) Box(
            Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(Radii.medium)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)).padding(8.dp),
        ) {
            TrafficSparkline(download, upload, semantic.download, semantic.upload, Modifier.fillMaxSize())
        }
        Text(
            m.strings.get(R.string.connect_received, m.strings.bytes(traffic.downloadBytes).toString()),
            style = MonoStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable private fun SessionRate(label: String, rate: ValueAndUnit, accent: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(label.uppercase(), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(rate.value, style = MonoStyle.copy(fontSize = MaterialTheme.typography.titleLarge.fontSize), color = accent, maxLines = 1)
        Text(" " + rate.unit, style = MaterialTheme.typography.labelSmall, color = accent.copy(alpha = 0.75f), modifier = Modifier.padding(bottom = 3.dp), maxLines = 1)
    }
}

@Composable private fun TransferCard(m: DesktopModel, onPair: () -> Unit) {
    GlassPanel(Modifier.fillMaxWidth(), shape = TileShape, padding = PaddingValues(20.dp)) {
        Kicker(m.text("Первый запуск", "First run"))
        Text(m.text("Уже пользуешься Yumi?", "Already using Yumi?"), style = MaterialTheme.typography.titleLarge)
        Text(
            m.text("Передай подписку с телефона по QR-коду.", "Transfer a subscription from your phone using a QR code."),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AccentButton(m.text("С телефона", "From phone"), icon = Icons.Rounded.QrCodeScanner, onClick = onPair)
    }
}

/* ── Servers ──────────────────────────────────────────────────────────────────────────────── */

@Composable private fun ServersPage(m: DesktopModel) {
    val profiles by m.profiles.state.collectAsState()
    val pinging by m.pinging.collectAsState()
    val status by m.status.collectAsState()
    var query by remember { mutableStateOf("") }
    val visibleNodes = remember(profiles.nodes, profiles.subscriptions) { profiles.visibleNodes }
    val matches = remember(visibleNodes, query) {
        if (query.isBlank()) visibleNodes else visibleNodes.filter { it.name.contains(query.trim(), true) }
    }
    val sources = remember(profiles.subscriptions) { profiles.subscriptions.associate { it.id to (it.remoteTitle ?: it.name) } }
    val list = rememberLazyListState()
    val frameProbe = LocalUiFrameProbe.current
    DisposableEffect(list, frameProbe) {
        frameProbe?.servers = list
        onDispose { frameProbe?.servers = null }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageTitle(
            m.strings.get(R.string.servers_title),
            m.strings.get(R.string.servers_subtitle,
                m.strings.plural(R.plurals.servers, visibleNodes.size),
                m.strings.plural(R.plurals.sources, profiles.subscriptions.size)),
            description = m.text("Выбери сервер и проверь задержку перед подключением.", "Choose a server and check its latency before connecting."),
            controlsBelow = true,
        )
        Row(Modifier.fillMaxWidth().height(if (LocalCompactLayout.current) 64.dp else 48.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).glass(PillShape, glassTint())) {
                GlassField(query, { query = it }, Modifier.fillMaxWidth().testTag("server-search"),
                    placeholder = m.strings.get(R.string.servers_search_placeholder), leading = Icons.Rounded.Search,
                    containerColor = Color.Transparent, singleLineHeight = 48.dp)
            }
            AccentButton(
                if (pinging) m.text("Заново", "Restart") else m.text("Пинг", "Ping"),
                Modifier.testTag("ping-servers"),
                icon = if (pinging) Icons.Rounded.Refresh else Icons.Rounded.Bolt,
                enabled = visibleNodes.isNotEmpty(),
            ) { m.ping() }
        }
        Box(Modifier.weight(1f).fillMaxWidth().glass(PanelShape, glassTint()).testTag("server-panel")) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 30.dp, top = 18.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Kicker(m.text("Сервер", "Server"), Modifier.weight(1f))
                    if (pinging) Text(m.text("Проверяем…", "Measuring…"), Modifier.padding(end = 16.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Kicker(m.text("Задержка", "Latency"))
                }
                // Fixed height: restarting a probe never shifts the list under the pointer.
                Box(Modifier.fillMaxWidth().height(2.dp)) {
                    if (pinging) LinearProgressIndicator(Modifier.fillMaxSize())
                    else HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                }
                if (matches.isEmpty()) Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(m.strings.get(if (query.isBlank()) R.string.servers_empty_hint else R.string.servers_empty_filtered_hint),
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                } else Box(Modifier.weight(1f)) {
                    LazyColumn(
                        state = list,
                        modifier = Modifier.fillMaxSize().padding(end = 12.dp).testTag("server-list"),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(matches, key = { it.id }, contentType = { "server" }) { node ->
                            ServerListRow(m, node, node.id == profiles.selectedNodeId,
                                node.id == status.nodeId && status.state == "connected", profiles.latencies[node.id], sources[node.subscriptionId])
                        }
                    }
                    VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 10.dp))
                }
            }
        }
    }
}

@Composable private fun ServerListRow(m: DesktopModel, node: ProxyNode, selected: Boolean, connected: Boolean, latency: LatencyResult?, source: String?) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val scheme = MaterialTheme.colorScheme
    val details = remember(node, source) { (listOf(node.protocol.label) + node.desktopBadges + listOfNotNull(source)).distinct().joinToString(" · ") }
    Row(
        Modifier.fillMaxWidth().testTag("server-card-${node.id}")
            .semantics { this.selected = selected }
            .clip(RowShape)
            .background(when { selected -> scheme.primary.copy(alpha = 0.12f); hovered -> scheme.onSurface.copy(alpha = 0.05f); else -> Color.Transparent })
            .hoverable(interaction)
            .clickable(interaction, indication = null, role = androidx.compose.ui.semantics.Role.RadioButton) { m.selectNode(node.id) }
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape)
            .then(if (selected) Modifier.background(scheme.primary) else Modifier.border(1.5.dp, scheme.outlineVariant, CircleShape)), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Rounded.Check, m.text("Выбран", "Selected"), Modifier.size(18.dp), tint = scheme.onPrimary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(rememberEmojiText(node.name, 17.sp), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(details, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.width(92.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            LatencyLabel(m, latency)
            if (connected) Text(m.text("Подключён", "Connected"), style = MaterialTheme.typography.labelSmall, color = LocalSemanticColors.current.connected)
        }
    }
}

/* ── Subscriptions ────────────────────────────────────────────────────────────────────────── */

@Composable private fun SubscriptionsPage(m: DesktopModel, onAdd: () -> Unit, onPair: () -> Unit) {
    val profiles by m.profiles.state.collectAsState()
    val busy by m.busy.collectAsState()
    var remove by remember { mutableStateOf<Subscription?>(null) }
    val grid = rememberLazyGridState()

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageTitle(m.strings.get(R.string.subscriptions_title), m.strings.plural(R.plurals.sources, profiles.subscriptions.size),
            description = m.text("Обновляй серверы и проверяй остаток трафика по подпискам.", "Refresh servers and check the remaining subscription traffic.")) {
            PanelIconButton(Icons.Rounded.Refresh, m.strings.get(R.string.action_refresh), Modifier.glass(CircleShape, glassTint()), enabled = !busy) { m.refreshAll() }
            QuietButton(m.text("С телефона", "From phone"), icon = Icons.Rounded.QrCodeScanner, onClick = onPair)
            AccentButton(m.strings.get(R.string.action_add), icon = Icons.Rounded.Add, onClick = onAdd)
        }
        if (busy) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
        if (profiles.subscriptions.isEmpty()) EmptyState(
            Icons.Rounded.Cloud,
            m.strings.get(R.string.subscriptions_empty),
            m.text("Перенеси её из Yumi на телефоне или вставь ссылку.", "Transfer it from Yumi on your phone or paste a link."),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(440.dp),
                state = grid,
                modifier = Modifier.fillMaxSize().padding(end = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(profiles.subscriptions, key = { it.id }) { sub -> SubscriptionCard(m, sub, busy) { remove = sub } }
            }
            VerticalScrollbar(rememberScrollbarAdapter(grid), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }

    remove?.let { sub ->
        AlertDialog(
            onDismissRequest = { remove = null },
            shape = DialogShape,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text(m.text("Удалить подписку?", "Delete subscription?"), style = MaterialTheme.typography.headlineSmall) },
            text = { Text(sub.remoteTitle ?: sub.name, style = MaterialTheme.typography.bodyLarge) },
            confirmButton = { AccentButton(m.strings.get(R.string.action_delete)) { m.removeSubscription(sub.id); remove = null } },
            dismissButton = { TextButton(onClick = { remove = null }) { Text(m.strings.get(R.string.action_cancel)) } },
        )
    }
}

@Composable private fun SubscriptionCard(m: DesktopModel, sub: Subscription, busy: Boolean, onRemove: () -> Unit) {
    GlassPanel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Kicker(m.text("Подписка", "Subscription"))
                Text(sub.remoteTitle ?: sub.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            PanelIconButton(Icons.Rounded.Refresh, m.strings.get(R.string.action_refresh), size = 44.dp, enabled = !busy) { m.action { m.refresh(sub) } }
            PanelIconButton(Icons.Rounded.DeleteOutline, m.strings.get(R.string.action_delete), size = 44.dp, onClick = onRemove)
        }
        val info = sub.userInfo
        val fraction = info?.usedFraction
        if (fraction != null) {
            LinearWavyProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    m.strings.get(R.string.subscriptions_used_of, m.strings.bytes(info.usedBytes ?: 0L).toString(), m.strings.bytes(info.totalBytes ?: 0L).toString()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val days = daysUntil(info.expiresAtEpochSeconds)
                if (days != null) Text(
                    if (days > 0) m.strings.get(R.string.subscriptions_days_left, days) else m.strings.get(R.string.subscriptions_expired),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (days > 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary,
                )
            }
        } else {
            Text(m.strings.get(R.string.subscriptions_no_quota), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            m.strings.get(R.string.subscriptions_updated, m.strings.plural(R.plurals.servers, sub.nodeIds.size), m.strings.relativeTime(sub.lastUpdatedEpochMillis)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (sub.lastError != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.CloudOff, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
            Text(m.text("Не удалось обновить. Подписка сохранена.", "Refresh failed. Your subscription is saved."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

/* ── Split tunnelling ─────────────────────────────────────────────────────────────────────── */

/**
 * What goes through the tunnel and what goes around it.
 *
 * Three blocks, in the order the service applies them: the direction, then the databases — which
 * win over the applications, so a browser sent wholly through the tunnel still opens Russian sites
 * directly — then the applications, then what a person typed by name or address, which wins over
 * the databases because it is the most specific thing anybody said.
 */
@Composable private fun RoutingPage(m: DesktopModel) {
    val current by m.desktop.state.collectAsState()
    val settings by m.settings.settings.collectAsState()
    val status by m.status.collectAsState()
    var domains by remember(current.domains) { mutableStateOf(current.domains.joinToString("\n")) }
    var networks by remember(current.networks) { mutableStateOf(current.networks.joinToString("\n")) }
    var invalid by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val known by m.knownApps.collectAsState()
    LaunchedEffect(Unit) { if (known.isEmpty()) m.discoverApps() }

    ScrollingColumn {
        PageTitle(m.text("Туннелирование", "Split tunneling"), m.text("Что идёт через VPN, а что мимо", "What goes through the VPN, and what goes around it"),
            description = m.text("Выбери, какие приложения, сайты и сети должны идти через VPN. Остальной трафик может идти напрямую. Добавляй домены и подсети без ручного редактирования конфигурации.", "Choose which apps, websites and networks use the VPN. Other traffic can go directly. Add domains and subnets without editing configuration files."))

        GlassPanel(Modifier.fillMaxWidth()) {
            SectionTitle(m.text("Куда направлять трафик", "Where traffic goes"), m.text("Направление", "Direction"))
            Row(Modifier.fillMaxWidth().height(if (status.state in listOf("failed", "unavailable")) 196.dp else 160.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ModeCard(
                    Modifier.weight(1f).fillMaxHeight(),
                    Icons.Rounded.Shield,
                    m.text("Всё через VPN", "Everything through VPN"),
                    m.text("Кроме выбранных приложений и сайтов", "Except the apps and sites you choose"),
                    current.splitMode == SplitMode.ExceptSelected,
                ) { m.changeDesktop(current.copy(splitMode = SplitMode.ExceptSelected), true) }
                ModeCard(
                    Modifier.weight(1f).fillMaxHeight(),
                    Icons.Rounded.FilterAlt,
                    m.text("Только выбранное", "Only what you choose"),
                    m.text("Остальное идёт напрямую", "Everything else goes direct"),
                    current.splitMode == SplitMode.OnlySelected,
                ) { m.changeDesktop(current.copy(splitMode = SplitMode.OnlySelected), true) }
            }
            SwitchRow(
                m.text("Локальная сеть напрямую", "Local network directly"),
                settings.bypassLan,
                m.text("Роутер, принтер, NAS и перенос подписки с телефона", "Router, printer, NAS and phone transfer"),
            ) { m.changeSettings({ s -> s.copy(bypassLan = it) }, true) }
        }

        RussiaPanel(m, current, status)

        GlassPanel(Modifier.fillMaxWidth()) {
            SectionTitle(
                m.text("Приложения", "Applications"),
                if (current.splitMode == SplitMode.ExceptSelected) m.text("Мимо VPN", "Around the VPN") else m.text("Через VPN", "Through the VPN"),
            ) {
                AccentButton(m.text("Выбрать", "Choose"), icon = Icons.Rounded.Add) { picking = true }
            }
            if (current.applications.isEmpty()) Text(
                m.text(
                    "Найди программу в списке запущенных и установленных или впиши имя процесса — например, telegram.exe.",
                    "Find a program among the running and installed ones, or type a process name — telegram.exe, for example.",
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            current.applications.forEach { process ->
                val app = known.firstOrNull { it.process.equals(process, true) }
                Row(
                    Modifier.fillMaxWidth().clip(RowShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    AppIcon(rememberAppIcon(app?.path), Icons.Rounded.Terminal)
                    Column(Modifier.weight(1f)) {
                        Text(app?.name ?: com.yumi.desktop.platform.AppCatalog.prettify(process), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(process, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    if (app?.running == true) Plate(m.text("запущено", "running"))
                    PanelIconButton(Icons.Rounded.Close, m.text("Убрать", "Remove"), size = 40.dp) {
                        m.changeDesktop(current.copy(applications = current.applications - process), true)
                    }
                }
            }
        }

        GlassPanel(Modifier.fillMaxWidth()) {
            SectionTitle(m.text("Сайты и адреса", "Sites and addresses"), m.text("По имени и IP", "By name and IP"))
            GlassField(domains, { domains = it; invalid = false }, Modifier.fillMaxWidth(), label = m.text("Домены, по одному в строке", "Domains, one per line"), placeholder = "example.com", singleLine = false, minLines = 3, isError = invalid)
            GlassField(networks, { networks = it; invalid = false }, Modifier.fillMaxWidth(), label = m.text("IP и подсети, по одному в строке", "IP addresses and subnets, one per line"), placeholder = "203.0.113.0/24", singleLine = false, minLines = 3, isError = invalid)
            Text(
                m.text(
                    "Домены включают поддомены и действуют сильнее баз: так можно вернуть в VPN отдельный российский сайт. Сторонний защищённый DNS и ECH могут скрыть имя сайта — тогда используй правило приложения или IP.",
                    "Domains include subdomains and outrank the databases, so a single Russian site can be sent back through the VPN. External encrypted DNS and ECH can hide a site's name — use an application or IP rule then.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (invalid) Text(m.text("Проверь домены и IP-адреса.", "Check your domains and IP addresses."), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AccentButton(m.text("Сохранить", "Save")) {
                    try {
                        val next = current.copy(
                            domains = domains.lines().filter { it.isNotBlank() }.map(RuleValidation::domain).distinct(),
                            networks = networks.lines().filter { it.isNotBlank() }.map(RuleValidation::network).distinct(),
                        )
                        m.changeDesktop(next, true)
                    } catch (_: Exception) {
                        invalid = true
                    }
                }
                Text(m.text("Активное соединение переподключится.", "An active connection will reconnect."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (picking) AppPickerDialog(
        m,
        current.applications,
        onDone = { chosen -> picking = false; if (chosen != current.applications) m.changeDesktop(current.copy(applications = chosen), true) },
        onDismiss = { picking = false },
    )
}

@Composable private fun ModeCard(modifier: Modifier, icon: ImageVector, title: String, body: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
        colorTween(),
        label = "mode",
    )
    val content = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Column(
        modifier
            .pressMorph(interaction)
            .clip(TileShape)
            .background(container)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = content)
        Text(title, style = MaterialTheme.typography.titleMedium, color = content)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = content.copy(alpha = 0.78f))
    }
}

/**
 * Russian sites around the tunnel, by the databases — and the databases themselves, fetched the way
 * Yumi on the phone fetches them: by themselves the first time, and again with one round button.
 */
@Composable private fun RussiaPanel(m: DesktopModel, current: DesktopSettings, status: ServiceStatus) {
    GlassPanel(Modifier.fillMaxWidth()) {
        SectionTitle(m.text("Российские сайты напрямую", "Russian sites directly"), m.text("Базы маршрутизации", "Routing databases")) {
            Switch(current.bypassRussia, { m.changeDesktop(current.copy(bypassRussia = it), true) })
        }
        Text(
            m.text(
                "Сайты и адреса из баз geosite и geoip идут мимо VPN — даже из приложений, которые целиком отправлены в туннель. YouTube остаётся в туннеле.",
                "Sites and addresses in the geosite and geoip databases go around the VPN — even from applications sent wholly through the tunnel. YouTube stays in the tunnel.",
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth().clip(RowShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val semantic = LocalSemanticColors.current
            when {
                status.geoUpdating -> LoadingIndicator(Modifier.size(28.dp), color = MaterialTheme.colorScheme.primary)
                status.geoReady -> StateDot(semantic.connected, 10.dp)
                else -> StateDot(MaterialTheme.colorScheme.outline, 10.dp)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        status.state == "unavailable" -> m.text("Служба недоступна", "Service unavailable")
                        status.geoUpdating -> m.text("Скачиваю базы…", "Downloading the databases…")
                        status.geoReady -> m.text("Базы на месте", "Databases ready")
                        else -> m.text("Баз ещё нет", "No databases yet")
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    when {
                        status.geoError != null && !status.geoUpdating -> m.text("Не скачались: ", "Download failed: ") + status.geoError
                        status.geoReady -> m.strings.bytes(status.geoBytes).toString() + " · " + m.strings.relativeTime(status.geoUpdatedAt)
                        else -> m.text("Без них правило молчит, а всё идёт по остальным правилам", "Until they arrive this rule is silent and the others decide")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PanelIconButton(
                if (status.geoReady) Icons.Rounded.Refresh else Icons.Rounded.Download,
                m.text("Обновить базы", "Update databases"),
                size = 48.dp,
                checked = !status.geoReady && !status.geoUpdating && status.state != "unavailable",
                enabled = !status.geoUpdating && status.state != "unavailable",
            ) { m.updateGeo() }
        }
    }
}

/* ── Speed ────────────────────────────────────────────────────────────────────────────────── */

@Composable private fun SpeedPage(m: DesktopModel) {
    val semantic = LocalSemanticColors.current
    val speed by m.speed.collectAsState()
    val status by m.status.collectAsState()
    val running = speed.running
    val accent = if (speed.phase == SpeedPhase.Upload) semantic.upload else semantic.download

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageTitle(
            m.strings.get(R.string.speed_title),
            when {
                speed.phase == SpeedPhase.Failed -> speed.message ?: m.strings.get(R.string.speed_phase_failed)
                speed.throughTunnel && speed.serverName != null -> m.strings.get(R.string.speed_through_server, speed.serverName!!)
                speed.throughTunnel -> m.strings.get(R.string.speed_through_tunnel)
                status.state == "connected" -> m.text("Через VPN", "Through the tunnel")
                else -> m.text("Напрямую — туннель выключен, это канал самого ПК", "Direct — the tunnel is off, this is your PC's own link")
            },
            description = m.text("Измерь скорость загрузки и отдачи, задержку и стабильность канала. При включённом VPN тест проходит через выбранный сервер. Чем ниже задержка и джиттер, тем стабильнее соединение.", "Measure download, upload, latency and stability. With VPN enabled, the test uses your connected server. Lower latency and jitter mean a steadier connection."),
        )
        if (speed.hasResult && !running) {
            SpeedResult(m.strings.get(R.string.connect_rate_download), Icons.Rounded.ArrowDownward, m.strings.megabits(speed.downloadBytesPerSecond), speed.downloadSeries, semantic.download, Modifier.weight(1f))
            SpeedResult(m.strings.get(R.string.connect_rate_upload), Icons.Rounded.ArrowUpward, m.strings.megabits(speed.uploadBytesPerSecond), speed.uploadSeries, semantic.upload, Modifier.weight(1f))
        } else {
            GlassPanel(Modifier.weight(1f).fillMaxWidth()) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val side = minOf(maxWidth, maxHeight)
                    val roomy = side >= 320.dp
                    SpeedGauge(
                        bytesPerSecond = speed.liveBytesPerSecond,
                        accent = accent,
                        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                        modifier = Modifier.size(side),
                    )
                    Column(Modifier.offset(y = side * 0.26f).widthIn(max = side * 0.86f), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (running) {
                            val reading = m.strings.megabits(speed.liveBytesPerSecond)
                            Text(reading.value, style = (if (roomy) MaterialTheme.typography.displayLarge else MaterialTheme.typography.headlineLarge).copy(fontFeatureSettings = "tnum"))
                            Text(reading.unit, style = MaterialTheme.typography.labelLarge, color = accent)
                            if (roomy) {
                                Spacer(Modifier.height(10.dp))
                                Text(m.strings.get(speed.phase.labelRes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                            }
                        } else if (roomy) {
                            Text(m.text("Замерь канал", "Measure the link"), style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                }
                if (!running) Text(
                    if (status.state == "connected") m.text("Тест пройдёт через туннель и покажет, что даёт выбранный сервер.", "The test runs through the tunnel and shows what the chosen server gives you.")
                    else m.text("Туннель выключен, так что будет измерен канал самого ПК.", "The tunnel is off, so this measures your PC's own link."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (running) {
                    SpeedTrace(samples = speed.series, accent = accent, gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth().height(88.dp))
                    LinearWavyProgressIndicator(
                        progress = { speed.phaseProgress },
                        modifier = Modifier.fillMaxWidth(),
                        color = accent,
                        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (speed.latencyMillis > 0) Plate(m.strings.get(R.string.speed_latency_chip, speed.latencyMillis))
            if (speed.jitterMillis > 0) Plate(m.strings.get(R.string.speed_jitter_chip, speed.jitterMillis))
            if (speed.phase == SpeedPhase.Failed) Plate(m.strings.get(R.string.speed_interrupted))
        }
        val interaction = remember { MutableInteractionSource() }
        val enabled = status.state !in listOf("connecting", "disconnecting")
        Box(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
                .height(84.dp)
                .pressMorph(interaction, enabled)
                .then(
                    if (running) Modifier.glass(RoundedCornerShape(Radii.extraLarge), glassTint()).border(1.5.dp, accent, RoundedCornerShape(Radii.extraLarge))
                    else Modifier.clip(RoundedCornerShape(Radii.extraExtraLarge)).background(MaterialTheme.colorScheme.primary),
                )
                .clickable(interaction, indication = null, enabled = enabled) { if (running) m.cancelSpeed() else m.startSpeed() },
            contentAlignment = Alignment.Center,
        ) {
            val ink = if (running) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimary
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when {
                        running -> Icons.Rounded.Stop
                        speed.hasResult -> Icons.Rounded.Refresh
                        else -> Icons.Rounded.PlayArrow
                    },
                    null,
                    Modifier.size(26.dp),
                    tint = ink,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    m.strings.get(
                        when {
                            running -> R.string.speed_stop
                            speed.hasResult -> R.string.speed_again
                            else -> R.string.speed_start
                        },
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    color = ink,
                )
            }
        }
    }
}

@Composable private fun SpeedResult(label: String, icon: ImageVector, value: ValueAndUnit, samples: List<Long>, accent: Color, modifier: Modifier) {
    GlassPanel(modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 22.dp, vertical = 18.dp), spacing = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(accent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(18.dp), tint = accent)
            }
            Spacer(Modifier.width(12.dp))
            Kicker(label)
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(value.value, style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"), color = accent)
                Text(value.unit, style = MaterialTheme.typography.labelMedium, color = accent.copy(alpha = 0.8f), modifier = Modifier.padding(bottom = 6.dp))
            }
        }
        SpeedTrace(samples = samples, accent = accent, gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), live = false, modifier = Modifier.fillMaxWidth().weight(1f))
    }
}

/* ── Settings ─────────────────────────────────────────────────────────────────────────────── */

@Composable private fun SettingsPage(m: DesktopModel, onInstallUpdate: () -> Unit) {
    val settings by m.settings.settings.collectAsState()
    val desktop by m.desktop.state.collectAsState()
    val adapters by m.adapters.collectAsState()
    var dns by remember(settings.remoteDns) { mutableStateOf(settings.remoteDns) }
    var mtu by remember(settings.mtu) { mutableStateOf(settings.mtu.toString()) }
    var invalid by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { m.refreshAdapters() }
    // A saved adapter that has since been unplugged stays visible, so the choice reads as a
    // choice rather than silently reverting to Automatic.
    val adapterChoices = remember(adapters, desktop.outboundAdapter) {
        (listOf("") + adapters.map { it.name }).let { options ->
            if (desktop.outboundAdapter.isNotEmpty() && desktop.outboundAdapter !in options) options + desktop.outboundAdapter else options
        }.distinct()
    }

    ScrollingColumn {
        PageTitle(m.text("Настройки", "Settings"), "Yumi Desktop $AppVersion · Xray · GPL-3.0-or-later",
            description = m.text("Выбери оформление и поведение приложения, настрой защиту и обновления. Здесь можно сменить палитру, настроить запуск вместе с системой и проверить новую версию. Подписки и выбранный сервер сохранятся.", "Choose the appearance, app behavior, protection and updates. Change the palette, configure startup and check for a new version. Your subscriptions and selected server are preserved."))

        UpdatePanel(m, desktop, onInstallUpdate)

        GlassPanel(Modifier.fillMaxWidth()) {
            SectionTitle(m.text("Защита", "Protection"), m.text("Килл свитч", "Kill switch"))
            SwitchRow(
                m.text("Блокировать интернет без VPN", "Block the internet without the VPN"),
                desktop.killSwitch,
                m.text(
                    "С подключения и до отключения ничего не уходит мимо туннеля — даже если сервер упал или служба перезапускается. Локальная сеть остаётся, если она идёт напрямую.",
                    "From connecting until you disconnect nothing leaves around the tunnel — even if the server drops or the service restarts. The local network stays reachable while it bypasses the tunnel.",
                ),
            ) { m.changeDesktop(desktop.copy(killSwitch = it), true) }
        }

        GlassPanel(Modifier.fillMaxWidth()) {
            SectionTitle(m.text("Внешний вид", "Appearance"))
            ChoiceRow(m.text("Тема", "Theme"), ThemeMode.entries, settings.themeMode, { m.strings.get(it.labelRes) }) { mode -> m.changeSettings({ it.copy(themeMode = mode) }) }
            PaletteRow(m, settings, desktop)
            SwitchRow("AMOLED", settings.amoled, m.text("Чёрный фон вместо тёмного", "True black instead of dark")) { m.changeSettings({ s -> s.copy(amoled = it) }) }
            SwitchRow(m.text("Живой фон", "Live backdrop"), desktop.liveBackdrop, m.text("Фигуры за стеклом медленно плывут и следят за курсором", "The shapes behind the glass drift and follow the pointer")) {
                m.changeDesktop(desktop.copy(liveBackdrop = it))
            }
            ChoiceRow(m.text("Фигура", "Figure"), Visualizer.entries, settings.visualizer, { m.strings.get(it.labelRes) }) { value -> m.changeSettings({ it.copy(visualizer = value) }) }
            ChoiceRow(m.text("Язык", "Language"), AppLanguage.entries, settings.language, { m.strings.get(it.labelRes) }) { lang -> m.changeSettings({ it.copy(language = lang) }) }
        }

        GlassPanel(Modifier.fillMaxWidth()) {
            SectionTitle(
                m.text("Соединение и DNS", "Connection and DNS"),
                trailing = {
                    QuietButton(m.text("Обновить", "Refresh"), icon = Icons.Rounded.Refresh) { m.refreshAdapters() }
                },
            )
            GlassField(dns, { dns = it; invalid = false }, Modifier.fillMaxWidth(), label = m.text("DNS-резолвер", "DNS resolver"), isError = invalid)
            Text(
                m.text("DoH (https://), TCP, UDP или IP-адрес. Без автоматической замены резолвера.", "DoH (https://), TCP, UDP or an IP address. No automatic resolver substitution."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GlassField(mtu, { mtu = it; invalid = false }, Modifier.width(220.dp), label = "MTU · 1280–9000", isError = invalid)
            SwitchRow("IPv6", settings.enableIpv6) { m.changeSettings({ s -> s.copy(enableIpv6 = it) }, true) }
            ChoiceRow(
                m.text("Откуда брать интернет", "Where the internet comes from"),
                adapterChoices,
                desktop.outboundAdapter,
                { name ->
                    if (name.isEmpty()) m.text("Автоматически", "Automatic")
                    else adapters.firstOrNull { it.name == name }?.let { adapter ->
                        val title = adapter.name + if (adapter.default) m.text(" · основной", " · default") else ""
                        if (adapter.address != null) "$title · ${adapter.address}" else title
                    } ?: name
                },
            ) { name ->
                // A live tunnel has to move onto the new interface now, not at the next connect.
                m.changeDesktop(desktop.copy(outboundAdapter = name), true)
            }
            Text(
                m.text(
                    "Автоматически — адаптер основного маршрута Windows. Выбери вручную, если интернет уходит не через тот адаптер или туннель не поднимается.",
                    "Automatic follows the Windows default route. Pick one by hand when traffic leaves through the wrong adapter or the tunnel will not start.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AccentButton(m.text("Сохранить соединение", "Save connection settings")) {
                val value = mtu.toIntOrNull()
                val resolver = runCatching {
                    val raw = dns.trim()
                    if ("://" in raw) {
                        val uri = java.net.URI(raw)
                        require(uri.scheme in listOf("https", "tcp", "udp") && !uri.host.isNullOrBlank() && uri.userInfo == null)
                    } else {
                        RuleValidation.domain(raw).let {}
                    }
                    raw
                }.recoverCatching { RuleValidation.network(dns.trim()).also { require('/' !in it) } }.getOrNull()
                if (value == null || value !in 1280..9000 || resolver == null) invalid = true
                else m.changeSettings({ it.copy(remoteDns = resolver, mtu = value) }, true)
            }
            if (invalid) Text(m.text("Проверь DNS и MTU.", "Check DNS and MTU."), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodyMedium)
        }

        GlassPanel(Modifier.fillMaxWidth()) {
            SectionTitle(m.text("Поведение", "Behavior"))
            SwitchRow(m.text("Запускать при входе в Windows", "Launch at Windows sign-in"), desktop.launchAtLogin) { m.changeDesktop(desktop.copy(launchAtLogin = it)) }
            SwitchRow(
                m.text("Подключать VPN при запуске", "Connect VPN on launch"),
                desktop.connectOnLaunch,
                m.text("После запуска Yumi подключится к выбранному серверу, когда служба будет готова.", "After Yumi starts, connect to the selected server when the service is ready."),
            ) { m.changeDesktop(desktop.copy(connectOnLaunch = it)) }
            SwitchRow(m.text("Обновлять подписки автоматически", "Refresh subscriptions automatically"), settings.subscriptionAutoUpdate) { m.changeSettings({ s -> s.copy(subscriptionAutoUpdate = it) }) }
            if (settings.subscriptionAutoUpdate) ChoiceRow(
                m.text("Как часто", "How often"),
                UpdateIntervals,
                settings.subscriptionUpdateMinutes,
                { intervalLabel(m, it) },
            ) { minutes -> m.changeSettings({ it.copy(subscriptionUpdateMinutes = minutes) }) }
            Text(
                m.text(
                    "Крестик сворачивает окно в трей. Выход через меню трея отключает VPN. Сервер меняется только по твоему выбору — сам он не переключается никогда.",
                    "Closing the window keeps Yumi in the tray. Exit from the tray disconnects VPN. Servers change only when you choose them — never by themselves.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** How often subscriptions are re-read: half an hour to a day. */
private val UpdateIntervals = listOf(30, 60, 180, 360, 720, 1440)

private fun intervalLabel(m: DesktopModel, minutes: Int): String = when {
    minutes % 60 == 0 -> m.text("${minutes / 60} ч", "${minutes / 60} h")
    else -> m.text("$minutes мин", "$minutes min")
}

/**
 * The palettes, shown as what they are: swatches in each scheme's own accent. The chosen one turns
 * into a cookie with a check — the design's mark for "this one".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun PaletteRow(m: DesktopModel, settings: AppSettings, desktop: DesktopSettings) {
    val dark = isDark(settings)
    Column(Modifier.fillMaxWidth().testTag("palette-picker"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Kicker(m.text("Палитра", "Palette") + " · " + m.text(desktop.palette.ru, desktop.palette.en))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Palette.entries.forEach { option ->
                val scheme = remember(option, dark) { option.scheme(dark) }
                val chosen = option == desktop.palette
                val interaction = remember { MutableInteractionSource() }
                val size by animateDpAsState(if (chosen) 50.dp else 42.dp, Springs.toggle(), label = "swatch")
                val label = m.text(option.ru, option.en)
                Column(
                    Modifier.width(72.dp).testTag("palette-${option.name}")
                        .pressMorph(interaction)
                        .clip(RoundedCornerShape(16.dp))
                        .semantics { selected = chosen; contentDescription = label }
                        .clickable(interaction, indication = null, role = androidx.compose.ui.semantics.Role.RadioButton) { m.changeDesktop(desktop.copy(palette = option)) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(50.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(size).clip(if (chosen) MaterialShapes.Cookie9Sided.toShape() else CircleShape).background(scheme.primary), contentAlignment = Alignment.Center) {
                            if (chosen) Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), tint = scheme.onPrimary)
                        }
                    }
                    Text(label, style = MaterialTheme.typography.labelSmall,
                        color = if (chosen) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/* ── Dialogs ──────────────────────────────────────────────────────────────────────────────── */

@Composable private fun ImportDialog(m: DesktopModel, onClose: () -> Unit) {
    var raw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        shape = DialogShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(m.text("Добавить в Yumi", "Add to Yumi"), style = MaterialTheme.typography.displaySmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(m.text("Ссылка подписки или конфигурация сервера", "Subscription link or server configuration"), style = MaterialTheme.typography.bodyLarge)
                GlassField(raw, { raw = it }, Modifier.width(480.dp), placeholder = "https://… · vless://…", singleLine = false, minLines = 4)
            }
        },
        confirmButton = { AccentButton(m.strings.get(R.string.action_add), enabled = raw.isNotBlank()) { m.importText(raw); onClose() } },
        dismissButton = { TextButton(onClick = onClose) { Text(m.strings.get(R.string.action_cancel)) } },
    )
}

@Composable private fun PairingDialog(m: DesktopModel, onClose: () -> Unit) {
    val state by m.pairing.state.collectAsState()
    val addresses by m.localAddresses.collectAsState()
    val selected by m.selectedAddress.collectAsState()
    DisposableEffect(Unit) { onDispose { m.pairing.stop() } }
    AlertDialog(
        onDismissRequest = onClose,
        shape = DialogShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(m.text("Подписка с телефона", "Subscription from phone"), style = MaterialTheme.typography.displaySmall) },
        text = {
            Column(Modifier.width(440.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    m.text(
                        "Подключи устройства к одной локальной сети. В Yumi на телефоне открой сканер, отсканируй QR и выбери подписку.",
                        "Connect both devices to the same local network. Open the scanner in Yumi on your phone, scan the QR and choose a subscription.",
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
                when (val s = state) {
                    is PairingReceiverState.Waiting -> {
                        Surface(shape = RoundedCornerShape(Radii.largeIncreased), color = Color.White) {
                            QrCode(s.invite.encode(), Modifier.size(290.dp).padding(14.dp))
                        }
                        Kicker(m.text("Одноразовый код · 5 минут", "Single-use code · 5 minutes"))
                    }
                    is PairingReceiverState.Receiving -> {
                        LoadingIndicator(color = MaterialTheme.colorScheme.primary)
                        Text(m.text("Сохраняем подписку…", "Saving subscription…"), style = MaterialTheme.typography.bodyLarge)
                    }
                    is PairingReceiverState.Complete -> {
                        Box(Modifier.size(64.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Check, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onPrimary)
                        }
                        Text(
                            if (s.result.status == "saved_refresh_failed") m.text("Подписка сохранена. Обнови её после проверки соединения.", "Subscription saved. Refresh it after checking your connection.")
                            else m.text("Подписка перенесена", "Subscription transferred"),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    is PairingReceiverState.Failed -> TroubleNote(
                        Icons.Rounded.WifiOff,
                        m.text("Не удалось принять подписку", "Transfer failed"),
                        m.text("Проверь локальную сеть, брандмауэр и срок действия QR.", "Check your local network, firewall and QR expiry."),
                        action = m.text("Новый QR", "New QR"),
                        onAction = { m.startPairing() },
                    )
                    else -> LoadingIndicator(color = MaterialTheme.colorScheme.primary)
                }
                if (addresses.size > 1) addresses.forEach { address ->
                    Row(Modifier.fillMaxWidth().clip(RowShape).clickable { m.selectedAddress.value = address; m.startPairing() }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(address == selected, { m.selectedAddress.value = address; m.startPairing() })
                        Text(address.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(m.strings.get(R.string.action_close)) } },
        dismissButton = { TextButton(onClick = { m.startPairing() }) { Text(m.text("Новый QR", "New QR")) } },
    )
}

/* ── Words for the service's error codes ──────────────────────────────────────────────────── */

private fun serviceError(m: DesktopModel, code: String, includeUnknown: Boolean = false) = when (code) {
    "service_denied" -> m.text("Служба туннеля зарегистрирована на другую учётную запись Windows.", "The tunnel service is registered to a different Windows account.")
    "service_missing" -> m.text("Служба туннеля не установлена или остановлена.", "The tunnel service is not installed, or is not running.")
    "service_unavailable" -> m.text("Служба туннеля недоступна.", "The tunnel service is unavailable.")
    "platform_not_supported" -> m.text("VPN-туннель поддерживается только в Windows.", "The VPN tunnel is supported only on Windows.")
    "worker_stopped" -> m.text("Ядро VPN неожиданно остановилось.", "The VPN core stopped unexpectedly.")
    "invalid_dns" -> m.text("Проверь адрес DNS-резолвера.", "Check the DNS resolver address.")
    "invalid_application" -> m.text("Одно из приложений в туннелировании записано неправильно.", "One of the split-tunnelling applications is malformed.")
    "kill_switch_failed" -> m.text("Не удалось включить килл свитч, поэтому туннель не поднят. Выключи его в настройках, чтобы подключиться без него.", "The kill switch could not be raised, so the tunnel was not started. Turn it off in Settings to connect without it.")
    "adapter_unavailable", "invalid_adapter" ->
        m.text("Выбранный адаптер недоступен. Подключи его или выбери «Автоматически» в настройках.", "The selected adapter is unavailable. Connect it or choose Automatic in Settings.")
    "invalid_configuration", "forbidden_field", "unsupported_protocol" ->
        m.text("Конфигурация сервера не поддерживается ядром.", "The server configuration is not supported by the core.")
    else -> m.text("Не удалось запустить туннель. Проверь VPN-службу и выбранный сервер.", "Could not start the tunnel. Check the service and selected server.") + if (includeUnknown) "\n\n$code" else ""
}

private val WorldLandMask: ByteArray by lazy {
    checkNotNull(DesktopModel::class.java.getResourceAsStream("/world_land.bin")).use { it.readBytes() }
}
