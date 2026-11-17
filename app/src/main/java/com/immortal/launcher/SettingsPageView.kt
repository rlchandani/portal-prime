/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.immortal.launcher.ui.theme.LocalAdvancedSettings

private val PageSidePadding = SettingsSplitMetrics.PAGE_SIDE_PADDING_DP.dp
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
    val layout = remember(page, advanced, screensaverOn) { settingsPageLayout(page, advanced, screensaverOn) }
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
    CompositionLocalProvider(LocalApprovedRequestedControl provides highlight, LocalSettingsComposition provides scheduler) {
        // Pane-work attribution is a no-op unless the benchmark build opts in.
        LazyColumn(Modifier.fillMaxSize().then(rememberSettingsPaneWorkModifier(page, active = true)), state = listState,
            contentPadding = PaddingValues(start = PageSidePadding, end = PageSidePadding, bottom = PageBottomPadding)) {
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
        SettingsEntryKind.BLOCK -> SettingsBlockHighlight(highlighted) {
            SettingsBlockContent((entry.item as SettingsBlockItem).block)
        }
        SettingsEntryKind.LINK -> {
            val link = entry.item as SettingsLinkItem
            SettingsNavigationRow(link.key, link.title, "", entry.first, entry.last, highlight) { onOpenPage(link.target) }
        }
        SettingsEntryKind.EXTERNAL -> {
            val context = LocalContext.current
            val external = entry.item as SettingsExternalItem
            SettingsNavigationRow(external.key, external.title, external.subtitle, entry.first, entry.last, highlight) {
                launchSettingsExternal(context, external)
            }
        }
        SettingsEntryKind.FOOTER -> SettingsSectionFooter(entry.text.orEmpty())
        SettingsEntryKind.NOTICE -> SettingsNotice(entry.text.orEmpty())
        SettingsEntryKind.ADVANCED_HINT -> SettingsAdvancedHintRow()
        SettingsEntryKind.GAP -> SettingsSectionGap()
    }
}
