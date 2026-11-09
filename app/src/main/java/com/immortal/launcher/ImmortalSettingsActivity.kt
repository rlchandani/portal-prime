/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val STATE_STACK = "settings_split_stack"
private const val STATE_CONTROL = "settings_split_control"

/**
 * Hearthpane Settings: an iPadOS-style split view. The left column lists the pages in four
 * groups; the right column shows the selected page with a back stack of at most three pages.
 * Navigation is a plain state write in the click handler (no effect write-back), and only the
 * active page is composed. Deep links from Home, Music, Guest and the phone remote pass an old or
 * new page id in [APPROVED_SETTINGS_PAGE]; [SettingsLegacyIds] maps it into the tree.
 */
class ImmortalSettingsActivity : ComponentActivity() {
  internal var settingsUiProbe: SettingsUiProbeStore? = null
    private set
  internal fun settingsUiRequestedRoute() =
      SettingsUiProbeRoute(stack.root, stack.current, stack.control)
  internal fun settingsUiHistoryDepth() = stack.pages.size
  private var entryReport: SettingsEntryReport? = null
  private var entryFirstDraw: SettingsEntryFirstDrawObservation? = null

  private var stack by mutableStateOf(SettingsBackStack.of(SettingsPageIds.DEFAULT))

  private fun observeEntryFirstDraw() {
    val report = entryReport ?: return
    if (report.record(SettingsEntryStage.CREATE_FINISHED))
      entryFirstDraw = FramePerformanceCapture.bindSettingsEntry(window, report)
  }

  override fun onResume() {
    entryReport?.record(SettingsEntryStage.RESUME_STARTED)
    super.onResume()
    ApprovedSettingsRemoteBack.resumed(this)
    if (BuildConfig.PORTAL_SETTINGS_UI_DIAGNOSTICS) SettingsUiDiagnostics.resumed(this)
    entryReport?.record(SettingsEntryStage.RESUME_FINISHED)
  }

  override fun onPause() {
    ApprovedSettingsRemoteBack.unavailable(this)
    if (BuildConfig.PORTAL_SETTINGS_UI_DIAGNOSTICS) SettingsUiDiagnostics.paused(this)
    super.onPause()
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    if (hasFocus) entryReport?.record(SettingsEntryStage.FOCUS_GAINED)
    super.onWindowFocusChanged(hasFocus)
  }

  override fun onDestroy() {
    settingsUiProbe?.clear()
    settingsUiProbe = null
    if (BuildConfig.PORTAL_SETTINGS_UI_DIAGNOSTICS) SettingsUiDiagnostics.paused(this)
    entryFirstDraw?.dispose()
    entryFirstDraw = null
    entryReport?.record(SettingsEntryStage.DESTROYED)
    entryReport = null
    super.onDestroy()
  }

  /** Every navigation funnels here: one state write, and the page id is remembered for next time. */
  private fun show(next: SettingsBackStack) {
    if (next == stack) return
    stack = next
    SettingsLastPage.write(this, next.current)
  }

  /** Deep-link page (old or new id) or category; null when the intent names no page. */
  private fun requestedStack(intent: Intent): SettingsBackStack? {
    val destination = SettingsLegacyIds.resolve(
        intent.getStringExtra(APPROVED_SETTINGS_PAGE), intent.getStringExtra(APPROVED_SETTINGS_CATEGORY),
        intent.getStringExtra(APPROVED_SETTINGS_CONTROL))
    return when (destination) {
      is SettingsDestination.Page -> SettingsBackStack.of(destination.page, destination.control)
      is SettingsDestination.Activity -> {
        // A full-screen editor id: open it over its home page, as before.
        startActivity(approvedSettingsIntent(this, destination.id))
        null
      }
      null -> null
    }
  }

  private fun initialStack(intent: Intent, saved: Bundle?): SettingsBackStack =
      requestedStack(intent)
          ?: SettingsBackStack.restore(saved?.getStringArrayList(STATE_STACK), saved?.getString(STATE_CONTROL))
          ?: SettingsBackStack.of(SettingsLastPage.resolve(SettingsLastPage.read(this),
              ImmortalSettings.advancedSettings(this)))

  /** Remote and physical Back walk up the right column; at the top they leave Settings. */
  internal fun handleRemoteSettingsBack(): Boolean {
    if (!stack.canGoBack) return false
    show(stack.back())
    return true
  }

  /** Content rows (Stocks › Alpaca, Photo source, …) open pages here; full-screen editors launch. */
  private fun navigateLocal(route: ApprovedSettingsRoute) {
    when (val destination = SettingsLegacyIds.resolve(route.page, route.category, route.control)) {
      is SettingsDestination.Page -> show(stack.open(destination.page, destination.control))
      is SettingsDestination.Activity -> startActivity(approvedSettingsIntent(this, destination.id))
      null -> Unit
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    entryReport = FramePerformanceCapture.beginSettingsCreate(intent)
    super.onCreate(savedInstanceState)
    if (BuildConfig.PORTAL_SETTINGS_UI_DIAGNOSTICS) {
      val index = approvedSettingsSearchIndex()
      settingsUiProbe = SettingsUiProbeStore(
          SettingsTree.topLevel.map { it.id }.toSet(), SettingsTree.pages.map { it.id }.toSet(),
          index.mapNotNull { it.control }.toSet())
    }
    stack = initialStack(intent, savedInstanceState)
    SettingsLastPage.write(this, stack.current)
    setContent {
      PortalPrimeTheme(darkTheme = false) {
        val navigate = remember { ::navigateLocal }
        val back = remember { { show(stack.back()) } }
        // Reads the live stack, so a highlight that ends late can never undo a newer navigation.
        val clearHighlight = remember { { show(stack.withoutControl()) } }
        val viewports = remember { ApprovedSettingsViewportMemory() }
        CompositionLocalProvider(
            LocalApprovedSettingsNavigate provides navigate,
            LocalApprovedSettingsBack provides back,
            LocalApprovedSettingsViewportMemory provides viewports,
        ) {
          SettingsHostScreen(stack, ::show, clearHighlight)
        }
      }
    }
    observeEntryFirstDraw()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    requestedStack(intent)?.let(::show)
  }

  override fun onSaveInstanceState(outState: Bundle) {
    outState.putStringArrayList(STATE_STACK, ArrayList(stack.pages))
    stack.control?.let { outState.putString(STATE_CONTROL, it) }
    super.onSaveInstanceState(outState)
  }
}

@Composable
private fun SettingsHostScreen(
    stack: SettingsBackStack,
    show: (SettingsBackStack) -> Unit,
    clearHighlight: () -> Unit,
) {
  val context = LocalContext.current
  val advanced = rememberApprovedAdvanced()
  BackHandler(enabled = stack.canGoBack) { show(stack.back()) }
  var prerequisite by remember(stack.current, stack.control) { mutableStateOf<String?>(null) }
  LaunchedEffect(stack.current, stack.control) {
    stack.control?.let { control ->
      prerequisite = withContext(Dispatchers.IO) { approvedSettingsPrerequisite(context, control) }
    }
  }
  val page = stack.current
  val actions = rememberSettingsHostActions(stack, show)
  SettingsSplitShell(
      selectedRoot = SettingsTree.rootOf(page),
      heading = SettingsDetailHeading(
          title = SettingsTree.page(page)?.title.orEmpty(),
          backTitle = stack.parent?.let { SettingsTree.page(it)?.title },
          onBack = if (stack.canGoBack) actions.back else null,
      ),
      onSelectPage = actions.select,
      onSearchResult = actions.searchResult,
      onHome = actions.home,
      diagnosticsScope = "${SettingsTree.rootOf(page)}/$page",
      diagnosticsControl = stack.control,
  ) {
    key(page) {
      if (SettingsTree.needsAdvanced(page) && !advanced) {
        ApprovedSettingsAdvancedGate(LocalApprovedEnableAdvanced.current)
      } else {
        SettingsPageView(page, stack.control, onOpenPage = actions.open, onHighlightShown = clearHighlight)
      }
    }
  }
  prerequisite?.let { message ->
    ApprovedSettingsDialog("Setting prerequisite", onDismiss = { prerequisite = null }, help = message) {
      ApprovedSettingsTextButton("Review parent settings") { prerequisite = null }
    }
  }
}

/** Navigation callbacks with a stable identity, so a page switch leaves unchanged sidebar rows skipped. */
internal class SettingsHostActions(
    val select: (String) -> Unit,
    val open: (String) -> Unit,
    val back: () -> Unit,
    val searchResult: (ApprovedSettingsSearchResult) -> Unit,
    val home: () -> Unit,
)

/** Built once; each callback reads [stack] when it runs, so it always acts on the latest stack. */
internal fun settingsHostActions(
    stack: () -> SettingsBackStack,
    show: (SettingsBackStack) -> Unit,
    home: () -> Unit,
) = SettingsHostActions(
    select = { show(stack().select(it)) },
    open = { show(stack().open(it)) },
    back = { show(stack().back()) },
    searchResult = { show(SettingsBackStack.of(it.page, it.control)) },
    home = home,
)

@Composable
private fun rememberSettingsHostActions(
    stack: SettingsBackStack,
    show: (SettingsBackStack) -> Unit,
): SettingsHostActions {
  val context = LocalContext.current
  val currentStack by rememberUpdatedState(stack)
  val currentShow by rememberUpdatedState(show)
  return remember(context) {
    settingsHostActions({ currentStack }, { currentShow(it) }) {
      context.startActivity(Intent(context, HomeActivity::class.java)
          .putExtra("approved_home_tab", "home")
          .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
      (context as? android.app.Activity)?.finish()
    }
  }
}
