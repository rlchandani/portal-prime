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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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

internal val IosBackground = Color(0xFFF2F2F7)
internal val IosCard = Color(0xFFFFFFFF)
internal val IosLabel = Color(0xFF000000)
internal val IosSecondary = Color(0xFF8E8E93)
internal val IosTertiary = Color(0xFF6E6E73)
internal val IosDivider = Color(0xFFE5E5EA)
internal val IosBlue = Color(0xFF007AFF)
internal val IosGreen = Color(0xFF34C759)
internal val IosChevron = Color(0xFFC7C7CC)

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
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.4.sp,
        modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
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
        shape = RoundedCornerShape(12.dp),
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
                .padding(start = 58.dp)
                .height(1.dp)
                .background(IosDivider),
        )
    }
}

// ---------------------------------------------------------------------------
// Icon badge helper
// ---------------------------------------------------------------------------

/**
 * A 30x30dp rounded square icon badge used on the left side of each setting row. The
 * background [color] follows the mock spec per-row; the [symbol] is a single glyph/emoji
 * rendered at 17sp. Radius is 7dp per iOS spec.
 */
@Composable
internal fun IconBadge(color: Color, symbol: String) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = symbol,
            color = Color.White,
            fontSize = 17.sp,
            lineHeight = 17.sp,
        )
    }
}

// ---------------------------------------------------------------------------
// Toggle row
// ---------------------------------------------------------------------------

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
            fontSize = 16.sp,
            letterSpacing = (-0.2).sp,
            modifier = Modifier.weight(1f),
        )
        // Visual only — the row toggles it (so the remote's centre button works).
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = IosGreen,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = IosDivider,
                uncheckedBorderColor = IosDivider,
            ),
        )
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
                fontSize = 16.sp,
                letterSpacing = (-0.2).sp,
            )
            if (subLabel.isNotEmpty()) {
                Text(
                    text = subLabel,
                    color = IosSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (value.isNotEmpty()) {
            Text(
                text = value,
                color = IosSecondary,
                fontSize = 15.sp,
                modifier = Modifier.padding(end = 6.dp),
            )
        }
        Text(
            text = ">",
            color = IosChevron,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
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
            Text(text = title, color = IosLabel, fontSize = 16.sp)
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    color = IosSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (selected) {
            Text(text = "v", color = IosBlue, fontSize = 16.sp, fontWeight = FontWeight.Medium)
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
            .background(Color(0xFFE5E5EA), RoundedCornerShape(8.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (label, value) ->
            val on = value == selected
            Surface(
                color = if (on) Color.White else Color.Transparent,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.tvFocusable(RoundedCornerShape(6.dp)) { onSelect(value) },
            ) {
                Text(
                    text = label,
                    color = if (on) Color(0xFF000000) else Color(0xFF3A3A3C),
                    fontSize = 13.sp,
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
            .background(if (focused) Color(0xFFE5F0FF) else Color.Transparent)
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
            fontSize = 16.sp,
            letterSpacing = (-0.2).sp,
            modifier = Modifier.weight(1f),
        )
        StepperButton("-", focused) { onMinus() }
        Text(
            text = valueText,
            color = IosLabel,
            fontSize = 14.sp,
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
            .size(width = 32.dp, height = 30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (rowFocused) Color(0xFFE5F0FF) else Color(0xFFF2F2F7))
            .focusProperties { canFocus = false }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            color = IosBlue,
            fontSize = 18.sp,
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
        fontSize = 13.sp,
        lineHeight = 19.5.sp,
        modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
    )
}

// ---------------------------------------------------------------------------
// Back button (no-op kept for older call sites)
// ---------------------------------------------------------------------------

/** Back is handled by the system/gesture path; keep this no-op for older call sites. */
@Composable
fun FolderBackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {}
