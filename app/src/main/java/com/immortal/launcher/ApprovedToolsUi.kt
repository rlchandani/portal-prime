/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.immortal.launcher.ui.theme.ApprovedDarkPalette
import com.immortal.launcher.ui.theme.LocalApprovedPalette

internal val ApprovedToolsPalette = ApprovedDarkPalette.copy(
    error = Color(0xFFE6B5BA))

@Composable
internal fun toolsTint(tool: ApprovedTool): Color =
    if (LocalApprovedPalette.current.dark) Color(tool.tint) else when (tool.tint) {
        0xFFEEC184L -> Color(0xFF99610F)
        0xFFA9C9F5L -> Color(0xFF176ABB)
        0xFFB4DCD0L -> Color(0xFF28735A)
        0xFFC4B6E7L -> Color(0xFF74529D)
        else -> Color(0xFF856121)
    }

@Composable
internal fun ToolsGlyph(name: String, tint: Color, modifier: Modifier = Modifier) {
    when (name) {
        "cameraLens" -> Icon(painterResource(R.drawable.tools_camera), null, modifier, tint)
        "book" -> Icon(painterResource(R.drawable.tools_book), null, modifier, tint)
        else -> ApprovedIcon(name, modifier, tint)
    }
}

@Composable
internal fun ToolsPanel(
    title: String, modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalApprovedPalette.current
    Surface(color = p.surface, shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, p.divider), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(title, color = p.text, fontSize = 30.sp, lineHeight = 39.sp,
                fontWeight = FontWeight.Medium)
            content()
        }
    }
}

/** One bounded scroll owner, with a vertical fallback for enlarged text/narrow displays. */
@Composable
internal fun ToolsTwoPane(
    left: @Composable ColumnScope.() -> Unit,
    right: @Composable ColumnScope.() -> Unit,
) {
    val scale = LocalConfiguration.current.fontScale
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val scroll = rememberScrollState()
        if (maxWidth < (900 * scale).dp) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(24.dp)) { left(); right() }
        } else {
            Row(Modifier.fillMaxSize().verticalScroll(scroll),
                horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Column(Modifier.weight(1.3f), verticalArrangement = Arrangement.spacedBy(24.dp), content = left)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp), content = right)
            }
        }
    }
}

@Composable
internal fun ToolsText(text: String, secondary: Boolean = false, modifier: Modifier = Modifier) {
    val p = LocalApprovedPalette.current
    Text(text, color = if (secondary) p.secondary else p.text,
        fontSize = if (secondary) 22.sp else 26.sp,
        lineHeight = if (secondary) 32.sp else 35.sp, modifier = modifier)
}

@Composable
internal fun ToolsButton(
    label: String, primary: Boolean = false, enabled: Boolean = true,
    destructive: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    val p = LocalApprovedPalette.current
    val shape = RoundedCornerShape(16.dp)
    Box(modifier.heightIn(min = 68.dp).clip(shape)
        .background(if (primary && enabled) p.accent else p.surface2)
        .tvFocusable(shape, focusScale = 1f,
            ringColor = if (primary && enabled) {
                if (p.dark) Color(0xFF182C39) else Color.White
            } else p.accent,
            enabled = enabled, onClick = onClick)
        .padding(horizontal = 24.dp, vertical = 16.dp), contentAlignment = Alignment.Center) {
        Text(label, color = when {
            !enabled -> p.secondary.copy(alpha = .5f)
            destructive -> p.error
            primary -> if (p.dark) Color(0xFF182C39) else Color.White
            else -> p.text
        }, fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun ToolsField(
    label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
    numeric: Boolean = false, multiline: Boolean = false, integer: Boolean = false,
    enabled: Boolean = true,
) {
    val p = LocalApprovedPalette.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ToolsText(label, secondary = true)
        Surface(color = p.background, shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, p.divider)) {
            BasicTextField(value, onChange, singleLine = !multiline, enabled = enabled,
                textStyle = TextStyle(color = p.text, fontSize = 26.sp, lineHeight = 36.sp),
                cursorBrush = SolidColor(p.accent),
                keyboardOptions = KeyboardOptions(keyboardType =
                    if (integer) KeyboardType.Number else if (numeric) KeyboardType.Decimal else KeyboardType.Text),
                modifier = Modifier.fillMaxWidth()
                    .heightIn(min = if (multiline) 200.dp else 68.dp, max = if (multiline) 360.dp else 120.dp)
                    .semantics { contentDescription = label }
                    .padding(horizontal = 20.dp, vertical = 16.dp))
        }
    }
}

/** Fixed header/actions and bounded form body keep Save/Cancel reachable above the IME. */
@Composable
internal fun ToolsSheet(
    title: String, onDismiss: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val active = LocalApprovedLauncherPageActive.current
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(active) { if (!active) dismiss() }
    if (!active) return
    val p = LocalApprovedPalette.current
    val cfg = LocalConfiguration.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(
        usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(color = p.surface, shape = RoundedCornerShape(28.dp),
            border = BorderStroke(1.dp, p.divider),
            modifier = Modifier.padding(24.dp).imePadding().widthIn(max = 880.dp)
                .fillMaxWidth().heightIn(max = (cfg.screenHeightDp - 100).dp)) {
            Column {
                Row(Modifier.fillMaxWidth().padding(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = p.text, fontSize = 32.sp, lineHeight = 42.sp,
                        fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Box(Modifier.size(68.dp).clip(RoundedCornerShape(16.dp)).background(p.surface2)
                        .tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f,
                            ringColor = p.accent, onClick = onDismiss)
                        .semantics { contentDescription = "Close $title" },
                        contentAlignment = Alignment.Center) {
                        ApprovedAppsGlyph("x", p.secondary, 28.dp)
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(p.divider))
                Column(Modifier.weight(1f, fill = false).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
                Box(Modifier.fillMaxWidth().height(1.dp).background(p.divider))
                Row(Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End), content = actions)
            }
        }
    }
}

@Composable
internal fun ToolsChoice(title: String, value: String, options: List<String>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val active = LocalApprovedLauncherPageActive.current
    LaunchedEffect(active) { if (!active) open = false }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ToolsText(title, secondary = true)
        ToolsButton("$value  ›", modifier = Modifier.fillMaxWidth()) { open = true }
    }
    if (open) ToolsSheet(title, { open = false }, actions = {
        ToolsButton("Cancel") { open = false }
    }) {
        options.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { option ->
                    ToolsButton(option + if (option == value) "  ✓" else "",
                        primary = option == value, modifier = Modifier.weight(1f)) {
                        onSelect(option); open = false
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
internal fun ToolsStatus(text: String, active: Boolean = false) {
    val p = LocalApprovedPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(9.dp).clip(RoundedCornerShape(5.dp)).background(if (active) p.green else p.secondary))
        ToolsText(text, secondary = true)
    }
}

@Composable
internal fun ToolsConfirm(
    title: String, message: String, confirmLabel: String,
    onDismiss: () -> Unit, onConfirm: () -> Unit,
) {
    ToolsSheet(title, onDismiss, actions = {
        ToolsButton("Cancel", onClick = onDismiss)
        ToolsButton(confirmLabel, destructive = true, onClick = onConfirm)
    }) { ToolsText(message, secondary = true) }
}
