/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.jarvis.LocalJarvisPulse
import com.immortal.launcher.jarvis.SettingsJarvisPulse
import com.immortal.launcher.jarvis.jarvisPulse

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.immortal.launcher.ui.theme.LocalAdvancedSettings

private val PageSidePadding = SettingsSplitMetrics.PAGE_SIDE_PADDING_DP.dp
private val SinglePaneSidePadding = SettingsSplitMetrics.SINGLE_PANE_SIDE_PADDING_DP.dp
private val PageBottomPadding = 80.dp

/**
 * The selected page as inset grouped cards in one lazy list. Only this page is composed; rows
 * compose as they scroll in, and each entry kind is its own content type. The switch frame
 * composes only a few cheap entries ([SettingsRevealPlan]); the rest, and every bespoke block,
 * are precomposed over the following frames by the page's [SettingsCompositionScheduler].
 * [control] scrolls to and briefly highlights a row (search), then [onHighlightShown] clears it.
 */
@Composable
internal fun SettingsPageView(
    page: String,
    control: String?,
    onOpenPage: (String) -> Unit,
    onHighlightShown: () -> Unit,
) {
    val composition = FramePerformanceCapture.beginSettingsUiWork(SettingsUiWorkStage.DETAIL_COMPOSITION)
    val advanced = LocalAdvancedSettings.current
    val states = rememberSettingsDomainStates(page)
    val screensaverOn = states.screensaverOn()
    // Only the note itself recomposes the page, not every peer heartbeat in the status.
    val syncStatus = SettingsSync.status.collectAsState()
    val syncNote by remember { derivedStateOf { settingsSyncPageNote(syncStatus.value) } }
    val context = LocalContext.current
    // Recomputed with the snapshots (a few gate checks); the layout only changes when the set does.
    val emptyRows = settingsEmptyRowItems(page, states, context, advanced)
    val layout = remember(page, advanced, screensaverOn, syncNote, emptyRows) {
        settingsPageLayout(page, advanced, screensaverOn, syncNote, emptyRows)
    }
    val highlight = rememberBriefHighlight(control, onHighlightShown)
    val highlightIndex = highlight?.let { layout.controlIndex[it] }
    val listState = rememberApprovedSettingsLazyScrollState(page)
    val scheduler = remember(page) { SettingsCompositionScheduler() }
    // Decided once per page from where it opens, so later layout changes never move an entry
    // between the direct and the deferred path (which would recreate its state).
    // Read without observation: the page must not recompose on every scroll.
    val immediate = remember(page) {
        val start = highlightIndex ?: Snapshot.withoutReadObservation { listState.firstVisibleItemIndex }
        SettingsRevealPlan.immediateKeys(layout.entries, start)
    }
    LaunchedEffect(scheduler) { scheduler.run() }
    LaunchedEffect(control, highlightIndex) { if (highlightIndex != null) listState.scrollToItem(highlightIndex) }
    CompositionLocalProvider(LocalApprovedRequestedControl provides highlight, LocalSettingsComposition provides scheduler,
        LocalSettingsSyncLabels provides (syncNote != null)) {
        // Pane-work attribution is a no-op unless the benchmark build opts in.
        LazyColumn(Modifier.fillMaxSize().then(rememberSettingsPaneWorkModifier(page, active = true)), state = listState,
            contentPadding = if (LocalSettingsSinglePane.current)
              PaddingValues(start = SinglePaneSidePadding, end = SinglePaneSidePadding, bottom = PageBottomPadding)
            else PaddingValues(start = PageSidePadding, end = PageSidePadding, bottom = PageBottomPadding)) {
            itemsIndexed(layout.entries, key = { _, entry -> entry.key }, contentType = { _, entry -> entry.kind }) { index, entry ->
                val draw = @Composable {
                    SettingsPageEntryContent(page, entry, states, highlight, index == highlightIndex, onOpenPage)
                }
                if (entry.key in immediate) draw()
                else SettingsDeferredSlot(SettingsRevealPlan.placeholderDp(entry).dp, content = draw)
            }
        }
    }
    composition?.finish()
}

@Composable
private fun SettingsPageEntryContent(
    page: String,
    entry: SettingsPageEntry,
    states: Map<String, SettingsDomainState>,
    highlight: String?,
    highlighted: Boolean,
    onOpenPage: (String) -> Unit,
) {
    when (entry.kind) {
        SettingsEntryKind.LEDE -> SettingsTree.page(page)?.let { SettingsLede(it, entry.text.orEmpty()) }
        SettingsEntryKind.HEADER -> SettingsSectionHeader(entry.text.orEmpty(), entry.advanced)
        SettingsEntryKind.ROWS -> {
            val rows = entry.item as SettingsDomainRows
            states[rows.domain]?.let { SettingsDomainRowsCard(rows, it, highlight, entry.first, entry.last) }
        }
        SettingsEntryKind.BLOCK -> {
            val block = (entry.item as SettingsBlockItem).block
            // Jarvis pointing at a control this block draws (Night Shift, Touch sounds…) rings the block.
            Box(Modifier.jarvisPulse(SettingsJarvisPulse.blockPulses(block, LocalJarvisPulse.current))) {
                SettingsCardGapsIfDrawn(entry.gapBefore, entry.gapAfter) {
                    SettingsBlockHighlight(highlighted) { SettingsBlockContent(block) }
                }
            }
        }
        SettingsEntryKind.LINK -> {
            val link = entry.item as SettingsLinkItem
            SettingsNavigationRow(link.key, link.title, "", entry.first, entry.last, highlight,
                value = settingsLinkValue(link.target, states), tile = SettingsTree.page(link.target)?.takeIf { link.showTile },
            ) { onOpenPage(link.target) }
        }
        SettingsEntryKind.EXTERNAL -> {
            val context = LocalContext.current
            val external = entry.item as SettingsExternalItem
            SettingsNavigationRow(external.key, external.title, external.subtitle, entry.first, entry.last, highlight) {
                launchSettingsExternal(context, external)
            }
        }
        SettingsEntryKind.FOOTER -> if (entry.key == SETTINGS_SYNC_NOTE_KEY) SettingsPageNote(entry.text.orEmpty())
            else SettingsSectionFooter(entry.text.orEmpty())
        SettingsEntryKind.NOTICE -> SettingsNotice(entry.text.orEmpty())
        SettingsEntryKind.ADVANCED_HINT -> SettingsAdvancedHintRow()
        SettingsEntryKind.GAP -> SettingsSectionGap()
    }
}

private const val CALENDAR_NOT_SET = "Not set"

/** The value a link row shows, from snapshots the page already holds (never a fresh read). */
internal fun settingsLinkValue(target: String, states: Map<String, SettingsDomainState>): String {
    if (target != SettingsPageIds.CALENDAR) return ""
    val snapshot = (states["calendar"] ?: states["screensaver"])?.snapshot as? ScreensaverConfig.Settings ?: return ""
    return when {
        !snapshot.hasCalendarLink -> CALENDAR_NOT_SET
        snapshot.calendarEnabled -> "On"
        else -> "Off"
    }
}
