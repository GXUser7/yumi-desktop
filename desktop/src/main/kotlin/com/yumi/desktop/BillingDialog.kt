package com.yumi.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.mydrop.vpn.core.model.BillingAddon
import com.mydrop.vpn.core.model.BillingManifest
import com.mydrop.vpn.core.model.BillingPlan
import com.mydrop.vpn.core.model.Subscription
import com.mydrop.vpn.core.model.formatBillingMoney
import com.mydrop.vpn.core.net.billingDisplayHost
import com.mydrop.vpn.shared.R

data class BillingDialogState(
    val subscription: Subscription,
    val loading: Boolean = false,
    val manifest: BillingManifest? = null,
    val loadError: String? = null,
    val submitting: Boolean = false,
    val checkoutError: String? = null,
)

private enum class BillingTab { Plans, Addons }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BillingDialog(m: DesktopModel, state: BillingDialogState) {
    val s = m.strings
    val manifest = state.manifest
    var tab by remember(manifest) {
        mutableStateOf(
            if (manifest != null && manifest.plans.isEmpty() && manifest.addons.isNotEmpty()) BillingTab.Addons else BillingTab.Plans,
        )
    }
    var selectedPlanId by remember(manifest) { mutableStateOf(manifest?.plans?.firstOrNull()?.id) }
    var selectedAddonId by remember(manifest) { mutableStateOf(manifest?.addons?.firstOrNull()?.id) }
    var selectedPayId by remember(manifest) { mutableStateOf(manifest?.paymentMethods?.firstOrNull()?.id.orEmpty()) }
    var promoExpanded by remember { mutableStateOf(false) }
    var promoDraft by remember { mutableStateOf("") }
    var promoApplied by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = { m.dismissBillingSheet() }) {
        GlassPanel(
            Modifier
                .widthIn(min = 500.dp, max = 660.dp)
                .heightIn(max = 700.dp),
        ) {
            Column(
                Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        SectionTitle(manifest?.provider?.name ?: state.subscription.remoteTitle ?: state.subscription.name)
                        Text(
                            s.get(R.string.billing_sheet_title),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val supportUrl = manifest?.provider?.supportUrl ?: state.subscription.supportUrl
                    if (supportUrl != null) {
                        QuietButton(s.get(R.string.subscriptions_action_support), icon = Icons.AutoMirrored.Rounded.OpenInNew) {
                            m.openExternalBillingUrl(supportUrl)
                        }
                    }
                    PanelIconButton(Icons.Rounded.Close, s.get(R.string.action_close), size = 40.dp) { m.dismissBillingSheet() }
                }

                when {
                    state.loading -> {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(12.dp))
                            Text(s.get(R.string.billing_sheet_loading), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    state.loadError != null -> {
                        TroubleNote(Icons.Rounded.ErrorOutline, s.get(R.string.billing_sheet_title), state.loadError)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AccentButton(s.get(R.string.billing_sheet_retry), icon = Icons.Rounded.Refresh) {
                                m.retryBillingManifest()
                            }
                            state.subscription.webPageUrl?.let { portal ->
                                QuietButton(s.get(R.string.billing_sheet_open_web), icon = Icons.AutoMirrored.Rounded.OpenInNew) {
                                    m.openExternalBillingUrl(portal)
                                }
                            }
                        }
                    }
                    manifest != null -> {
                        manifest.account?.let { account ->
                            val used = account.devicesUsed
                            val limit = account.deviceLimit
                            val details = listOfNotNull(
                                account.currentPlan,
                                account.expiresAt,
                                if (used != null && limit != null) {
                                    s.get(R.string.billing_devices_count, used, limit)
                                } else null,
                            ).joinToString(" · ")
                            if (details.isNotEmpty()) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RowShape)
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                ) {
                                    Text(details, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        if (manifest.plans.isNotEmpty() && manifest.addons.isNotEmpty()) {
                            ChoiceRow(
                                label = null,
                                options = BillingTab.entries,
                                selected = tab,
                                text = {
                                    when (it) {
                                        BillingTab.Plans -> s.get(R.string.billing_sheet_tab_plans)
                                        BillingTab.Addons -> s.get(R.string.billing_sheet_tab_addons)
                                    }
                                },
                                onSelect = { tab = it },
                            )
                        }

                        Column(
                            Modifier
                                .weight(1f, fill = false)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            when (tab) {
                                BillingTab.Plans -> {
                                    if (manifest.plans.isEmpty()) {
                                        Text(s.get(R.string.billing_sheet_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    } else {
                                        manifest.plans.forEach { plan ->
                                            PlanOptionRow(m, plan, selected = plan.id == selectedPlanId) {
                                                selectedPlanId = plan.id
                                            }
                                        }
                                    }
                                }
                                BillingTab.Addons -> {
                                    if (manifest.addons.isEmpty()) {
                                        Text(s.get(R.string.billing_sheet_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    } else {
                                        manifest.addons.forEach { addon ->
                                            AddonOptionRow(addon, selected = addon.id == selectedAddonId) {
                                                selectedAddonId = addon.id
                                            }
                                        }
                                    }
                                }
                            }

                            if (manifest.promoSupported) {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RowShape)
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            if (promoApplied != null) {
                                                s.get(R.string.billing_sheet_promo_applied, promoApplied!!)
                                            } else {
                                                s.get(R.string.billing_sheet_promo_toggle)
                                            },
                                            style = MaterialTheme.typography.labelLarge,
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (promoApplied != null) {
                                            QuietButton(s.get(R.string.billing_sheet_promo_clear)) {
                                                promoApplied = null
                                                promoDraft = ""
                                            }
                                        } else {
                                            QuietButton(if (promoExpanded) "−" else "+") {
                                                promoExpanded = !promoExpanded
                                            }
                                        }
                                    }
                                    if (promoExpanded && promoApplied == null) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            GlassField(
                                                value = promoDraft,
                                                onValueChange = { promoDraft = it },
                                                modifier = Modifier.weight(1f),
                                                placeholder = s.get(R.string.billing_sheet_promo_label),
                                            )
                                            AccentButton(s.get(R.string.billing_sheet_promo_apply), enabled = promoDraft.isNotBlank()) {
                                                promoApplied = promoDraft.trim()
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (manifest.paymentMethods.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                manifest.paymentMethods.forEach { method ->
                                    val chosen = method.id == selectedPayId
                                    Box(
                                        Modifier
                                            .clip(PillShape)
                                            .background(
                                                if (chosen) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f),
                                            )
                                            .clickable { selectedPayId = method.id }
                                            .padding(horizontal = 14.dp, vertical = 8.dp),
                                    ) {
                                        Text(
                                            method.title,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = if (chosen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                        }

                        state.checkoutError?.let {
                            TroubleNote(Icons.Rounded.ErrorOutline, s.get(R.string.billing_sheet_title), it)
                        }

                        val chosenPlan = if (tab == BillingTab.Plans) manifest.plans.firstOrNull { it.id == selectedPlanId } else null
                        val chosenAddon = if (tab == BillingTab.Addons) manifest.addons.firstOrNull { it.id == selectedAddonId } else null
                        val priceLabel = when {
                            chosenPlan != null -> formatBillingMoney(chosenPlan.price, chosenPlan.currency, chosenPlan.currencyDecimals)
                            chosenAddon != null -> formatBillingMoney(chosenAddon.price, chosenAddon.currency, chosenAddon.currencyDecimals)
                            else -> null
                        }
                        val endpoint = manifest.checkout?.endpoint

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (endpoint != null && priceLabel != null) {
                                val free = (chosenPlan?.price ?: chosenAddon?.price ?: 1L) <= 0L
                                AccentButton(
                                    if (state.submitting) "…"
                                    else if (free) s.get(R.string.billing_sheet_pay_free_cta)
                                    else s.get(R.string.billing_sheet_pay_cta, priceLabel),
                                    modifier = Modifier.weight(1f),
                                    enabled = !state.submitting && (manifest.paymentMethods.isEmpty() || selectedPayId.isNotBlank()),
                                ) {
                                    m.clearBillingCheckoutError()
                                    m.submitBillingCheckout(
                                        planId = chosenPlan?.id,
                                        addonId = chosenAddon?.id,
                                        paymentMethod = selectedPayId.ifBlank { "default" },
                                        promoCode = promoApplied,
                                    )
                                }
                            }
                            val fallbackUrl = manifest.fallbackWebUrl ?: state.subscription.webPageUrl
                            if (fallbackUrl != null) {
                                QuietButton(s.get(R.string.billing_sheet_open_web), icon = Icons.AutoMirrored.Rounded.OpenInNew) {
                                    m.openExternalBillingUrl(fallbackUrl)
                                }
                            }
                        }
                        if (endpoint != null) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.size(6.dp))
                                Text(
                                    s.get(R.string.billing_sheet_domain_hint, billingDisplayHost(endpoint)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanOptionRow(m: DesktopModel, plan: BillingPlan, selected: Boolean, onClick: () -> Unit) {
    val s = m.strings
    val ring = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else BorderStroke(0.dp, Color.Transparent)
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RowShape)
            .background(bg)
            .border(ring, RowShape)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(plan.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    plan.badge?.takeIf { it.isNotBlank() }?.let { Plate(it, loud = true) }
                }
                val trafficGb = plan.trafficGb
                val meta = listOfNotNull(
                    plan.durationDays?.let { s.get(R.string.billing_days_duration, it) },
                    when {
                        trafficGb == null -> null
                        trafficGb <= 0 -> s.get(R.string.billing_traffic_unlimited)
                        else -> s.get(R.string.billing_traffic_gb, trafficGb)
                    },
                    plan.devices?.let { s.get(R.string.billing_devices_count, it, it) },
                ).joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(formatBillingMoney(plan.price, plan.currency, plan.currencyDecimals), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                plan.oldPrice?.let {
                    Text(
                        formatBillingMoney(it, plan.currency, plan.currencyDecimals),
                        style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.LineThrough),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (plan.features.isNotEmpty()) {
            Text(plan.features.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AddonOptionRow(addon: BillingAddon, selected: Boolean, onClick: () -> Unit) {
    val ring = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else BorderStroke(0.dp, Color.Transparent)
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RowShape)
            .background(bg)
            .border(ring, RowShape)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onPrimary)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(addon.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                addon.badge?.takeIf { it.isNotBlank() }?.let { Plate(it, loud = true) }
            }
            addon.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(formatBillingMoney(addon.price, addon.currency, addon.currencyDecimals), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}
