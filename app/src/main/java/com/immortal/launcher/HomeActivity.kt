/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import com.immortal.launcher.jarvis.HearthpaneScreens
import com.immortal.launcher.jarvis.ScreenContext
import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.ui.input.key.key
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.immortal.launcher.ui.components.OnResume
import com.immortal.launcher.ui.components.launchSafely
import com.immortal.launcher.ui.theme.LocalAdvancedSettings
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import org.json.JSONObject

private const val HOME_APP_WIDGET_HOST_ID = 0x4611
private const val SAVED_LAUNCHER_TAB = "approved_saved_launcher_tab"
internal const val APPROVED_HOME_MUSIC = "approved_home_music"

/**
 * The custom Portal home launcher. Replaces the stock Aloha home (selected via
 * `cmd package set-home-activity`). Shows a clock/date/weather header, an App
 * Store tile, and a grid of every installed launchable app. Built for Portal's
 * form factor: dark theme, top 64dp reserved for the system overlay, large
 * touch targets, landscape.
 */
class HomeActivity : ComponentActivity() {
  private var widgetLibraryRequest by mutableStateOf(0)
  private var launcherTabState by mutableStateOf(ApprovedLauncherTab.HOME)
  private var launcherTab: ApprovedLauncherTab
    get() = launcherTabState
    set(value) {
      if (value != launcherTabState) JarvisHandoff.cancelPending(this)
      launcherTabState = value
    }
  private var appsSearchOpenRequest by mutableStateOf(0)
  private var musicOpenRequest by mutableStateOf(0)
  private var pendingResumeIntent: Intent? = null
  private var cancelWebViewStartup: (() -> Unit)? = null
  private var cancelMusicPrewarm: (() -> Unit)? = null

  /** Read-only navigation diagnostics; never simulate Android lifecycle callbacks to read state. */
  internal fun approvedCurrentTab(): ApprovedLauncherTab = launcherTab

  /** Media keys drive the YouTube player (it has no MediaSession); see [MusicMediaKeys]. */
  override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
      MusicMediaKeys.dispatch(event) || super.dispatchKeyEvent(event)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // A restored Activity carries its old launch Intent; only a new launch or onNewIntent
    // delivery can identify Meta's bootstrap redirect.
    pendingResumeIntent = if (savedInstanceState == null) intent else null
    launcherTab = ApprovedLauncherTab.fromId(savedInstanceState?.getString(SAVED_LAUNCHER_TAB))
        ?: ApprovedLauncherTab.HOME
    consumeLauncherTabIntent(intent)
    consumeWidgetLibraryIntent(intent)
    trackForJarvis()
    enterImmersive()
    // First launch: show the friendly tour once so new users aren't dropped in cold.
    if (!HelpActivity.hasSeen(this)) {
      window.decorView.post {
        runCatching { startActivity(Intent(this, HelpActivity::class.java)) }
      }
    }
    setContent {
      PortalPrimeTheme(darkTheme = false) {
        LauncherScreen(
            widgetLibraryRequest = widgetLibraryRequest,
            appsSearchOpenRequest = appsSearchOpenRequest,
            musicOpenRequest = musicOpenRequest,
            onOpenMusic = { musicOpenRequest++ },
            selectedTab = launcherTab,
            onSelectTab = { launcherTab = it },
            onLaunch = { cn ->
              runCatching {
                if (cn == CALLS_COMPONENT) {
                  // The "Calls" tile opens Hearthpane Calling (rooms, recent, Call everyone); Portal's
                  // own Messenger/WhatsApp calling stays one tap away inside it.
                  CallsActivity.open(this@HomeActivity)
                } else if (cn.packageName == JarvisHandoff.PACKAGE) {
                  if (!JarvisHandoff.launch(this@HomeActivity))
                    android.widget.Toast.makeText(this@HomeActivity, "Jarvis couldn’t be opened.",
                        android.widget.Toast.LENGTH_LONG).show()
                } else if (cn.packageName == packageName &&
                    cn.className == ImmortalSettingsActivity::class.java.name) {
                  // Our own iOS-style settings (exported=false, no LAUNCHER filter) — launch it
                  // directly with an explicit intent rather than the generic MAIN/LAUNCHER one.
                  FramePerformanceCapture.launchSettings(this@HomeActivity,
                      Intent(this@HomeActivity, ImmortalSettingsActivity::class.java)
                          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else if (cn.packageName == packageName &&
                    cn.className == StoreActivity::class.java.name) {
                  // Our own App Store — explicit launch (same reasoning as settings).
                  startActivity(Intent(this@HomeActivity, StoreActivity::class.java))
                } else if (cn.packageName == packageName &&
                    cn.className == ToolsActivity::class.java.name) {
                  launcherTab = ApprovedLauncherTab.TOOLS
                } else if (cn == ComponentName(this@HomeActivity, HomeDashboardActivity::class.java)) {
                  HomeDashboardActivity.openDashboard(this@HomeActivity)
                } else if (cn.packageName == packageName &&
                    cn.className == ControlCenterActivity::class.java.name) {
                  startActivity(ControlCenterActivity.intent(this@HomeActivity))
                } else if (cn.packageName == packageName &&
                    cn.className == WebAppActivity::class.java.name) {
                  // Music stays inside the retained Home host; no separate Activity/theme.
                  musicOpenRequest++
                } else {
                  startActivity(
                      Intent(Intent.ACTION_MAIN)
                          .addCategory(Intent.CATEGORY_LAUNCHER)
                          .setComponent(cn)
                          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
              }
            },
            onOpenStore = {
              runCatching { startActivity(Intent(this, StoreActivity::class.java)) }
            },
            onOpenHelp = {
              runCatching { startActivity(Intent(this, HelpActivity::class.java)) }
            },
            onStartScreensaver = {
              runCatching {
                startActivity(Intent(this, PhotoFramePreviewActivity::class.java))
              }
            },
            onExitHome = { launchStockHome() },
            onUninstall = { pkg ->
              // System uninstall dialog; no special permission needed.
              runCatching {
                startActivity(
                    Intent(Intent.ACTION_DELETE)
                        .setData(android.net.Uri.parse("package:$pkg"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
              }
            },
        )
      }
    }
  }

  /** Floating Jarvis: Home (or the Music screen over it) is what `screen_context` describes; never its photos. */
  private fun trackForJarvis() {
    fun musicShown() = YouTubeMusicEngine.shownIn == MusicPlayerHostKind.MUSIC_SCREEN
    HearthpaneScreens.track(this, HearthpaneScreens.Entry(
        id = { if (musicShown()) HearthpaneScreens.MUSIC else HearthpaneScreens.HOME },
        context = {
          if (musicShown()) ScreenContext.music(YouTubeMusicEngine.state.value, FleetConfig.name(this))
          else ScreenContext.home(approvedCurrentTab().id, YouTubeMusicEngine.state.value)
        },
    ))
  }

  override fun onSaveInstanceState(outState: Bundle) {
    outState.putString(SAVED_LAUNCHER_TAB, launcherTab.id)
    super.onSaveInstanceState(outState)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    pendingResumeIntent = intent
    consumeLauncherTabIntent(intent)
    consumeWidgetLibraryIntent(intent)
  }

  /** Main-thread state application shared by the retained-host route and Intent delivery. */
  internal fun selectApprovedHomeTab(tab: ApprovedLauncherTab, searchApps: Boolean) {
    launcherTab = if (searchApps) ApprovedLauncherTab.APPS else tab
    if (searchApps) appsSearchOpenRequest++
  }

  private fun consumeLauncherTabIntent(incoming: Intent) {
    val tab = ApprovedLauncherTab.fromId(incoming.getStringExtra(APPROVED_HOME_TAB))
    val search = incoming.getBooleanExtra(APPROVED_HOME_SEARCH, false)
    if (tab != null) {
      ImmortalDreamNavigation.dismissForNavigation()
      selectApprovedHomeTab(tab, search)
    } else if (search) selectApprovedHomeTab(ApprovedLauncherTab.APPS, true)
    incoming.removeExtra(APPROVED_HOME_TAB)
    incoming.removeExtra(APPROVED_HOME_SEARCH)
    if (incoming.getBooleanExtra(APPROVED_HOME_MUSIC, false)) {
      ImmortalDreamNavigation.dismissForNavigation()
      musicOpenRequest++
    }
    incoming.removeExtra(APPROVED_HOME_MUSIC)
  }

  /** Settings can open the same library without taking ownership of AppWidgetHost callbacks. */
  private fun consumeWidgetLibraryIntent(incoming: Intent) {
    if (incoming.getBooleanExtra("approved_home_widgets", false) ||
        incoming.getStringExtra(APPROVED_SETTINGS_PAGE) == "widgets") {
      incoming.removeExtra("approved_home_widgets")
      if (incoming.getStringExtra(APPROVED_SETTINGS_PAGE) == "widgets")
        incoming.removeExtra(APPROVED_SETTINGS_PAGE)
      widgetLibraryRequest++
      launcherTab = ApprovedLauncherTab.HOME
    }
  }

  // Re-assert immersive fullscreen whenever the launcher regains focus — e.g.
  // after the screensaver or another app closes — since the system restores the
  // bars when another window takes over.
  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus) enterImmersive()
  }

  // Self-heal: if anything (e.g. the stock launcher) reset our screensaver
  // settings, put them back every time Immortal comes to the foreground.
  override fun onResume() {
    super.onResume()
    // Meta's cold bootstrap sends MAIN/HOME/CLEAR_TOP to the default launcher. This transient
    // resume is still part of Calls; apply bounded recovery before treating it as the user's return.
    val incoming = pendingResumeIntent
    pendingResumeIntent = null
    if (PortalHomeHandoffRecovery.recoverColdHome(this, incoming)) return
    // The user is back on Immortal, so any stock-launcher call handoff is over:
    // allow the photo frame to resume its normal screensaver behaviour. A Jarvis tap still being
    // prepared here owns the bridge (clearing it would silently drop the tap); its own retire clears.
    if (!JarvisHandoff.isPreparing(this)) DreamPolicy.clearBridge(this)
    SettingsGuard.reaffirmScreensaver(this)
    // The user is back on the launcher: end the idle session and, inside the overnight window,
    // arm the touch-renewed "you have the device" session. SleepScheduler owns that policy.
    SleepScheduler.onReturnedToLauncher(this)
    ApprovedHomeNavigation.resumed(this)
    // Music's first open no longer pays Chromium's start-up: it happens here, once, at idle.
    cancelMusicPrewarm?.invoke()
    cancelMusicPrewarm = MusicPlayerPrewarm.scheduleForHome(applicationContext, window.decorView)
    if (BuildConfig.PORTAL_WEBVIEW_BACKGROUND_STARTUP) {
      cancelWebViewStartup?.invoke()
      cancelWebViewStartup =
          WebViewStartupPreparation.scheduleForHome(applicationContext, window.decorView)
    }
  }

  override fun onPause() {
    cancelWebViewStartup?.invoke()
    cancelWebViewStartup = null
    cancelMusicPrewarm?.invoke()
    cancelMusicPrewarm = null
    ApprovedHomeNavigation.unavailable(this)
    super.onPause()
    // Don't carry a pending overnight re-sleep into another activity or a real sleep.
    SleepScheduler.onLeftLauncher()
  }

  override fun onDestroy() {
    cancelWebViewStartup?.invoke()
    cancelWebViewStartup = null
    ApprovedHomeNavigation.unavailable(this)
    super.onDestroy()
  }

  // dispatchTouchEvent is the top of the input chain, so it sees every touch before Compose
  // consumes it. Renews the overnight session; a no-op outside the window.
  override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
    SleepScheduler.onInteraction(this)
    return super.dispatchTouchEvent(ev)
  }

  private fun enterImmersive() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).apply {
      hide(WindowInsetsCompat.Type.systemBars())
      systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
  }

  /**
   * The "Calls" tile bridges to the STOCK Portal home — the only caller Meta trusts
   * to launch Contacts/calling/camera.
   *
   * Why a deep link and not a plain HOME launch: the Contacts app
   * (com.facebook.alohaapps.contacts) enforces a signature-based trusted-caller
   * check (Meta's com.facebook.secure framework) that Immortal can never satisfy,
   * so we route through its `portal://launcher/home` deep link. Even that link can trigger
   * Meta's idle/dream bootstrap on a cold start; [PortalHomeHandoffRecovery] handles its
   * bootstrap HOME redirects while preserving [DreamPolicy]'s screensaver suppression.
   * Both Calls entry points use [PortalHomeBridge] so resolution and fallback cannot drift.
   */
  private fun launchStockHome() {
    PortalHomeBridge.launch(this)
  }
}

@Composable
private fun LauncherScreen(
    widgetLibraryRequest: Int,
    appsSearchOpenRequest: Int,
    musicOpenRequest: Int,
    onOpenMusic: () -> Unit,
    selectedTab: ApprovedLauncherTab,
    onSelectTab: (ApprovedLauncherTab) -> Unit,
    onLaunch: (ComponentName) -> Unit,
    onOpenStore: () -> Unit,
    onOpenHelp: () -> Unit,
    onStartScreensaver: () -> Unit,
    onExitHome: () -> Unit,
    onUninstall: (String) -> Unit,
) {
  val context = androidx.compose.ui.platform.LocalContext.current

  // Preferences refresh on resume; app/provider bitmaps only refresh on package broadcasts
  // or an explicit refresh. Returning from Settings reuses the same list/icon identities.
  var appsRefreshKey by remember { mutableStateOf(0) }
  val inventoryRevision by ApprovedHomeInventory.revision.collectAsState()
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    ApprovedHomeInventory.register(context)
    val onResume = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) appsRefreshKey++
    }
    lifecycleOwner.lifecycle.addObserver(onResume)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(onResume)
    }
  }
  val installedApps by produceState(initialValue = ApprovedHomeInventory.apps.peek().orEmpty(), inventoryRevision) {
    value = withContext(Dispatchers.IO) {
      ApprovedHomeInventory.apps.load { loadApps(context.applicationContext) }
    }
  }

  var editMode by remember { mutableStateOf(false) }
  var showLibrary by rememberSaveable { mutableStateOf(false) }
  val addFlow = remember { HomeAddFlowState() }
  LaunchedEffect(editMode) { if (!editMode) addFlow.reset() }
  LaunchedEffect(addFlow.ring) { if (addFlow.ring != null) { delay(ADD_RING_MS); addFlow.ring = null } }
  val musicLayoutState = rememberSaveable { mutableStateOf<MusicPlayerLayout?>(null) }
  // Written here; composition reads go through homeMusicLayout and MusicFullScreenSlot only, so opening
  // full-screen Music recomposes nothing of Home (see HomeMusicCover).
  var musicLayout by musicLayoutState
  var musicCovered by remember { mutableStateOf(false) }
  // Home's tab pages come back a frame before its chrome when Music closes (HomeMusicCover.closePlan).
  var musicTabsCovered by remember { mutableStateOf(false) }
  LaunchedEffect(musicLayoutState) {
    snapshotFlow { musicLayoutState.value == MusicPlayerLayout.FULL }.collectLatest { full ->
      if (full) { delay(HomeMusicCover.SETTLE_MS); musicCovered = true; musicTabsCovered = true }
      else { musicCovered = false; musicTabsCovered = false }
    }
  }
  val homeMusicLayout by remember { derivedStateOf { HomeMusicCover.homeLayout(musicLayoutState.value, musicCovered) } }
  val homeTabsMusicLayout by remember { derivedStateOf { HomeMusicCover.homeLayout(musicLayoutState.value, musicTabsCovered) } }
  // Leaving full-screen Music runs over three frames (HomeMusicCover.closePlan). Read in layer blocks only.
  val homeShownOverMusic = remember { mutableStateOf(false) }
  var musicClosing by remember { mutableStateOf(false) }
  val musicCloseScope = rememberCoroutineScope()
  val closeMusic: (onRemoved: () -> Unit) -> Unit = { onRemoved ->
    if (!musicClosing) {
      val plan = HomeMusicCover.closePlan(musicLayoutState.value, musicCovered)
      if (plan.size == 1) { musicLayout = null; onRemoved() } else {
        musicClosing = true
        musicCloseScope.launch {
          try {
            for (step in plan) withFrameNanos {
              when (step) {
                MusicCloseStep.UNCOVER_TABS -> musicTabsCovered = false
                MusicCloseStep.UNCOVER_HOME -> musicCovered = false
                MusicCloseStep.SETTLE -> Unit
                MusicCloseStep.SHOW_HOME -> homeShownOverMusic.value = true
                MusicCloseStep.REMOVE_MUSIC -> { musicLayout = null; onRemoved() }
              }
            }
          } finally {
            musicClosing = false
            homeShownOverMusic.value = false
          }
        }
      }
    }
  }
  // Set by Home's Now playing chip: the player resumes the paused song once it is on screen.
  var musicResume by remember { mutableStateOf(false) }
  LaunchedEffect(musicOpenRequest) {
    if (musicOpenRequest > 0) {
      musicLayout = MusicPlayerLayout.FULL
      editMode = false
      showLibrary = false
    }
  }
  LaunchedEffect(widgetLibraryRequest) {
    if (widgetLibraryRequest > 0) showLibrary = true
  }
  var showSearch by remember { mutableStateOf(false) }
  var appsSearchRequest by remember { mutableStateOf(0) }
  var toolsLibraryRequest by rememberSaveable { mutableStateOf(0) }
  var pendingSetting by remember { mutableStateOf<ApprovedSettingsSearchResult?>(null) }
  var searchApplyError by remember { mutableStateOf<String?>(null) }
  var showPair by remember { mutableStateOf(false) }
  var showSleep by remember { mutableStateOf(false) }
  var pairUrl by remember { mutableStateOf<String?>(null) }
  var pairPin by remember { mutableStateOf<String?>(null) }
  var appMenu by remember { mutableStateOf<AppEntry?>(null) }
  var search by remember { mutableStateOf("") }
  val settings = remember(appsRefreshKey) { ImmortalSettings.load(context) }
  val welcome = remember(appsRefreshKey) { WelcomeConfig.load(context) }
  val room = remember(appsRefreshKey) { FleetConfig.name(context) }
  var assignments by remember { mutableStateOf(UserLayout.load(context)) }
  var order by remember { mutableStateOf(UserLayout.loadOrder(context)) }
  fun saveAssignments(updated: Map<String, String>): Boolean {
    val saved = UserLayout.saveAssignmentsFromSnapshot(context, assignments, updated)
    assignments = UserLayout.load(context)
    if (!saved) Toast.makeText(context,
        "Folders changed on another screen. Review the layout and try again.", Toast.LENGTH_LONG).show()
    return saved
  }
  fun saveAppOrder(updated: List<String>): Boolean {
    val saved = UserLayout.saveOrderFromSnapshot(context, order, updated)
    order = UserLayout.loadOrder(context)
    if (!saved) Toast.makeText(context,
        "Favorites changed on another screen. Review the layout and try again.", Toast.LENGTH_LONG).show()
    return saved
  }
  LaunchedEffect(selectedTab) {
    showSearch = false
    showSleep = false
    showPair = false
    appMenu = null
    if (selectedTab != ApprovedLauncherTab.HOME) {
      editMode = false
      showLibrary = false
    }
    assignments = UserLayout.load(context)
    order = UserLayout.loadOrder(context)
  }
  OnResume {
    assignments = UserLayout.load(context)
    order = UserLayout.loadOrder(context)
  }
  val orderedApps = remember(installedApps, order) {
    UserLayout.applyOrder(installedApps, order) { it.component.flattenToString() }
  }
  val favorites = remember(orderedApps, order) {
    if (order.isNotEmpty()) orderedApps.take(6) else {
      val curated = HomePresentation.defaultFavoritePackages
          .mapNotNull { choices -> orderedApps.firstOrNull { it.component.packageName in choices } }
      (curated + orderedApps).distinctBy { it.component }.take(6)
    }
  }
  var homeWidgets by remember {
    HomeWidgetStore.migrateDashboardCards(context)
    HomeWidgetStore.dropHomeAssistantWidgetOnce(context)
    mutableStateOf(HomeWidgetStore.load(context))
  }
  OnResume { homeWidgets = HomeWidgetStore.load(context) }
  DisposableEffect(context) {
    val stop = HomeWidgetStore.observe(context) { homeWidgets = HomeWidgetStore.load(context) }
    // Read after subscribing so a restore between composition and this effect is visible.
    homeWidgets = HomeWidgetStore.load(context)
    onDispose { stop() }
  }
  val saveWidgets: HomeWidgetChange = { expected, updated ->
    val saved = HomeWidgetStore.replaceIfUnchanged(context, expected, updated)
    homeWidgets = HomeWidgetStore.load(context)
    if (!saved) Toast.makeText(context,
        "Widgets changed on another screen. Review the layout and try again.", Toast.LENGTH_LONG).show()
    saved
  }
  val host = remember { AppWidgetHost(context, HOME_APP_WIDGET_HOST_ID) }
  val manager = remember { AppWidgetManager.getInstance(context) }
  var pending by androidx.compose.runtime.saveable.rememberSaveable(stateSaver = PendingWidgetAdd.Saver) {
    mutableStateOf<PendingWidgetAdd?>(null)
  }
  val completeAdd: () -> Unit = {
    pending?.let { addition ->
      (manager.getAppWidgetInfo(addition.appWidgetId)?.provider
          ?: android.content.ComponentName.unflattenFromString(addition.provider))?.let { component ->
        val widget = HomeWidgetStore.HomeWidget(
            appWidgetId = addition.appWidgetId,
            providerPackage = component.packageName, providerClass = component.className,
            spanX = addition.spanX, spanY = addition.spanY)
        val slot = addition.slot
        val span = addition.span
        if (slot != null && span != null && addIntoSlot(context, widget, slot, span)) addFlow.added(widget.key)
        else {
          // The slot was taken meanwhile: keep the bound widget, auto-placed.
          homeWidgets = HomeWidgetStore.add(context, widget)
          addFlow.draft = null
        }
        homeWidgets = HomeWidgetStore.load(context)
      }
    }
    pending = null
    showLibrary = false
  }
  val cancelAdd: () -> Unit = {
    pending?.let { runCatching { host.deleteAppWidgetId(it.appWidgetId) } }
    pending = null
  }
  val configureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
    if (it.resultCode == Activity.RESULT_OK) completeAdd() else cancelAdd()
  }
  val configureOrAdd: () -> Unit = {
    val addition = pending
    val component = addition?.let { manager.getAppWidgetInfo(it.appWidgetId)?.configure }
    if (addition != null && component != null) {
      runCatching { configureLauncher.launch(Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
          .setComponent(component).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, addition.appWidgetId)) }
          .onFailure { cancelAdd() }
    } else completeAdd()
  }
  val bindLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
    if (it.resultCode == Activity.RESULT_OK) configureOrAdd() else cancelAdd()
  }
  val providers by produceState<List<WidgetProviderEntry>?>(
      initialValue = ApprovedHomeInventory.providers.peek(), inventoryRevision, showLibrary,
      addFlow.pickerSlot != null) {
    if (showLibrary || addFlow.pickerSlot != null) {
      value = ApprovedHomeInventory.providers.peek()
      value = withContext(Dispatchers.IO) {
        ApprovedHomeInventory.providers.load {
          loadWidgetProviders(context.applicationContext, 128.dp).filter { it.info != null }
        }
      }
    }
  }
  val use24 = remember(appsRefreshKey) { ImmortalSettings.use24HourClock(context) }
  var connected by remember { mutableStateOf<Boolean?>(null) }
  LaunchedEffect(appsRefreshKey) {
    while (true) {
      connected = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        cm.activeNetwork != null
      }.getOrDefault(false)
      delay(15_000)
    }
  }
  // Always enabled: the launcher root consumes Back (Android 10 would finish it and show Meta's home).
  // Overlays composed below (widget picker, dialogs) register later and still close first.
  BackHandler(enabled = true) {
    when (HomeBackPolicy.resolve(
        musicOpen = musicLayout != null,
        onHomeTab = selectedTab == ApprovedLauncherTab.HOME,
        hasDraft = addFlow.draft != null,
        editMode = editMode,
    )) {
      HomeBackAction.CLOSE_MUSIC -> closeMusic {}
      HomeBackAction.GO_HOME_TAB -> onSelectTab(ApprovedLauncherTab.HOME)
      HomeBackAction.CLEAR_DRAFT -> addFlow.draft = null
      HomeBackAction.EXIT_EDIT -> editMode = false
      HomeBackAction.CONSUME -> Unit
    }
  }
  // Home's Now Playing card hosts the YouTube player once the Music screen closes (no pause).
  val musicCard = rememberMusicHomeCardShown(
      musicOpen = HomeMusicCover.cardSeesMusicOpen(homeMusicLayout, musicClosing), customizing = editMode)
  val toastInsets = rememberMusicToastInsets(musicCard)
  MusicHomeDialogCover(showLibrary || addFlow.pickerSlot != null || addFlow.draft != null || showPair || showSleep ||
      showSearch || pendingSetting != null || appMenu != null)
  MusicVideoScreenHold()
  CompositionLocalProvider(
      com.immortal.launcher.ui.theme.LocalApprovedPalette provides
          if (com.immortal.launcher.ui.theme.LocalApprovedPalette.current.dark) ApprovedHomeTabPalette
          else com.immortal.launcher.ui.theme.ApprovedLightPalette,
      LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = FontFamily.Default)) {
    // Full-screen Music is opaque: Home stays composed beneath it but isn't drawn, so every Music
    // frame (open, countdown, spinner) composites one layer instead of three (Portal Mini GPU).
    // A zero-alpha layer is skipped by the renderer yet keeps its recorded display list, so hiding and
    // showing Home is a layer property: it recomposes nothing and re-records nothing.
    val homeHiddenUnderMusic = remember {
      Modifier.graphicsLayer { alpha = HomeMusicCover.homeAlpha(musicLayoutState.value, homeShownOverMusic.value) }
    }
    Box(Modifier.fillMaxSize()) {
      // A single cached display list for the static/minute-updated wallpaper.
      Box(Modifier.matchParentSize().then(homeHiddenUnderMusic)) { ApprovedHomeBackground() }
      // Who's there?: the recognised person's warm tint, over the wallpaper and under the chrome.
      // Only the coarse "whose Home" snapshot is read here, so indicator/camera states never recompose Home.
      val whosThereHome by WhosThere.homeView.collectAsState()
      if (whosThereHome.personal) WhosThereTint(Modifier.matchParentSize().then(homeHiddenUnderMusic))
      Column(Modifier.fillMaxSize().then(homeHiddenUnderMusic)) {
        // Launcher chrome trimmed 108 → 92dp; the 68dp header buttons keep a 12dp margin (Home v2 space).
        val hey by produceState<String?>(null, installedApps) { value = withContext(Dispatchers.IO) { heyPackage(context) } }
        // Without Millennium the header mic opens Jarvis's floating card (through JarvisHandoff), when installed.
        val jarvisMic by produceState(false, installedApps, hey) {
          value = hey == null && withContext(Dispatchers.IO) { jarvisInstalled(context) }
        }
        val showCustomize = selectedTab == ApprovedLauncherTab.HOME && homeMusicLayout == null
        val homeControl = rememberHomeControlShown()
        // Optional header pieces drop at narrow widths so the round buttons never squish.
        val header = HomeHeaderBudget.plan(LocalConfiguration.current.screenWidthDp, HomeHeaderItems(
            musicChip = homeMusicLayout == null, miniPlayer = settings.showMiniPlayer, mic = hey != null || jarvisMic,
            customize = showCustomize, homeControl = homeControl))
        val openSettings: () -> Unit = {
          runCatching {
            FramePerformanceCapture.launchSettings(context,
                Intent(context, ImmortalSettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
          }
        }
        if (header.compact) {
          // Compact tier (the Mini upright): 56 dp buttons; phone, sleep and theme live in "⋯".
          val b = HomeHeaderBudget.COMPACT_BUTTON.dp
          Row(Modifier.fillMaxWidth().height(HomeHeaderBudget.COMPACT_HEIGHT.dp).padding(horizontal = HomeHeaderBudget.EDGE.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(HomeHeaderBudget.COMPACT_GAP.dp)) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(HomeHeaderBudget.COMPACT_GAP.dp)) {
              ApprovedHeaderButton("screensaver", "Start screensaver", size = b, onClick = onStartScreensaver)
              if (header.musicChip) MusicV3HomeChip { musicResume = true; onOpenMusic() }
            }
            if (header.wifi) ApprovedHomeIcon("wifi", Modifier.size(22.dp))
            if (showCustomize)
              ApprovedHeaderButton(if (editMode) "check" else "edit", if (editMode) "Done" else "Customize",
                  lit = editMode, size = b) { editMode = !editMode }
            HomeHeaderOverflowButton(size = b,
                status = when (connected) { true -> "Connected"; false -> "Offline"; else -> "Connecting" },
                onRemote = {
                  val (url, pin) = enableRemoteAndMintPin(context)
                  pairUrl = url; pairPin = pin; showPair = true
                },
                onSleep = { showSleep = true })
            hey?.let { pkg ->
              Box(Modifier.size(b).clip(CircleShape).background(ApprovedHomePalette.Chrome).approvedOutline(CircleShape)
                  .semantics { contentDescription = "Voice assistant" }
                  .tvFocusable(CircleShape, onLongClick = { openHeyPicker(context, pkg) }) { fireHey(context, pkg) },
                  contentAlignment = Alignment.Center) { MicGlyph(ApprovedHomePalette.Text) }
            }
            if (jarvisMic) JarvisHeaderButton(b) { triggerJarvis(context) }
            ApprovedHeaderButton("music", "Open music player", lit = rememberMusicV3Loaded(), size = b, onClick = onOpenMusic)
            if (homeControl) HomeControlHeaderButton(size = b)
            ApprovedHeaderButton("search", "Search apps and settings", size = b) {
              if (selectedTab == ApprovedLauncherTab.APPS) appsSearchRequest++ else showSearch = true
            }
            ApprovedHeaderButton("settings", "Settings", size = b, onClick = openSettings)
          }
        } else {
          // The turned Mini (740 dp tall) gives the widgets 12 dp more; LARGE keeps 92 dp.
          Row(Modifier.fillMaxWidth().height(if (currentLayoutClass().compactLandscape) HomeHeaderBudget.COMPACT_HEIGHT.dp else 92.dp)
              .padding(horizontal = HomeHeaderBudget.EDGE.dp),
              verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(HomeHeaderBudget.GAP.dp)) {
            // The leading group owns all the free width, so the trailing buttons always end on the
            // 24 dp edge on every tab. (A fill=false weight beside a weighted Spacer split the free
            // width and left the unused half after Settings: 8 px short on Home, ~100–190 px on
            // Apps and Tools, where Customize is absent.)
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(HomeHeaderBudget.GAP.dp)) {
              if (header.wordmark || header.room) {
                Row(Modifier.weight(1f, fill = false), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                  if (header.wordmark) HearthpaneWordmark(ApprovedHomePalette.Text)
                  if (header.wordmark && header.room)
                    Box(Modifier.padding(horizontal = 12.dp).width(1.dp).height(40.dp).background(ApprovedHomePalette.Border))
                  if (header.room) Text(room, color = ApprovedHomePalette.Secondary, fontSize = 22.sp, maxLines = 1,
                      overflow = TextOverflow.Ellipsis)
                }
              }
              ApprovedHeaderButton("screensaver", "Start screensaver", onClick = onStartScreensaver)
              if (header.musicChip) MusicV3HomeChip { musicResume = true; onOpenMusic() }
            }
            ApprovedHomeIcon("wifi", Modifier.size(22.dp))
            if (header.connectedLabel)
              Text(when (connected) { true -> "Connected"; false -> "Offline"; else -> "Connecting" }, color = ApprovedHomePalette.Secondary, fontSize = 20.sp)
            if (showCustomize)
              ApprovedHomeTextButton(if (editMode) "Done" else "Customize") { editMode = !editMode }
            ApprovedHeaderButton("remote", "Connect your phone") {
              val (url, pin) = enableRemoteAndMintPin(context)
              pairUrl = url; pairPin = pin; showPair = true
            }
            ApprovedHeaderButton("sleep", "Sleep screen") { showSleep = true }
            ApprovedThemeButton()
            hey?.let { pkg ->
              Box(Modifier.size(68.dp).clip(CircleShape).background(ApprovedHomePalette.Chrome).approvedOutline(CircleShape)
                  .semantics { contentDescription = "Voice assistant" }
                  .tvFocusable(CircleShape, onLongClick = { openHeyPicker(context, pkg) }) { fireHey(context, pkg) },
                  contentAlignment = Alignment.Center) { MicGlyph(ApprovedHomePalette.Text) }
            }
            if (jarvisMic) JarvisHeaderButton(68.dp) { triggerJarvis(context) }
            ApprovedHeaderButton("music", "Open music player", lit = rememberMusicV3Loaded(), onClick = onOpenMusic)
            if (header.miniPlayer) ApprovedHomeHeaderPlayer(Modifier.width(HomeHeaderBudget.MINI_PLAYER.dp), onOpenMusic)
            if (homeControl) HomeControlHeaderButton()
            ApprovedHeaderButton("search", "Search apps and settings") {
              if (selectedTab == ApprovedLauncherTab.APPS) appsSearchRequest++ else showSearch = true
            }
            ApprovedHeaderButton("settings", "Settings") {
              runCatching {
                FramePerformanceCapture.launchSettings(context,
                    Intent(context, ImmortalSettingsActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
              }
            }
          }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
        ApprovedHomeTabs(selectedTab, Modifier.fillMaxSize().padding(
            bottom = if (homeTabsMusicLayout == MusicPlayerLayout.COMPACT) 210.dp else 0.dp),
            contentActive = homeTabsMusicLayout == null || homeTabsMusicLayout == MusicPlayerLayout.COMPACT,
            prefetch = LauncherTabPrefetch.HOME_TABS) { tab ->
          when (tab) {
          ApprovedLauncherTab.HOME -> {
          ApprovedHomeWidgetListening(host, enabled = homeWidgets.any { it.isAppWidget })
          // Who's there? may only open the camera while this Home page is really in front.
          WhosThereHomeVisibility(LocalApprovedLauncherPageActive.current && homeTabsMusicLayout != MusicPlayerLayout.FULL && !editMode)
          var whoSheet by remember { mutableStateOf(false) }
          val personal = whosThereHome.personal && !editMode
          val greetWelcome = remember(welcome, whosThereHome) {
            if (whosThereHome.names.isEmpty()) welcome else welcome.copy(userName = whosThereHome.names.joinToString(" & "))
          }
          // Shared notes (Share to Portal): first free page-1 slot, else the greeting-row strip.
          val shareHome = rememberShareHomeState()
          val notePlacement = homeNotePlacement(shareHome.note != null,
              freeSlotOnFirstPage = remember(homeWidgets) { HomeSlots.emptySlots(homeWidgets, 0).isNotEmpty() },
              customizing = editMode)
          Box(Modifier.fillMaxSize()) {
          androidx.compose.runtime.CompositionLocalProvider(
              LocalHomeNote provides (shareHome.takeIf { notePlacement == HomeNotePlacement.SLOT })) {
          Column(Modifier.fillMaxSize().padding(horizontal = LauncherEdge).padding(top = 6.dp)) {
            val homeLayout = currentLayoutClass()
            if (homeLayout.compactPortrait) {
              // Upright Mini: greeting above the clock, the briefing card on its own row.
              Box(Modifier.fillMaxWidth()) {
                ApprovedHomeGreeting(greetWelcome, use24, compact = connected == false, layout = homeLayout)
                WhosThereIndicatorChip(use24, { whoSheet = true }, Modifier.align(Alignment.TopEnd))
              }
              BriefingHomeCard(Modifier.padding(top = 10.dp))
            } else {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                  ApprovedHomeGreeting(greetWelcome, use24, compact = connected == false, layout = homeLayout)
                }
                WhosThereIndicatorChip(use24, { whoSheet = true }, Modifier.padding(start = 24.dp), fixedSlot = true)
                BriefingHomeCard(Modifier.padding(start = 24.dp))
              }
            }
            if (notePlacement == HomeNotePlacement.STRIP) HomeNoteStrip(shareHome, Modifier.fillMaxWidth().padding(top = 8.dp))
            SettingsSyncHomeCard(Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(if (homeLayout.compactLandscape) 8.dp else 16.dp))
            if (connected == false) {
              val dark = com.immortal.launcher.ui.theme.LocalApprovedPalette.current.dark
              Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                  .background(if (dark) Color(0xFF463E2C) else Color(0xFFF6EACF))
                  .padding(horizontal = 23.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Offline. Saved photos remain available.",
                    color = if (dark) Color(0xFFF0D9AA) else Color(0xFF705215), fontSize = 22.sp,
                    modifier = Modifier.weight(1f))
                ApprovedHomeTextButton("Connection help") { context.openApprovedHomeSettings("system") }
              }
              Spacer(Modifier.height(24.dp))
            }
            // The shared widgets stay composed under a personal Home (just not drawn), so returning to
            // the shared Home is a redraw, not a rebuild of every widget.
            val personalNow = rememberUpdatedState(personal)
            Box(Modifier.weight(1f).fillMaxWidth()) {
              CompositionLocalProvider(LocalHomeAddFlow provides addFlow) {
                ApprovedHomePagedWidgets(homeWidgets, editMode, connected == false, host, manager, saveWidgets,
                    onEnterEdit = { editMode = true },
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (personalNow.value) 0f else 1f })
              }
              if (personal) WhosTherePersonalHome(use24, homeLayout.compactPortrait, { whoSheet = true }, Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(6.dp))
            // Slimmer favorites strip (approved Home v2, change 7): 88 → 72dp, icons 56 → 46dp.
            Row(Modifier.fillMaxWidth().height(favoriteTileHeight(homeLayout)),
                horizontalArrangement = Arrangement.spacedBy(if (homeLayout.compactPortrait) 10.dp else 16.dp)) {
              favorites.forEach { app ->
                ApprovedFavoriteTile(app, Modifier.weight(1f), { onLaunch(app.component) }, { appMenu = app },
                    layout = homeLayout)
              }
              repeat(6 - favorites.size) { Spacer(Modifier.weight(1f)) }
            }
          }
          }
          // Household overlays: share toasts / chime bubble and the routine "closed" toast.
          // Bottom toasts centre beside the (draggable) Now Playing card so they never overlap the player.
          val toastSides = Modifier.padding(start = toastInsets.start, end = toastInsets.end)
          ShareHomeToasts(shareHome, Modifier.align(Alignment.BottomCenter).then(toastSides).padding(bottom = 96.dp))
          HomeNoteChimeBubble(Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = LauncherEdge))
          RoutineHomeToast(Modifier.align(Alignment.BottomCenter).then(toastSides).padding(bottom = 24.dp))
          ChoresCelebrationBanner(Modifier.align(Alignment.BottomCenter).then(toastSides))
          ReminderAlertBanner(Modifier.align(Alignment.BottomCenter).then(toastSides))
          MusicV3PausedNote(Modifier.align(Alignment.BottomCenter).then(toastSides).padding(bottom = 24.dp))
          WhosThereHiChip(Modifier.align(Alignment.TopCenter).padding(top = 4.dp))
          if (whoSheet) WhosThereNotYouSheet(onSettings = { context.openApprovedHomeSettings(SettingsPageIds.PEOPLE) },
              onDismiss = { whoSheet = false })
          }
          }
          ApprovedLauncherTab.APPS -> ApprovedAppsScreen(
              onExit = { onSelectTab(ApprovedLauncherTab.HOME) }, embedded = true,
              searchRequest = appsSearchRequest,
              openSearchRequest = appsSearchOpenRequest,
              onOpenMusic = onOpenMusic,
              onSelectTab = { id -> ApprovedLauncherTab.fromId(id)?.let(onSelectTab) })
          ApprovedLauncherTab.TOOLS -> ApprovedToolsScreen(
              onExit = { onSelectTab(ApprovedLauncherTab.HOME) }, embedded = true,
              libraryRequest = toolsLibraryRequest)
          }
        }
        // FULL is music v3, drawn over the whole launcher (own header) after this column.
        if (homeMusicLayout != MusicPlayerLayout.FULL) homeMusicLayout?.let { layout ->
          MusicPlayerHost(layout,
              modifier = if (layout == MusicPlayerLayout.COMPACT)
                Modifier.align(Alignment.BottomCenter).widthIn(max = 720.dp).fillMaxWidth()
                    .padding(horizontal = 24.dp)
              else Modifier.fillMaxSize().padding(horizontal = 32.dp),
              onDismiss = { musicLayout = null }, onLayoutChange = { musicLayout = it })
        }
        // Draggable over the content area only: never onto the header, the favourites or the dock.
        if (musicCard) MusicHomeFloatingPlayer(room, onOpenMusic, sideInset = LauncherEdge,
            bottomInset = favoriteTileHeight(currentLayoutClass()) + 16.dp, modifier = Modifier.matchParentSize())
        // The tile's ✕ paused and closed it: "Paused · Undo" for 5 s, on whichever tab it was closed.
        MusicTileUndoToast(Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
          ApprovedHomeDock(selectedTab.id) { destination ->
            musicLayout = null
            when (destination) {
              "apps" -> { editMode = false; onSelectTab(ApprovedLauncherTab.APPS) }
              "tools" -> {
                editMode = false
                if (selectedTab == ApprovedLauncherTab.TOOLS) toolsLibraryRequest++
                else onSelectTab(ApprovedLauncherTab.TOOLS)
              }
              else -> { editMode = false; onSelectTab(ApprovedLauncherTab.HOME) }
            }
          }
        }
        Spacer(Modifier.height(12.dp))
      }
      MusicFullScreenSlot(musicLayoutState, hidden = homeShownOverMusic) {
        // Music v3 (official YouTube route): its own header; leaving it hands the player to Home's card.
        MusicV3Screen(roomName = room, resumeOnOpen = musicResume,
            onClose = { closeMusic { musicResume = false } }, modifier = Modifier.fillMaxSize())
      }
      addFlow.draft?.let { draft ->
        val fitting = remember(homeWidgets, draft.tapped) { HomeSlots.fittingShapes(homeWidgets, draft.tapped) }
        // Covers the favorites and dock while placing, as approved.
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(ADD_BAR_AREA)
            .background(ApprovedHomePalette.Background), contentAlignment = Alignment.Center) {
          HomeAddShapeBar(draft, fitting, onBack = addFlow::backToPicker,
              onShape = { addFlow.reshape(homeWidgets, it) },
              onAdd = {
                if (draft.providerKey == null) {
                  val widget = HomeWidgetStore.custom(draft.kind)
                  if (addIntoSlot(context, widget, draft.origin, draft.span)) addFlow.added(widget.key)
                  else Toast.makeText(context, "That spot is no longer free. Choose another.",
                      Toast.LENGTH_SHORT).show()
                  homeWidgets = HomeWidgetStore.load(context)
                } else {
                  val provider = providers?.firstOrNull { it.info?.provider?.flattenToString() == draft.providerKey }
                  val info = provider?.info
                  if (provider == null || info == null) {
                    Toast.makeText(context, "That app widget is no longer available.", Toast.LENGTH_SHORT).show()
                    addFlow.draft = null
                  } else {
                    val id = host.allocateAppWidgetId()
                    pending = PendingWidgetAdd.of(id, provider, info, draft.origin, draft.span)
                    if (manager.bindAppWidgetIdIfAllowed(id, info.provider)) configureOrAdd()
                    else runCatching { bindLauncher.launch(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)) }.onFailure { cancelAdd() }
                  }
                }
              })
        }
      }
      addFlow.pickerSlot?.let { slot ->
        val advanced = LocalAdvancedSettings.current
        val occupied = remember(homeWidgets, slot.page) {
          val empty = HomeSlots.emptySlots(homeWidgets, slot.page).map { it.column to it.row }.toSet()
          (0..3).flatMap { c -> (0..1).map { r -> c to r } }.filterNot { it in empty }.toSet()
        }
        val more = remember(advanced, homeWidgets) {
          HomeWidgetPicker.more(advanced, HomeWidgetPicker.photoMemoryAllowed(homeWidgets))
        }
        val providerChoices = remember(providers) {
          providers?.mapNotNull { entry ->
            entry.info?.let { HomePickerChoice(it.provider.flattenToString(), entry.label, entry.icon, entry.packageLabel) }
          }
        }
        HomeWidgetPickerSheet(slot, occupied, addFlow.slotSize, HomeWidgetPicker.everyday, more, providerChoices,
            HomeWidgetPreviewRenderer,
            onKind = { kind -> addFlow.choose(homeWidgets, kind, null, HomeWidgetPicker.label(kind), null) },
            onProvider = { choice ->
              val entry = providers?.firstOrNull { it.info?.provider?.flattenToString() == choice.key }
              val natural = entry?.let { HomePresentation.slotSpan(it.spanX, it.spanY) }?.let { it.columns to it.rows }
              addFlow.choose(homeWidgets, HomeWidgetStore.KIND_APP_WIDGET, choice.key, choice.label, choice.image, natural)
            },
            onDismiss = { addFlow.pickerSlot = null })
      }
      if (showLibrary) ApprovedWidgetLibrary(LocalAdvancedSettings.current, homeWidgets, providers, room, connected,
          onAdd = { kind ->
            val spanY = if (kind in listOf(HomeWidgetStore.KIND_TODAY, HomeWidgetStore.KIND_QUICK_CONTROLS,
                HomeWidgetStore.KIND_PHOTO_MEMORY)) 1 else 2
            homeWidgets = HomeWidgetStore.add(context, HomeWidgetStore.custom(kind, spanY = spanY))
            showLibrary = false
          }, onProvider = { provider ->
            val info = provider.info
            if (info != null) {
              val id = host.allocateAppWidgetId()
              pending = PendingWidgetAdd.of(id, provider, info)
              if (manager.bindAppWidgetIdIfAllowed(id, info.provider)) configureOrAdd()
              else runCatching { bindLauncher.launch(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                  .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                  .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)) }.onFailure { cancelAdd() }
            }
          }, onDismiss = { showLibrary = false },
          onHome = { showLibrary = false; editMode = false },
          onCustomize = { showLibrary = false; editMode = true },
          onRefresh = { ApprovedHomeInventory.invalidate() },
          onPair = {
            showLibrary = false
            val (url, pin) = enableRemoteAndMintPin(context)
            pairUrl = url; pairPin = pin; showPair = true
          },
          onSleep = { showLibrary = false; showSleep = true },
          onSearch = { showLibrary = false; showSearch = true },
          onSettings = { showLibrary = false; context.launchSafely(ImmortalSettingsActivity::class.java) })
      if (showPair) ApprovedHomeDialog("Control from your phone", { showPair = false }) {
        Text("Scan with a phone on the same Wi-Fi, or open the address and enter the code.",
            color = ApprovedHomePalette.Secondary, fontSize = 23.sp, lineHeight = 32.sp)
        RemotePairCard(pairUrl, pairPin)
        PairDoneButton { showPair = false }
      }
      if (showSleep) ApprovedHomeDialog("Sleep screen", { showSleep = false }) {
        Text(if (ScreenControl.isAdminActive(context)) "Turn off the display. Tap the screen to wake it."
            else "Screen-off permission needs setup. Open device settings for help.",
            color = ApprovedHomePalette.Secondary, fontSize = 23.sp)
        ApprovedHomeTextButton(if (ScreenControl.isAdminActive(context)) "Sleep now" else "Device settings") {
          showSleep = false
          if (ScreenControl.isAdminActive(context)) ScreenControl.sleep(context)
          else context.launchSafely(DeviceHealthActivity::class.java)
        }
      }
      if (showSearch) ApprovedHomeDialog("Search apps and settings", { showSearch = false }) {
        androidx.compose.material3.OutlinedTextField(value = search, onValueChange = { search = it },
            singleLine = true, placeholder = { Text("Search apps") }, modifier = Modifier.fillMaxWidth())
        val results = remember(orderedApps, search) { orderedApps.filter { it.label.contains(search.trim(), true) } }
        Text("Apps", color = ApprovedHomePalette.Secondary, fontSize = 22.sp)
        results.take(6).forEach { app ->
          ApprovedHomeTextButton(app.label, Modifier.fillMaxWidth()) { showSearch = false; onLaunch(app.component) }
        }
        if (results.size > 6) ApprovedHomeTextButton("All apps") {
          showSearch = false
          onSelectTab(ApprovedLauncherTab.APPS)
        }
        val index = remember { approvedSettingsSearchIndex() }
        val settingsResults = remember(index, search) { approvedSettingsSearch(index, search) }
        val advanced = LocalAdvancedSettings.current
        Text("Settings", color = ApprovedHomePalette.Secondary, fontSize = 22.sp)
        settingsResults.forEach { result ->
          ApprovedHomeTextButton(if (result.advanced) "Advanced · ${result.title}" else result.title,
              Modifier.fillMaxWidth()) {
            showSearch = false
            if (result.advanced && !advanced) pendingSetting = result
            else runCatching { context.startActivity(approvedSettingsIntent(context, result.page, result.id.ifBlank { null })) }
          }
        }
        if (search.isBlank()) ApprovedHomeTextButton("All settings") {
          showSearch = false
          context.launchSafely(ImmortalSettingsActivity::class.java)
        }
      }
      pendingSetting?.let { result ->
        ApprovedHomeDialog("Advanced settings", { pendingSetting = null; searchApplyError = null }) {
          Text("Turn on Advanced settings to open ${result.title}. Your existing configuration stays in place.",
              color = ApprovedHomePalette.Secondary, fontSize = 23.sp, lineHeight = 32.sp)
          searchApplyError?.let {
            Text(it, color = com.immortal.launcher.ui.theme.LocalApprovedPalette.current.error, fontSize = 22.sp)
          }
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            ApprovedHomeTextButton("Cancel") { pendingSetting = null; searchApplyError = null }
            ApprovedHomeTextButton("Turn on Advanced settings") {
              val applied = com.immortal.launcher.settings.SettingsDomains.immortal.apply(context,
                  JSONObject().put("advancedSettings", true))
              if ("advancedSettings" in applied) {
                pendingSetting = null; searchApplyError = null
                runCatching { context.startActivity(approvedSettingsIntent(context, result.page, result.id.ifBlank { null })) }
              } else searchApplyError = "Advanced settings could not be changed. Try again."
            }
          }
        }
      }
      appMenu?.let { app ->
        ApprovedAppActions(app, assignments, favorites.any { it.component == app.component },
            onLaunch = { appMenu = null; onLaunch(app.component) },
            onFavorite = {
              val id = app.component.flattenToString()
              val all = orderedApps.map { it.component.flattenToString() }.filterNot { it == id }
              saveAppOrder(if (favorites.any { it.component == app.component }) all + id else listOf(id) + all)
              appMenu = null
            }, onFolder = { target ->
              saveAssignments(assignments + (app.component.packageName to target))
              appMenu = null
            }, onUninstall = { appMenu = null; onUninstall(app.component.packageName) },
            onDismiss = { appMenu = null })
      }
    }
  }

}
