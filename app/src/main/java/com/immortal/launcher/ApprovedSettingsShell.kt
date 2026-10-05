/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal val LocalApprovedEnableAdvanced = staticCompositionLocalOf<() -> Unit> { {} }

/** The theme observes the existing preference store; no polling or separate UI store. */
@Composable
internal fun rememberApprovedAdvanced(): Boolean = com.immortal.launcher.ui.theme.LocalAdvancedSettings.current

internal data class ApprovedSettingsLazyDetail(
    val state: LazyListState,
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
    KeepInteractiveScreenAwake()
    val context = LocalContext.current
    val navigate = rememberApprovedSettingsNavigator()
    val localNavigation = LocalApprovedSettingsNavigate.current != null
    val versionName by produceState<String?>(null, context.applicationContext) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull()
        }
    }
    val focus = LocalFocusManager.current
    val appliedAdvanced = com.immortal.launcher.ui.theme.LocalAdvancedSettings.current
    var query by remember { mutableStateOf("") }
    var pendingSearch by remember { mutableStateOf<ApprovedSettingsSearchResult?>(null) }
    var consent by remember { mutableStateOf(false) }
    var applyError by remember { mutableStateOf<String?>(null) }
    val searchIndex = remember { approvedSettingsSearchIndex() }
    val results = remember(searchIndex, query) { approvedSettingsSearch(searchIndex, query) }
    val palette = LocalApprovedPalette.current
    val displayedCategory = if (category == "advanced" && !appliedAdvanced) "device" else category

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
        Column(Modifier.fillMaxSize().background(IosBackground)) {
            Row(
                Modifier.fillMaxWidth().height(Sizes.headerHeight - 1.dp).padding(horizontal = 56.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ApprovedSettingsBackButton("Home") {
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
                        Box {
                            ApprovedSettingsSearchField(query) { query = it }
                            if (query.isNotBlank()) {
                                val y = with(LocalDensity.current) { 76.dp.roundToPx() }
                                Popup(alignment = Alignment.TopStart, offset = IntOffset(0, y),
                                    onDismissRequest = { query = "" },
                                    properties = PopupProperties(focusable = false)) {
                                    Surface(
                                        color = IosCard, shape = RoundedCornerShape(18.dp),
                                        border = BorderStroke(1.dp, IosDivider), shadowElevation = 16.dp,
                                        modifier = Modifier.width(720.dp),
                                    ) {
                                        LazyColumn(Modifier.height(800.dp.coerceAtMost(
                                            (results.size.coerceAtLeast(1) * 100).dp))) {
                                            if (results.isEmpty()) {
                                                item(key = "empty") {
                                                    Text("No settings found.", color = IosSecondary, fontSize = 23.sp,
                                                        modifier = Modifier.padding(24.dp))
                                                }
                                            }
                                            items(results, key = { it.id.ifBlank { "page:${it.page}" } }) { result ->
                                                Row(
                                                    Modifier.fillMaxWidth()
                                                        .tvFocusableRow(focusFill = IosFocusTint) { openSearch(result) }
                                                        .padding(24.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Column(Modifier.weight(1f)) {
                                                        Text(result.title, color = IosLabel, fontSize = 24.sp)
                                                        Text(approvedSettingsPage(result.page)?.title.orEmpty() +
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
                        LazyColumn(
                            Modifier.weight(1f).fillMaxWidth().padding(top = 28.dp).focusGroup(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(ApprovedSettingsCategories.filter { it.id != "advanced" || appliedAdvanced },
                                key = { it.id }) { item ->
                                    val selected = displayedCategory == item.id
                                    Row(
                                        Modifier.fillMaxWidth().height(76.dp)
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
                                        IconBadge(Color(0xFF527AB0), "", item.icon)
                                        Text(item.title, color = if (selected) IosBlue else IosLabel,
                                            fontSize = 22.sp, lineHeight = 30.sp,
                                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
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
                            Text(FleetConfig.name(context), color = IosLabel, fontSize = 22.sp,
                                fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp))
                            Text("${android.os.Build.MODEL} · Immortal${versionName?.let { " $it" }.orEmpty()}",
                                color = IosSecondary, fontSize = 18.sp,
                                modifier = Modifier.padding(top = 5.dp))
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
                    ApprovedSettingsDetailPanes(detail, Modifier.weight(1f).fillMaxHeight()) { consent = true }
                } else {
                    ApprovedSettingsDetailBody(detail, Modifier.weight(1f).fillMaxHeight()) { consent = true }
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
}

private class SettingsPane(initial: ApprovedSettingsDetail) {
    val id = "${initial.routeKey}/${initial.requested.orEmpty()}"
    var detail by mutableStateOf(initial)
    val opacity = Animatable(1f)
    var entering by mutableStateOf(true)
}

/** Keep at most four visited detail panes; animation changes only their display layers. */
@Composable
private fun ApprovedSettingsDetailPanes(
    detail: ApprovedSettingsDetail,
    modifier: Modifier,
    onAdvanced: () -> Unit,
) {
    val selected = "${detail.routeKey}/${detail.requested.orEmpty()}"
    var panes by remember { mutableStateOf(listOf(SettingsPane(detail))) }
    val focus = LocalFocusManager.current
    val distance = with(LocalDensity.current) { 24.dp.toPx() }
    SideEffect { panes.firstOrNull { it.id == selected }?.detail = detail }
    LaunchedEffect(selected) {
        focus.clearFocus(force = true)
        var incoming = panes.firstOrNull { it.id == selected }
        if (incoming == null) {
            incoming = SettingsPane(detail)
            incoming.opacity.snapTo(0f)
        }
        val target = incoming
        target.entering = true
        panes.filter { it !== target }.forEach { it.entering = false }
        // Moving a selected pane to the end makes this a bounded least-recently-used cache.
        panes = (panes.filter { it !== target && it.detail.retainInactive } + target).takeLast(4)
        coroutineScope {
            panes.forEach { pane ->
                launch { pane.opacity.animateTo(if (pane === target) 1f else 0f, tween(160)) }
            }
        }
    }
    Box(modifier.clipToBounds()) {
        panes.forEach { pane ->
            val active = pane.id == selected
            // Rich editors own TTS, HTTP requests, polling and drafts. Dispose them as soon
            // as navigation leaves them rather than retaining those resources out of view.
            if (active || pane.detail.retainInactive) {
                key(pane.id) {
                    Box(Modifier.fillMaxSize().zIndex(if (active) 1f else 0f)
                        .graphicsLayer {
                            val progress = pane.opacity.value
                            alpha = progress
                            translationX = (if (pane.entering) distance else -distance) * (1f - progress)
                        }.focusProperties { canFocus = active }
                        .then(if (active) Modifier else Modifier.clearAndSetSemantics { })
                        .pointerInput(active) {
                            if (!active) awaitPointerEventScope {
                                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                            }
                        }) {
                        CompositionLocalProvider(LocalApprovedLauncherPageActive provides active) {
                            ApprovedLauncherPageLifecycle(active) {
                                ApprovedSettingsDetailBody(pane.detail, Modifier.fillMaxSize(), onAdvanced)
                            }
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
    // Outgoing transition content retains its own request; the next route never inherits it.
    CompositionLocalProvider(LocalApprovedRequestedControl provides detail.requested) {
        if (detail.lazy == null) {
            Column(
                modifier.verticalScroll(rememberScrollState())
                    .padding(start = 64.dp, end = 64.dp, top = 32.dp, bottom = 80.dp).focusGroup(),
            ) {
                ApprovedSettingsDetailHeading(detail.title, detail.subtitle, detail.onBack)
                if (detail.gated) ApprovedSettingsAdvancedGate(onAdvanced) else detail.content()
            }
        } else {
            LazyColumn(
                modifier.focusGroup(),
                state = detail.lazy.state,
                contentPadding = PaddingValues(start = 64.dp, end = 64.dp, top = 32.dp, bottom = 80.dp),
            ) {
                // The heading remains an ordinary scrolling item.
                item(key = "settings:detail-heading", contentType = "detail-heading") {
                    ApprovedSettingsDetailHeading(detail.title, detail.subtitle, detail.onBack)
                }
                if (detail.gated) {
                    item(key = "settings:advanced-gate") { ApprovedSettingsAdvancedGate(onAdvanced) }
                } else {
                    detail.lazy.body(this)
                }
            }
        }
    }
}

@Composable
private fun ApprovedSettingsSearchField(query: String, onChange: (String) -> Unit) {
    val color = IosSecondary
    Surface(color = IosCard, shape = RoundedCornerShape(16.dp),
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
}

@Composable
private fun ApprovedSettingsDetailHeading(title: String, subtitle: String, onBack: (() -> Unit)?) {
    Column {
        if (onBack != null) {
            ApprovedSettingsBackButton("Settings", onBack)
        }
        Text(title, color = IosLabel, fontSize = 40.sp, lineHeight = 52.sp,
            fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 12.dp))
        if (subtitle.isNotEmpty()) {
            Text(subtitle, color = IosSecondary, fontSize = 23.sp, lineHeight = 34.sp,
                modifier = Modifier.padding(top = 12.dp))
        }
        Spacer(Modifier.height(34.dp))
    }
}
