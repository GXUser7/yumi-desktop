package com.yumi.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.yumi.desktop.platform.DesktopPlatform
import kotlinx.coroutines.delay
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/*
 * The window, laid out the way the phone and the television are.
 *
 * Three things carry the family resemblance and nothing else has to: the poster headline every
 * screen opens with, the rounded figure the tunnel lives inside, and the one loud control under
 * it. Everything else is a surface with a radius on it. What the window adds — and the phone
 * cannot — is a second column beside the figure, so the numbers a phone hides behind a drag
 * gesture are simply on screen.
 */

/** Screen margin, and the gap between the rail, the main column and the aside. */
private val Inset = 18.dp

/**
 * Where the wordmark starts inside the rail — and, because it is the same number, where every
 * page's headline starts too.
 *
 * The two are set at different sizes and live in different columns, so nothing lines them up on
 * its own: the rail was indented and the pages were not, and a title sat a centimetre above the
 * logo it was meant to read alongside. Both poster styles trim their leading down to the ink, so
 * boxes that start at the same height put their capitals at the same height as well, whatever size
 * the title happens to be — which is why one constant is enough for a 38 px page title and the
 * tunnel screen's 52 px one alike.
 */
private val HeadlineTop = 26.dp

/**
 * The tile's own inner rim, and the reason the state can sit in one without losing its line.
 *
 * A page carries [HeadlineTop] itself — [PageHeader] for the pages that have a title, the tunnel
 * screen for its own — so the tunnel screen can start its mosaic exactly this much higher and let
 * the tile's padding put the word back on the wordmark's line. Move one of the two and the
 * headline stops agreeing with the logo.
 */
private val TileRim = 18.dp

/** Corner radius of the flow figure. The phone's, unchanged: it is the app's signature shape. */
private val FigureShape = RoundedCornerShape(44.dp)

private val CardShape = RoundedCornerShape(28.dp)
private val TileShape = RoundedCornerShape(18.dp)

/** The tab column's own shape: square enough to read as a column, round enough to belong here. */
private val RailShape = RoundedCornerShape(30.dp)

/** And the tab inside it, which keeps the soft corner the selected tab has everywhere else. */
private val RailItemShape = RoundedCornerShape(20.dp)

enum class Page(val ru: String, val en: String, val icon: ImageVector) {
    Connect("Подключение", "Connection", Icons.Rounded.Shield),
    Servers("Серверы", "Servers", Icons.Rounded.Dns),
    Subscriptions("Подписки", "Subscriptions", Icons.Rounded.Cloud),
    Routing("Туннелирование", "Split tunneling", Icons.AutoMirrored.Rounded.AltRoute),
    Speed("Тест скорости", "Speed test", Icons.Rounded.Speed),
    Settings("Настройки", "Settings", Icons.Rounded.Settings),
}

@Composable fun YumiApp(model: DesktopModel, initialPage: Page = Page.Connect) {
    val settings by model.settings.settings.collectAsState()
    val desktop by model.desktop.state.collectAsState()
    val notice by model.message.collectAsState()
    var page by remember(initialPage) { mutableStateOf(initialPage) }
    var add by remember { mutableStateOf(false) }
    var pairing by remember { mutableStateOf(false) }
    val openPairing = { pairing = true; model.startPairing() }

    YumiTheme(settings, desktop.palette) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints {
                // The rail keeps its labels while there is room for them; below that a column of
                // icons still says where you are.
                val railLabels = maxWidth >= 880.dp

                Row(Modifier.fillMaxSize().padding(Inset), horizontalArrangement = Arrangement.spacedBy(Inset)) {
                    NavigationRail(model, page, railLabels) { page = it }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        when (page) {
                            Page.Connect -> ConnectPage(model, onServers = { page = Page.Servers }, onPair = openPairing)
                            Page.Servers -> ServersPage(model)
                            Page.Subscriptions -> SubscriptionsPage(model, onAdd = { add = true }, onPair = openPairing)
                            Page.Routing -> RoutingPage(model)
                            Page.Speed -> SpeedPage(model)
                            Page.Settings -> SettingsPage(model)
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
                    shape = CardShape,
                    text = { Text(it) },
                )
            }
        }
    }
}

/* ── The rail ─────────────────────────────────────────────────────────────────────────────── */

/**
 * The television's rail, standing in a window.
 *
 * A stadium at both ends, floating over the background rather than framing it, with the selected
 * tab carrying the quiet container and the hovered one the full accent. Three appearances rather
 * than two, for the reason the television needs three: if hover and selection took the same tone,
 * pointing at the tab you are already on would change nothing on screen.
 */
@Composable private fun NavigationRail(m: DesktopModel, page: Page, labels: Boolean, onSelect: (Page) -> Unit) {
    val status by m.status.collectAsState()
    val semantic = LocalSemanticColors.current
    // Dragged across the breakpoint the rail used to jump between two widths in a single frame,
    // which is the most visible thing on screen at exactly the moment the user is looking at the
    // edge of the window. It travels now, and the labels leave and arrive with it — a spring
    // rather than a tween, so that grabbing the edge and dragging back mid-flight is a redirection
    // instead of a second jump.
    val width by animateDpAsState(
        if (labels) 200.dp else 88.dp,
        MaterialTheme.motionScheme.slowSpatialSpec(),
        label = "rail-width",
    )
    Surface(
        modifier = Modifier.width(width.coerceAtLeast(72.dp)).fillMaxHeight(),
        // A rounded rectangle rather than the television's stadium. A stadium is a shape for
        // something that floats over a screen the length of a room; standing full height in a
        // window it wastes both ends on curve and reads as a lozenge rather than as a column.
        shape = RailShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 10.dp,
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp).padding(top = HeadlineTop, bottom = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Crossfaded rather than swapped. Both forms are the same word at the same size, so
            // the wordmark stays put and only loses its tail — which is what a rail closing looks
            // like from the inside.
            // The mark, not a letter, and the same mark either way — collapsed it stands alone,
            // expanded the word joins it. "DESKTOP" underneath is gone: it named the one thing a
            // window on a desktop does not have to be told, and it was the only line in the rail
            // that existed to fill a gap.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                YumiMark(
                    plate = MaterialTheme.colorScheme.primary,
                    glyph = MaterialTheme.colorScheme.onPrimary,
                    size = if (labels) 32.dp else 38.dp,
                )
                AnimatedVisibility(labels) {
                    Text(
                        "Yumi",
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Page.entries.forEach { item -> RailItem(m, item, item == page, labels) { onSelect(item) } }
            Spacer(Modifier.weight(1f))
            // The one place the window says out loud what the tunnel is doing while the user is
            // on another screen. A dot rather than a word: it has to survive the rail collapsing.
            val live = status.state == "connected"
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.size(8.dp).clip(CircleShape).background(
                        when (status.state) {
                            "connected" -> semantic.connected
                            "connecting", "disconnecting" -> semantic.connecting
                            "unavailable", "failed" -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.outline
                        },
                    ),
                )
                AnimatedVisibility(labels) {
                    Text(
                        if (live) m.text("Защищено", "Protected") else m.text("Не защищено", "Unprotected"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable private fun RailItem(m: DesktopModel, item: Page, selected: Boolean, labels: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val scale by animateFloatAsState(if (hovered) 1.04f else 1f, label = "rail-hover")
    val container by animateColorAsState(
        when {
            hovered -> MaterialTheme.colorScheme.primary
            selected -> MaterialTheme.colorScheme.primaryContainer
            else -> Color.Transparent
        },
        label = "rail-container",
    )
    val content = when {
        hovered -> MaterialTheme.colorScheme.onPrimary
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = m.text(item.ru, item.en)
    // Not switched between a left-aligned row and a centred one, which would put the icon in a
    // different place on the first frame after the breakpoint. The row stays left-aligned and the
    // inset travels instead, so that once the label has shrunk to nothing the icon has arrived in
    // the middle of a 64 dp item by itself.
    val inset by animateDpAsState(
        if (labels) 16.dp else 21.5.dp,
        MaterialTheme.motionScheme.slowSpatialSpec(),
        label = "rail-item-inset",
    )
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(54.dp).scale(scale).testTag("nav-${item.name}"),
        shape = RailItemShape,
        color = container,
        contentColor = content,
        interactionSource = interaction,
    ) {
        Row(
            Modifier.padding(horizontal = inset.coerceAtLeast(0.dp)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(item.icon, label, Modifier.size(21.dp))
            AnimatedVisibility(labels) {
                Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/* ── Shared furniture ─────────────────────────────────────────────────────────────────────── */

/**
 * The poster headline every screen opens with, in place of a title bar.
 *
 * An app bar would reserve height for a title never long enough to need it. Setting the title as
 * body content gives the type room to be the size it wants to be.
 */
@Composable private fun PageHeader(
    title: String,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().padding(top = HeadlineTop, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.displaySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Spacer(Modifier.height(10.dp))
                // Through the flag pass as well: a speed test's subtitle names the server it went
                // through, and that name carries a flag like any other.
                Text(
                    rememberEmojiText(subtitle, 13.sp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

@Composable private fun Panel(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier, shape = CardShape, color = color) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable private fun Chip(text: String) {
    Text(
        text,
        style = MonoStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/** Protocol pill. REALITY-capable protocols take the accent so they stand out in long lists. */
@Composable private fun ProtocolBadge(protocol: Protocol) {
    val loud = protocol == Protocol.VLESS || protocol == Protocol.HYSTERIA2
    Text(
        protocol.label,
        style = MaterialTheme.typography.labelSmall,
        color = if (loud) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(if (loud) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable private fun FeatureBadge(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

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
    Text(text, style = MonoStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize), color = color, maxLines = 1)
}

@Composable private fun EmptyState(icon: ImageVector, title: String, body: String) {
    Panel(Modifier.fillMaxWidth()) {
        Icon(icon, null, Modifier.size(38.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun Toggle(label: String, checked: Boolean, hint: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

/**
 * A scrolling page with the bar a pointer expects; the wheel works either way.
 *
 * The content stops at [ReadingWidth] rather than filling whatever the window is. A settings panel
 * stretched across a wide monitor puts its switch a foot away from the label it belongs to, and a
 * paragraph of explanation set to the same width stops being readable at the second line.
 */
@Composable private fun ScrollingColumn(content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 14.dp)) {
            Column(
                Modifier.widthIn(max = ReadingWidth),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                content = content,
            )
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

/** As wide as a column of prose and a row of controls should ever get. */
private val ReadingWidth = 840.dp

/* ── Connect ──────────────────────────────────────────────────────────────────────────────── */

/**
 * One column, the whole width.
 *
 * There was an aside here holding the connection's details and the session's counters, and the
 * screen is better without it: the figure was paying for that column in width, and what the column
 * carried was already on screen — the headline names the server and its protocol, and the rates
 * are written on the figure itself while traffic is moving. What is left in the aside's place is
 * only what has nowhere else to be: a warning that the service is missing, and, for someone who
 * has no server at all, the way to get one.
 */
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

    // Started this much above the page's own inset so that the state tile's rim can give it back:
    // the word inside the tile lands on the same line as the wordmark in the rail, which is where
    // it was before the tile existed.
    BoxWithConstraints(Modifier.fillMaxSize().padding(top = HeadlineTop - TileRim)) {
        // A poster headline is two lines of 52 sp. On a short window that is a fifth of the height
        // spent before anything has been said, so it steps down a size rather than squeezing
        // everything under it.
        val tight = maxHeight < 640.dp
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(2f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(Modifier.fillMaxWidth(), shape = CardShape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Headline(m, status, node, tight, Modifier.padding(TileRim))
                }
                FlowFigure(m, status, traffic, node, Modifier.weight(1f).fillMaxWidth())
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ServerRow(m, node, profiles.latencies[node?.id], onServers)
                ControlTile(m, status, node != null, Modifier.weight(1f), m::toggleConnection)
                // The corner the mosaic leaves for whatever the tunnel has to say. With a server
                // that is what it is carrying; without one, how to get a server.
                if (node == null) TransferCard(m, onPair)
                else SessionTile(m, traffic, download.values, upload.values)
            }
        }
    }
}

/**
 * The server, and the square that changes it.
 *
 * The latency used to be a badge floating in the corner of the map, which made the map carry a
 * control that had nothing to do with it and put the one number worth reading on top of moving
 * coastline. Here it is a tile like the others: a square that opens the server list, with the
 * reading on it, and beside it the server that reading belongs to.
 */
@Composable private fun ServerRow(m: DesktopModel, node: ProxyNode?, latency: LatencyResult?, onServers: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Below this the square would leave the name about seventy points to live in, which is
        // two clipped words and a badge wrapped onto its own line. The square keeps its reading
        // and its job; the server it belongs to is already named in the headline above.
        if (maxWidth < 240.dp) ServerButton(m, node, latency, Modifier.fillMaxWidth().height(70.dp), true, onServers)
        else Row(Modifier.fillMaxWidth().height(94.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ServerButton(m, node, latency, Modifier.fillMaxHeight().aspectRatio(1f), false, onServers)
            Surface(Modifier.weight(1f).fillMaxHeight(), shape = CardShape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(horizontal = 15.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (node != null) rememberEmojiText(node.name, 14.sp)
                        else AnnotatedString(m.text("Сервер не выбран", "No server chosen")),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (node != null) Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        ProtocolBadge(node.protocol)
                        node.badges.take(2).forEach { FeatureBadge(it) }
                    }
                }
            }
        }
    }
}

/** The square itself: the arrows that say what it does, and the number that says why you would. */
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
    Surface(
        onClick = onServers,
        modifier = modifier.testTag("server-button"),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        if (wide) Row(
            Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Rounded.SwapHoriz, m.text("Сменить сервер", "Choose server"), Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            if (node != null) Text(rememberEmojiText(node.name, 15.sp), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            else Text(m.text("Выбрать", "Choose"), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(
                value?.let { m.strings.get(R.string.servers_latency_value, it) } ?: "—",
                style = MonoStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
                color = ink,
                maxLines = 1,
            )
        } else Column(
            Modifier.fillMaxSize().padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Rounded.SwapHoriz, m.text("Сменить сервер", "Choose server"), Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(3.dp))
            Text(
                value?.toString() ?: "—",
                style = MonoStyle.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize),
                color = ink,
            )
            Text(
                m.strings.get(R.string.unit_millis).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable private fun Headline(
    m: DesktopModel,
    status: ServiceStatus,
    node: ProxyNode?,
    tight: Boolean,
    modifier: Modifier,
) {
    val semantic = LocalSemanticColors.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status.state, status.connectedAt) {
        while (status.state == "connected") {
            now = System.currentTimeMillis()
            delay(500)
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
    // Only what the square beside this tile cannot say. It carries the flag, the name, the
    // protocol and the latency, so repeating any of that here was the same sentence twice on one
    // screen. What is left is the two cases the server row has no way of explaining: a handshake
    // that failed, and a service that is not installed.
    val subtitle = when {
        // Three different situations used to share one sentence, and two of them were then given
        // advice that could not help. A service that is refusing this account is running: telling
        // its owner to install it sends them to reinstall something that works.
        status.state == "unavailable" -> when (status.error) {
            "service_denied" -> m.text(
                "Служба туннеля зарегистрирована на другую учётную запись Windows.",
                "The tunnel service is registered to a different Windows account.",
            )
            "service_missing" -> m.text(
                "Служба туннеля не установлена или остановлена.",
                "The tunnel service is not installed, or is not running.",
            )
            else -> m.text(
                "Установите Yumi вместе с VPN-службой, чтобы подключаться.",
                "Install Yumi with its tunnel service to connect.",
            )
        }
        status.state == "failed" -> status.error?.let { serviceError(m, it) }
        else -> null
    }
    val ink = if (connected) semantic.connected else MaterialTheme.colorScheme.onSurface
    val poster = if (tight) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displayLarge
    Column(modifier) {
        Text(primary, style = poster, color = ink, maxLines = 1)
        // Keyed on the state, never on the text: keying on the text would crossfade the whole
        // line once a second while the timer ticks, which reads as flicker. Tabular figures keep
        // it from twitching sideways as digit widths change.
        @Suppress("UnusedContentLambdaTargetStateParameter")
        AnimatedContent(
            targetState = status.state,
            transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
            label = "headline-second-line",
        ) { _ ->
            Text(
                secondary,
                style = poster.copy(fontFeatureSettings = "tnum"),
                color = ink,
                maxLines = 1,
            )
        }
        if (subtitle != null) {
            Spacer(Modifier.height(if (tight) 8.dp else 12.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (status.state == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // The one failure the user can put right from here, and the only one worth a button: the
        // service is running and simply does not admit this account. Everything else on this
        // screen is a report; this is a door.
        if (status.state == "unavailable" && status.error == "service_denied") {
            Spacer(Modifier.height(12.dp))
            Button(onClick = { m.repairService() }, shape = RoundedCornerShape(percent = 50)) {
                Text(m.text("Переназначить службу на меня", "Register the service to me"))
            }
        }
        // What the core itself said, under the sentence that categorises it. Set in the figure
        // face because it is a machine's words and should not be mistaken for ours, and shown at
        // all because a tunnel that will not start is otherwise unattributable: the code alone
        // sends everyone to check the same three things that were never the problem.
        val detail = status.detail?.takeIf { status.state == "failed" && it.isNotBlank() }
        if (detail != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                detail,
                style = MonoStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable private fun FlowFigure(
    m: DesktopModel,
    status: ServiceStatus,
    traffic: TrafficStats,
    node: ProxyNode?,
    modifier: Modifier,
) {
    val semantic = LocalSemanticColors.current
    val settings by m.settings.settings.collectAsState()
    val running = status.state == "connected"
    val warming = status.state == "connecting"
    val container by animateColorAsState(
        if (running) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerLowest,
        label = "figure-container",
    )
    Box(
        modifier
            .clip(FigureShape)
            .background(container)
            // An empty outline is how "no traffic" is stated. Once the field is full the border
            // would only compete with it.
            .then(if (running) Modifier else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, FigureShape)),
    ) {
        when (settings.visualizer) {
            Visualizer.Waves -> TrafficWaves(
                downloadBytesPerSecond = traffic.downloadBytesPerSecond,
                uploadBytesPerSecond = traffic.uploadBytesPerSecond,
                downloadColor = semantic.download,
                uploadColor = semantic.upload,
                warming = warming,
            )
            Visualizer.Planet -> {
                // Two kilobytes read once. A missing asset leaves an empty array, which the mask
                // reads as "all sea" — a blank planet rather than a crash, because a decorative
                // figure must never be able to take the window down.
                val mask = remember {
                    runCatching {
                        checkNotNull(DesktopModel::class.java.getResourceAsStream("/world_land.bin")).use { it.readBytes() }
                    }.getOrElse { ByteArray(0) }
                }
                // The flag in the server's own name is its country code. Nothing is asked of the
                // network to find out where a server is.
                val place = remember(node?.name) {
                    node?.name?.let(WorldMap::countryCodeOf)?.let(WorldMap::centroidOf)
                }
                PixelPlanet(
                    mask = mask,
                    latitude = place?.get(0),
                    longitude = place?.get(1),
                    connected = running,
                    warming = warming,
                    seaColor = MaterialTheme.colorScheme.primaryContainer,
                    landColor = MaterialTheme.colorScheme.primary,
                    gapColor = container,
                    markerColor = semantic.marker,
                )
            }
        }

        // Only the idle line, on a scrim. The rates live in the session tile now, and the sentence
        // that is left has nowhere else to go — it needs the scrim because the planet fills this
        // corner with coastline at exactly the contrast the text is set in.
        if (!running) Surface(
            modifier = Modifier.align(Alignment.BottomStart).padding(18.dp),
            shape = RoundedCornerShape(19.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
        ) {
            Text(
                m.strings.get(if (warming) R.string.connect_stream_warming else R.string.connect_stream_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * The control, standing up.
 *
 * It was a bar across the bottom because the screen was a stack and the bottom was what was left.
 * In the mosaic it is a column of its own, which is what it should have been: the one thing on the
 * screen that does something is now also the tallest thing on it, and the icon sits over the word
 * rather than beside it because a tall shape has height to spend and no width to waste.
 */
@Composable private fun ControlTile(
    m: DesktopModel,
    status: ServiceStatus,
    hasNode: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val semantic = LocalSemanticColors.current
    val connected = status.state == "connected"
    val busy = status.state == "connecting" || status.state == "disconnecting"
    val container by animateColorAsState(
        when {
            // A container tone rather than `surface`. As a bar across the bottom the connected
            // control could be an outline on the page's own ground; standing a third of the
            // window tall, the same treatment makes the largest object on screen the darkest
            // one — a hole cut in the mosaic exactly where the main control should be.
            connected -> MaterialTheme.colorScheme.surfaceContainerLow
            busy -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.primary
        },
        label = "control-container",
    )
    val content by animateColorAsState(
        if (connected || busy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimary,
        label = "control-content",
    )
    // Stopping never needs a server. Gating the whole control on one was fine until the server a
    // running tunnel was raised on stopped resolving — a subscription refreshed underneath it, or
    // switched off — and then the only way out of a connection was the tray.
    val usable = when (status.state) {
        "unavailable", "disconnecting" -> false
        "connected", "connecting" -> true
        else -> hasNode
    }
    BoxWithConstraints(modifier) {
        // Half the shorter side is a capsule whatever the column turns out to be; connected it
        // settles to a slab. Animating the radius rather than swapping shapes keeps the corners
        // continuous, and taking the radius from the measured size rather than from a constant is
        // what lets the same control be a lozenge at 1280 and a rounded box at 680.
        val capsule = minOf(maxWidth, maxHeight) / 2
        val corner by animateDpAsState(if (connected) 34.dp else capsule, label = "control-corner")
        val roomy = maxWidth >= 230.dp
        Button(
            onClick = onClick,
            enabled = usable,
            modifier = Modifier.fillMaxSize().testTag("connect-button"),
            shape = RoundedCornerShape(corner.coerceAtLeast(0.dp)),
            colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
            border = if (connected) BorderStroke(1.5.dp, semantic.connected) else null,
            contentPadding = PaddingValues(18.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when {
                    busy -> ShapeSpinner(color = content, size = if (roomy) 42.dp else 32.dp)
                    connected -> Icon(Icons.Rounded.Pause, null, Modifier.size(if (roomy) 44.dp else 34.dp))
                    status.state == "failed" -> Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(if (roomy) 40.dp else 32.dp))
                    else -> Icon(Icons.Rounded.PowerSettingsNew, null, Modifier.size(if (roomy) 40.dp else 32.dp))
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
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
        }
    }
}

/**
 * What the tunnel is carrying, in the corner the mosaic leaves for it.
 *
 * The rates stacked rather than set side by side: this tile is a third of a column and can come
 * out under two hundred points wide, where two figures beside each other are two clipped figures.
 */
@Composable private fun SessionTile(m: DesktopModel, traffic: TrafficStats, download: List<Long>, upload: List<Long>) {
    val semantic = LocalSemanticColors.current
    Surface(Modifier.fillMaxWidth(), shape = CardShape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                m.strings.get(R.string.connect_session).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SessionRate(m.strings.get(R.string.connect_rate_download), m.strings.rate(traffic.downloadBytesPerSecond), semantic.download)
            SessionRate(m.strings.get(R.string.connect_rate_upload), m.strings.rate(traffic.uploadBytesPerSecond), semantic.upload)
            // Only once there is a shape to draw. An empty framed box reads as a chart that failed
            // to load rather than as a tunnel that is not carrying anything.
            if (download.size >= 2) Box(
                Modifier.fillMaxWidth().height(46.dp).clip(TileShape).background(MaterialTheme.colorScheme.surfaceContainer).padding(8.dp),
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
}

@Composable private fun SessionRate(label: String, rate: ValueAndUnit, accent: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(
            label.uppercase(),
            Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(rate.value, style = MonoStyle.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize), color = accent, maxLines = 1)
        Text(
            " " + rate.unit,
            style = MaterialTheme.typography.labelSmall,
            color = accent.copy(alpha = 0.75f),
            modifier = Modifier.padding(bottom = 2.dp),
            maxLines = 1,
        )
    }
}

@Composable private fun TransferCard(m: DesktopModel, onPair: () -> Unit) {
    Panel(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer) {
        Text(m.text("Уже пользуешься Yumi?", "Already using Yumi?"), style = MaterialTheme.typography.headlineSmall)
        Text(
            m.text("Передай подписку с телефона по QR-коду.", "Transfer a subscription from your phone using a QR code."),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(onClick = onPair, shape = RoundedCornerShape(percent = 50)) {
            Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(m.text("С телефона", "From phone"))
        }
    }
}

/* ── Servers ──────────────────────────────────────────────────────────────────────────────── */

@Composable private fun ServersPage(m: DesktopModel) {
    val profiles by m.profiles.state.collectAsState()
    val busy by m.busy.collectAsState()
    var query by remember { mutableStateOf("") }
    val matches = remember(profiles.visibleNodes, query) { profiles.visibleNodes.filter { it.name.contains(query, true) } }
    val grid = rememberLazyGridState()

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader(
            m.strings.get(R.string.servers_title),
            m.strings.get(
                R.string.servers_subtitle,
                m.strings.plural(R.plurals.servers, profiles.visibleNodes.size),
                m.strings.plural(R.plurals.sources, profiles.subscriptions.size),
            ),
        ) {
            FilledTonalButton(
                onClick = { m.ping() },
                enabled = !busy && profiles.visibleNodes.isNotEmpty(),
                shape = RoundedCornerShape(percent = 50),
            ) {
                Icon(Icons.Rounded.Bolt, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(m.strings.get(R.string.servers_measure_latency))
            }
        }
        OutlinedTextField(
            query,
            { query = it },
            Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(m.strings.get(R.string.servers_search_placeholder)) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            shape = RoundedCornerShape(percent = 50),
        )
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (matches.isEmpty()) EmptyState(
            Icons.Rounded.Dns,
            m.strings.get(if (query.isBlank()) R.string.servers_empty else R.string.servers_empty_filtered),
            m.strings.get(if (query.isBlank()) R.string.servers_empty_hint else R.string.servers_empty_filtered_hint),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(300.dp),
                state = grid,
                modifier = Modifier.fillMaxSize().padding(end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(matches, key = { it.id }) { node ->
                    ServerCard(m, node, node.id == profiles.selectedNodeId, profiles.latencies[node.id])
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(grid), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

@Composable private fun ServerCard(m: DesktopModel, node: ProxyNode, selected: Boolean, latency: LatencyResult?) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val container by animateColorAsState(
        when {
            selected -> MaterialTheme.colorScheme.primaryContainer
            hovered -> MaterialTheme.colorScheme.surfaceContainerHigh
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        },
        label = "server-container",
    )
    Surface(
        onClick = { m.selectNode(node.id) },
        shape = RoundedCornerShape(26.dp),
        color = container,
        interactionSource = interaction,
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    rememberEmojiText(node.name, 15.sp),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    minLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (selected) Icon(Icons.Rounded.CheckCircle, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                ProtocolBadge(node.protocol)
                node.badges.take(2).forEach { FeatureBadge(it) }
                Spacer(Modifier.weight(1f))
                LatencyLabel(m, latency)
            }
        }
    }
}

/* ── Subscriptions ────────────────────────────────────────────────────────────────────────── */

@Composable private fun SubscriptionsPage(m: DesktopModel, onAdd: () -> Unit, onPair: () -> Unit) {
    val profiles by m.profiles.state.collectAsState()
    val busy by m.busy.collectAsState()
    var remove by remember { mutableStateOf<Subscription?>(null) }
    val grid = rememberLazyGridState()

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader(
            m.strings.get(R.string.subscriptions_title),
            m.strings.plural(R.plurals.sources, profiles.subscriptions.size),
        ) {
            OutlinedButton(onClick = { m.refreshAll() }, enabled = !busy, shape = RoundedCornerShape(percent = 50)) {
                Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(m.strings.get(R.string.action_refresh))
            }
            FilledTonalButton(onClick = onPair, shape = RoundedCornerShape(percent = 50)) {
                Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(m.text("С телефона", "From phone"))
            }
            Button(onClick = onAdd, shape = RoundedCornerShape(percent = 50)) {
                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(m.strings.get(R.string.action_add))
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (profiles.subscriptions.isEmpty()) EmptyState(
            Icons.Rounded.Cloud,
            m.strings.get(R.string.subscriptions_empty),
            m.text("Перенеси её из Yumi на телефоне или вставь ссылку.", "Transfer it from Yumi on your phone or paste a link."),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // A subscription card is a paragraph and two numbers. Stretched across a wide window
            // it puts the quota at one end and the expiry at the other with a metre of nothing
            // between them, so several sit side by side instead.
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
            shape = CardShape,
            title = { Text(m.text("Удалить подписку?", "Delete subscription?")) },
            text = { Text(sub.remoteTitle ?: sub.name) },
            confirmButton = { TextButton(onClick = { m.removeSubscription(sub.id); remove = null }) { Text(m.strings.get(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { remove = null }) { Text(m.strings.get(R.string.action_cancel)) } },
        )
    }
}

@Composable private fun SubscriptionCard(m: DesktopModel, sub: Subscription, busy: Boolean, onRemove: () -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                sub.remoteTitle ?: sub.name,
                Modifier.weight(1f),
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = { m.action { m.refresh(sub) } }, enabled = !busy) {
                Icon(Icons.Rounded.Refresh, m.strings.get(R.string.action_refresh))
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Rounded.DeleteOutline, m.strings.get(R.string.action_delete), tint = MaterialTheme.colorScheme.error)
            }
        }
        val info = sub.userInfo
        val fraction = info?.usedFraction
        if (fraction != null) {
            // Coloured explicitly rather than left to the default: a quota bar is a reading, and
            // the accent is what every other reading in this window is set in.
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    m.strings.get(
                        R.string.subscriptions_used_of,
                        m.strings.bytes(info.usedBytes ?: 0L).toString(),
                        m.strings.bytes(info.totalBytes ?: 0L).toString(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                val days = daysUntil(info.expiresAtEpochSeconds)
                if (days != null) Text(
                    if (days > 0) m.strings.get(R.string.subscriptions_days_left, days) else m.strings.get(R.string.subscriptions_expired),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (days > 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
        } else {
            Text(m.strings.get(R.string.subscriptions_no_quota), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            m.strings.get(
                R.string.subscriptions_updated,
                m.strings.plural(R.plurals.servers, sub.nodeIds.size),
                m.strings.relativeTime(sub.lastUpdatedEpochMillis),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (sub.lastError != null) Text(
            m.text("Не удалось обновить. Подписка сохранена.", "Refresh failed. Your subscription is saved."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/* ── Split tunnelling ─────────────────────────────────────────────────────────────────────── */

@Composable private fun RoutingPage(m: DesktopModel) {
    val current by m.desktop.state.collectAsState()
    val settings by m.settings.settings.collectAsState()
    var domains by remember(current.domains) { mutableStateOf(current.domains.joinToString("\n")) }
    var networks by remember(current.networks) { mutableStateOf(current.networks.joinToString("\n")) }
    var apps by remember(current.applications) { mutableStateOf(current.applications) }
    var mode by remember(current.splitMode) { mutableStateOf(current.splitMode) }
    var invalid by remember { mutableStateOf(false) }

    ScrollingColumn {
        PageHeader(m.text("Туннелирование", "Split tunneling"), m.text("Что идёт через VPN, а что мимо", "What goes through the VPN, and what goes around it"))
        Panel(Modifier.fillMaxWidth()) {
            Text(m.text("Куда направлять трафик", "Where traffic goes"), style = MaterialTheme.typography.headlineSmall)
            SplitMode.entries.forEach { value ->
                Row(Modifier.fillMaxWidth().clip(TileShape).clickable { mode = value }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(mode == value, { mode = value })
                    Text(
                        if (value == SplitMode.ExceptSelected) m.text("Всё через VPN, кроме выбранного", "Everything through VPN except selected")
                        else m.text("Только выбранное через VPN", "Only selected traffic through VPN"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Toggle(m.text("Локальная сеть напрямую", "Bypass local network"), settings.bypassLan) { m.changeSettings({ s -> s.copy(bypassLan = it) }, true) }
        }
        Panel(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(m.text("Приложения", "Applications"), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                OutlinedButton(
                    onClick = {
                        val chooser = JFileChooser()
                        chooser.fileFilter = FileNameExtensionFilter("Windows applications (*.exe)", "exe")
                        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                            apps = (apps + RuleValidation.application(chooser.selectedFile.absolutePath)).distinct()
                        }
                    },
                    shape = RoundedCornerShape(percent = 50),
                ) { Text(m.text("Выбрать .exe", "Choose .exe")) }
            }
            apps.forEach { path ->
                Row(
                    Modifier.fillMaxWidth().clip(TileShape).background(MaterialTheme.colorScheme.surfaceContainer).padding(start = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(path, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    IconButton(onClick = { apps = apps - path }) { Icon(Icons.Rounded.Close, m.text("Убрать", "Remove"), Modifier.size(18.dp)) }
                }
            }
            OutlinedTextField(
                domains,
                { domains = it; invalid = false },
                Modifier.fillMaxWidth(),
                label = { Text(m.text("Домены, по одному в строке", "Domains, one per line")) },
                minLines = 3,
                shape = TileShape,
                isError = invalid,
            )
            OutlinedTextField(
                networks,
                { networks = it; invalid = false },
                Modifier.fillMaxWidth(),
                label = { Text(m.text("IP и подсети, по одному в строке", "IP addresses and subnets, one per line")) },
                minLines = 3,
                shape = TileShape,
                isError = invalid,
            )
            Text(
                m.text(
                    "Домены включают поддомены. Сторонний защищённый DNS и ECH могут скрыть имя сайта: тогда используй правило приложения или IP. Системный DNS при включённом VPN идёт через выбранный резолвер.",
                    "Domains include subdomains. External encrypted DNS and ECH can hide site names: use an application or IP rule in that case. System DNS uses your selected resolver through VPN.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (invalid) Text(m.text("Проверь домены и IP-адреса.", "Check your domains and IP addresses."), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = {
                    try {
                        val next = current.copy(
                            splitMode = mode,
                            applications = apps,
                            domains = domains.lines().filter { it.isNotBlank() }.map(RuleValidation::domain).distinct(),
                            networks = networks.lines().filter { it.isNotBlank() }.map(RuleValidation::network).distinct(),
                        )
                        m.changeDesktop(next, true)
                    } catch (_: Exception) {
                        invalid = true
                    }
                },
                shape = RoundedCornerShape(percent = 50),
            ) { Text(m.text("Сохранить правила", "Save rules")) }
            Text(m.text("Активное соединение будет переподключено.", "An active connection will reconnect."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader(
            m.strings.get(R.string.speed_title),
            when {
                speed.phase == SpeedPhase.Failed -> speed.message ?: m.strings.get(R.string.speed_phase_failed)
                speed.throughTunnel && speed.serverName != null -> m.strings.get(R.string.speed_through_server, speed.serverName!!)
                speed.throughTunnel -> m.strings.get(R.string.speed_through_tunnel)
                status.state == "connected" -> m.text("Через VPN", "Through the tunnel")
                else -> m.text("Напрямую — туннель выключен, это канал самого ПК", "Direct — the tunnel is off, this is your PC's own link")
            },

        )
        // Two stages: the dial, and what the dial produced. The dial is on screen before the test
        // starts as well as during it — resting at zero with its scale showing, which is what a
        // dial is for. A screen that only grows an instrument once you press the button leaves the
        // reading with nothing to be read against.
        if (speed.hasResult && !running) {
            SpeedResult(m.strings.get(R.string.connect_rate_download), Icons.Rounded.ArrowDownward, m.strings.megabits(speed.downloadBytesPerSecond), speed.downloadSeries, semantic.download, Modifier.weight(1f))
            SpeedResult(m.strings.get(R.string.connect_rate_upload), Icons.Rounded.ArrowUpward, m.strings.megabits(speed.uploadBytesPerSecond), speed.uploadSeries, semantic.upload, Modifier.weight(1f))
        } else {
            Panel(Modifier.weight(1f).fillMaxWidth()) {
                BoxWithConstraints(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    val side = minOf(maxWidth, maxHeight)
                    // The wedge under the hub is a fixed fraction of the dial, and type is not.
                    // At 1280 the dial is four hundred points across and the invitation sits in it
                    // comfortably; shrink the window and the dial halves while the words stay at
                    // 26 sp, until they lie across the needle and the last two numbers of the
                    // scale. Past this size the dial keeps its own face and the words go under it.
                    val roomy = side >= 320.dp
                    SpeedGauge(
                        bytesPerSecond = speed.liveBytesPerSecond,
                        accent = accent,
                        trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(side),
                    )
                    // Below the hub, in the wedge the dial leaves open at the bottom. Centred, it
                    // would sit on the needle's pivot with the needle sweeping through the digits.
                    Column(
                        Modifier.offset(y = side * 0.26f).widthIn(max = side * 0.86f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (running) {
                            val reading = m.strings.megabits(speed.liveBytesPerSecond)
                            Text(
                                reading.value,
                                style = (if (roomy) MaterialTheme.typography.displayLarge else MaterialTheme.typography.headlineLarge)
                                    .copy(fontFeatureSettings = "tnum"),
                            )
                            Text(reading.unit, style = MaterialTheme.typography.labelLarge, color = accent)
                            if (roomy) {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    m.strings.get(speed.phase.labelRes),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        } else if (roomy) {
                            // The invitation and nothing else. At rest the needle lies along the
                            // bottom-left spoke and passes straight through this wedge, so a line
                            // of explanation put here comes out with a needle drawn across it —
                            // it goes under the dial instead. On a small dial even this much is
                            // too much, and the line below the dial says it on its own.
                            Text(m.text("Замерь канал", "Measure the link"), style = MaterialTheme.typography.headlineMedium)
                        }
                    }
                }
                if (!running) Text(
                    if (status.state == "connected")
                        m.text("Тест пройдёт через туннель и покажет, что даёт выбранный сервер.", "The test runs through the tunnel and shows what the chosen server gives you.")
                    else
                        m.text("Туннель выключен, так что будет измерен канал самого ПК.", "The tunnel is off, so this measures your PC's own link."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (running) {
                    SpeedTrace(
                        samples = speed.series,
                        accent = accent,
                        gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth().height(88.dp),
                    )
                    LinearProgressIndicator(
                        progress = { speed.phaseProgress },
                        modifier = Modifier.fillMaxWidth(),
                        color = accent,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (speed.latencyMillis > 0) Chip(m.strings.get(R.string.speed_latency_chip, speed.latencyMillis))
            if (speed.jitterMillis > 0) Chip(m.strings.get(R.string.speed_jitter_chip, speed.jitterMillis))
            if (speed.phase == SpeedPhase.Failed) Chip(m.strings.get(R.string.speed_interrupted))
        }
        Button(
            onClick = { if (running) m.cancelSpeed() else m.startSpeed() },
            enabled = status.state !in listOf("connecting", "disconnecting"),
            // Sits low, near the bottom edge of the window, where the hand already is.
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp).height(84.dp),
            shape = RoundedCornerShape(if (running) 30.dp else 42.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (running) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.primary,
                contentColor = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(
                when {
                    running -> Icons.Rounded.Stop
                    speed.hasResult -> Icons.Rounded.Refresh
                    else -> Icons.Rounded.PlayArrow
                },
                null,
                Modifier.size(26.dp),
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
            )
        }
    }
}

/**
 * One direction's result: the figure, then the shape it was made of. An average alone cannot
 * separate a steady link from one that burst and stalled to the same mean.
 */
@Composable private fun SpeedResult(
    label: String,
    icon: ImageVector,
    value: ValueAndUnit,
    samples: List<Long>,
    accent: Color,
    modifier: Modifier,
) {
    Column(
        modifier.fillMaxWidth().clip(CardShape).background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(17.dp), tint = accent)
            }
            Spacer(Modifier.width(10.dp))
            Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(value.value, style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"), color = accent)
                Text(value.unit, style = MaterialTheme.typography.labelMedium, color = accent.copy(alpha = 0.8f), modifier = Modifier.padding(bottom = 6.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        SpeedTrace(
            samples = samples,
            accent = accent,
            gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            live = false,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

/* ── Settings ─────────────────────────────────────────────────────────────────────────────── */

@Composable private fun SettingsPage(m: DesktopModel) {
    val settings by m.settings.settings.collectAsState()
    val desktop by m.desktop.state.collectAsState()
    var dns by remember(settings.remoteDns) { mutableStateOf(settings.remoteDns) }
    var mtu by remember(settings.mtu) { mutableStateOf(settings.mtu.toString()) }
    var invalid by remember { mutableStateOf(false) }

    ScrollingColumn {
        PageHeader(m.text("Настройки", "Settings"), "Yumi Desktop 0.1.2 · Xray · GPL-3.0-or-later")
        Panel(Modifier.fillMaxWidth()) {
            Text(m.text("Внешний вид", "Appearance"), style = MaterialTheme.typography.headlineSmall)
            ChipRow(m.text("Тема", "Theme")) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(settings.themeMode == mode, { m.changeSettings({ it.copy(themeMode = mode) }) }, label = { Text(m.strings.get(mode.labelRes)) })
                }
            }
            PaletteRow(m, settings, desktop)
            Toggle("AMOLED", settings.amoled, m.text("Чёрный фон вместо тёмно-синего", "True black instead of deep blue")) { m.changeSettings({ s -> s.copy(amoled = it) }) }
            ChipRow(m.text("Фигура", "Figure")) {
                Visualizer.entries.forEach { value ->
                    FilterChip(settings.visualizer == value, { m.changeSettings({ it.copy(visualizer = value) }) }, label = { Text(m.strings.get(value.labelRes)) })
                }
            }
            ChipRow(m.text("Язык", "Language")) {
                AppLanguage.entries.forEach { lang ->
                    FilterChip(settings.language == lang, { m.changeSettings({ it.copy(language = lang) }) }, label = { Text(m.strings.get(lang.labelRes)) })
                }
            }
        }
        Panel(Modifier.fillMaxWidth()) {
            Text(m.text("Соединение и DNS", "Connection and DNS"), style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                dns,
                { dns = it; invalid = false },
                Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(m.text("DNS-резолвер", "DNS resolver")) },
                shape = TileShape,
                isError = invalid,
            )
            Text(
                m.text("DoH (https://), TCP, UDP или IP-адрес. Без автоматической замены резолвера.", "DoH (https://), TCP, UDP or an IP address. No automatic resolver substitution."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(mtu, { mtu = it; invalid = false }, Modifier.width(220.dp), singleLine = true, label = { Text("MTU · 1280–9000") }, shape = TileShape, isError = invalid)
            Toggle("IPv6", settings.enableIpv6) { m.changeSettings({ s -> s.copy(enableIpv6 = it) }, true) }
            Button(
                onClick = {
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
                },
                shape = RoundedCornerShape(percent = 50),
            ) { Text(m.text("Сохранить соединение", "Save connection settings")) }
            if (invalid) Text(m.text("Проверь DNS и MTU.", "Check DNS and MTU."), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Panel(Modifier.fillMaxWidth()) {
            Text(m.text("Поведение", "Behavior"), style = MaterialTheme.typography.headlineSmall)
            Toggle(m.text("Запускать при входе в Windows", "Launch at Windows sign-in"), desktop.launchAtLogin) { m.changeDesktop(desktop.copy(launchAtLogin = it)) }
            Toggle(m.text("Обновлять подписки автоматически", "Refresh subscriptions automatically"), settings.subscriptionAutoUpdate) { m.changeSettings({ s -> s.copy(subscriptionAutoUpdate = it) }) }
            // Shown whatever the switch says, because the interval is what the switch means: a
            // greyed-out number would leave somebody turning the switch on to find out what they
            // had just agreed to. Refreshing on launch happens regardless — see DesktopModel.
            ChipRow(m.text("Как часто", "How often")) {
                UpdateIntervals.forEach { minutes ->
                    FilterChip(
                        settings.subscriptionUpdateMinutes == minutes,
                        { m.changeSettings({ it.copy(subscriptionUpdateMinutes = minutes) }) },
                        label = { Text(intervalLabel(m, minutes)) },
                        enabled = settings.subscriptionAutoUpdate,
                    )
                }
            }
            Text(
                m.text(
                    "Крестик сворачивает окно в трей. Выход через меню трея отключает VPN. Сервер меняется только по твоему выбору.",
                    "Closing the window keeps Yumi in the tray. Exit from the tray disconnects VPN. Servers change only when you choose them.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * How often subscriptions are re-read, as a row to pick from rather than a number to type.
 *
 * Half an hour to a day. Below it the provider is being asked more often than it changes; above it
 * the list is older than the session it is being used in. The app also refreshes once on launch,
 * so the shortest interval is not there to make the list fresh at the start of an evening — it is
 * there for somebody whose provider rotates servers during one.
 */
private val UpdateIntervals = listOf(30, 60, 180, 360, 720, 1440)

@Composable private fun intervalLabel(m: DesktopModel, minutes: Int): String = when {
    minutes % 60 == 0 -> m.text("${minutes / 60} ч", "${minutes / 60} h")
    else -> m.text("$minutes мин", "$minutes min")
}

/**
 * The palettes, shown as what they are.
 *
 * Named chips would make the user read six words and then guess which one is the pale green. A
 * swatch answers the question it is asked, and each one is painted in its own scheme's accent
 * rather than in the current one — the row is the choice, not a label for it.
 */
@Composable private fun PaletteRow(m: DesktopModel, settings: AppSettings, desktop: DesktopSettings) {
    val dark = isDark(settings)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            m.text("Палитра", "Palette").uppercase() + " · " + m.text(desktop.palette.ru, desktop.palette.en),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Palette.entries.forEach { option ->
                val scheme = remember(option, dark) { option.scheme(dark) }
                val chosen = option == desktop.palette
                Surface(
                    onClick = { m.changeDesktop(desktop.copy(palette = option)) },
                    modifier = Modifier.size(42.dp).testTag("palette-${option.name}"),
                    shape = CircleShape,
                    color = scheme.primary,
                    contentColor = scheme.onPrimary,
                    border = if (chosen) BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface) else null,
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (chosen) Icon(Icons.Rounded.Check, m.text(option.ru, option.en), Modifier.size(19.dp))
                    }
                }
            }
        }
    }
}

@Composable private fun ChipRow(label: String, content: @Composable RowScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

/* ── Dialogs ──────────────────────────────────────────────────────────────────────────────── */

@Composable private fun ImportDialog(m: DesktopModel, onClose: () -> Unit) {
    var raw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        shape = CardShape,
        title = { Text(m.text("Добавить в Yumi", "Add to Yumi"), style = MaterialTheme.typography.headlineMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(m.text("Ссылка подписки или конфигурация сервера", "Subscription link or server configuration"), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(raw, { raw = it }, Modifier.width(460.dp), minLines = 4, maxLines = 8, shape = TileShape)
            }
        },
        confirmButton = {
            Button(onClick = { m.importText(raw); onClose() }, enabled = raw.isNotBlank(), shape = RoundedCornerShape(percent = 50)) {
                Text(m.strings.get(R.string.action_add))
            }
        },
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
        shape = CardShape,
        title = { Text(m.text("Подписка с телефона", "Subscription from phone"), style = MaterialTheme.typography.headlineMedium) },
        text = {
            Column(Modifier.width(440.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    m.text(
                        "Подключи устройства к одной локальной сети. В Yumi на телефоне открой сканер, отсканируй QR и выбери подписку.",
                        "Connect both devices to the same local network. Open the scanner in Yumi on your phone, scan the QR and choose a subscription.",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                when (val s = state) {
                    is PairingReceiverState.Waiting -> {
                        Surface(shape = TileShape, color = Color.White) {
                            QrCode(s.invite.encode(), Modifier.size(290.dp).padding(14.dp))
                        }
                        Text(m.text("Одноразовый код · 5 минут", "Single-use code · 5 minutes"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is PairingReceiverState.Receiving -> {
                        ShapeSpinner(MaterialTheme.colorScheme.primary, size = 34.dp)
                        Text(m.text("Сохраняем подписку…", "Saving subscription…"), style = MaterialTheme.typography.bodyMedium)
                    }
                    is PairingReceiverState.Complete -> {
                        Icon(Icons.Rounded.CheckCircle, null, Modifier.size(60.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(
                            if (s.result.status == "saved_refresh_failed") m.text("Подписка сохранена. Обнови её после проверки соединения.", "Subscription saved. Refresh it after checking your connection.")
                            else m.text("Подписка перенесена", "Subscription transferred"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    is PairingReceiverState.Failed -> Text(
                        m.text(
                            "Не удалось принять подписку. Проверь локальную сеть, брандмауэр и срок действия QR.",
                            "Transfer failed. Check your local network, firewall and QR expiry.",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> ShapeSpinner(MaterialTheme.colorScheme.primary, size = 34.dp)
                }
                if (addresses.size > 1) addresses.forEach { address ->
                    Row(
                        Modifier.fillMaxWidth().clip(TileShape).clickable { m.selectedAddress.value = address; m.startPairing() },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(address == selected, { m.selectedAddress.value = address; m.startPairing() })
                        Text(address.label, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(m.strings.get(R.string.action_close)) } },
        dismissButton = { TextButton(onClick = { m.startPairing() }) { Text(m.text("Новый QR", "New QR")) } },
    )
}

/* ── Words for the service's error codes ──────────────────────────────────────────────────── */

private fun serviceError(m: DesktopModel, code: String) = when (code) {
    "invalid_dns" -> m.text("Проверь адрес DNS-резолвера.", "Check the DNS resolver address.")
    "invalid_configuration", "forbidden_field", "unsupported_protocol" ->
        m.text("Конфигурация сервера не поддерживается ядром.", "The server configuration is not supported by the core.")
    else -> m.text("Не удалось запустить туннель. Проверь VPN-службу и выбранный сервер.", "Could not start the tunnel. Check the service and selected server.")
}

