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
import com.immortal.launcher.jarvis.HearthpaneScreens
import com.immortal.launcher.jarvis.JarvisHighlight
import com.immortal.launcher.jarvis.LocalJarvisPulse
import com.immortal.launcher.jarvis.ScreenContext
import com.immortal.launcher.jarvis.SettingsJarvisContext
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val STATE_STACK = "settings_split_stack"
private const val STATE_CONTROL = "settings_split_control"
private const val STATE_LIST = "settings_single_pane_list"

/**
 * Where Settings opens. A recreate (rotation, theme) restores its saved stack and never re-applies
 * the launch intent's deep link, which was consumed on first open (it would otherwise reopen the
 * deep-linked page or relaunch its editor). Pure.
 */
internal fun settingsEntryStack(
    recreated: Boolean,
    deepLink: () -> SettingsBackStack?,
    restored: () -> SettingsBackStack?,
    lastPage: () -> SettingsBackStack,
): SettingsBackStack = (if (recreated) restored() else deepLink()) ?: lastPage()

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
  /** Single-pane Settings (narrow window): the page list is showing rather than a page. */
  private var showList by mutableStateOf(true)

  private fun singlePaneNow(): Boolean {
    val config = resources.configuration
    return LayoutClass.of(config).compact && config.screenWidthDp < LayoutClass.NARROW_WIDTH_DP
  }

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
    SettingsPreviewPhotos.clear()
    entryReport?.record(SettingsEntryStage.DESTROYED)
    entryReport = null
    super.onDestroy()
  }

  /** A page was picked (list row, search result, in-page link): single-pane shows the page. */
  private fun reveal(next: SettingsBackStack) {
    showList = false
    show(next)
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

  private fun initialStack(intent: Intent, saved: Bundle?): SettingsBackStack = settingsEntryStack(
      recreated = saved != null,
      deepLink = { requestedStack(intent)?.also { showList = false } },
      restored = { SettingsBackStack.restore(saved?.getStringArrayList(STATE_STACK), saved?.getString(STATE_CONTROL)) },
      lastPage = { SettingsBackStack.of(SettingsLastPage.resolve(SettingsLastPage.read(this),
          ImmortalSettings.advancedSettings(this))) },
  )

  /** Remote and physical Back walk up the right column; at the top they leave Settings. */
  internal fun handleRemoteSettingsBack(): Boolean {
    if (!stack.canGoBack && singlePaneNow() && !showList) {
      showList = true
      return true
    }
    if (!stack.canGoBack) return false
    show(stack.back())
    return true
  }

  /** Content rows (Stocks › Alpaca, Photo source, …) open pages here; full-screen editors launch. */
  private fun navigateLocal(route: ApprovedSettingsRoute) {
    when (val destination = SettingsLegacyIds.resolve(route.page, route.category, route.control)) {
      is SettingsDestination.Page -> reveal(stack.open(destination.page, destination.control))
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
    savedInstanceState?.let { showList = it.getBoolean(STATE_LIST, true) }
    stack = initialStack(intent, savedInstanceState)
    SettingsLastPage.write(this, stack.current)
    // Kept awake while visible, set before the first frame so it costs no second window relayout.
    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    keepHomesBarState()
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
            LocalJarvisPulse provides jarvisPulse,
        ) {
          val listPane = remember { SettingsPane(showList = true, onShowList = { showList = true }) }
          val pagePane = remember { SettingsPane(showList = false, onShowList = listPane.onShowList) }
          SettingsHostScreen(stack, ::show, ::reveal, clearHighlight, if (showList) listPane else pagePane)
        }
      }
    }
    observeEntryFirstDraw()
    trackForJarvis()
  }

  /**
   * Home hides the navigation bar (immersive, sticky). A focused window that doesn't makes Android clear Home's
   * hide-navigation flag as Settings covers it, which relayouts Home's whole window (insets, measure, a fresh
   * recording) in the frames Settings opens. Settings asks for the same hidden navigation bar before its first
   * frame, so the bars' state never changes. The Portal has no navigation bar to show, so Settings looks the same.
   */
  private fun keepHomesBarState() {
    androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
      hide(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
      systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
  }

  /** Floating Jarvis: this page is what `screen_context` describes, and Jarvis can point at its rows. */
  private fun trackForJarvis() {
    HearthpaneScreens.track(this, HearthpaneScreens.Entry({ HearthpaneScreens.SETTINGS }) {
      SettingsJarvisContext.build(this, stack.current)
    })
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        JarvisHighlight.current.collect { request ->
          val control = request?.target?.takeIf { it.startsWith(ScreenContext.SETTINGS_TARGET) }
              ?.removePrefix(ScreenContext.SETTINGS_TARGET) ?: return@collect
          val page = SettingsJarvisContext.pageOf(control) ?: return@collect
          jarvisPulse = control
          show(if (stack.current == page) stack.copy(control = control) else stack.open(page, control))
          delay(JarvisHighlight.SHOW_MS)
          if (jarvisPulse == control) jarvisPulse = null
          JarvisHighlight.done(request)
        }
      }
    }
  }

  private var jarvisPulse by mutableStateOf<String?>(null)

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    requestedStack(intent)?.let(::reveal)
  }

  override fun onSaveInstanceState(outState: Bundle) {
    outState.putStringArrayList(STATE_STACK, ArrayList(stack.pages))
    stack.control?.let { outState.putString(STATE_CONTROL, it) }
    outState.putBoolean(STATE_LIST, showList)
    super.onSaveInstanceState(outState)
  }
}

@Composable
private fun SettingsHostScreen(
    stack: SettingsBackStack,
    show: (SettingsBackStack) -> Unit,
    reveal: (SettingsBackStack) -> Unit,
    clearHighlight: () -> Unit,
    pane: SettingsPane,
) {
  val context = LocalContext.current
  val advanced = rememberApprovedAdvanced()
  BackHandler(enabled = stack.canGoBack) { show(stack.back()) }
  // Single pane: Back at a page's root returns to the page list before leaving Settings.
  BackHandler(enabled = !stack.canGoBack && !pane.showList && settingsSinglePane()) { pane.onShowList() }
  var prerequisite by remember(stack.current, stack.control) { mutableStateOf<String?>(null) }
  LaunchedEffect(stack.current, stack.control) {
    stack.control?.let { control ->
      prerequisite = withContext(Dispatchers.IO) { approvedSettingsPrerequisite(context, control) }
    }
  }
  val page = stack.current
  val actions = rememberSettingsHostActions(stack, show, reveal)
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
      pane = pane,
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
    reveal: (SettingsBackStack) -> Unit = show,
    home: () -> Unit,
) = SettingsHostActions(
    select = { reveal(stack().select(it)) },
    open = { reveal(stack().open(it)) },
    back = { show(stack().back()) },
    searchResult = { reveal(SettingsBackStack.of(it.page, it.control)) },
    home = home,
)

@Composable
private fun rememberSettingsHostActions(
    stack: SettingsBackStack,
    show: (SettingsBackStack) -> Unit,
    reveal: (SettingsBackStack) -> Unit,
): SettingsHostActions {
  val context = LocalContext.current
  val currentStack by rememberUpdatedState(stack)
  val currentShow by rememberUpdatedState(show)
  val currentReveal by rememberUpdatedState(reveal)
  return remember(context) {
    settingsHostActions({ currentStack }, { currentShow(it) }, { currentReveal(it) }) {
      context.startActivity(Intent(context, HomeActivity::class.java)
          .putExtra("approved_home_tab", "home")
          .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
      (context as? android.app.Activity)?.finish()
    }
  }
}
