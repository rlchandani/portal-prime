/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal val LocalApprovedRequestedControl = staticCompositionLocalOf<String?> { null }

private const val DISABLED_SWITCH_ALPHA = .38f
private val SWITCH_THUMB_RING = Color(0x26000000)
internal val LocalApprovedSettingsHelp = staticCompositionLocalOf<String?> { null }

@Composable
internal fun ApprovedSettingsSectionLabel(text: String) {
    Text(text, color = IosSecondary, fontSize = 22.sp, lineHeight = 30.sp,
        modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 12.dp))
}

@Composable
internal fun ApprovedSettingsLabel(title: String, help: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, color = IosLabel, fontSize = Sizes.rowTitle, lineHeight = 34.sp)
        if (!help.isNullOrBlank()) {
            Text(help, color = IosSecondary, fontSize = Sizes.rowSub, lineHeight = 28.sp,
                modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/**
 * Approved native switch, in flow at 66 × 40 dp with a 32 dp thumb at (4, 4) or (30, 4).
 * A null callback leaves interaction and accessibility to the enclosing toggle row.
 * State changes render immediately; there is no animation or idle work.
 */
@Composable
internal fun ApprovedSettingsSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: SwitchColors? = null,
) {
    val shape = RoundedCornerShape(20.dp)
    val track = when {
        colors == null -> if (checked) IosGreen else IosSegmentTrack
        enabled -> if (checked) colors.checkedTrackColor else colors.uncheckedTrackColor
        checked -> colors.disabledCheckedTrackColor
        else -> colors.disabledUncheckedTrackColor
    }
    val thumb = when {
        colors == null -> Color.White
        enabled -> if (checked) colors.checkedThumbColor else colors.uncheckedThumbColor
        checked -> colors.disabledCheckedThumbColor
        else -> colors.disabledUncheckedThumbColor
    }
    val border = if (checked) track else IosDivider
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val action = if (onCheckedChange != null) {
        Modifier.toggleable(
            value = checked,
            interactionSource = interactions,
            indication = null,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ).semantics { stateDescription = if (checked) "On" else "Off" }
    } else {
        Modifier.clearAndSetSemantics {}
    }
    Box(
        modifier.size(66.dp, 40.dp)
            .then(action)
            // Alpha only when disabled: an always-on alpha layer costs a GPU pass per switch.
            .then(if (!enabled && colors == null) Modifier.alpha(DISABLED_SWITCH_ALPHA) else Modifier)
            .clip(shape)
            .background(track)
            .border(1.dp, border, shape)
            .then(if (focused) Modifier.border(3.dp, IosBlue, shape) else Modifier),
    ) {
        Box(Modifier.offset(x = if (checked) 30.dp else 4.dp, y = 4.dp)
            .size(32.dp)
            // A pre-drawn ring instead of a per-thumb shadow layer (perf report, settings rows).
            .border(1.dp, SWITCH_THUMB_RING, RoundedCornerShape(16.dp))
            .background(thumb, RoundedCornerShape(16.dp)))
    }
}

@Composable
internal fun ApprovedSettingsChevron(back: Boolean = false) {
    Box(Modifier.size(20.dp, 30.dp), contentAlignment = Alignment.Center) {
        ApprovedIcon("chevL", tint = IosSecondary,
            modifier = Modifier.size(14.dp, 24.dp).graphicsLayer { rotationZ = if (back) 0f else 180f })
    }
}

@Composable
internal fun ApprovedSettingsBackButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.defaultMinSize(minHeight = 68.dp)
            .tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f, ringColor = IosBlue, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ApprovedIcon("chevL", tint = IosBlue, modifier = Modifier.size(26.dp))
        Text(label, color = IosBlue, fontSize = 24.sp, fontWeight = FontWeight.Medium,
            lineHeight = 32.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/** Fixed, readable dialog pattern shared by enum pickers, numeric editors and consent. */
@Composable
internal fun ApprovedSettingsDialog(
    title: String,
    onDismiss: () -> Unit,
    help: String? = null,
    content: @Composable () -> Unit,
) {
    if (!LocalApprovedLauncherPageActive.current) return
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = IosCard,
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(1.dp, IosDivider),
            modifier = Modifier.widthIn(max = 880.dp).fillMaxWidth().heightIn(max = 1096.dp),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(36.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = IosLabel, fontSize = 32.sp, lineHeight = 42.sp,
                        fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Box(
                        Modifier.padding(start = 24.dp).size(68.dp)
                            .clip(CircleShape).background(IosSegmentTrack)
                            .tvFocusable(CircleShape, focusScale = 1f, ringColor = IosBlue, onClick = onDismiss)
                            .semantics { contentDescription = "Close dialog" },
                        contentAlignment = Alignment.Center,
                    ) {
                        ApprovedIcon("x", tint = IosLabel, modifier = Modifier.size(30.dp))
                    }
                }
                if (!help.isNullOrBlank()) {
                    Text(help, color = IosSecondary, fontSize = 23.sp, lineHeight = 34.sp,
                        modifier = Modifier.padding(top = 18.dp, bottom = 24.dp))
                } else {
                    Spacer(Modifier.size(18.dp))
                }
                content()
            }
        }
    }
}

@Composable
internal fun ApprovedSettingsTextButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.defaultMinSize(minHeight = 68.dp)
            .tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f, ringColor = IosBlue, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = IosBlue, fontSize = 24.sp, fontWeight = FontWeight.Medium,
            lineHeight = 32.sp)
    }
}

@Composable
internal fun ApprovedSettingsAdvancedConsent(onEnable: () -> Unit, onCancel: () -> Unit) {
    ApprovedSettingsDialog(
        "Advanced settings", onDismiss = onCancel,
        help = "Turn on Advanced settings to see technical options. Your current settings will stay the same.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionPill("Turn on Advanced settings", onClick = onEnable)
            ApprovedSettingsTextButton("Cancel", onCancel)
        }
    }
}

@Composable
internal fun ApprovedSettingsAdvancedGate(onEnable: () -> Unit) {
    Card {
        Column(Modifier.padding(38.dp)) {
            Text("Available in Advanced settings", color = IosLabel, fontSize = 30.sp,
                lineHeight = 40.sp, fontWeight = FontWeight.Medium)
            Text(
                "Turn on Advanced settings to set up technical connections and tools. Your current configuration stays the same.",
                color = IosSecondary, fontSize = 24.sp, lineHeight = 34.sp,
                modifier = Modifier.padding(top = 18.dp, bottom = 30.dp),
            )
            ActionPill("Turn on Advanced settings", onClick = onEnable)
        }
    }
}
