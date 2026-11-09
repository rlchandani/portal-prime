/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SidebarRowHeight = 80.dp
private val SidebarGroupRadius = 24.dp
private val SidebarDividerInset = 84.dp
private val ProfileAvatar = 84.dp
private val ProfileGlyph = 34.dp
private val AvatarTop = Color(0xFF3A2A20)
private val AvatarBottom = Color(0xFF1D1714)
private val StatusOk = Color(0xFF34B864)
private val StatusAvailable = Color(0xFFEE9B1A)

/** Profile card, then the four groups. Only the two rows whose selection changes recompose. */
internal fun LazyListScope.settingsSidebarGroups(selectedRoot: String, onSelect: (String) -> Unit) {
    item(key = "profile", contentType = "profile") {
        SettingsProfileCard(selectedRoot == SettingsPageIds.ABOUT) { onSelect(SettingsPageIds.ABOUT) }
    }
    SettingsGroup.entries.forEach { group ->
        group.title?.let { title ->
            item(key = "group:${group.name}", contentType = "group-header") {
                Text(title, color = IosSecondary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 22.dp, end = 6.dp, bottom = 10.dp))
            }
        }
        val pages = SettingsTree.groupPages(group)
        itemsIndexed(pages, key = { _, page -> "row:${page.id}" }, contentType = { _, _ -> "row" }) { index, page ->
            SettingsSidebarRow(page, page.id == selectedRoot, index == 0, index == pages.lastIndex) { onSelect(page.id) }
        }
        item(key = "gap:${group.name}", contentType = "gap") { Spacer(Modifier.height(22.dp)) }
    }
}

@Composable
private fun SettingsSidebarRow(page: SettingsPageDef, selected: Boolean, first: Boolean, last: Boolean, onClick: () -> Unit) {
    val shape = remember(first, last) {
        RoundedCornerShape(topStart = if (first) SidebarGroupRadius else 0.dp, topEnd = if (first) SidebarGroupRadius else 0.dp,
            bottomStart = if (last) SidebarGroupRadius else 0.dp, bottomEnd = if (last) SidebarGroupRadius else 0.dp)
    }
    val divider = IosDivider
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = SidebarRowHeight).clip(shape)
            .background(if (selected) IosFocusTint else IosCard)
            .drawBehind {
                if (!first && !selected) drawLine(divider, Offset(SidebarDividerInset.toPx(), 0f),
                    Offset(size.width, 0f), 1.dp.toPx())
            }
            .then(if (BuildConfig.PORTAL_SETTINGS_UI_DIAGNOSTICS) Modifier.settingsUiTarget("category", page.id) else Modifier)
            .tvFocusableRow(focusFill = IosFocusTint, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(start = 18.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SettingsTileIcon(page.icon, Color(page.color), SettingsTileSize.SIDEBAR, shared = true)
        Text(page.title, color = IosLabel, fontSize = 27.sp, lineHeight = 32.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f))
        if (page.id == SettingsPageIds.ADVANCED) {
            Text(if (com.immortal.launcher.ui.theme.LocalAdvancedSettings.current) "On" else "Off",
                color = IosSecondary, fontSize = 23.sp)
        }
    }
}

/** Portal name, model, and Hearthpane version with its update state; opens About & Health. */
@Composable
private fun SettingsProfileCard(selected: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val name = remember(context) { FleetConfig.name(context) }
    val ota by OtaUpdater.state.collectAsState()
    val status = remember(ota) { settingsProfileStatus(ota, BuildConfig.VERSION_NAME) }
    val shape = RoundedCornerShape(SidebarGroupRadius)
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 26.dp).defaultMinSize(minHeight = 120.dp).clip(shape)
            .background(if (selected) IosFocusTint else IosCard)
            .tvFocusableRow(focusFill = IosFocusTint, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$name, ${status.text}" }
            .padding(horizontal = 22.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Box(Modifier.size(ProfileAvatar).clip(CircleShape)
            .background(Brush.radialGradient(listOf(AvatarTop, AvatarBottom))), contentAlignment = Alignment.Center) {
            HearthpaneGlyph(ProfileGlyph)
        }
        Column(Modifier.weight(1f)) {
            Text(name, color = IosLabel, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(android.os.Build.MODEL, color = IosSecondary, fontSize = 21.sp, lineHeight = 28.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val dot = when (status.badge) {
                    SettingsUpdateBadge.UP_TO_DATE -> StatusOk
                    SettingsUpdateBadge.AVAILABLE -> StatusAvailable
                    else -> null
                }
                dot?.let { Box(Modifier.size(10.dp).background(it, CircleShape)) }
                Text(status.text, color = IosSecondary, fontSize = 20.sp, lineHeight = 26.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Results replace the groups while a query is typed, like iPadOS. */
@Composable
internal fun SettingsSearchResults(
    query: String,
    results: List<ApprovedSettingsSearchResult>,
    queryToken: Any?,
    onPick: (ApprovedSettingsSearchResult) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(modifier.focusGroup(), contentPadding = PaddingValues(bottom = 40.dp)) {
        if (results.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                Text("No results for “${query.trim()}”", color = IosSecondary, fontSize = 24.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 20.dp))
            }
            return@LazyColumn
        }
        item(key = "count", contentType = "group-header") {
            Text("${results.size} result${if (results.size == 1) "" else "s"}", color = IosSecondary,
                fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 22.dp, bottom = 10.dp))
        }
        itemsIndexed(results, key = { _, r -> "${r.page}|${r.control.orEmpty()}|${r.title}" },
            contentType = { _, _ -> "result" }) { index, result ->
            SettingsSearchResultRow(result, index == 0, index == results.lastIndex,
                if (BuildConfig.PORTAL_SETTINGS_UI_DIAGNOSTICS)
                    Modifier.settingsUiTarget("result", index.toString(), queryToken = queryToken) else Modifier) {
                onPick(result)
            }
        }
    }
}

@Composable
private fun SettingsSearchResultRow(
    result: ApprovedSettingsSearchResult,
    first: Boolean,
    last: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val root = SettingsTree.page(SettingsTree.rootOf(result.page)) ?: return
    val shape = RoundedCornerShape(topStart = if (first) SidebarGroupRadius else 0.dp, topEnd = if (first) SidebarGroupRadius else 0.dp,
        bottomStart = if (last) SidebarGroupRadius else 0.dp, bottomEnd = if (last) SidebarGroupRadius else 0.dp)
    Row(
        modifier.fillMaxWidth().defaultMinSize(minHeight = 96.dp).clip(shape).background(IosCard)
            .tvFocusableRow(focusFill = IosFocusTint, onClick = onClick)
            .padding(start = 18.dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SettingsTileIcon(root.icon, Color(root.color), SettingsTileSize.SMALL, shared = true)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(result.title, color = IosLabel, fontSize = 27.sp, lineHeight = 32.sp,
                    modifier = Modifier.weight(1f, fill = false))
                if (result.advanced) SettingsAdvancedBadge()
            }
            Text(approvedSettingsSearchBreadcrumb(result), color = IosSecondary, fontSize = 20.sp,
                lineHeight = 26.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp))
        }
    }
}
