/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.settings.BoolSpec
import com.immortal.launcher.settings.DerivedSpec
import com.immortal.launcher.settings.EnumSpec
import com.immortal.launcher.settings.Entry
import com.immortal.launcher.settings.IntSpec
import com.immortal.launcher.settings.NavSpec
import com.immortal.launcher.settings.SettingSpec
import com.immortal.launcher.settings.SettingsDomain
import com.immortal.launcher.settings.SettingsPresentation
import com.immortal.launcher.settings.StringSpec

/**
 * Registry-driven native rows. Presentation filtering is additive to every existing parent
 * and hardware gate. No values are rewritten when Advanced is hidden. The caller applies
 * through the registry and reloads its snapshot; editors never predict a stored value.
 * Each spec composes lazily with a stable identity, in a bounded viewport even in legacy shells.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <S> SettingsList(
    domain: SettingsDomain<S>,
    snapshot: S,
    exclude: Set<String> = emptySet(),
    onApply: (key: String, value: Any) -> Unit,
) {
    val model = prepareSettingsList(domain, snapshot, exclude, onApply)
    val scroll = rememberLazyListState()
    val requestedIndex = model.requested?.let { model.projection.controlIndices[it] }
    LaunchedEffect(model.requested, requestedIndex) {
        if (requestedIndex != null) scroll.scrollToItem(requestedIndex)
    }
    val maxHeight = (LocalConfiguration.current.screenHeightDp - 360).coerceAtLeast(400).dp
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxHeight), state = scroll) {
        settingsListItems(model)
    }
}

internal data class PreparedSettingsList<S>(
    val domain: SettingsDomain<S>,
    val snapshot: S,
    val advanced: Boolean,
    val projection: ApprovedSettingsLazyProjection<SettingSpec<S>>,
    val requested: String?,
    val showAdvancedHint: Boolean,
    val onApply: (String, Any) -> Unit,
) {
    val itemCount: Int get() = projection.items.size + if (showAdvancedHint) 1 else 0
}

@Composable
internal fun <S> prepareSettingsList(
    domain: SettingsDomain<S>,
    snapshot: S,
    exclude: Set<String> = emptySet(),
    onApply: (String, Any) -> Unit,
): PreparedSettingsList<S> {
    val context = LocalContext.current
    val advanced = com.immortal.launcher.ui.theme.LocalAdvancedSettings.current
    val groups = LinkedHashMap<String?, MutableList<SettingSpec<S>>>()
    domain.specs.filter { spec ->
        spec.key !in exclude && spec !is DerivedSpec<*> &&
            spec.visibleWhen(context, snapshot) &&
            (advanced || !SettingsPresentation.isAdvanced(domain.id, spec.key)) &&
            (domain.id != "screensaver" || snapshot !is ScreensaverConfig.Settings ||
                snapshot.enabled || spec.key == "enabled")
    }.forEach { spec ->
        val section = if (SettingsPresentation.isAdvanced(domain.id, spec.key)) "Advanced options"
            else domain.sections[spec.key]
        groups.getOrPut(section) { mutableListOf() }.add(spec)
    }
    val projection = approvedSettingsLazyProjection(domain.id, groups) { it.key }
    val requested = LocalApprovedRequestedControl.current
        ?: (context as? Activity)?.intent?.getStringExtra(APPROVED_SETTINGS_CONTROL)
    val hint = !advanced && domain.specs.any { it.key !in exclude &&
        it.visibleWhen(context, snapshot) && SettingsPresentation.isAdvanced(domain.id, it.key) }
    return PreparedSettingsList(domain, snapshot, advanced, projection, requested, hint, onApply)
}

/** Emits rows into an existing scroll owner; creates no nested list or bounded viewport. */
internal fun <S> LazyListScope.settingsListItems(model: PreparedSettingsList<S>) {
    items(model.projection.items, key = { it.id }, contentType = { it.kind }) { item ->
        when (item.kind) {
            ApprovedSettingsLazyKind.HEADER -> {
                if (item.firstGroup) ApprovedSettingsSectionLabel(item.section!!)
                else SectionLabel(item.section!!)
            }
            ApprovedSettingsLazyKind.GAP -> Spacer(Modifier.size(22.dp))
            ApprovedSettingsLazyKind.CONTROL -> ApprovedSettingsLazyRow(
                item.id, item.firstRow, item.lastRow, model.requested,
            ) {
                SettingControl(model.domain.id, item.value!!, model.snapshot, model.advanced, model.onApply)
            }
        }
    }
    if (model.showAdvancedHint) {
        item(key = "${model.domain.id}:advanced-hint", contentType = "advanced-hint") {
            Text("More customization is available in Advanced settings.", color = IosSecondary,
                fontSize = 21.sp, lineHeight = 30.sp, modifier = Modifier.padding(top = 30.dp))
            ApprovedSettingsTextButton("Show advanced options", LocalApprovedEnableAdvanced.current)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ApprovedSettingsLazyRow(
    id: String,
    first: Boolean,
    last: Boolean,
    requested: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val highlight = requested == id
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(highlight) { if (highlight) requester.bringIntoView() }
    Column(modifier.fillMaxWidth().bringIntoViewRequester(requester)
        .approvedSettingsGroupRow(first, last, highlight)
        .semantics { contentDescription = id }
        .padding(top = if (first) 0.dp else 1.dp)) { content() }
}

/** Continuous card fill and perimeter. Paths/strokes are rebuilt only when geometry/colors change. */
@Composable
private fun Modifier.approvedSettingsGroupRow(first: Boolean, last: Boolean, highlight: Boolean): Modifier {
    val shape = remember(first, last) {
        RoundedCornerShape(
            topStart = if (first) 24.dp else 0.dp, topEnd = if (first) 24.dp else 0.dp,
            bottomStart = if (last) 24.dp else 0.dp, bottomEnd = if (last) 24.dp else 0.dp,
        )
    }
    val fill = if (highlight) IosFocusTint else IosCard
    val divider = IosDivider
    val accent = IosBlue
    return clip(shape).drawWithCache {
        val w = size.width
        val h = size.height
        val half = .5.dp.toPx()
        val radius = 24.dp.toPx().coerceAtMost(minOf(w, h) / 2f)
        val perimeter = Path().apply {
            moveTo(half, if (first) radius else 0f)
            if (first) {
                arcTo(Rect(half, half, radius * 2 - half, radius * 2 - half), 180f, 90f, false)
                lineTo(w - radius, half)
                arcTo(Rect(w - radius * 2 + half, half, w - half, radius * 2 - half), 270f, 90f, false)
            } else moveTo(w - half, 0f)
            lineTo(w - half, if (last) h - radius else h)
            if (last) {
                arcTo(Rect(w - radius * 2 + half, h - radius * 2 + half, w - half, h - half), 0f, 90f, false)
                lineTo(radius, h - half)
                arcTo(Rect(half, h - radius * 2 + half, radius * 2 - half, h - half), 90f, 90f, false)
            } else moveTo(half, h)
            lineTo(half, if (first) radius else 0f)
        }
        val borderStroke = Stroke(1.dp.toPx())
        val highlightStroke = Stroke(3.dp.toPx())
        val dividerStart = Offset(28.dp.toPx(), half)
        val dividerEnd = Offset(w, half)
        onDrawWithContent {
            drawRect(fill)
            drawContent()
            drawPath(perimeter, divider, style = borderStroke)
            if (!first) drawLine(divider, dividerStart, dividerEnd, strokeWidth = borderStroke.width)
            if (highlight) drawRect(accent, style = highlightStroke)
        }
    }
}

@Composable
private fun <S> SettingControl(
    domainId: String,
    spec: SettingSpec<S>,
    snapshot: S,
    advanced: Boolean,
    onApply: (String, Any) -> Unit,
) {
    val context = LocalContext.current
    val navigate = rememberApprovedSettingsNavigator()
    fun open(activity: Class<*>) {
        val destination = ApprovedSettingsPages.firstOrNull { it.standaloneActivity == activity }
        if (destination != null) navigate(approvedSettingsRoute(destination.id))
        else context.startActivity(Intent(context, activity))
    }
    val fullHelp = when (spec) {
        is BoolSpec<*> -> spec.help
        is EnumSpec<*> -> spec.help
        is IntSpec<*> -> spec.help
        is StringSpec<*> -> spec.help
        is NavSpec<*> -> spec.help
        is DerivedSpec<*> -> null
    }
    val help = if (advanced) fullHelp else SettingsPresentation.conciseHelp(domainId, spec.key, fullHelp)
    CompositionLocalProvider(LocalApprovedSettingsHelp provides help) {
        @Suppress("UNCHECKED_CAST")
        when (spec) {
            is BoolSpec<*> -> {
                val s = spec as BoolSpec<S>
                ToggleRow(s.title, s.get(snapshot)) { onApply(s.key, it) }
            }
            is EnumSpec<*> -> {
                val s = spec as EnumSpec<S>
                val selected = if (domainId == "immortal" && s.key == "settingsTheme") {
                    if (com.immortal.launcher.ui.theme.LocalApprovedPalette.current.dark) "dark" else "light"
                } else s.get(snapshot)
                val options = SettingsPresentation.visibleEnumOptions(
                    domainId, s.key, s.options, selected, advanced)
                PickerRow(s.title, selected, options.map { (value, label) -> PickerOption(value, label) }) {
                    onApply(s.key, it)
                }
            }
            is IntSpec<*> -> {
                val s = spec as IntSpec<S>
                ApprovedIntSetting(s, s.get(snapshot), help) { onApply(s.key, it) }
            }
            is StringSpec<*> -> {
                val s = spec as StringSpec<S>
                val entry = s.entry
                if (entry is Entry.Nav) {
                    NavRow(s.title, s.get(snapshot).ifBlank { "Not set" }) {
                        open(entry.activity)
                    }
                } else {
                    ApprovedStringSetting(s, s.get(snapshot), help) { onApply(s.key, it) }
                }
            }
            is NavSpec<*> -> {
                val s = spec as NavSpec<S>
                NavRow(s.title, s.value(context, snapshot)) {
                    open(s.activity)
                }
            }
            is DerivedSpec<*> -> Unit
        }
    }
}

/** Numeric choices open the approved dialog; bounds/wrapping come exclusively from the spec. */
@Composable
private fun <S> ApprovedIntSetting(spec: IntSpec<S>, value: Int, help: String?, onApply: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var draft by remember(open, value) { mutableStateOf(value.toString()) }
    var error by remember(open) { mutableStateOf<String?>(null) }
    NavRow(spec.title, spec.format(value)) { open = true }
    if (open) {
        ApprovedSettingsDialog(spec.title, onDismiss = { open = false }, help = help) {
            if (spec.asText) {
                IosTextField(draft, { draft = it; error = null }, spec.title)
            } else {
                Stepper(spec.title, draft.toIntOrNull()?.let(spec.format) ?: draft,
                    onMinus = {
                        draft.toIntOrNull()?.let { current ->
                            val next = current - spec.step
                            if (spec.wrap) draft = (Math.floorMod(next - spec.min, spec.max - spec.min + 1) + spec.min).toString()
                            else if (next >= spec.min) draft = next.toString()
                        }
                    },
                    onPlus = {
                        draft.toIntOrNull()?.let { current ->
                            val next = current + spec.step
                            if (spec.wrap) draft = (Math.floorMod(next - spec.min, spec.max - spec.min + 1) + spec.min).toString()
                            else if (next <= spec.max) draft = next.toString()
                        }
                    })
            }
            error?.let { Text(it, color = IosRed, fontSize = Sizes.rowSub) }
            Row(Modifier.padding(top = 24.dp)) {
                ActionPill("Save") {
                    val number = draft.toIntOrNull()
                    if (number == null || (!spec.wrap && number !in spec.min..spec.max)) {
                        error = "Enter a value from ${spec.min} to ${spec.max}."
                    } else {
                        // Wrapping is advertised by the spec; normalize only a wrapping draft.
                        val range = spec.max - spec.min + 1
                        val chosen = if (spec.wrap) Math.floorMod(number - spec.min, range) + spec.min else number
                        onApply(chosen)
                        open = false
                    }
                }
                ApprovedSettingsTextButton("Cancel") { open = false }
            }
        }
    }
}

/** Scalar strings use a dialog. Atomic credential forms still use their original Activities. */
@Composable
private fun <S> ApprovedStringSetting(spec: StringSpec<S>, value: String, help: String?, onApply: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var draft by remember(open, value) { mutableStateOf(if (spec.secret) "" else value) }
    var error by remember(open) { mutableStateOf<String?>(null) }
    NavRow(spec.title, if (spec.secret) { if (value.isBlank()) "Not set" else "Entered" } else value.ifBlank { "Not set" }) {
        open = true
    }
    if (open) {
        ApprovedSettingsDialog(spec.title, onDismiss = { open = false }, help = help) {
            OutlinedTextField(
                value = draft, onValueChange = { draft = it; error = null }, singleLine = true,
                colors = iosTextFieldColors(), textStyle = TextStyle(fontSize = 26.sp, color = IosLabel),
                visualTransformation = if (spec.secret) PasswordVisualTransformation() else VisualTransformation.None,
                placeholder = { Text(if (spec.secret) "Enter a new value" else spec.title) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp),
            )
            error?.let { Text(it, color = IosRed, fontSize = Sizes.rowSub) }
            Row(Modifier.padding(top = 24.dp)) {
                ActionPill("Save") {
                    if ((spec.secret && draft.isBlank()) || spec.applyWhen(draft)) {
                        // A blank secret is the registry's "keep existing" operation.
                        if (!spec.secret || draft.isNotBlank()) onApply(draft)
                        open = false
                    } else error = "Enter a valid value for ${spec.title.lowercase()}."
                }
                ApprovedSettingsTextButton("Cancel") { open = false }
            }
        }
    }
}
