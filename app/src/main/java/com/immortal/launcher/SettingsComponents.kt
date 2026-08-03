/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * iOS Settings-style shared UI vocabulary. All components match the iOS Settings aesthetic:
 * light background (#f2f2f7), white cards with 12dp corner radius, blue accents (#007aff),
 * green toggles (#34c759), grey secondary text, uppercase section headers (13sp), and
 * standard 54dp row heights with icon badges.
 *
 * Built on the D-pad focus primitives in [TvFocus] so the TV remote still navigates correctly.
 */

// ---------------------------------------------------------------------------
// iOS color palette
// ---------------------------------------------------------------------------

// Dusk Aurora DARK palette (the approved settings mock, 02-settings-v3): a cool near-black
// panel with dark glass cards, white type, and iOS-dark accents. Every settings surface reads
// these tokens, so this block IS the theme — the light values it replaced live in git history.
internal val IosBackground = Color(0xFF10131C)   // detail panel (mock rgba(22,26,38,.55) solidified)
internal val IosCard = Color(0xFF1E2230)         // grouped card (mock rgba(255,255,255,.07) on dark)
internal val IosLabel = Color(0xFFFFFFFF)
internal val IosSecondary = Color(0xFFA1A1AA)    // ~66% white
internal val IosTertiary = Color(0xFF74747E)     // ~45% white
internal val IosDivider = Color(0xFF2A2E3C)      // hairline on dark (mock rgba(255,255,255,.09))
internal val IosBlue = Color(0xFF0A84FF)         // iOS dark-mode blue
internal val IosGreen = Color(0xFF30D158)        // iOS dark-mode green
internal val IosChevron = Color(0xFF4A4E5C)
internal val IosRed = Color(0xFFFF453A)          // destructive / stop actions (dark-mode red)
internal val IosFocusTint = Color(0xFF1C3350)    // D-pad focus highlight + action-pill fill (blue-dark)
internal val IosSegmentTrack = Color(0xFF2A2E3C) // segmented-control track (== IosDivider tone)
internal val IosSegmentInactive = Color(0xFFA1A1AA) // unselected segment label
internal val IosSidebar = Color(0xFF0C0F17)      // sidebar panel (mock rgba(14,17,26,.74) solidified)

// ---------------------------------------------------------------------------
// Sizing scale (single source of truth)
// ---------------------------------------------------------------------------

/**
 * The "Large" sizing scale, tuned for the Portal's 2160x1440 touch panel viewed at room
 * distance. Every settings surface — the two-panel main screen and every full-screen
 * sub-page — reads its type sizes and metrics from here so they can't drift.
 */
internal object Sizes {
    val titlePage = 34.sp      // sub-page H1
    // Shared two-panel header title — used for BOTH the sidebar "Settings" heading and the
    // right-panel section title so they match in size/weight and sit on the same baseline.
    val panelTitle = 30.sp     // two-panel header title (sidebar + detail top-bar)
    val panelTitleTop = 20.dp  // top padding above the header title in both panels (aligns baselines)
    val panelTitleBottom = 14.dp // bottom padding below the header title in both panels (equal heights)
    val pageSubtitle = 19.sp   // sub-page subtitle
    val sectionLabel = 16.sp   // uppercase grey group headers
    val navLabel = 21.sp       // sidebar nav-row label
    val rowTitle = 22.sp       // row body text
    val rowSub = 16.sp         // row secondary/subtitle
    val rowValue = 19.sp       // right-side value text (steppers, pickers)
    val actionButton = 17.sp   // blue action-button labels (Test / Preview / Save / Add)
    val segmentLabel = 15.sp   // segmented-control segment label
    val chevron = 22.sp        // "›"
    val iconBadge = 40.dp      // content-row icon badge
    val iconGlyph = 20.sp
    val iconRadius = 9.dp
    val rowMinHeight = 68.dp
    val cardRadius = 14.dp  // THE canonical container corner radius — cards, search field, selection
                            // pill, and result rows all use this so every rounded surface matches.
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
        text = text.uppercase(),
        color = IosTertiary,
        fontSize = Sizes.sectionLabel,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.4.sp,
        modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
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
                .padding(start = 68.dp)
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
 * Vector drawables are preferred (crisp at any density, reliable on the Portal's Android 10 where
 * emoji coverage is patchy); [symbol] remains for rows without a dedicated icon yet.
 */
@Composable
internal fun IconBadge(color: Color, symbol: String, iconRes: Int? = null) {
    Box(
        modifier = Modifier
            .size(Sizes.iconBadge)
            .clip(RoundedCornerShape(Sizes.iconRadius))
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        if (iconRes != null) {
            androidx.compose.material3.Icon(
                painter = androidx.compose.ui.res.painterResource(iconRes),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(Sizes.iconBadge * 0.62f),
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
 * The one iOS-style Switch color set: green track when on, light-grey track when off, white thumb.
 * Every Switch in the settings surface MUST use this — a bare `Switch()` otherwise inherits the
 * theme's blue primary + dark off-track (PortalPrimeTheme resolves to a dark scheme regardless of
 * its `darkTheme` arg), which is the "blue on / weird black off" inconsistency this centralizes away.
 */
@Composable
internal fun iosSwitchColors() =
    SwitchDefaults.colors(
        checkedThumbColor = Color.White,
        checkedTrackColor = IosGreen,
        uncheckedThumbColor = Color.White,
        uncheckedTrackColor = IosDivider,
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
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Sizes.rowMinHeight)
            .tvFocusableRow { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconSymbol.isNotEmpty()) {
            IconBadge(color = iconColor, symbol = iconSymbol)
            Spacer(Modifier.size(12.dp))
        }
        Text(
            text = title,
            color = IosLabel,
            fontSize = Sizes.rowTitle,
            letterSpacing = (-0.2).sp,
            modifier = Modifier.weight(1f),
        )
        // Visual only — the row toggles it (so the remote's centre button works).
        Switch(checked = checked, onCheckedChange = null, colors = iosSwitchColors())
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
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Sizes.rowMinHeight)
            .tvFocusableRow { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconSymbol.isNotEmpty()) {
            IconBadge(color = iconColor, symbol = iconSymbol)
            Spacer(Modifier.size(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = IosLabel,
                fontSize = Sizes.rowTitle,
                letterSpacing = (-0.2).sp,
            )
            if (subLabel.isNotEmpty()) {
                Text(
                    text = subLabel,
                    color = IosSecondary,
                    fontSize = Sizes.rowSub,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (value.isNotEmpty()) {
            Text(
                text = value,
                color = IosSecondary,
                fontSize = Sizes.rowValue,
                modifier = Modifier.padding(end = 6.dp),
            )
        }
        Text(
            text = ">",
            color = IosChevron,
            fontSize = Sizes.chevron,
            fontWeight = FontWeight.Medium,
        )
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Sizes.rowMinHeight)
            .tvFocusableRow { expanded = true }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconSymbol.isNotEmpty()) {
            IconBadge(color = iconColor, symbol = iconSymbol)
            Spacer(Modifier.size(12.dp))
        }
        Text(
            text = title,
            color = IosLabel,
            fontSize = Sizes.rowTitle,
            letterSpacing = (-0.2).sp,
            modifier = Modifier.weight(1f),
        )
        // Anchor the menu to the value + caret so it drops under them (right side), not the row start.
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = current?.label ?: "",
                    color = IosSecondary,
                    fontSize = Sizes.rowValue,
                    modifier = Modifier.padding(end = 6.dp),
                )
                // Filled down-triangle reads unambiguously as "dropdown" (⌄ has poor font
                // coverage on the Portal's Android 10 and rendered as a stray "v").
                Text(
                    text = "▾",
                    color = IosChevron,
                    fontSize = Sizes.chevron,
                    fontWeight = FontWeight.Medium,
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(IosCard),
            ) {
                options.forEach { opt ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                opt.label,
                                color = IosLabel,
                                fontSize = Sizes.rowTitle,
                                fontWeight = if (opt.value == selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                        trailingIcon = {
                            if (opt.value == selected) {
                                Text("✓", color = IosBlue, fontSize = Sizes.rowTitle, fontWeight = FontWeight.SemiBold)
                            }
                        },
                        onClick = {
                            expanded = false
                            onSelect(opt.value)
                        },
                    )
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
            .tvFocusableRow { onClick() }
            .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
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
            Text(text = "v", color = IosBlue, fontSize = Sizes.rowTitle, fontWeight = FontWeight.Medium)
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
                color = if (on) Color(0xFF3A4152) else Color.Transparent,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.tvFocusable(RoundedCornerShape(6.dp)) { onSelect(value) },
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
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) when (e.key) {
                    Key.DirectionLeft -> { onMinus(); true }
                    Key.DirectionRight -> { onPlus(); true }
                    else -> false
                } else false
            }
            .focusable(interactionSource = src)
            .background(if (focused) IosFocusTint else Color.Transparent)
            .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
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
            letterSpacing = (-0.2).sp,
            modifier = Modifier.weight(1f),
        )
        StepperButton("-", focused) { onMinus() }
        Text(
            text = valueText,
            color = IosLabel,
            fontSize = Sizes.rowValue,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = widthMin),
        )
        StepperButton("+", focused) { onPlus() }
    }
}

@Composable
private fun StepperButton(glyph: String, rowFocused: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 40.dp, height = 38.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (rowFocused) IosFocusTint else IosBackground)
            .focusProperties { canFocus = false }
            .clickable(onClick = onClick),
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
            .size(48.dp)
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
        lineHeight = 24.sp,
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
        modifier = modifier.tvFocusable(RoundedCornerShape(12.dp)) { onClick() },
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
        visualTransformation =
            if (password) androidx.compose.ui.text.input.PasswordVisualTransformation()
            else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
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
            Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f) {
                if (enabled) onClick()
            },
    ) {
        Text(
            label,
            color = if (enabled) Color.White else IosSecondary,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
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
            Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f) {
                onClick()
            },
    ) {
        Text(
            label,
            color = color,
            fontSize = Sizes.rowTitle,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(vertical = 14.dp).fillMaxWidth(),
        )
    }
}

/** THE small blue action pill (Apply / Sign in / Test) — 10dp radius, white 17sp label. */
@Composable
internal fun ActionPill(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        color = IosBlue,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.tvFocusable(RoundedCornerShape(10.dp)) { onClick() },
    ) {
        Text(
            label,
            color = Color.White,
            fontSize = Sizes.actionButton,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
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
        Text(text, color = IosSecondary, fontSize = Sizes.rowSub, lineHeight = 20.sp)
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
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = IosLabel, fontSize = Sizes.rowTitle)
            Text(
                subtitle,
                color = IosSecondary,
                fontSize = Sizes.rowSub,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = iosSwitchColors())
    }
}
