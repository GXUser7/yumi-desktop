package com.yumi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.mydrop.vpn.shared.R
import com.yumi.desktop.platform.openExternalUrl

private const val GITHUB_ISSUES_URL = "https://github.com/GXUser7/Yumi/issues"

private data class ContributorEntry(
    val name: String,
    val tag: String,
    val roleRes: Int,
    val badgeRes: Int,
)

private val GRATITUDE_CONTRIBUTORS = listOf(
    ContributorEntry("Sha Li", "tester", R.string.gratitude_sha_li, R.string.gratitude_badge_telegram),
    ContributorEntry("EternalBlueInfinity", "#12", R.string.gratitude_eternal_blue, R.string.gratitude_badge_telegram),
    ContributorEntry("Lesak777", "#11", R.string.gratitude_lesak, R.string.gratitude_badge_telegram),
    ContributorEntry("Obsida", "#9", R.string.gratitude_obsida, R.string.gratitude_badge_telegram),
    ContributorEntry("thetimick", "#2", R.string.gratitude_thetimick, R.string.gratitude_badge_telegram),
    ContributorEntry("aror", "tester", R.string.gratitude_aror, R.string.gratitude_badge_tester),
)

@Composable
internal fun GratitudeDialog(m: DesktopModel, onClose: () -> Unit) {
    val s = m.strings
    Dialog(onDismissRequest = onClose) {
        GlassPanel(
            Modifier
                .widthIn(min = 440.dp, max = 560.dp)
                .heightIn(max = 640.dp),
        ) {
            Column(
                Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Rounded.Favorite,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(Modifier.weight(1f)) {
                        SectionTitle(s.get(R.string.settings_gratitude_title))
                        Text(
                            s.get(R.string.settings_gratitude_sheet_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    PanelIconButton(Icons.Rounded.Close, s.get(R.string.action_close), size = 40.dp, onClick = onClose)
                }

                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GRATITUDE_CONTRIBUTORS.forEachIndexed { index, entry ->
                        val shape = when {
                            GRATITUDE_CONTRIBUTORS.size <= 1 -> RowShape
                            index == 0 -> RoundedCornerShape(topStart = Radii.large, topEnd = Radii.large, bottomStart = 6.dp, bottomEnd = 6.dp)
                            index == GRATITUDE_CONTRIBUTORS.lastIndex -> RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = Radii.large, bottomEnd = Radii.large)
                            else -> RoundedCornerShape(6.dp)
                        }
                        val highlighted = entry.tag == "tester"
                        val container = if (highlighted) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(shape)
                                .background(container)
                                .clickable { openExternalUrl(GITHUB_ISSUES_URL) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        entry.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Plate(entry.tag, loud = highlighted)
                                }
                                Text(
                                    s.get(entry.roleRes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Plate(s.get(entry.badgeRes))
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        s.get(R.string.settings_gratitude_footer_prompt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    AccentButton(
                        s.get(R.string.settings_gratitude_footer_action),
                        icon = Icons.AutoMirrored.Rounded.OpenInNew,
                    ) {
                        openExternalUrl(GITHUB_ISSUES_URL)
                    }
                }
            }
        }
    }
}
