/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.graphics.Rect
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import com.immortal.launcher.settings.SettingsDomains
import com.immortal.launcher.settings.SettingsPresentation
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val LocalApprovedEnableAdvanced = staticCompositionLocalOf<() -> Unit> { {} }

/** The theme observes the existing preference store; no polling or separate UI store. */
@Composable
internal fun rememberApprovedAdvanced(): Boolean = com.immortal.launcher.ui.theme.LocalAdvancedSettings.current

internal data class ApprovedSettingsLazyDetail(
    val state: LazyListState,
    val accessibilityLabel: String? = null,
    val body: LazyListScope.() -> Unit,
)

private data class ApprovedSettingsDetail(
    val routeKey: String,
    val title: String,
    val subtitle: String,
    val onBack: (() -> Unit)?,
    val gated: Boolean,
    val requested: String?,
    val lazy: ApprovedSettingsLazyDetail?,
    val content: @Composable () -> Unit,
    val retainInactive: Boolean,
)

/** The same header/sidebar/detail pattern for the overview and native sub-settings. */
@Composable
internal fun ApprovedSettingsShell(
    category: String,
    title: String,
    subtitle: String = "",
    onBack: (() -> Unit)? = null,
    page: String? = null,
    content: @Composable () -> Unit,
) = ApprovedSettingsShell(category, title, subtitle, onBack, page, lazyDetail = null, content = content)

@Composable
internal fun ApprovedSettingsShell(
    category: String,
    title: String,
    subtitle: String = "",
    onBack: (() -> Unit)? = null,
    page: String? = null,
    lazyDetail: ApprovedSettingsLazyDetail?,
    content: @Composable () -> Unit,
) {
    val composition = FramePerformanceCapture.beginSettingsUiWork(SettingsUiWorkStage.SHELL_COMPOSITION)
    KeepInteractiveScreenAwake()
    val context = LocalContext.current
    val navigate = rememberApprovedSettingsNavigator()
    val localNavigation = LocalApprovedSettingsNavigate.current != null
    val focus = LocalFocusManager.current
    val appliedAdvanced = com.immortal.launcher.ui.theme.LocalAdvancedSettings.current
    var query by remember { mutableStateOf("") }
    val searchView = LocalView.current
    val searchViewLocation = remember { IntArray(2) }
    var searchAnchorBottom by remember { mutableStateOf(0) }
    var searchBoundsInRoot by remember { mutableStateOf(ComposeRect.Zero) }
    val searchPopupHeight = rememberApprovedSearchPopupHeight(searchAnchorBottom, query.isNotBlank())
    var pendingSearch by remember { mutableStateOf<ApprovedSettingsSearchResult?>(null) }
    var consent by remember { mutableStateOf(false) }
    var applyError by remember { mutableStateOf<String?>(null) }
    val searchIndex = remember { approvedSettingsSearchIndex() }
    val results = remember(searchIndex, query) { approvedSettingsSearch(searchIndex, query) }
    val palette = LocalApprovedPalette.current
    val displayedCategory = if (category == "advanced" && !appliedAdvanced) "device" else category
    val diagnosticQueryToken = if (BuildConfig.PORTAL_SETTINGS_UI_DIAGNOSTICS) remember(query) { Any() } else null
    if (BuildConfig.PORTAL_SETTINGS_UI_DIAGNOSTICS) {
        val host = context as? ImmortalSettingsActivity
        val control = LocalApprovedRequestedControl.current
        SideEffect {
            host?.settingsUiProbe?.publish(
                SettingsUiProbeRoute(displayedCategory, page, control), diagnosticQueryToken!!, query.length,
                results.map { result ->
                    val destination = approvedSettingsRoute(result.page, result.id.ifBlank { null })
                    SettingsUiProbeRoute(destination.category, destination.page, destination.control)
                })
        }
    }
    val sidebarCategories = remember(appliedAdvanced) {
        ApprovedSettingsCategories.filter { it.id != "advanced" || appliedAdvanced }
    }

    fun applyAdvanced(on: Boolean): Boolean {
        val applied = SettingsDomains.immortal.apply(context, JSONObject().put("advancedSettings", on))
        if ("advancedSettings" !in applied) {
            applyError = "Advanced settings could not be changed. Your settings are unchanged."
            return false
        }
        return true
    }

    fun openSearch(result: ApprovedSettingsSearchResult) {
        focus.clearFocus()
        query = ""
        if (result.advanced && !appliedAdvanced) {
            pendingSearch = result
            consent = true
        } else {
            navigate(approvedSettingsRoute(result.page, result.id.ifBlank { null }))
        }
    }

    CompositionLocalProvider(
        LocalApprovedEnableAdvanced provides { consent = true },
    ) {
        Column(Modifier.settingsUiWorkMeasure(SettingsUiWorkStage.SHELL_MEASURE).fillMaxSize()
            .background(IosBackground).pointerInput(query.isNotBlank()) {
                if (query.isBlank()) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.pressed && !it.previousPressed &&
                                !searchBoundsInRoot.contains(it.position) }) query = ""
                    }
                }
            }) {
            Row(
                Modifier.fillMaxWidth().height(Sizes.headerHeight - 1.dp).padding(horizontal = 56.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ApprovedSettingsBackButton("Home", modifier = if (BuildConfig.PORTAL_SETTINGS_UI_DIAGNOSTICS)
                    Modifier.settingsUiTarget("home", "home") else Modifier) {
                    context.startActivity(Intent(context, HomeActivity::class.java)
                        .putExtra("approved_home_tab", "home")
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    (context as? Activity)?.finish()
                }
                ApprovedSettingsTextButton(if (palette.dark) "Light appearance" else "Dark appearance") {
                    val value = if (palette.dark) "light" else "dark"
                    if ("settingsTheme" !in SettingsDomains.immortal.apply(context,
                            JSONObject().put("settingsTheme", value))) {
                        applyError = "Appearance could not be changed."
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(IosDivider))
            Row(Modifier.fillMaxWidth().weight(1f)) {
                Row(Modifier.width(Sizes.sidebarWidth).fillMaxHeight().background(IosSidebar)) {
                    Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 24.dp, vertical = 34.dp)) {
                        Text("Settings", color = IosLabel, fontSize = 40.sp, lineHeight = 52.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(start = 12.dp, bottom = 28.dp))
                        Box(Modifier.onGloballyPositioned { coordinates ->
                            searchBoundsInRoot = coordinates.boundsInRoot()
                            searchView.getLocationOnScreen(searchViewLocation)
                            searchAnchorBottom = searchViewLocation[1] +
                                (coordinates.positionInRoot().y + coordinates.size.height).toInt()
                        }) {
                            ApprovedSettingsSearchField(query) { query = it }
                            if (query.isNotBlank()) {
                                val y = with(LocalDensity.current) { 76.dp.roundToPx() }
                                Popup(alignment = Alignment.TopStart, offset = IntOffset(0, y),
                                    onDismissRequest = { query = "" },
                                    properties = PopupProperties(focusable = false, dismissOnClickOutside = false)) {
                                    Surface(
                                        color = IosCard, shape = RoundedCornerShape(18.dp),
                                        border = BorderStroke(1.dp, IosDivider), shadowElevation = 16.dp,
                                        modifier = Modifier.width(720.dp),
                                    ) {
                                        LazyColumn(Modifier.heightIn(max = searchPopupHeight)) {
                                            if (results.isEmpty()) {
                                                item(key = "empty") {
                                                    Text("No settings found.", color = IosSecondary, fontSize = 23.sp,
                                                        modifier = Modifier.padding(24.dp))
                                                }
                                            }
                                            items(results, key = { it.id.ifBlank { "page:${it.page}" } }) { result ->
                                                Row(
                                                    Modifier.fillMaxWidth()
                                                        .then(if (BuildConfig.PORTAL_SETTINGS_UI_DIAGNOSTICS)
                                                            Modifier.settingsUiTarget("result", results.indexOf(result).toString(),
                                                                queryToken = diagnosticQueryToken) else Modifier)
                                                        .tvFocusableRow(focusFill = IosFocusTint) { openSearch(result) }
                                                        .padding(24.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Column(Modifier.weight(1f)) {
                                                        Text(result.title, color = IosLabel, fontSize = 24.sp)
                                                        Text(approvedSettingsSearchBreadcrumb(result) +
                                                            if (result.automatic) " · Automatic" else "",
                                                            color = IosSecondary, fontSize = 19.sp,
                                                            modifier = Modifier.padding(top = 6.dp))
                                                    }
                                                    if (result.advanced) {
                                                        Text("Advanced", color = IosBlue, fontSize = 20.sp,
                                                            modifier = Modifier.background(IosFocusTint,
                                                                RoundedCornerShape(10.dp)).padding(10.dp))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Column(
                            Modifier.settingsUiWorkMeasure(SettingsUiWorkStage.SIDEBAR_MEASURE)
                                .weight(1f).fillMaxWidth().padding(top = 28.dp).focusGroup()
                                .verticalScroll(rememberScrollState())
                                .semantics { collectionInfo = CollectionInfo(sidebarCategories.size, 1) },
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            sidebarCategories.forEachIndexed { index, item ->
                                key(item.id) {
                                    val itemComposition = FramePerformanceCapture.beginSettingsUiWork(
                                        SettingsUiWorkStage.SIDEBAR_ITEM_COMPOSITION)
                                    val selected = displayedCategory == item.id
                                    Row(
                                        Modifier.fillMaxWidth().height(76.dp)
                                            .then(if (BuildConfig.PORTAL_SETTINGS_UI_DIAGNOSTICS)
                                                Modifier.settingsUiTarget("category", item.id) else Modifier)
                                            .semantics { collectionItemInfo = CollectionItemInfo(index, 1, 0, 1) }
                                            .background(if (selected) IosFocusTint else Color.Transparent,
                                                RoundedCornerShape(14.dp))
                                            .tvFocusable(RoundedCornerShape(14.dp), focusScale = 1f,
                                                ringColor = IosBlue) {
                                                query = ""
                                                navigate(ApprovedSettingsRoute(category = item.id))
                                            }
                                            .padding(horizontal = 12.dp, vertical = 15.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                                    ) {
                                        IconBadge(Color(0xFF527AB0), "", imageVector = approvedSettingsSidebarVector(item.icon))
                                        Text(item.title, color = if (selected) IosBlue else IosLabel,
                                            fontSize = 22.sp, lineHeight = 30.sp,
                                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
                                    }
                                    itemComposition?.finish()
                                }
                            }
                        }
                        Column(Modifier.padding(horizontal = 12.dp)) {
                            Spacer(Modifier.height(22.dp))
                            Box(Modifier.fillMaxWidth().height(1.dp).background(IosDivider))
                            Row(
                                Modifier.fillMaxWidth().height(90.dp)
                                    .tvFocusableRow(focusFill = IosFocusTint) { applyAdvanced(!appliedAdvanced) }
                                    .semantics {
                                        role = Role.Switch
                                        toggleableState = ToggleableState(appliedAdvanced)
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Advanced settings", color = IosLabel, fontSize = 21.sp, lineHeight = 28.sp,
                                    modifier = Modifier.weight(1f))
                                ApprovedSettingsSwitch(appliedAdvanced)
                            }
                            ApprovedSettingsDeviceIdentity(FleetConfig.name(context))
                        }
                    }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(IosDivider))
                }
                val detail = ApprovedSettingsDetail(
                    routeKey = "$displayedCategory/${page ?: "overview"}",
                    title = title, subtitle = subtitle, onBack = onBack,
                    gated = page != null && SettingsPresentation.isAdvancedPage(page) && !appliedAdvanced,
                    requested = LocalApprovedRequestedControl.current,
                    lazy = lazyDetail, content = content,
                    retainInactive = page == null || approvedSettingsPage(page)?.standaloneActivity == null,
                )
                if (localNavigation) {
                    ApprovedSettingsDetailPanes(detail, Modifier.weight(1f).fillMaxHeight()
                        .settingsUiWorkMeasure(SettingsUiWorkStage.DETAIL_MEASURE)) { consent = true }
                } else {
                    ApprovedSettingsDetailBody(detail, Modifier.weight(1f).fillMaxHeight()
                        .settingsUiWorkMeasure(SettingsUiWorkStage.DETAIL_MEASURE)) { consent = true }
                }
            }
        }
    }
    if (consent) {
        ApprovedSettingsAdvancedConsent(
            onEnable = {
                if (applyAdvanced(true)) {
                    consent = false
                    pendingSearch?.let { navigate(approvedSettingsRoute(it.page, it.id.ifBlank { null })) }
                    pendingSearch = null
                }
            },
            onCancel = { consent = false; pendingSearch = null },
        )
    }
    applyError?.let { error ->
        ApprovedSettingsDialog("Settings", onDismiss = { applyError = null }, help = error) {
            ApprovedSettingsTextButton("Done") { applyError = null }
        }
    }
    composition?.finish()
}

/** Version publication recomposes only these labels, not the category sidebar. */
@Composable
private fun ApprovedSettingsDeviceIdentity(deviceName: String) {
    val context = LocalContext.current
    val versionName by produceState<String?>(null, context.applicationContext) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull()
        }
        FramePerformanceCapture.markSettingsUiWork(SettingsUiWorkStage.VERSION_PUBLISHED)
    }
    Text(deviceName, color = IosLabel, fontSize = 22.sp,
        fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp))
    Text("${android.os.Build.MODEL} · ${Brand.NAME}${versionName?.let { " $it" }.orEmpty()}",
        color = IosSecondary, fontSize = 18.sp,
        modifier = Modifier.padding(top = 5.dp))
}

private class SettingsPane(initial: ApprovedSettingsDetail) {
    val id = "${initial.routeKey}/${initial.requested.orEmpty()}"
    // Updated after a committed composition; inactive panes retain their own route and draft.
    var detail = initial
}

/** Replace the detail immediately while retaining at most four visited scalar panes. */
@Composable
private fun ApprovedSettingsDetailPanes(
    detail: ApprovedSettingsDetail,
    modifier: Modifier,
    onAdvanced: () -> Unit,
) {
    val selected = "${detail.routeKey}/${detail.requested.orEmpty()}"
    var panes by remember { mutableStateOf(listOf(SettingsPane(detail))) }
    val focus = LocalFocusManager.current
    // Admit the incoming pane in this composition, rather than waiting for an effect to add it.
    val target = remember(selected) { panes.firstOrNull { it.id == selected } ?: SettingsPane(detail) }
    val displayed = remember(panes, target) {
        (panes.filter { it !== target && it.detail.retainInactive } + target).takeLast(4)
    }
    SideEffect {
        target.detail = detail
        panes = displayed
    }
    LaunchedEffect(selected) {
        FramePerformanceCapture.markSettingsUiWork(SettingsUiWorkStage.PANE_EFFECT_START)
        focus.clearFocus(force = true)
        FramePerformanceCapture.markSettingsUiWork(SettingsUiWorkStage.PANE_EFFECT_END)
    }
    Box(modifier.clipToBounds()) {
        displayed.forEach { pane ->
            val active = pane === target
            // Rich editors dispose on departure; retained scalar panes stop input and lifecycle work.
            key(pane.id) {
                val work = rememberSettingsPaneWorkModifier(pane.detail.routeKey.substringAfter('/'), active)
                Box(Modifier.fillMaxSize().then(work).zIndex(if (active) 1f else 0f)
                    .graphicsLayer { alpha = if (active) 1f else 0f }
                    .focusProperties { canFocus = active }
                    .then(if (active) Modifier else Modifier.clearAndSetSemantics { })
                    .pointerInput(active) {
                        if (!active) awaitPointerEventScope {
                            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }) {
                    CompositionLocalProvider(LocalApprovedLauncherPageActive provides active) {
                        ApprovedLauncherPageLifecycle(active) {
                            ApprovedSettingsDetailBody(if (active) detail else pane.detail,
                                Modifier.fillMaxSize(), onAdvanced)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ApprovedSettingsDetailBody(
    detail: ApprovedSettingsDetail,
    modifier: Modifier,
    onAdvanced: () -> Unit,
) {
    val composition = FramePerformanceCapture.beginSettingsUiWork(SettingsUiWorkStage.DETAIL_COMPOSITION)
    val observedModifier = if (BuildConfig.PORTAL_SETTINGS_UI_DIAGNOSTICS)
        modifier.settingsUiDrawn(detail.routeKey, detail.requested) else modifier
    // Outgoing transition content retains its own request; the next route never inherits it.
    CompositionLocalProvider(LocalApprovedRequestedControl provides detail.requested) {
        // History stays available while either kind of detail content scrolls.
        Column(observedModifier.padding(start = 48.dp, end = 48.dp, top = 40.dp).focusGroup()) {
            ApprovedSettingsDetailHeading(detail.title, detail.subtitle, detail.onBack)
            if (detail.lazy == null) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberApprovedSettingsScrollState(
                        "${detail.routeKey}/${detail.requested.orEmpty()}"))
                        .padding(bottom = 80.dp),
                ) {
                    if (detail.gated) ApprovedSettingsAdvancedGate(onAdvanced) else detail.content()
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().focusGroup()
                        .then(detail.lazy.accessibilityLabel?.let { label ->
                            Modifier.semantics { contentDescription = label }
                        } ?: Modifier),
                    state = detail.lazy.state,
                    contentPadding = PaddingValues(bottom = 80.dp),
                ) {
                    if (detail.gated) {
                        item(key = "settings:advanced-gate") { ApprovedSettingsAdvancedGate(onAdvanced) }
                    } else {
                        detail.lazy.body(this)
                    }
                }
            }
        }
    }
    composition?.finish()
}

/** Observe keyboard/window layout only while results are visible; never poll the window. */
@Composable
private fun rememberApprovedSearchPopupHeight(anchorBottomOnScreen: Int, active: Boolean): Dp {
    val view = LocalView.current
    val density = LocalDensity.current
    var height by remember { mutableStateOf(800.dp) }
    DisposableEffect(view, density, anchorBottomOnScreen, active) {
        if (!active) return@DisposableEffect onDispose { }
        val visibleFrame = Rect()
        val bottomGap = with(density) { 20.dp.roundToPx() }
        val observer = view.viewTreeObserver
        val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            view.getWindowVisibleDisplayFrame(visibleFrame)
            height = with(density) {
                (visibleFrame.bottom - anchorBottomOnScreen - bottomGap)
                    .coerceAtLeast(1).toDp().coerceAtMost(800.dp)
            }
        }
        observer.addOnGlobalLayoutListener(listener)
        listener.onGlobalLayout()
        onDispose {
            if (observer.isAlive) observer.removeOnGlobalLayoutListener(listener)
        }
    }
    return height
}

@Composable
private fun ApprovedSettingsSearchField(query: String, onChange: (String) -> Unit) {
    val composition = FramePerformanceCapture.beginSettingsUiWork(SettingsUiWorkStage.SEARCH_COMPOSITION)
    val color = IosSecondary
    Surface(modifier = Modifier.settingsUiWorkMeasure(SettingsUiWorkStage.SEARCH_MEASURE)
        .then(if (BuildConfig.PORTAL_SETTINGS_UI_DIAGNOSTICS)
            Modifier.settingsUiTarget("search", "input") else Modifier),
        color = IosCard, shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, IosDivider)) {
        Row(Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ApprovedIcon("search", tint = color, modifier = Modifier.size(26.dp))
            BasicTextField(
                value = query, onValueChange = onChange, singleLine = true,
                textStyle = TextStyle(color = IosLabel, fontSize = 22.sp),
                cursorBrush = SolidColor(IosBlue), modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("Search settings", color = IosSecondary, fontSize = 22.sp)
                    inner()
                },
            )
        }
    }
    composition?.finish()
}

@Composable
private fun ApprovedSettingsDetailHeading(title: String, subtitle: String, onBack: (() -> Unit)?) {
    val controls = LocalApprovedSettingsHistoryControls.current
        ?: ApprovedSettingsHistoryControls(onBack, null)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Surface(color = LocalApprovedPalette.current.surface2, shape = RoundedCornerShape(50.dp),
                border = BorderStroke(1.dp, IosDivider)) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    ApprovedSettingsHistoryButton(false, controls.onBack, controls.backTitle)
                    Box(Modifier.width(1.dp).height(38.dp).background(IosDivider))
                    ApprovedSettingsHistoryButton(true, controls.onForward, controls.forwardTitle)
                }
            }
            Text(title, color = IosLabel, fontSize = 38.sp, lineHeight = 48.sp,
                fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        }
        if (subtitle.isNotEmpty()) {
            Text(subtitle, color = IosSecondary, fontSize = 23.sp, lineHeight = 34.sp,
                modifier = Modifier.padding(top = 12.dp))
        }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun ApprovedSettingsHistoryButton(forward: Boolean, onClick: (() -> Unit)?, destination: String?) {
    val label = if (forward) "Settings Forward" else "Settings Back"
    Box(Modifier.width(66.dp).height(62.dp)
        .tvFocusable(RoundedCornerShape(32.dp), enabled = onClick != null, focusScale = 1f,
            ringColor = IosBlue, onClick = { onClick?.invoke() })
        .semantics {
            contentDescription = label
            role = Role.Button
            if (onClick == null) disabled()
            if (destination != null) stateDescription = "${if (forward) "Forward" else "Back"} to $destination"
        }, contentAlignment = Alignment.Center) {
        ApprovedIcon("chevL", tint = if (onClick == null) IosLabel.copy(alpha = .32f) else IosLabel,
            modifier = Modifier.width(28.dp).height(34.dp).rotate(if (forward) 180f else 0f))
    }
}
