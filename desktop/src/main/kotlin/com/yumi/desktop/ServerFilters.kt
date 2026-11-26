package com.yumi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.CountryFilterMode
import com.mydrop.vpn.core.model.Protocol
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.ServerFilter
import com.mydrop.vpn.core.model.ServerSort
import com.mydrop.vpn.core.model.WorldMap
import com.mydrop.vpn.shared.R

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ServerFiltersBar(
    m: DesktopModel,
    nodes: List<ProxyNode>,
    settings: AppSettings,
) {
    val s = m.strings
    val availableProtocols = remember(nodes) {
        ServerFilter.availableProtocols(nodes).sortedBy { it.label }
    }
    val availableCountries = remember(nodes) {
        ServerFilter.availableSubscriptionCountries(nodes).sorted()
    }
    val activeProtocols = remember(nodes, settings.serverFilterProtocols) {
        ServerFilter.resolveProtocols(nodes, settings.serverFilterProtocols)
    }
    val activeCountries = remember(nodes, settings.serverFilterCountries) {
        ServerFilter.resolveCountries(nodes, settings.serverFilterCountries)
    }
    var showCountries by remember { mutableStateOf(false) }

    val hasActiveFilter = activeProtocols.isNotEmpty() ||
        activeCountries.isNotEmpty() ||
        settings.serverSort != ServerSort.Default

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChipPill(
                label = s.get(R.string.servers_fastest),
                icon = Icons.Rounded.Bolt,
                selected = false,
                enabled = nodes.isNotEmpty(),
                modifier = Modifier.testTag("fastest-server"),
            ) { m.selectFastestServer() }

            FilterChipPill(
                label = "${s.get(R.string.servers_filter_sort_label)}: ${s.get(settings.serverSort.shortLabelRes)}",
                icon = Icons.Rounded.SwapVert,
                selected = settings.serverSort != ServerSort.Default,
                modifier = Modifier.testTag("sort-servers"),
            ) {
                val entries = ServerSort.entries
                val next = entries[(entries.indexOf(settings.serverSort) + 1) % entries.size]
                m.setServerSort(next)
            }

            if (availableCountries.isNotEmpty()) {
                val label = if (activeCountries.isEmpty()) {
                    s.get(R.string.servers_filter_countries)
                } else {
                    val mode = if (settings.serverFilterCountryMode == CountryFilterMode.Exclude) "≠ " else ""
                    "$mode${s.get(R.string.servers_filter_countries)} (${activeCountries.size})"
                }
                FilterChipPill(
                    label = label,
                    icon = Icons.Rounded.Public,
                    selected = activeCountries.isNotEmpty() || showCountries,
                    modifier = Modifier.testTag("country-filter-toggle"),
                ) { showCountries = !showCountries }
            }

            availableProtocols.forEach { protocol ->
                FilterChipPill(
                    label = protocol.label,
                    selected = protocol in activeProtocols,
                    modifier = Modifier.testTag("filter-protocol-${protocol.name}"),
                ) { m.toggleProtocolFilter(protocol) }
            }

            if (hasActiveFilter) {
                FilterChipPill(
                    label = s.get(R.string.servers_filter_clear),
                    icon = Icons.Rounded.Clear,
                    selected = false,
                    modifier = Modifier.testTag("clear-server-filters"),
                ) { m.resetServerFilters() }
            }
        }

        if (showCountries && availableCountries.isNotEmpty()) {
            GlassPanel(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CountryFilterMode.entries.forEach { mode ->
                            val text = when (mode) {
                                CountryFilterMode.Include -> s.get(R.string.servers_filter_country_mode_include)
                                CountryFilterMode.Exclude -> s.get(R.string.servers_filter_country_mode_exclude)
                            }
                            FilterChipPill(
                                label = text,
                                selected = settings.serverFilterCountryMode == mode,
                            ) { m.setCountryFilterMode(mode) }
                        }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        availableCountries.forEach { code ->
                            val flag = WorldMap.flagOf(code).orEmpty()
                            FilterChipPill(
                                label = if (flag.isNotEmpty()) "$flag $code" else code,
                                selected = code in activeCountries,
                                modifier = Modifier.testTag("filter-country-$code"),
                            ) { m.toggleCountryFilter(code) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChipPill(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val bg = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    }
    val fg = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        selected -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier
            .pressMorph(interaction)
            .clip(PillShape)
            .background(bg)
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(15.dp), tint = fg)
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
    }
}
