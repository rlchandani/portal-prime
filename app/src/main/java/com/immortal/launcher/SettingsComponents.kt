/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import com.immortal.launcher.ui.theme.LocalApprovedPalette
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared native settings rows for the approved Portal+ UI, with D-pad support. */

// ---------------------------------------------------------------------------
// iOS color palette
// ---------------------------------------------------------------------------

// Keep these names stable: existing editors share the approved palette supplied by the theme.
internal val IosBackground: Color @Composable get() = LocalApprovedPalette.current.background
internal val IosSidebar: Color @Composable get() = LocalApprovedPalette.current.sidebar
internal val IosCard: Color @Composable get() = LocalApprovedPalette.current.surface
internal val IosLabel: Color @Composable get() = LocalApprovedPalette.current.text
internal val IosSecondary: Color @Composable get() = LocalApprovedPalette.current.secondary
internal val IosTertiary: Color @Composable get() = LocalApprovedPalette.current.secondary
internal val IosDivider: Color @Composable get() = LocalApprovedPalette.current.divider
internal val IosBlue: Color @Composable get() = LocalApprovedPalette.current.accent
internal val IosGreen: Color @Composable get() = LocalApprovedPalette.current.green
internal val IosChevron: Color @Composable get() = LocalApprovedPalette.current.secondary
internal val IosRed: Color @Composable get() = LocalApprovedPalette.current.error
internal val IosFocusTint: Color @Composable get() = LocalApprovedPalette.current.selected
internal val IosSegmentTrack: Color @Composable get() = LocalApprovedPalette.current.surface2
internal val IosSegmentInactive: Color @Composable get() = LocalApprovedPalette.current.secondary

/** Revision 04 measurements in dp/sp, at the Portal+'s native 1920 × 1280 dp viewport. */
internal object Sizes {
    val titlePage = 40.sp
    val panelTitle = 40.sp
    val panelTitleTop = 12.dp
    val panelTitleBottom = 34.dp
    val pageSubtitle = 23.sp
    val sectionLabel = 22.sp
    val navLabel = 22.sp
    val rowTitle = 26.sp
    val rowSub = 20.sp
    val rowValue = 23.sp
    val actionButton = 24.sp
    val segmentLabel = 23.sp
    val chevron = 24.sp
    val iconBadge = 42.dp
    val iconGlyph = 26.sp
    val iconRadius = 11.dp
    val rowMinHeight = 100.dp
    val cardRadius = 24.dp
    val sidebarWidth = 368.dp
    val headerHeight = 104.dp
    val primaryTarget = 68.dp
}

// ---------------------------------------------------------------------------
// Section label
// ---------------------------------------------------------------------------

/**
 * iOS-style uppercase grey section header with standard iOS typography (13sp, letter-spacing
 * 0.4sp). Padding matches the iOS spec: 20dp start, 6dp bottom, 16dp top.
 */
@Composable
internal fun SectionLabel(text: String) {
    Text(
        text = text,
        color = IosTertiary,
        fontSize = Sizes.sectionLabel,
        fontWeight = FontWeight.Normal,
        lineHeight = 30.sp,
        modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 32.dp, bottom = 12.dp),
    )
}

// ---------------------------------------------------------------------------
// Card
// ---------------------------------------------------------------------------

/**
 * A white rounded card (12dp corner radius) that groups a column of setting rows. Matches
 * the iOS grouped-table-view card style with horizontal 16dp margin applied by the caller.
 */
@Composable
internal fun Card(content: @Composable () -> Unit) {
    Surface(
        color = IosCard,
        shape = RoundedCornerShape(Sizes.cardRadius),
        border = BorderStroke(1.dp, IosDivider),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column { content() }
    }
}

// ---------------------------------------------------------------------------
// Divider
// ---------------------------------------------------------------------------

/** A hairline iOS-style divider between rows, inset to 58dp from the left (after the icon). */
@Composable
internal fun Divider() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp)) {
        Spacer(
            Modifier
                .fillMaxWidth()
                .padding(start = 28.dp)
                .height(1.dp)
                .background(IosDivider),
        )
    }
}

// ---------------------------------------------------------------------------
// Icon badge helper
// ---------------------------------------------------------------------------

/**
 * A rounded square icon badge used on the left side of each setting row. The background [color]
 * follows the mock spec per-row. When [iconRes] is supplied it draws that (white-tinted) vector
 * drawable; otherwise it falls back to the single-glyph [symbol]. Radius is per iOS spec.
 *
 * An already parsed [imageVector] may be supplied by a bounded resource cache.
 * Vector drawables are preferred (crisp at any density, reliable on the Portal's Android 10 where
 * emoji coverage is patchy); [symbol] remains for rows without a dedicated icon yet.
 */
@Composable
internal fun IconBadge(color: Color, symbol: String, iconRes: Int? = null, imageVector: ImageVector? = null) {
    Box(
        modifier = Modifier
            .size(Sizes.iconBadge)
            .clip(RoundedCornerShape(Sizes.iconRadius))
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        if (imageVector != null || iconRes != null) {
            val painter = if (imageVector != null) rememberVectorPainter(imageVector)
                else androidx.compose.ui.res.painterResource(requireNotNull(iconRes))
            androidx.compose.material3.Icon(
                painter = painter,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(26.dp),
            )
        } else {
            Text(
                text = symbol,
                color = Color.White,
                fontSize = Sizes.iconGlyph,
                lineHeight = Sizes.iconGlyph,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Toggle row
// ---------------------------------------------------------------------------

/**
 * Compatibility colors for existing callers. [ApprovedSettingsSwitch] uses the reactive approved
 * palette by default; callers can also pass this existing Material3 color set.
 */
@Composable
internal fun iosSwitchColors() =
    SwitchDefaults.colors(
        checkedThumbColor = Color.White,
        checkedTrackColor = IosGreen,
        uncheckedThumbColor = Color.White,
        uncheckedTrackColor = IosSegmentTrack,
        uncheckedBorderColor = IosDivider,
    )

/**
 * The one iOS-style text-field color set for the light settings/editor surface. Every
 * [androidx.compose.material3.OutlinedTextField] / TextField on a light background MUST use this.
 *
 * Why it's mandatory: PortalPrimeTheme always resolves to a DARK color scheme regardless of its
 * `darkTheme` arg (see Theme.kt), so a bare text field inherits white body text + white cursor +
 * near-white label from `MaterialTheme.colorScheme`, rendering invisible on the white [IosCard].
 * This pins every relevant slot to the dark-on-light Ios tokens so typed text is always readable.
 */
@Composable
internal fun iosTextFieldColors() =
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = IosLabel,
        unfocusedTextColor = IosLabel,
        disabledTextColor = IosSecondary,
        cursorColor = IosBlue,
        focusedBorderColor = IosBlue,
        unfocusedBorderColor = IosDivider,
        focusedLabelColor = IosBlue,
        unfocusedLabelColor = IosSecondary,
        focusedPlaceholderColor = IosSecondary,
        unfocusedPlaceholderColor = IosSecondary,
        focusedContainerColor = IosCard,
        unfocusedContainerColor = IosCard,
    )

/**
 * The filled-[androidx.compose.material3.TextField] equivalent of [iosTextFieldColors]. Filled
 * fields have a tinted container + a bottom indicator instead of a full border, so they need the
 * `TextFieldDefaults.colors(...)` slots rather than the outlined ones. Same reason: the dark theme
 * would otherwise paint white text on the light card.
 */
@Composable
internal fun iosFilledTextFieldColors() =
    TextFieldDefaults.colors(
        focusedTextColor = IosLabel,
        unfocusedTextColor = IosLabel,
        disabledTextColor = IosSecondary,
        cursorColor = IosBlue,
        focusedContainerColor = IosCard,
        unfocusedContainerColor = IosCard,
        disabledContainerColor = IosCard,
        focusedIndicatorColor = IosBlue,
        unfocusedIndicatorColor = IosDivider,
        focusedLabelColor = IosBlue,
        unfocusedLabelColor = IosSecondary,
        focusedPlaceholderColor = IosSecondary,
        unfocusedPlaceholderColor = IosSecondary,
    )

/**
 * An iOS-style toggle row: optional 30dp icon badge on the left, 17sp label, and a green
 * iOS-style Switch on the right. The whole row is the click target so the TV remote's centre
 * button works. Min height 54dp, padding 10dp vertical / 16dp horizontal.
 */
@Composable
internal fun ToggleRow(
    title: String,
    checked: Boolean,
    iconColor: Color = Color(0xFF636366),
    iconSymbol: String = "",
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Sizes.rowMinHeight)
            .then(if (enabled) Modifier.tvFocusableRow(focusFill = IosFocusTint) { onChange(!checked) }
                else Modifier.semantics { disabled() })
            .semantics { role = Role.Switch; toggleableState = ToggleableState(checked) }
            .padding(horizontal = 28.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconSymbol.isNotEmpty()) {
            IconBadge(color = iconColor, symbol = iconSymbol)
            Spacer(Modifier.size(12.dp))
        }
        ApprovedSettingsLabel(title, LocalApprovedSettingsHelp.current,
            Modifier.weight(1f).padding(end = 24.dp))
        // Visual only — the row toggles it (so the remote's centre button works).
        ApprovedSettingsSwitch(checked, enabled = enabled)
    }
}

// ---------------------------------------------------------------------------
// Nav row
// ---------------------------------------------------------------------------

/**
 * An iOS-style navigation row: optional icon badge, 17sp label, grey current-value text, and
 * a chevron (›). The whole row is the click target. Min height 54dp.
 */
@Composable
internal fun NavRow(
    title: String,
    value: String = "",
    subLabel: String = "",
    iconColor: Color = Color(0xFF636366),
    iconSymbol: String = "",
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Sizes.rowMinHeight)
            .then(if (enabled) Modifier.tvFocusableRow(focusFill = IosFocusTint, onClick = onClick)
                else Modifier.semantics { disabled() })
            .padding(horizontal = 28.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconSymbol.isNotEmpty()) {
            IconBadge(color = iconColor, symbol = iconSymbol)
            Spacer(Modifier.size(12.dp))
        }
        Column(modifier = Modifier.weight(1f).padding(end = 24.dp)) {
            Text(
                text = title,
                color = IosLabel,
                fontSize = Sizes.rowTitle,
                lineHeight = 34.sp,
            )
            val help = subLabel.ifEmpty { LocalApprovedSettingsHelp.current.orEmpty() }
            if (help.isNotEmpty()) {
                Text(
                    text = help,
                    color = IosSecondary,
                    fontSize = Sizes.rowSub,
                    lineHeight = 28.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        if (value.isNotEmpty()) {
            Text(
                text = value,
                color = IosSecondary,
                fontSize = Sizes.rowValue,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 500.dp).padding(end = 20.dp),
            )
        }
        ApprovedSettingsChevron()
    }
}

// ---------------------------------------------------------------------------
// Picker row (dropdown / popup button)
// ---------------------------------------------------------------------------

/** One choice in a [PickerRow]: the persisted [value] and the [label] shown to the user. */
internal data class PickerOption<T>(val value: T, val label: String)

/**
 * A macOS/iPad-style "popup button" row: title on the left, the current value + a ⌄ affordance
 * on the right. Tapping the row opens a [DropdownMenu] of all [options] with a check on the
 * current one — rather than blind-cycling to the next value on each tap. [onSelect] is called
 * with the chosen option's value. The whole row is the click target so the TV remote works.
 */
@Composable
internal fun <T> PickerRow(
    title: String,
    selected: T,
    options: List<PickerOption<T>>,
    iconColor: Color = Color(0xFF636366),
    iconSymbol: String = "",
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.value == selected }
    NavRow(title, current?.label ?: selected.toString(), iconColor = iconColor,
        iconSymbol = iconSymbol, onClick = { expanded = true })
    if (expanded) {
        ApprovedSettingsDialog(title, onDismiss = { expanded = false }) {
            Card {
                LazyColumn(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp - 400)
                    .coerceAtLeast(300).dp)) {
                    itemsIndexed(options, key = { _, option -> option.value.toString() }) { index, option ->
                        if (index > 0) Divider()
                        SelectableRow(option.label, "", option.value == selected) {
                            expanded = false
                            onSelect(option.value)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Selectable row
// ---------------------------------------------------------------------------

/** A single-select row with a title + subtitle and a radio dot. The whole row is the click target. */
@Composable
internal fun SelectableRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Sizes.rowMinHeight)
            .tvFocusableRow(focusFill = IosFocusTint) { onClick() }
            .padding(horizontal = 28.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 24.dp)) {
            Text(text = title, color = IosLabel, fontSize = Sizes.rowTitle)
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    color = IosSecondary,
                    fontSize = Sizes.rowSub,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (selected) {
            Text(text = "✓", color = IosBlue, fontSize = Sizes.rowTitle, fontWeight = FontWeight.Medium)
        }
    }
}

// ---------------------------------------------------------------------------
// Segmented control
// ---------------------------------------------------------------------------

/** A compact iOS-style segmented control. [options] are label-to-value pairs. */
@Composable
internal fun Segmented(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .background(IosSegmentTrack, RoundedCornerShape(8.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (label, value) ->
            val on = value == selected
            Surface(
                // Selected segment: lighter fill so it reads against the dark track (the mock's
                // rgba(255,255,255,.22)); IosCard is too close to the track tone on dark.
                color = if (on) IosCard else Color.Transparent,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.tvFocusable(RoundedCornerShape(6.dp), focusScale = 1f, ringColor = IosBlue) { onSelect(value) },
            ) {
                Text(
                    text = label,
                    color = if (on) IosLabel else IosSegmentInactive,
                    fontSize = Sizes.segmentLabel,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Stepper
// ---------------------------------------------------------------------------

/**
 * iOS-style stepper row: label on the left, minus/value/plus on the right. Focusable for
 * D-pad: LEFT decrements, RIGHT increments; UP/DOWN pass through so the remote can move off.
 */
@Composable
internal fun Stepper(
    label: String,
    valueText: String,
    widthMin: Dp = 64.dp,
    iconColor: Color = Color(0xFF636366),
    iconSymbol: String = "",
    enabled: Boolean = true,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Sizes.rowMinHeight)
            .onKeyEvent { e ->
                if (enabled && e.type == KeyEventType.KeyDown) when (e.key) {
                    Key.DirectionLeft -> { onMinus(); true }
                    Key.DirectionRight -> { onPlus(); true }
                    else -> false
                } else false
            }
            .focusable(enabled = enabled, interactionSource = src)
            .then(if (enabled) Modifier else Modifier.semantics { disabled() })
            .background(if (focused) IosFocusTint else Color.Transparent)
            .padding(horizontal = 28.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconSymbol.isNotEmpty()) {
            IconBadge(color = iconColor, symbol = iconSymbol)
            Spacer(Modifier.size(12.dp))
        }
        Text(
            text = label,
            color = IosLabel,
            fontSize = Sizes.rowTitle,
            lineHeight = 34.sp,
            modifier = Modifier.weight(1f).padding(end = 24.dp),
        )
        StepperButton("-", focused, enabled) { onMinus() }
        Text(
            text = valueText,
            color = IosLabel,
            fontSize = Sizes.rowValue,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = widthMin),
        )
        StepperButton("+", focused, enabled) { onPlus() }
    }
}

@Composable
private fun StepperButton(glyph: String, rowFocused: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 68.dp, height = 68.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (rowFocused) IosFocusTint else IosBackground)
            .focusProperties { canFocus = false }
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier.semantics { disabled() }),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            color = IosBlue,
            fontSize = Sizes.chevron,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ---------------------------------------------------------------------------
// Arrow button (kept for legacy callers in per-screen steppers)
// ---------------------------------------------------------------------------

/**
 * A tappable triangle arrow for bespoke per-screen steppers. Excluded from D-pad focus
 * traversal so on the remote the parent row handles LEFT/RIGHT.
 */
@Composable
internal fun ArrowButton(glyph: String, rowFocused: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(68.dp)
            .clip(RoundedCornerShape(12.dp))
            .focusProperties { canFocus = false }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            color = if (rowFocused) IosBlue else IosSecondary,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ---------------------------------------------------------------------------
// Footer note
// ---------------------------------------------------------------------------

/** Grey footer text shown beneath a card group, matching iOS 13sp / line-height 1.5. */
@Composable
internal fun FooterNote(text: String) {
    Text(
        text = text,
        color = IosTertiary,
        fontSize = Sizes.rowSub,
        lineHeight = 30.sp,
        modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
    )
}

// ---------------------------------------------------------------------------
// Back button
// ---------------------------------------------------------------------------

/**
 * A visible iOS-style "‹ Back" pill for a full-screen settings sub-page: white card, blue
 * label, focusable for the TV remote. Sub-pages also handle the hardware/gesture Back key,
 * so this is the on-screen affordance to leave the page.
 */
@Composable
internal fun SettingsBackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = IosCard,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.heightIn(min = 68.dp).tvFocusable(RoundedCornerShape(12.dp), focusScale = 1f, ringColor = IosBlue) { onClick() },
    ) {
        Text(
            text = "‹  Back",
            color = IosBlue,
            fontSize = Sizes.pageSubtitle,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 11.dp),
        )
    }
}

/**
 * Older call sites used a no-op `FolderBackButton`; it now draws the real [SettingsBackButton]
 * so every legacy sub-page gets a visible, consistent back control for free.
 */
@Composable
fun FolderBackButton(onClick: () -> Unit, modifier: Modifier = Modifier) =
    SettingsBackButton(onClick, modifier)

// ---------------------------------------------------------------------------
// Shared form controls (consolidated from the connect/entry screens)
// ---------------------------------------------------------------------------

/** THE iOS text field: white rounded 14dp, min-56dp tall, optional password masking. */
@Composable
internal fun IosTextField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    password: Boolean = false,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.OutlinedTextField(
        colors = iosTextFieldColors(),
        value = value,
        onValueChange = onChange,
        placeholder = { Text(placeholder) },
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 26.sp, color = IosLabel),
        visualTransformation =
            if (password) androidx.compose.ui.text.input.PasswordVisualTransformation()
            else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = modifier.fillMaxWidth().heightIn(min = 68.dp),
        shape = RoundedCornerShape(14.dp),
    )
}

/** THE full-width primary pill (blue when enabled, grey-disabled): Test & connect / Use this X. */
@Composable
internal fun PrimaryPillButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        color = if (enabled) IosBlue else IosDivider,
        shape = RoundedCornerShape(16.dp),
        modifier =
            Modifier.fillMaxWidth().heightIn(min = 68.dp).tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f, ringColor = IosBlue, enabled = enabled) {
                if (enabled) onClick()
            },
    ) {
        Text(
            label,
            color = if (enabled) MaterialTheme.colorScheme.onPrimary else IosSecondary,
            fontSize = Sizes.actionButton,
            fontWeight = FontWeight.Medium,
            lineHeight = 32.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(vertical = 16.dp).fillMaxWidth(),
        )
    }
}

/** THE full-width white card pill (Cancel / Done / Remove — pass IosRed for destructive). */
@Composable
internal fun CardPillButton(label: String, color: Color = IosBlue, onClick: () -> Unit) {
    Surface(
        color = IosCard,
        shape = RoundedCornerShape(16.dp),
        modifier =
            Modifier.fillMaxWidth().heightIn(min = 68.dp).tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f, ringColor = IosBlue) {
                onClick()
            },
    ) {
        Text(
            label,
            color = color,
            fontSize = Sizes.actionButton,
            fontWeight = FontWeight.Medium,
            lineHeight = 32.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(vertical = 14.dp).fillMaxWidth(),
        )
    }
}

/** Approved primary action: a 68 dp target, 16 dp radius and 24 sp medium label. */
@Composable
internal fun ActionPill(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    SettingsActionSurface(label, modifier, onClick, enabled)
}

/** One accessible action for the full row, including its visible 68 dp action button. */
@Composable
internal fun ActionSettingRow(
    title: String,
    subtitle: String = "",
    actionLabel: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier.fillMaxWidth().defaultMinSize(minHeight = Sizes.rowMinHeight)
            .then(if (enabled) Modifier.tvFocusableRow(focusFill = IosFocusTint, onClick = onClick)
                else Modifier)
            .semantics {
                role = Role.Button
                if (!enabled) disabled()
            }
            .padding(horizontal = 28.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        ApprovedSettingsLabel(title, subtitle.ifBlank { LocalApprovedSettingsHelp.current.orEmpty() },
            Modifier.weight(1f))
        // The full row owns touch, accessibility and D-pad; this button adds no second focus stop.
        SettingsActionSurface(actionLabel, enabled = enabled)
    }
}

@Composable
private fun SettingsActionSurface(
    label: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        color = if (enabled) IosBlue else IosDivider,
        shape = shape,
        modifier = modifier.heightIn(min = Sizes.primaryTarget)
            .then(if (enabled && onClick != null)
                Modifier.tvFocusable(shape, focusScale = 1f, ringColor = IosBlue, onClick = onClick)
                else Modifier),
    ) {
        Text(
            label,
            color = if (enabled) MaterialTheme.colorScheme.onPrimary else IosSecondary,
            fontSize = Sizes.actionButton,
            fontWeight = FontWeight.Medium,
            lineHeight = 32.sp,
            modifier = Modifier.padding(horizontal = 26.dp, vertical = 18.dp),
        )
    }
}

/** Numbered setup step ("1.  Do the thing") for the connection how-to cards. */
@Composable
internal fun SetupStep(n: String, text: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            "$n.",
            color = IosBlue,
            fontSize = Sizes.rowSub,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(end = 10.dp),
        )
        Text(text, color = IosSecondary, fontSize = Sizes.rowSub, lineHeight = 28.sp)
    }
}

/** Card-header toggle: title + wrapping subtitle on the left, iOS switch right. */
@Composable
internal fun ToggleHeaderRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 24.dp)) {
            Text(title, color = IosLabel, fontSize = Sizes.rowTitle)
            Text(
                subtitle,
                color = IosSecondary,
                fontSize = Sizes.rowSub,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        ApprovedSettingsSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}
