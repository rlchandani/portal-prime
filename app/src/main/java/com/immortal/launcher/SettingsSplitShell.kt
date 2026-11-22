/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.settings.SettingsDomains
import com.immortal.launcher.ui.theme.LocalAdvancedSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Asks the shell to explain and turn on Advanced settings (hint rows, gated pages). */
internal val LocalApprovedEnableAdvanced = staticCompositionLocalOf<() -> Unit> { {} }

/** The theme observes the existing preference store; no polling or separate UI store. */
@Composable
internal fun rememberApprovedAdvanced(): Boolean = LocalAdvancedSettings.current

private val PageMaxWidth = SettingsSplitMetrics.PAGE_MAX_WIDTH_DP.dp
private val NavBarHeight = 104.dp
private const val BACK_FRACTION = .4f
private const val TITLE_FRACTION = .5f
private const val SINGLE_PANE_BACK_FRACTION = .3f

/**
 * True when Settings runs single-pane (master list → detail page) because the window is too narrow
 * for the split view (the Portal Mini upright). Pages read it for their side padding.
 */
internal val LocalSettingsSinglePane = staticCompositionLocalOf { false }

/** The split view needs [LayoutClass.NARROW_WIDTH_DP]; below it a compact window goes single-pane. */
@Composable
internal fun settingsSinglePane(): Boolean =
    currentLayoutClass().compact && LocalConfiguration.current.screenWidthDp < LayoutClass.NARROW_WIDTH_DP

/** Which pane a single-pane Settings shows. The split view ignores it. */
internal class SettingsPane(val showList: Boolean, val onShowList: () -> Unit)

/** What the right column shows at the top: the page title and, below the root, a back button. */
internal data class SettingsDetailHeading(val title: String, val backTitle: String?, val onBack: (() -> Unit)?)

/**
 * iPadOS-style split view: a fixed left column (Home, title, search, profile card, grouped pages)
 * and the selected page on the right. Navigation is reported through plain callbacks; the host
 * owns the back stack, so a sidebar tap is one state write and one recomposition.
 */
@Composable
internal fun SettingsSplitShell(
    selectedRoot: String,
    heading: SettingsDetailHeading,
    onSelectPage: (String) -> Unit,
    onSearchResult: (ApprovedSettingsSearchResult) -> Unit,
    onHome: () -> Unit,
    diagnosticsScope: String,
    diagnosticsControl: String?,
    pane: SettingsPane? = null,
    detail: @Composable () -> Unit,
) {
    val composition = FramePerformanceCapture.beginSettingsUiWork(SettingsUiWorkStage.SHELL_COMPOSITION)
    KeepInteractiveScreenAwake()
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val advanced = LocalAdvancedSettings.current
    // A reopen of the retained Settings starts with no query and no dialog, as a fresh Settings does.
    val reopen = LocalSettingsReopenGeneration.current
    var query by remember(reopen) { mutableStateOf("") }
    var pending by remember(reopen) { mutableStateOf<ApprovedSettingsSearchResult?>(null) }
    var consent by remember(reopen) { mutableStateOf(false) }
    var applyError by remember(reopen) { mutableStateOf<String?>(null) }
    // Built off the main thread once Settings is up; nothing reads it until a query is typed.
    val index by produceState<List<ApprovedSettingsSearchResult>?>(null) {
        value = withContext(Dispatchers.Default) { approvedSettingsSearchIndex() }
    }
    val results = remember(index, query) { index?.let { approvedSettingsSearch(it, query) }.orEmpty() }
    // The master column being built while Settings opens; the page waits for it (see SettingsOpenStage).
    val openingMaster = remember { mutableStateOf<SettingsMasterReveal?>(null) }
    val stage = rememberSettingsOpenStage(holdDetail = { openingMaster.value?.shown == false })
    val queryToken = if (BuildConfig.PORTAL_SETTINGS_UI_DIAGNOSTICS) remember(query) { Any() } else null
    if (BuildConfig.PORTAL_SETTINGS_UI_DIAGNOSTICS) {
        val host = context as? ImmortalSettingsActivity
        SideEffect {
            host?.settingsUiProbe?.publish(SettingsUiProbeRoute(selectedRoot, diagnosticsScope, diagnosticsControl),
                queryToken!!, query.length, results.map { SettingsUiProbeRoute(SettingsTree.rootOf(it.page), it.page, it.control) })
        }
    }
    val singlePane = settingsSinglePane()
    val sidebarWidth = SettingsSplitMetrics.sidebarWidthDp(LocalConfiguration.current.screenWidthDp).dp

    fun applyAdvanced(): Boolean {
        val applied = SettingsDomains.immortal.apply(context, JSONObject().put("advancedSettings", true))
        if ("advancedSettings" !in applied) applyError = "Advanced settings could not be changed. Your settings are unchanged."
        return "advancedSettings" in applied
    }

    // Hoisted out of the LazyColumn builder (not a composable scope) so its identity survives a page
    // switch; otherwise every sidebar row receives a new click handler and recomposes.
    val selectPage = remember(onSelectPage, reopen) { { page: String -> query = ""; onSelectPage(page) } }
    // Read by the sidebar rows, not the list builder (see settingsSidebarGroups).
    val currentRoot = rememberUpdatedState(selectedRoot)
    val selectedRootProvider = remember { { currentRoot.value } }
    val sidebarFacts = rememberSettingsSidebarFacts()
    val sidebarFactsProvider = remember(sidebarFacts) { { sidebarFacts.value } }

    fun pick(result: ApprovedSettingsSearchResult) {
        focus.clearFocus()
        query = ""
        if (result.advanced && !advanced) { pending = result; consent = true } else onSearchResult(result)
    }

    // The master column: Home, the title, search and the grouped pages (or search results). While
    // Settings opens it is built over a few frames and appears at once (see [SettingsMasterReveal]).
    val master = @Composable { modifier: Modifier ->
        val reveal = rememberSettingsMasterReveal(stage)
        if (!reveal.shown) SideEffect { openingMaster.value = reveal }
        val sidebarList = rememberLazyListState()
        SettingsReopenScrollsToTop(sidebarList)
        SettingsMasterRevealEffect(reveal, sidebarList, remember { settingsSidebarEntryCount() })
        Column(modifier.fillMaxHeight().background(IosSidebar).settingsRevealed(reveal)
            .settingsUiWorkMeasure(SettingsUiWorkStage.SIDEBAR_MEASURE)) {
            Column(Modifier.padding(start = 28.dp, end = 28.dp, top = 22.dp, bottom = 10.dp)) {
                ApprovedSettingsBackButton("Home", onClick = onHome)
                Text("Settings", color = IosLabel, fontSize = 52.sp, lineHeight = 60.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 16.dp))
                if (reveal.searchComposed) SettingsSearchField(query) { query = it }
                else Spacer(Modifier.fillMaxWidth().height(SettingsSearchFieldHeight))
            }
            val listModifier = Modifier.weight(1f).fillMaxWidth()
            if (query.isBlank()) {
                LazyColumn(listModifier.focusGroup(), state = sidebarList,
                    contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 8.dp, bottom = 40.dp)) {
                    settingsSidebarGroups(selectedRootProvider, sidebarFactsProvider, selectPage, reveal.listLimit)
                }
            } else {
                SettingsSearchResults(query, results, queryToken, ::pick,
                    listModifier.padding(horizontal = 28.dp))
            }
        }
    }
    // The detail column: the nav bar and the page, capped and centred.
    val detailColumn = @Composable { modifier: Modifier, navHeading: SettingsDetailHeading ->
        Column(modifier.fillMaxHeight().settingsUiWorkMeasure(SettingsUiWorkStage.DETAIL_MEASURE)
            .then(if (BuildConfig.PORTAL_SETTINGS_UI_DIAGNOSTICS)
                Modifier.settingsUiDrawn(diagnosticsScope, diagnosticsControl) else Modifier)) {
            SettingsNavBar(navHeading)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = PageMaxWidth).fillMaxSize().focusGroup()) { detail() }
            }
        }
    }

    // Entry is staged over three frames (see [SettingsOpenStage]): the first frame is the shell's
    // own backdrops, then the master column, then the page. Each pane keeps its final position.
    CompositionLocalProvider(LocalApprovedEnableAdvanced provides { consent = true },
        LocalSettingsSinglePane provides singlePane,
        LocalTextStyle provides LocalTextStyle.current.copy(lineBreak = SettingsLineBreak)) {
        if (!singlePane) {
            Row(Modifier.fillMaxSize().background(IosBackground)) {
                if (stage.showsMaster) master(Modifier.width(sidebarWidth))
                else Box(Modifier.width(sidebarWidth).fillMaxHeight().background(IosSidebar))
                Box(Modifier.width(1.dp).fillMaxHeight().background(IosDivider))
                if (stage.showsDetail) detailColumn(Modifier.weight(1f), heading)
            }
        } else if (pane != null && pane.showList) {
            // Single-pane master: only the list is composed (the page is not built behind it).
            Box(Modifier.fillMaxSize().background(IosSidebar)) { if (stage.showsMaster) master(Modifier.fillMaxWidth()) }
        } else {
            // Single-pane detail: at a page's root, Back returns to the list ("Settings").
            val navHeading = if (heading.onBack != null || pane == null) heading
                else heading.copy(backTitle = "Settings", onBack = pane.onShowList)
            Box(Modifier.fillMaxSize().background(IosBackground)) {
                if (stage.showsMaster) detailColumn(Modifier.fillMaxWidth(), navHeading)
            }
        }
    }
    if (consent) {
        ApprovedSettingsAdvancedConsent(
            onEnable = {
                if (applyAdvanced()) {
                    consent = false
                    pending?.let(onSearchResult)
                    pending = null
                }
            },
            onCancel = { consent = false; pending = null },
        )
    }
    applyError?.let { error ->
        ApprovedSettingsDialog("Settings", onDismiss = { applyError = null }, help = error) {
            ApprovedSettingsTextButton("Done") { applyError = null }
        }
    }
    composition?.finish()
}

/** Back button label width per character (dp) at 24 sp, and its chevron + padding. */
internal const val BACK_LABEL_DP_PER_CHAR = 14
internal const val BACK_BUTTON_CHROME_DP = 20 * 2 + 26 + 12

/**
 * The parent's name when it fits the back button's [widthDp], else "Back", as iPadOS does: never
 * a cut-off "Widgets & …" on the Mini.
 */
internal fun settingsBackLabel(parent: String?, widthDp: Int): String {
    val label = parent ?: return "Back"
    return if (label.length * BACK_LABEL_DP_PER_CHAR + BACK_BUTTON_CHROME_DP <= widthDp) label else "Back"
}

/**
 * Width (dp) of the single-pane title, centred between equal margins that clear the back button.
 * The margin is the back label's own width (capped at [sideDp]), not the whole cap: once the label
 * has become "Back", "Open when you tap to exit" fits instead of ending in "…".
 */
internal fun settingsSinglePaneTitleMaxWidthDp(widthDp: Int, sideDp: Int, backLabel: String?): Int {
    val back = backLabel?.let { minOf(sideDp, it.length * BACK_LABEL_DP_PER_CHAR + BACK_BUTTON_CHROME_DP) } ?: 0
    return (widthDp - back * 2 - TITLE_BACK_GAP_DP).coerceAtLeast(0)
}

/**
 * Settings text breaks into balanced lines: a wrapped help, footer or title never leaves its last
 * word alone on a line ("…share / link.", "…switch it back / on."). One-line text is unaffected.
 */
internal val SettingsLineBreak = LineBreak.Simple.copy(strategy = LineBreak.Strategy.Balanced)

/** Air between the back button and the centred title (dp). */
internal const val TITLE_BACK_GAP_DP = 16

/** Back button with the parent's name (≤ 40%), and the page title centred (≤ 50%), like iPadOS. */
@Composable
private fun SettingsNavBar(heading: SettingsDetailHeading) {
    if (LocalSettingsSinglePane.current) {
        // Single pane (800 dp): the back button and the centred title get disjoint widths, so a
        // long parent name can never run under the title.
        BoxWithConstraints(Modifier.fillMaxWidth().height(NavBarHeight).padding(horizontal = 20.dp),
            contentAlignment = Alignment.CenterStart) {
            val side = maxWidth * SINGLE_PANE_BACK_FRACTION
            val backLabel = heading.onBack?.let { settingsBackLabel(heading.backTitle, side.value.toInt()) }
            if (heading.onBack != null && backLabel != null) {
                ApprovedSettingsBackButton(backLabel, Modifier.widthIn(max = side), heading.onBack)
            }
            Text(heading.title, color = IosLabel, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center)
                    .widthIn(max = settingsSinglePaneTitleMaxWidthDp(maxWidth.value.toInt(), side.value.toInt(), backLabel).dp))
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth().height(NavBarHeight).padding(horizontal = 28.dp),
        contentAlignment = Alignment.CenterStart) {
        if (heading.onBack != null) {
            ApprovedSettingsBackButton(settingsBackLabel(heading.backTitle, (maxWidth * BACK_FRACTION).value.toInt()),
                Modifier.widthIn(max = maxWidth * BACK_FRACTION), heading.onBack)
        }
        Text(heading.title, color = IosLabel, fontSize = 29.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).widthIn(max = maxWidth * TITLE_FRACTION))
    }
}
