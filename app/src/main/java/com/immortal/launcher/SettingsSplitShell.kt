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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.settings.SettingsDomains
import com.immortal.launcher.ui.theme.LocalAdvancedSettings
import org.json.JSONObject

/** Asks the shell to explain and turn on Advanced settings (hint rows, gated pages). */
internal val LocalApprovedEnableAdvanced = staticCompositionLocalOf<() -> Unit> { {} }

/** The theme observes the existing preference store; no polling or separate UI store. */
@Composable
internal fun rememberApprovedAdvanced(): Boolean = LocalAdvancedSettings.current

/** The left column is about a third of the screen: 600 dp at 1920, 420 dp at 1280. */
private const val SIDEBAR_FRACTION = .31f
private val SidebarMin = 420.dp
private val SidebarMax = 600.dp
private val PageMaxWidth = 1120.dp
private val NavBarHeight = 104.dp
private const val BACK_FRACTION = .4f
private const val TITLE_FRACTION = .5f

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
    detail: @Composable () -> Unit,
) {
    val composition = FramePerformanceCapture.beginSettingsUiWork(SettingsUiWorkStage.SHELL_COMPOSITION)
    KeepInteractiveScreenAwake()
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val advanced = LocalAdvancedSettings.current
    var query by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<ApprovedSettingsSearchResult?>(null) }
    var consent by remember { mutableStateOf(false) }
    var applyError by remember { mutableStateOf<String?>(null) }
    val index = remember { approvedSettingsSearchIndex() }
    val results = remember(index, query) { approvedSettingsSearch(index, query) }
    val queryToken = if (BuildConfig.PORTAL_SETTINGS_UI_DIAGNOSTICS) remember(query) { Any() } else null
    if (BuildConfig.PORTAL_SETTINGS_UI_DIAGNOSTICS) {
        val host = context as? ImmortalSettingsActivity
        SideEffect {
            host?.settingsUiProbe?.publish(SettingsUiProbeRoute(selectedRoot, diagnosticsScope, diagnosticsControl),
                queryToken!!, query.length, results.map { SettingsUiProbeRoute(SettingsTree.rootOf(it.page), it.page, it.control) })
        }
    }
    val sidebarWidth = (LocalConfiguration.current.screenWidthDp * SIDEBAR_FRACTION).dp.coerceIn(SidebarMin, SidebarMax)

    fun applyAdvanced(): Boolean {
        val applied = SettingsDomains.immortal.apply(context, JSONObject().put("advancedSettings", true))
        if ("advancedSettings" !in applied) applyError = "Advanced settings could not be changed. Your settings are unchanged."
        return "advancedSettings" in applied
    }

    fun pick(result: ApprovedSettingsSearchResult) {
        focus.clearFocus()
        query = ""
        if (result.advanced && !advanced) { pending = result; consent = true } else onSearchResult(result)
    }

    CompositionLocalProvider(LocalApprovedEnableAdvanced provides { consent = true }) {
        Row(Modifier.fillMaxSize().background(IosBackground)) {
            Column(Modifier.width(sidebarWidth).fillMaxHeight().background(IosSidebar)
                .settingsUiWorkMeasure(SettingsUiWorkStage.SIDEBAR_MEASURE)) {
                Column(Modifier.padding(start = 28.dp, end = 28.dp, top = 22.dp, bottom = 10.dp)) {
                    ApprovedSettingsBackButton("Home", onClick = onHome)
                    Text("Settings", color = IosLabel, fontSize = 52.sp, lineHeight = 60.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 16.dp))
                    SettingsSearchField(query) { query = it }
                }
                val listModifier = Modifier.weight(1f).fillMaxWidth()
                if (query.isBlank()) {
                    LazyColumn(listModifier.focusGroup(),
                        contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 8.dp, bottom = 40.dp)) {
                        settingsSidebarGroups(selectedRoot) { page -> query = ""; onSelectPage(page) }
                    }
                } else {
                    SettingsSearchResults(query, results, queryToken, ::pick,
                        listModifier.padding(horizontal = 28.dp))
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(IosDivider))
            Column(Modifier.weight(1f).fillMaxHeight().settingsUiWorkMeasure(SettingsUiWorkStage.DETAIL_MEASURE)
                .then(if (BuildConfig.PORTAL_SETTINGS_UI_DIAGNOSTICS)
                    Modifier.settingsUiDrawn(diagnosticsScope, diagnosticsControl) else Modifier)) {
                SettingsNavBar(heading)
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = PageMaxWidth).fillMaxSize().focusGroup()) { detail() }
                }
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

/** Back button with the parent's name (≤ 40%), and the page title centred (≤ 50%), like iPadOS. */
@Composable
private fun SettingsNavBar(heading: SettingsDetailHeading) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(NavBarHeight).padding(horizontal = 28.dp),
        contentAlignment = Alignment.CenterStart) {
        if (heading.onBack != null) {
            ApprovedSettingsBackButton(heading.backTitle ?: "Back", Modifier.widthIn(max = maxWidth * BACK_FRACTION),
                heading.onBack)
        }
        Text(heading.title, color = IosLabel, fontSize = 29.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).widthIn(max = maxWidth * TITLE_FRACTION))
    }
}
