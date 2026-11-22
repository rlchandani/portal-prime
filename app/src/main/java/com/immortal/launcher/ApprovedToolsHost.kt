/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.immortal.launcher.ui.theme.LocalAdvancedSettings
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Saveable drafts plus a bounded local-pane cache; hidden producers have no active lifecycle. */
@Composable
internal fun ApprovedToolsHost(
    onExit: () -> Unit, modifier: Modifier, embedded: Boolean, libraryRequest: Int,
) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var consumedLibraryRequest by rememberSaveable { mutableStateOf(libraryRequest) }
    val advanced = LocalAdvancedSettings.current
    val tool = ApprovedToolsCatalog.resolve(selected, advanced)
    val holder = rememberSaveableStateHolder()
    val grid = rememberLazyGridState()
    BackHandler { if (tool == null) onExit() else selected = null }
    LaunchedEffect(advanced) { if (selected == "speed" && !advanced) selected = null }
    LaunchedEffect(libraryRequest) {
        if (libraryRequest != consumedLibraryRequest) {
            selected = null
            consumedLibraryRequest = libraryRequest
        }
    }
    CompositionLocalProvider(
        LocalApprovedPalette provides if (LocalApprovedPalette.current.dark) ApprovedToolsPalette
            else com.immortal.launcher.ui.theme.ApprovedLightPalette,
        LocalApprovedToolPage provides true,
        LocalApprovedToolWorkspace provides true,
    ) {
        val layoutClass = currentLayoutClass()
        // Opening a tool and coming Back used to dispose one tree and compose the other from scratch
        // (two 100-150 ms frames each way on Family). The library now stays composed under an open tool,
        // and a local tool's workspace stays composed under the library, so either switch only flips
        // which layer draws and takes input. Camera, audio and network tools still leave on Back.
        var keptWorkspace by remember { mutableStateOf<ApprovedTool?>(null) }
        val workspace = tool ?: keptWorkspace?.takeIf { ToolsPaneRetention.keepsWorkspace(it.id) }
        SideEffect { keptWorkspace = workspace }
        // The first Reminders open of a session still composed its workspace inside the tap (145 ms on
        // Family). Like the launcher's hidden tabs, compose it ahead, hidden and inactive, at an idle
        // moment after the library; that first open then only flips which layer draws.
        LaunchedEffect(Unit) {
            delay(ToolsPaneRetention.PREBUILD_DELAY_MS)
            awaitMainLooperIdle()
            if (keptWorkspace == null && selected == null)
                keptWorkspace = ApprovedToolsCatalog.resolve(ToolsPaneRetention.PREBUILT_WORKSPACE, advanced)
        }
        val content: @Composable BoxScope.() -> Unit = {
            Box(modifier.fillMaxSize()) {
                ToolsLayer(visible = tool == null) {
                    holder.SaveableStateProvider("library") {
                        val edge = when {
                            embedded -> LauncherEdge
                            layoutClass.compactPortrait -> COMPACT_PORTRAIT_EDGE
                            layoutClass.compactLandscape -> COMPACT_LANDSCAPE_EDGE
                            else -> 96.dp
                        }
                        ToolsLibrary(grid, advanced, edge = edge) { selected = it.id }
                    }
                }
                if (workspace != null) ToolsLayer(visible = tool != null) {
                    ToolWorkspace(workspace, advanced, embedded, layoutClass, holder) { selected = it.id }
                }
            }
        }
        if (embedded) Box(content = content)
        else ApprovedAppsShell("tools", onExit, content = content)
    }
}

/** An open tool: the rail beside the tool's heading and pane (compact layouts in [CompactToolWorkspace]). */
@Composable
private fun ToolWorkspace(
    tool: ApprovedTool, advanced: Boolean, embedded: Boolean, layoutClass: LayoutClass, holder: SaveableStateHolder,
    onSelect: (ApprovedTool) -> Unit,
) {
    if (layoutClass.compact) {
        CompactToolWorkspace(tool, advanced, embedded, layoutClass.compactPortrait, holder, onSelect)
        return
    }
    Row(Modifier.fillMaxSize().padding(horizontal = if (embedded) LauncherEdge else 56.dp, vertical = 36.dp)) {
        ToolsRail(tool, advanced, onSelect = onSelect)
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.fillMaxWidth().heightIn(min = 152.dp)
                .padding(horizontal = 40.dp, vertical = 28.dp)) {
                Text(tool.title, color = IosLabel, fontSize = 44.sp, lineHeight = 55.sp,
                    fontWeight = FontWeight.Medium, letterSpacing = (-.6).sp)
                Text(tool.description, color = IosSecondary, fontSize = 22.sp,
                    lineHeight = 32.sp, modifier = Modifier.padding(top = 8.dp))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(IosDivider))
            Box(Modifier.weight(1f).fillMaxWidth()
                .padding(start = 40.dp, end = 40.dp, top = 32.dp, bottom = 8.dp)) {
                ToolsWorkspacePanes(tool.id, holder)
            }
        }
    }
}

/**
 * The library or an open tool, kept composed while the other one shows. Hidden, it draws nothing, takes no
 * touch, focus or semantics, and its page lifecycle stays CREATED (no refreshes, Back callbacks off), as the
 * launcher's retained tabs do.
 */
@Composable
private fun ToolsLayer(visible: Boolean, content: @Composable () -> Unit) {
    val active = visible && LocalApprovedLauncherPageActive.current
    val input = remember { LauncherPageInputGate() }
    val focus = LocalFocusManager.current
    LaunchedEffect(visible) { if (!visible) focus.clearFocus(force = true) }
    Box(Modifier.fillMaxSize()
        .zIndex(if (visible) 1f else 0f)
        .graphicsLayer { alpha = if (visible) 1f else 0f }
        .focusProperties { canFocus = active }
        .pointerInput(input) {
            try {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.pressed }) input.beginTouch()
                        if (!input.acceptsAction()) event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) input.endTouch()
                    }
                }
            } finally { input.endTouch() }
        }
        .then(if (visible) Modifier else Modifier.clearAndSetSemantics { })) {
        ApprovedLauncherPageLifecycle(active, input, content = content)
    }
}

private val COMPACT_PORTRAIT_EDGE = 24.dp
private val COMPACT_LANDSCAPE_EDGE = 48.dp
private val COMPACT_RAIL_W = 260.dp

/**
 * Compact open tool. Portrait: the tool list becomes a scrolling tab strip above the full-width tool.
 * Landscape: a narrower rail beside the tool, with tighter padding for the 740 dp height.
 */
@Composable
private fun CompactToolWorkspace(
    tool: ApprovedTool, advanced: Boolean, embedded: Boolean, portrait: Boolean, holder: SaveableStateHolder,
    onSelect: (ApprovedTool) -> Unit,
) {
    val edge = if (embedded) LauncherEdge else COMPACT_PORTRAIT_EDGE
    val heading: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(horizontal = if (portrait) 8.dp else 28.dp, vertical = 20.dp)) {
            Text(tool.title, color = IosLabel, fontSize = 36.sp, lineHeight = 45.sp,
                fontWeight = FontWeight.Medium, letterSpacing = (-.6).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tool.description, color = IosSecondary, fontSize = 22.sp,
                lineHeight = 30.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
    if (portrait) {
        Column(Modifier.fillMaxSize().padding(horizontal = edge, vertical = 24.dp)) {
            ToolsTabStrip(tool, advanced, onSelect)
            heading()
            Box(Modifier.fillMaxWidth().height(1.dp).background(IosDivider))
            Box(Modifier.weight(1f).fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 24.dp, bottom = 8.dp)) {
                ToolsWorkspacePanes(tool.id, holder)
            }
        }
    } else {
        Row(Modifier.fillMaxSize().padding(horizontal = edge, vertical = 20.dp)) {
            ToolsRail(tool, advanced, width = COMPACT_RAIL_W, singleLine = true, onSelect = onSelect)
            Column(Modifier.weight(1f).fillMaxHeight()) {
                heading()
                Box(Modifier.fillMaxWidth().height(1.dp).background(IosDivider))
                Box(Modifier.weight(1f).fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 8.dp)) {
                    ToolsWorkspacePanes(tool.id, holder)
                }
            }
        }
    }
}

/** Compact portrait: the rail's tools as one horizontally scrolling row of tabs. */
@Composable
private fun ToolsTabStrip(selectedTool: ApprovedTool, advanced: Boolean, onSelect: (ApprovedTool) -> Unit) {
    val tools = remember(advanced) { ApprovedToolsCatalog.visible(advanced) }
    val p = LocalApprovedPalette.current
    // An intentional strip (14 tools never fit 800 dp); it opens on the selected tool and keeps a
    // newly selected one in view, so the current tab is never off to the side.
    val selectedIndex = tools.indexOfFirst { it.id == selectedTool.id }.coerceAtLeast(0)
    val strip = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    LaunchedEffect(selectedIndex) {
        val visible = strip.layoutInfo.visibleItemsInfo
        val full = visible.any { it.index == selectedIndex && it.offset >= 0 &&
            it.offset + it.size <= strip.layoutInfo.viewportEndOffset }
        if (!full) strip.animateScrollToItem(selectedIndex)
    }
    LazyRow(Modifier.fillMaxWidth(), state = strip, horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)) {
        items(tools, key = { it.id }) { tool ->
            val selected = selectedTool.id == tool.id
            Row(Modifier.heightIn(min = 64.dp).clip(RoundedCornerShape(14.dp))
                .background(if (selected) p.surface2 else Color.Transparent)
                .semantics { this.selected = selected; role = Role.Tab }
                .tvFocusable(RoundedCornerShape(14.dp), focusScale = 1f, ringColor = p.accent) { onSelect(tool) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ToolsGlyph(tool.glyph, toolsTint(tool), Modifier.size(28.dp))
                Text(tool.title, color = if (selected) p.accent else p.secondary,
                    fontSize = 22.sp, lineHeight = 30.sp, maxLines = 1)
            }
        }
    }
}

/** Cached display lists avoid rebuilding local controls on every warmed switch. */
@Composable
private fun ToolsWorkspacePanes(selected: String, holder: SaveableStateHolder) {
    val foreground = LocalApprovedLauncherPageActive.current
    val focus = LocalFocusManager.current
    var retained by remember { mutableStateOf(emptyList<String>()) }
    val panes = remember(retained, selected) { ToolsPaneRetention.afterSelection(retained, selected) }
    SideEffect { retained = panes }
    LaunchedEffect(selected) { focus.clearFocus(force = true) }
    Box(Modifier.fillMaxSize()) {
        panes.forEach { id ->
            key(id) {
                val active = id == selected && foreground
                Box(Modifier.fillMaxSize()
                    .zIndex(if (id == selected) 1f else 0f)
                    .graphicsLayer { alpha = if (id == selected) 1f else 0f }
                    .focusProperties { canFocus = active }
                    .then(if (active) Modifier else Modifier.clearAndSetSemantics { })
                    .pointerInput(active) {
                        if (!active) awaitPointerEventScope {
                            while (true) awaitPointerEvent(PointerEventPass.Initial)
                                .changes.forEach { it.consume() }
                        }
                    }) {
                    ApprovedLauncherPageLifecycle(active) {
                        holder.SaveableStateProvider(id) {
                            when (id) {
                                "alarm" -> ToolsAlarmContent()
                                "timers" -> ToolsTimersContent()
                                "reminders" -> ToolsRemindersContent()
                                "countdowns" -> ToolsCountdownsContent()
                                "converter" -> ToolsConverterContent()
                                "lamp" -> ToolsLampContent()
                                "notes" -> if (rememberGuestHides(GuestContent.NOTES)) {
                                    GuestHiddenNote("Notes are hidden while guests are here.")
                                } else ToolsNotesContent()
                                "guests" -> ToolsGuestsContent()
                                "camera" -> ToolsCamerasContent()
                                "intercom" -> ToolsIntercomContent()
                                "stories" -> ToolsStoriesContent()
                                "iss" -> ToolsIssContent()
                                "aurora" -> ToolsAuroraContent()
                                "speed" -> ToolsSpeedContent()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolsRail(
    selectedTool: ApprovedTool, advanced: Boolean, width: Dp = 300.dp, singleLine: Boolean = false,
    onSelect: (ApprovedTool) -> Unit,
) {
    val tools = remember(advanced) { ApprovedToolsCatalog.visible(advanced) }
    val p = LocalApprovedPalette.current
    Row(Modifier.width(width).fillMaxHeight()) {
        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 16.dp)
            .padding(top = 28.dp, bottom = 32.dp)) {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(tools, key = { it.id }) { tool ->
                    val selected = selectedTool.id == tool.id
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(14.dp))
                        .background(if (selected) p.surface2 else Color.Transparent)
                        .semantics { this.selected = selected; role = Role.Tab }
                        .tvFocusable(RoundedCornerShape(14.dp), focusScale = 1f,
                            ringColor = p.accent) { onSelect(tool) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        ToolsGlyph(tool.glyph, toolsTint(tool), Modifier.size(32.dp))
                        if (singleLine) {
                            Text(tool.title, color = if (selected) p.accent else p.secondary,
                                fontSize = 22.sp, lineHeight = 30.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        } else {
                            Text(tool.title, color = if (selected) p.accent else p.secondary,
                                fontSize = 22.sp, lineHeight = 30.sp)
                        }
                    }
                }
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(p.divider))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolsLibrary(grid: LazyGridState, advanced: Boolean, edge: Dp, onOpen: (ApprovedTool) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf("All tools") }
    val selectedGroup = ApprovedToolsCatalog.resolveGroup(group, advanced)
    // Render the valid category immediately; then keep saved state consistent with that frame.
    SideEffect { if (group != selectedGroup) group = selectedGroup }
    val entries = remember(advanced, selectedGroup, query) {
        ApprovedToolsCatalog.visible(advanced, selectedGroup, query)
    }
    val p = LocalApprovedPalette.current
    val layoutClass = currentLayoutClass()
    val context = LocalContext.current.applicationContext
    val active = LocalApprovedLauncherPageActive.current
    val resume = rememberApprovedResume()
    // A library composed ahead of its first visit reads its summaries once while hidden, so the
    // first visible frame already shows them and activation's re-read publishes an equal map.
    val summaries by produceState<Map<String, String>>(emptyMap(), active, resume) {
        if (active || value.isEmpty()) value = withContext(Dispatchers.IO) {
            ApprovedToolsSummaries.load(context)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = edge).padding(top = 52.dp, bottom = 24.dp)) {
        val columns = ToolsCategoryLayout.libraryColumns(layoutClass, maxWidth.value.toDouble())
        LazyVerticalGrid(GridCells.Fixed(columns), state = grid, modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (layoutClass.compactPortrait) item(key = "heading", span = { GridItemSpan(maxLineSpan) }) {
                // Compact portrait: the search goes full width under the title.
                Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                    Text("Tools", color = p.text, fontSize = 44.sp, lineHeight = 55.sp, fontWeight = FontWeight.Medium)
                    ToolsText("Small things that make the day easier.", true, Modifier.padding(top = 10.dp))
                    ApprovedAppsSearch(query, { query = it }, "Find a tool", Modifier.fillMaxWidth().padding(top = 20.dp))
                }
            } else item(key = "heading", span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Tools", color = p.text, fontSize = 44.sp, lineHeight = 55.sp, fontWeight = FontWeight.Medium)
                        ToolsText("Small things that make the day easier.", true, Modifier.padding(top = 10.dp))
                    }
                    ApprovedAppsSearch(query, { query = it }, "Find a tool", Modifier.width(420.dp))
                }
            }
            item(key = "categories", span = { GridItemSpan(maxLineSpan) }) {
                val categories = ApprovedToolsCatalog.categories(advanced)
                val chips = ToolsCategoryLayout.of(layoutClass)
                val chip: @Composable (String) -> Unit = { category ->
                    ToolsCategoryChip(category, selectedGroup == category, chips.padding.dp) { group = category }
                }
                if (layoutClass.compact) {
                    // Compact: the chips are measured and wrap onto a further line only when they
                    // truly don't fit (never a sideways scroll); upright they're tighter so the
                    // whole set, Advanced included, sits on one line at the default text size.
                    FlowRow(Modifier.fillMaxWidth().padding(ToolsCategoryLayout.INSET_DP.dp),
                        horizontalArrangement = Arrangement.spacedBy(chips.gap.dp),
                        verticalArrangement = Arrangement.spacedBy(ToolsCategoryLayout.ROW_GAP_DP.dp)) {
                        categories.forEach { chip(it) }
                    }
                } else {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        categories.forEach { chip(it) }
                    }
                }
            }
            if (entries.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                ToolsPanel("No tools found") {
                    ToolsText("Try a different name or choose All tools.", true)
                    ToolsButton("Show all tools") { query = ""; group = "All tools" }
                }
            }
            ApprovedToolsCatalog.groups.forEach { section ->
                val members = entries.filter { it.group == section }
                if (members.isNotEmpty()) {
                    item(key = "group-$section", span = { GridItemSpan(maxLineSpan) }) {
                        Text(section, color = p.text, fontSize = 26.sp, lineHeight = 34.sp,
                            fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
                    }
                    items(members, key = { it.id }) { tool ->
                        ToolsLibraryEntry(tool, summaries[tool.id] ?: toolsDefaultSummary(tool.id),
                            compact = layoutClass.compact) { onOpen(tool) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolsLibraryEntry(tool: ApprovedTool, summary: String, compact: Boolean = false, onOpen: () -> Unit) {
    val p = LocalApprovedPalette.current
    Surface(color = p.surface, shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, p.divider), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.heightIn(min = 124.dp).tvFocusable(RoundedCornerShape(24.dp), focusScale = 1f,
            ringColor = p.accent, onClick = onOpen)
            .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(18.dp))
                .background(toolsTint(tool).copy(alpha = .12f)), contentAlignment = Alignment.Center) {
                ToolsGlyph(tool.glyph, toolsTint(tool), Modifier.size(36.dp))
            }
            Column(Modifier.weight(1f)) {
                if (compact) {
                    Text(tool.title, color = p.text, fontSize = 26.sp, lineHeight = 35.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    Text(tool.title, color = p.text, fontSize = 26.sp, lineHeight = 35.sp, fontWeight = FontWeight.Medium)
                }
                Text(tool.description, color = p.secondary, fontSize = 20.sp, lineHeight = 27.sp,
                    modifier = Modifier.padding(top = 2.dp))
                Text(summary, color = p.secondary, fontSize = 18.sp, lineHeight = 24.sp,
                    modifier = Modifier.padding(top = 2.dp))
            }
            Text("›", color = p.secondary, fontSize = 28.sp)
        }
    }
}

private fun toolsDefaultSummary(id: String): String = when (id) {
    "alarm" -> "Your next wake-up time"
    "timers" -> "Ready when you are"
    "reminders" -> "For everyone, or just you"
    "countdowns" -> "Dates to look forward to"
    "converter" -> "Works offline"
    "lamp" -> "Brightness and warmth"
    "notes" -> "Leave a message"
    "guests" -> "Wi-Fi card for visitors"
    "camera" -> "Saved camera streams"
    "intercom" -> "Broadcast or listen"
    "stories" -> "5 bundled stories"
    "iss" -> "Passes over your location"
    "aurora" -> "Planetary K-index"
    else -> "Run only when needed"
}

/** One category filter chip in the Tools library (underlined when selected). */
@Composable
private fun ToolsCategoryChip(category: String, selected: Boolean, padding: Dp, onSelect: () -> Unit) {
    val p = LocalApprovedPalette.current
    val shape = RoundedCornerShape(16.dp)
    Box(Modifier.heightIn(min = 68.dp).clip(shape)
        .background(if (selected) p.surface2 else Color.Transparent)
        .then(if (selected) Modifier.border(1.dp, p.divider, shape) else Modifier)
        .semantics { this.selected = selected; role = Role.Tab }
        .tvFocusable(shape, focusScale = 1f, ringColor = p.accent, onClick = onSelect),
        contentAlignment = Alignment.Center) {
        Text(category, color = if (selected) p.text else p.secondary,
            fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            modifier = Modifier.padding(horizontal = padding, vertical = 16.dp))
        if (selected) Column(Modifier.matchParentSize()) {
            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth().height(3.dp).background(p.accent))
        }
    }
}
