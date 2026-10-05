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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.LocalApprovedPalette

/** Editor-only form controls; the shared settings shell and registry rows remain the host APIs. */
@Composable
internal fun ApprovedEditorsForm(
    actions: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Card {
        Column(Modifier.padding(32.dp)) {
            SectionLabel("Options")
            Card { Column(content = content) }
            actions()
        }
    }
}

@Composable
internal fun ApprovedEditorsField(
    label: String, value: String, onChange: (String) -> Unit,
    placeholder: String = label, secret: Boolean = false, numeric: Boolean = false,
    enabled: Boolean = true,
) {
    var revealed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 24.dp)) {
        Text(label, color = IosSecondary, fontSize = 22.sp, lineHeight = 30.sp)
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = LocalApprovedPalette.current.background,
                shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, IosDivider),
                modifier = Modifier.weight(1f)) {
                BasicTextField(value, { onChange(it.replace("\n", "").replace("\r", "")) },
                    enabled = enabled, singleLine = true,
                    textStyle = TextStyle(color = IosLabel, fontSize = 26.sp, lineHeight = 36.sp),
                    keyboardOptions = KeyboardOptions(keyboardType = when {
                        secret -> KeyboardType.Password
                        numeric -> KeyboardType.Number
                        else -> KeyboardType.Text
                    }),
                    visualTransformation = if (secret && !revealed) PasswordVisualTransformation()
                        else VisualTransformation.None,
                    cursorBrush = SolidColor(IosBlue),
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 78.dp)
                        .semantics { contentDescription = label }
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                    decorationBox = { inner ->
                        if (value.isEmpty()) Text(placeholder, color = IosSecondary,
                            fontSize = 26.sp, lineHeight = 36.sp)
                        inner()
                    })
            }
            if (secret) ApprovedEditorsButton(if (revealed) "Hide" else "Show",
                enabled = enabled) { revealed = !revealed }
        }
    }
}

@Composable
internal fun ApprovedEditorsButton(
    label: String, primary: Boolean = false, enabled: Boolean = true,
    destructive: Boolean = false, onClick: () -> Unit,
) {
    val p = LocalApprovedPalette.current
    val fill = if (primary) p.accent else p.surface2
    Surface(color = if (enabled) fill else p.surface2, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.defaultMinSize(minHeight = 68.dp)
            .tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f, ringColor = p.accent,
                enabled = enabled, onClick = onClick)) {
        Box(Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center) {
            Text(label, fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium,
                color = when {
                    !enabled -> p.secondary
                    destructive -> p.error
                    primary -> if (p.dark) p.background else p.surface
                    else -> p.text
                })
        }
    }
}

@Composable
internal fun ApprovedEditorsActions(
    label: String = "Add", error: String? = null, enabled: Boolean = true,
    onCancel: () -> Unit, onSave: () -> Unit,
) {
    Column(Modifier.padding(vertical = 28.dp)) {
        // Reserve the reviewed validation/status line to avoid jumping buttons on errors.
        Text(error.orEmpty(), color = LocalApprovedPalette.current.error, fontSize = 23.sp,
            lineHeight = 34.sp, modifier = Modifier.defaultMinSize(minHeight = 34.dp))
        Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ApprovedEditorsButton(label, primary = true, enabled = enabled, onClick = onSave)
            ApprovedEditorsButton("Cancel", onClick = onCancel)
        }
    }
}

@Composable
internal fun ApprovedEditorsEmpty(message: String = "Nothing added yet. Add an item below.") {
    Text(message, color = IosSecondary, fontSize = 23.sp, lineHeight = 34.sp,
        modifier = Modifier.padding(26.dp))
}

/** Fixed maximum viewport makes nested scrolling safe and composition independent of list size. */
@Composable
internal fun <T> ApprovedEditorsCollection(
    title: String, entries: List<T>, key: (T) -> Any,
    empty: String = "Nothing added yet. Add an item below.",
    row: @Composable (T) -> Unit,
) {
    SectionLabel(title)
    Card {
        if (entries.isEmpty()) ApprovedEditorsEmpty(empty)
        else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            itemsIndexed(entries, key = { _, entry -> key(entry) }) { index, entry ->
                if (index > 0) Divider()
                row(entry)
            }
        }
    }
}

@Composable
internal fun ApprovedEditorsCollectionRow(
    title: String, detail: String = "", onRemove: () -> Unit,
    action: String? = null, onAction: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 100.dp)
        .padding(horizontal = 28.dp, vertical = 21.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, color = IosLabel, fontSize = 26.sp, lineHeight = 34.sp)
            if (detail.isNotEmpty()) Text(detail, color = IosSecondary, fontSize = 21.sp,
                lineHeight = 29.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp))
        }
        if (action != null && onAction != null) ApprovedSettingsTextButton(action, onAction)
        ApprovedSettingsTextButton("Remove", onRemove)
    }
}

@Composable
internal fun ApprovedEditorsRemoveDialog(
    name: String, onCancel: () -> Unit, onRemove: () -> Unit,
) {
    ApprovedSettingsDialog("Remove $name?", onDismiss = onCancel) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ApprovedEditorsButton("Cancel", onClick = onCancel)
            ApprovedEditorsButton("Remove", primary = true, onClick = onRemove)
        }
    }
}

@Composable
internal fun ApprovedEditorsNumber(
    title: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit,
    step: Int = 1, wrap: Boolean = false, format: (Int) -> String = { it.toString() },
    inputHelp: String? = null,
) {
    var draft by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val p = LocalApprovedPalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 24.dp)) {
        Text(title, color = p.secondary, fontSize = 22.sp, lineHeight = 30.sp)
        Spacer(Modifier.height(14.dp))
        Surface(color = p.background, shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, p.divider),
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 78.dp)
                .tvFocusable(RoundedCornerShape(14.dp), focusScale = 1f, ringColor = p.accent) {
                    draft = value.toString(); error = null
                }) {
            Text(format(value), color = p.text, fontSize = 26.sp, lineHeight = 36.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp))
        }
    }
    draft?.let { number ->
        ApprovedSettingsDialog(title, onDismiss = { draft = null }, help = inputHelp) {
            ApprovedEditorsField(title, number, { draft = it; error = null }, numeric = true)
            fun move(direction: Int) {
                val next = (draft?.toIntOrNull() ?: value) + direction * step
                draft = (if (wrap) when {
                    next < min -> max
                    next > max -> min
                    else -> next
                } else next.coerceIn(min, max)).toString()
            }
            Stepper(title, format(number.toIntOrNull() ?: value),
                onMinus = { move(-1) }, onPlus = { move(1) })
            ApprovedEditorsActions("Save", error, onCancel = { draft = null }) {
                val parsed = draft?.toIntOrNull()
                if (parsed == null || parsed !in min..max) error = "Enter a value from $min to $max."
                else { onChange(parsed); draft = null }
            }
        }
    }
}
