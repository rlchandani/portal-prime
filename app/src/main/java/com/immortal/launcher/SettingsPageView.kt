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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.immortal.launcher.ui.theme.LocalAdvancedSettings

private val PageSidePadding = 56.dp
private val PageBottomPadding = 80.dp

/**
 * The selected page as inset grouped cards in one lazy list. Only this page is composed; rows
 * compose as they scroll in, and each entry kind is its own content type. [control] scrolls to
 * and briefly highlights a row (search), then [onHighlightShown] clears the request.
 */
@Composable
internal fun SettingsPageView(
    page: String,
    control: String?,
    onOpenPage: (String) -> Unit,
    onHighlightShown: () -> Unit,
) {
    val composition = FramePerformanceCapture.beginSettingsUiWork(SettingsUiWorkStage.DETAIL_COMPOSITION)
    val context = LocalContext.current
    val advanced = LocalAdvancedSettings.current
    val states = rememberSettingsDomainStates(page)
    val screensaverOn = states.screensaverOn()
    val layout = remember(page, advanced, screensaverOn) { settingsPageLayout(page, advanced, screensaverOn) }
    val def = remember(page) { SettingsTree.page(page) }
    val highlight = rememberBriefHighlight(control, onHighlightShown)
    val highlightIndex = highlight?.let { layout.controlIndex[it] }
    val listState = rememberApprovedSettingsLazyScrollState(page)
    LaunchedEffect(control, highlightIndex) { if (highlightIndex != null) listState.scrollToItem(highlightIndex) }
    CompositionLocalProvider(LocalApprovedRequestedControl provides highlight) {
        // Pane-work attribution is a no-op unless the benchmark build opts in.
        LazyColumn(Modifier.fillMaxSize().then(rememberSettingsPaneWorkModifier(page, active = true)), state = listState,
            contentPadding = PaddingValues(start = PageSidePadding, end = PageSidePadding, bottom = PageBottomPadding)) {
            itemsIndexed(layout.entries, key = { _, entry -> entry.key }, contentType = { _, entry -> entry.kind }) { index, entry ->
                when (entry.kind) {
                    SettingsEntryKind.LEDE -> def?.let { SettingsLede(it, entry.text.orEmpty()) }
                    SettingsEntryKind.HEADER -> SettingsSectionHeader(entry.text.orEmpty(), entry.advanced)
                    SettingsEntryKind.ROWS -> {
                        val rows = entry.item as SettingsDomainRows
                        states[rows.domain]?.let { SettingsDomainRowsCard(rows, it, highlight, entry.first, entry.last) }
                    }
                    SettingsEntryKind.BLOCK -> SettingsBlockHighlight(index == highlightIndex) {
                        SettingsBlockContent((entry.item as SettingsBlockItem).block)
                    }
                    SettingsEntryKind.LINK -> {
                        val link = entry.item as SettingsLinkItem
                        SettingsNavigationRow(link.key, link.title, "", entry.first, entry.last, highlight) {
                            onOpenPage(link.target)
                        }
                    }
                    SettingsEntryKind.EXTERNAL -> {
                        val external = entry.item as SettingsExternalItem
                        SettingsNavigationRow(external.key, external.title, external.subtitle, entry.first,
                            entry.last, highlight) { launchSettingsExternal(context, external) }
                    }
                    SettingsEntryKind.FOOTER -> SettingsSectionFooter(entry.text.orEmpty())
                    SettingsEntryKind.NOTICE -> SettingsNotice(entry.text.orEmpty())
                    SettingsEntryKind.ADVANCED_HINT -> SettingsAdvancedHintRow()
                    SettingsEntryKind.GAP -> SettingsSectionGap()
                }
            }
        }
    }
    composition?.finish()
}
