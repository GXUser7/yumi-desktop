package com.yumi.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mydrop.vpn.ui.components.rememberEmojiText
import com.mydrop.vpn.ui.theme.KickerStyle

/*
 * The shared furniture, after YouCloud's ExpressiveComponents.
 *
 * What makes it expressive is motion and shape, not decoration: a pressed control sinks and rounds
 * on a spring, a switched-on one squares off and bounces, and every surface is glass with no rim.
 * The one ring left is the one that says "this is the selected one".
 */

/** The shape scale, a step rounder than stock Material 3 (DESIGN.md, "Формы"). */
object Radii {
    val small = 12.dp
    val medium = 16.dp
    val large = 24.dp
    val largeIncreased = 28.dp
    val extraLarge = 32.dp
    val extraLargeIncreased = 36.dp
    val extraExtraLarge = 44.dp
}

val PanelShape = RoundedCornerShape(Radii.extraLarge)
val TileShape = RoundedCornerShape(Radii.largeIncreased)
val RowShape = RoundedCornerShape(Radii.large)
val PillShape = RoundedCornerShape(percent = 50)

/** Springs, not curves: everything settles. */
object Springs {
    /** A tile or button under the pointer: sinks and rounds. */
    fun <T> press() = spring<T>(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)
    /** A switch turned on: squares off with a bounce. */
    fun <T> toggle() = spring<T>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
    /** Something moved into place by the user's choice. */
    fun <T> settle() = spring<T>(dampingRatio = 0.86f, stiffness = 320f)
}

/** Colour changes on switches and plates take 300 ms, as YouCloud's do. */
fun <T> colorTween() = tween<T>(300)

/**
 * The press response: 0.95 and a spring back. Applied to the layer, so a pressed tile never
 * re-measures its neighbours.
 */
@Composable
fun Modifier.pressMorph(interaction: MutableInteractionSource, enabled: Boolean = true): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.95f else 1f, Springs.press(), label = "press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/** A pane of glass with a page's worth of padding in it. */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = PanelShape,
    tint: Color = glassTint(),
    padding: PaddingValues = PaddingValues(22.dp),
    spacing: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.glass(shape, tint).padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/** The spaced capitals over a block. */
@Composable
fun Kicker(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text.uppercase(), modifier, style = KickerStyle, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** A panel's title, with an optional kicker over it. */
@Composable
fun SectionTitle(title: String, kicker: String? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (kicker != null) Kicker(kicker)
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

internal val RegularPageHeaderHeight = 184.dp
internal val CompactContentTop = 172.dp
internal val LocalCompactLayout = staticCompositionLocalOf { false }
internal val LocalDenseLayout = staticCompositionLocalOf { false }
internal val LocalCompactContentTop = staticCompositionLocalOf { CompactContentTop }

@Composable internal fun pageHeaderHeight(): Dp =
    animateDpAsState(if (LocalCompactLayout.current) LocalCompactContentTop.current - 88.dp else RegularPageHeaderHeight, tween(220), label = "page-header-height").value

@Composable internal fun pagePosterStyle(compact: Boolean = LocalCompactLayout.current, tight: Boolean = false): androidx.compose.ui.text.TextStyle {
    val size by animateFloatAsState(if (compact) 26f else if (tight) 36f else 46f, tween(220), label = "page-title-size")
    val line by animateFloatAsState(if (compact) 32f else if (tight) 44f else 54f, tween(220), label = "page-title-line")
    return MaterialTheme.typography.displayLarge.copy(fontSize = size.sp, lineHeight = line.sp)
}

/** A single header island, on the same grid as the connection poster. */
@Composable
fun PageTitle(title: String, subtitle: String? = null, description: String? = null, controlsBelow: Boolean = false, actions: (@Composable RowScope.() -> Unit)? = null) {
    val compact = LocalCompactLayout.current
    val baseHeight = pageHeaderHeight()
    val extraHeight by animateDpAsState(if (compact && !controlsBelow && actions == null) 76.dp else 0.dp, tween(220), label = "page-description-height")
    val headerHeight = baseHeight + extraHeight
    val poster = pagePosterStyle()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stackActions = actions != null && maxWidth < 850.dp
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().height(headerHeight).testTag("page-header")
                .glass(PanelShape, glassTint()).padding(if (compact) 12.dp else 22.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Text(rememberEmojiText((subtitle ?: "Yumi Desktop").uppercase(), 12.sp),
                        style = KickerStyle, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
                    Text(title, Modifier.testTag("page-title"), style = poster,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (description != null && (!compact || (!controlsBelow && actions == null))) {
                        Spacer(Modifier.height(8.dp))
                        Text(description, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (compact) 3 else 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (!stackActions && actions != null) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
            }
            if (stackActions) Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically, content = checkNotNull(actions))
        }
    }
}

/**
 * What went wrong, said the way YouCloud says it: a matte card, an icon in a cookie, what happened
 * in a person's words, and the one thing to do about it in the accent. Never a red plate.
 */
@Composable
fun TroubleNote(
    icon: ImageVector,
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: () -> Unit = {},
    detail: String? = null,
) {
    GlassPanel(modifier, shape = TileShape, padding = PaddingValues(18.dp), spacing = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(44.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(MaterialTheme.colorScheme.tertiaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        }
        if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (detail != null) Text(
            detail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (action != null) AccentButton(action, onClick = onAction)
    }
}

/** The one loud action of a panel: accent fill, 20 dp corners that round further when pressed. */
@Composable
fun AccentButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner by animateDpAsState(if (pressed) 26.dp else 20.dp, Springs.press(), label = "accent-corner")
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp).pressMorph(interaction, enabled),
        shape = RoundedCornerShape(corner),
        interactionSource = interaction,
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** A quiet action on glass: a tonal pill, no outline. */
@Composable
fun QuietButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 44.dp).pressMorph(interaction, enabled),
        shape = PillShape,
        interactionSource = interaction,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/**
 * YouCloud's panel icon button: a circle; switched on it takes the accent and squares off to a
 * corner of 30 % of its size, with a bounce. Pressing it sinks it.
 */
@Composable
fun PanelIconButton(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    checked: Boolean = false,
    size: Dp = 56.dp,
    enabled: Boolean = true,
    /** The corner, in per cent of the size, once switched on: 30 squares it off, 50 keeps the circle. */
    checkedCorner: Int = 30,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val corner by animateFloatAsState(
        when {
            checked -> checkedCorner.toFloat()
            pressed -> 40f
            else -> 50f
        },
        Springs.toggle(),
        label = "icon-corner",
    )
    val scale by animateFloatAsState(if (checked) 1.0f else if (pressed) 0.95f else 1f, Springs.toggle(), label = "icon-scale")
    val container by animateColorAsState(
        when {
            checked -> MaterialTheme.colorScheme.primary
            hovered -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f)
            else -> Color.Transparent
        },
        colorTween(),
        label = "icon-container",
    )
    val content by animateColorAsState(
        if (checked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        colorTween(),
        label = "icon-content",
    )
    Box(
        modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(percent = corner.toInt()))
            .background(container)
            .hoverable(interaction)
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(if (size < 40.dp) 20.dp else 24.dp), tint = content)
    }
}

/** A setting that is on or off: the words, the hint under them, the switch at the end. */
@Composable
fun SwitchRow(title: String, checked: Boolean, hint: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    // Not clipped: the words start at the row's very edge, and a rounded clip there shaved the
    // first letter of every line.
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(interaction, indication = null, enabled = enabled) { onChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange, enabled = enabled, interactionSource = interaction)
    }
}

/** One choice out of a few, as pills: the chosen one in the accent, the rest a quiet plate.
 *  Wraps rather than overflowing: adapter names are as long as Windows says they are. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceRow(label: String?, options: List<T>, selected: T, text: @Composable (T) -> String, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (label != null) Kicker(label)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                val chosen = option == selected
                val interaction = remember { MutableInteractionSource() }
                val container by animateColorAsState(
                    if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f),
                    colorTween(),
                    label = "choice",
                )
                val content by animateColorAsState(
                    if (chosen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    colorTween(),
                    label = "choice-content",
                )
                Box(
                    Modifier
                        .pressMorph(interaction)
                        .clip(PillShape)
                        .background(container)
                        .clickable(interaction, indication = null) { onSelect(option) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(text(option), style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
                }
            }
        }
    }
}

/**
 * A text field on glass: a quiet plate with no outline, and a ring only while it is being typed in
 * — the one place the design keeps a ring for something that is not selected.
 */
@Composable
fun GlassField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    label: String? = null,
    leading: ImageVector? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    isError: Boolean = false,
    shape: androidx.compose.ui.graphics.Shape = if (singleLine) PillShape else RowShape,
    containerColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isLightTheme()) 0.05f else 0.07f),
    singleLineHeight: Dp? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val ring by animateColorAsState(
        when {
            isError -> MaterialTheme.colorScheme.error
            focused -> MaterialTheme.colorScheme.primary
            else -> Color.Transparent
        },
        colorTween(),
        label = "field-ring",
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) Kicker(label, color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = minLines,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (singleLine && singleLineHeight != null) Modifier.height(singleLineHeight) else Modifier)
                        .clip(shape)
                        .background(containerColor)
                        .border(BorderStroke(1.5.dp, ring), shape)
                        .padding(horizontal = 18.dp, vertical = if (singleLine && singleLineHeight != null) 0.dp else if (singleLine) 13.dp else 14.dp),
                    verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (leading != null) Icon(leading, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder != null) Text(
                            placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            maxLines = if (singleLine) 1 else minLines,
                        )
                        inner()
                    }
                }
            },
        )
    }
}

/** A small plate on a panel: a fact, not a control. */
@Composable
fun Plate(text: String, modifier: Modifier = Modifier, loud: Boolean = false) {
    Text(
        text,
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (loud) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        color = if (loud) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

/** A dot of state that keeps its colour moving with the state. */
@Composable
fun StateDot(color: Color, size: Dp = 9.dp) {
    val animated by animateColorAsState(color, colorTween(), label = "dot")
    Box(Modifier.size(size).clip(CircleShape).background(animated))
}
