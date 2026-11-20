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
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.immortal.launcher.ui.components.GlassCard
import com.immortal.launcher.ui.components.Glass
import com.immortal.launcher.ui.components.OnResume
import com.immortal.launcher.ui.components.launchSafely
import com.immortal.launcher.ui.components.pollingState
import com.immortal.launcher.ui.theme.LocalAdvancedSettings
import com.immortal.launcher.ui.theme.ContentSecondary
import com.immortal.launcher.ui.theme.PrimeBlue
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import org.json.JSONObject

private data class PendingWidgetAdd(
    val appWidgetId: Int,
    /** The provider's flattened component; the bound id's own info is read back when completing. */
    val provider: String,
    val spanX: Int,
    val spanY: Int,
    /** Add-flow target: saved exactly here when it still fits, else auto-placed. */
    val slot: HomeWidgetStore.Slot? = null,
    val span: Pair<Int, Int>? = null,
) {
  companion object {
    fun of(id: Int, entry: WidgetProviderEntry, info: AppWidgetProviderInfo,
           slot: HomeWidgetStore.Slot? = null, span: Pair<Int, Int>? = null) =
        PendingWidgetAdd(id, info.provider.flattenToString(), entry.spanX, entry.spanY, slot, span)

    /**
     * Saved across a recreate or process death while the system bind/configure screen is in
     * front, so its result still completes the add (and a cancel still frees the allocated id).
     */
    val Saver = androidx.compose.runtime.saveable.listSaver<PendingWidgetAdd?, Any>(
        save = { p -> if (p == null) emptyList() else listOf(p.appWidgetId, p.provider, p.spanX, p.spanY,
            p.slot?.page ?: -1, p.slot?.column ?: -1, p.slot?.row ?: -1, p.span?.first ?: -1, p.span?.second ?: -1) },
        restore = { v -> if (v.size < 9) null else PendingWidgetAdd(v[0] as Int, v[1] as String, v[2] as Int, v[3] as Int,
            (v[4] as Int).takeIf { it >= 0 }?.let { HomeWidgetStore.Slot(it, v[5] as Int, v[6] as Int) },
            (v[7] as Int).takeIf { it >= 0 }?.let { it to (v[8] as Int) }) })
  }
}

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

/**
 * The mock's hero card: a 56sp thin clock over "Good evening, Name", with the current temp
 * (26sp light) + condition bottom-right. Ticks by the minute; honors the 12/24h setting.
 */
@Composable
private fun HeroClockCard(
    greetingWord: String,
    userName: String,
    weather: Weather.Current?,
    modifier: Modifier = Modifier,
) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val use24 = remember { ImmortalSettings.use24HourClock(context) }
  val fmt = remember(use24) { SimpleDateFormat(if (use24) "H:mm" else "h:mm", Locale.getDefault()) }
  var now by remember { mutableStateOf(Date()) }
  LaunchedEffect(Unit) {
    while (true) {
      now = Date()
      delay(15_000)
    }
  }
  LiquidGlassWidget(modifier = modifier) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
      Column(Modifier.weight(1f)) {
        Text(
            fmt.format(now),
            color = Color.White,
            fontSize = 56.sp,
            fontWeight = FontWeight.ExtraLight,
            letterSpacing = (-2.5).sp,
            lineHeight = 56.sp,
        )
        Text(
            if (userName.isNotBlank()) "$greetingWord, $userName" else greetingWord,
            color = Color.White.copy(alpha = 0.66f),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 2.dp),
        )
      }
      if (weather != null) {
        Column(horizontalAlignment = Alignment.End) {
          Text(
              "${weather.temp}°",
              color = Color.White,
              fontSize = 26.sp,
              fontWeight = FontWeight.Light,
          )
          Text(
              weatherConditionText(weather.code),
              color = Color.White.copy(alpha = 0.6f),
              fontSize = 12.5.sp,
              maxLines = 1,
          )
        }
      }
    }
  }
}

/** One Add-Widget gallery card's metadata: store kind + display name/blurb/glyph/accent. */
private data class GalleryEntry(
    val kind: String, val label: String, val desc: String, val glyph: String, val accent: Color)

private typealias HomeWidgetChange =
    (List<HomeWidgetStore.HomeWidget>, List<HomeWidgetStore.HomeWidget>) -> Boolean

/** A stable cell boundary keeps cached widget content out of paging recompositions. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ApprovedWidgetCell(
    widget: HomeWidgetStore.HomeWidget,
    cell: Modifier,
    widgets: List<HomeWidgetStore.HomeWidget>,
    editMode: Boolean,
    host: AppWidgetHost,
    manager: AppWidgetManager,
    onChanged: HomeWidgetChange,
    onEnterEdit: () -> Unit,
    onRemove: (HomeWidgetStore.HomeWidget) -> Unit,
    displaySpan: Pair<Int, Int> = widget.spanX to widget.spanY,
    onMove: () -> Unit = {},
    dragHandle: Modifier = Modifier,
    gripHandle: Modifier = Modifier,
) {
    androidx.compose.runtime.CompositionLocalProvider(LocalWidgetEditing provides editMode) {
    key(widget.key) {
      val context = LocalContext.current
      var showTimers by remember { mutableStateOf(false) }
      var widthPx by remember { mutableStateOf(0) }
      var heightPx by remember { mutableStateOf(0) }
      var resizeAccum by remember { mutableStateOf(Offset.Zero) }
      var startSpan by remember { mutableStateOf(widget.spanX to widget.spanY) }
      var resizeSnapshot by remember { mutableStateOf<List<HomeWidgetStore.HomeWidget>?>(null) }
      var resizeDraft by remember { mutableStateOf<Pair<Int, Int>?>(null) }
      var columnUnit by remember { mutableStateOf(1f) }
      var rowUnit by remember { mutableStateOf(1f) }
      val density = androidx.compose.ui.platform.LocalDensity.current
      val gapPx = with(density) { 24.dp.toPx() }
      val resizeRtl = androidx.compose.ui.platform.LocalLayoutDirection.current ==
          androidx.compose.ui.unit.LayoutDirection.Rtl
      LaunchedEffect(editMode) { resizeDraft = null; resizeSnapshot = null; resizeAccum = Offset.Zero }
      val compactLegend = currentLayoutClass().compact
      Box(cell.zIndex(if (resizeDraft != null) 2f else 0f)
          .onSizeChanged { widthPx = it.width; heightPx = it.height }
          .semantics { contentDescription = "${if (widget.isAppWidget) "App" else customWidgetLabel(widget.kind)} widget" }) {
        ApprovedHomeSurface(Modifier.fillMaxSize().combinedClickable(
            enabled = !editMode, onClick = {}, onLongClick = onEnterEdit)
            .then(if (editMode) Modifier.clearAndSetSemantics { } else Modifier)) {
          if (widget.isAppWidget) {
            ApprovedHostedWidget(widget, host, manager, editMode)
          } else {
            ImmortalWidgetContent(widget, Modifier.fillMaxSize(), onEnterEdit = onEnterEdit)
          }
        }
        if (editMode) {
          // Own the editing gesture above widget content, whose click/scroll handlers
          // otherwise compete with moving the card.
          Box(Modifier.matchParentSize().then(dragHandle))
          ApprovedWidgetDragGrip(gripHandle, Modifier.align(Alignment.TopEnd).padding(10.dp))
          val onSize: () -> Unit = {
              // Cycle to the next size that fits here; neighbours never get covered or pushed.
              var next = displaySpan
              var resized: List<HomeWidgetStore.HomeWidget>? = null
              repeat(4) {
                if (resized == null) {
                  next = HomeWidgetStore.nextWidgetSpan(next)
                  resized = HomeSlots.resizeForEditing(widgets, widget.key, next)
                }
              }
              resized?.let { onChanged(widgets, it) }
                  ?: context.showHomeLayoutRefusal("No room to resize here. Move it or free a spot first.")
            }
          val onSettings: () -> Unit = {
              when {
                widget.kind == HomeWidgetStore.KIND_TIMERS -> showTimers = true
                widget.isAppWidget -> {
                  val config = manager.getAppWidgetInfo(widget.appWidgetId)?.configure
                  val intent = if (config != null) Intent().setComponent(config)
                      .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widget.appWidgetId)
                      else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                          Uri.parse("package:${widget.providerPackage}"))
                  runCatching { context.startApprovedInternalActivity(intent) }.onFailure {
                    Toast.makeText(context, "Widget settings couldn’t be opened.", Toast.LENGTH_SHORT).show()
                  }
                }
                else -> context.openApprovedHomeSettings(when (widget.kind) {
                  HomeWidgetStore.KIND_WORLD_CLOCK -> "world"
                  HomeWidgetStore.KIND_TODAY -> "calendar"
                  HomeWidgetStore.KIND_PHOTO_MEMORY -> "sources"
                  HomeWidgetStore.KIND_QUICK_CONTROLS -> "quickbar"
                  else -> widget.kind
                })
              }
            }
          val legend = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
              .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
              .background(ApprovedHomePalette.Card).padding(start = 6.dp, top = 6.dp, bottom = 6.dp, end = 58.dp)
          if (!compactLegend) {
            Row(legend, horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
              ApprovedWidgetAction("Move", Modifier.weight(1f), onClick = onMove)
              ApprovedWidgetAction("Size", Modifier.weight(1f), onClick = onSize)
              ApprovedWidgetAction("Settings", Modifier.weight(1f), onClick = onSettings)
              ApprovedWidgetAction("Remove", Modifier.weight(1f)) { onRemove(widget) }
            }
          } else {
            // Narrow cards (compact Customize): two rows of two, so no label is cut to a letter.
            Column(legend) {
              Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ApprovedWidgetAction("Move", Modifier.weight(1f), onClick = onMove)
                ApprovedWidgetAction("Size", Modifier.weight(1f), onClick = onSize)
              }
              Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ApprovedWidgetAction("Settings", Modifier.weight(1f), onClick = onSettings)
                ApprovedWidgetAction("Remove", Modifier.weight(1f)) { onRemove(widget) }
              }
            }
          }
          resizeDraft?.let { span ->
            Canvas(Modifier.matchParentSize()) {
              drawRoundRect(ApprovedHomeTabPalette.accent,
                  size = Size(span.first * columnUnit - gapPx, span.second * rowUnit - gapPx),
                  cornerRadius = androidx.compose.ui.geometry.CornerRadius(20.dp.toPx()),
                  style = Stroke(3.dp.toPx()))
            }
          }
          ResizeBadge(Modifier.align(Alignment.BottomEnd), onResizeStart = {
            startSpan = displaySpan
            resizeSnapshot = widgets
            resizeAccum = Offset.Zero
            resizeDraft = displaySpan
            columnUnit = ((widthPx - gapPx * (displaySpan.first - 1)) / displaySpan.first).coerceAtLeast(1f) + gapPx
            rowUnit = ((heightPx - gapPx * (displaySpan.second - 1)) / displaySpan.second).coerceAtLeast(1f) + gapPx
          }, onResizeDrag = { delta ->
            resizeAccum += Offset(if (resizeRtl) -delta.x else delta.x, delta.y)
            fun steps(value: Float, unit: Float): Int = (kotlin.math.sign(value) *
                kotlin.math.floor(kotlin.math.abs(value / unit) + .35f)).toInt()
            val x = startSpan.first + steps(resizeAccum.x, columnUnit)
            val y = startSpan.second + steps(resizeAccum.y, rowUnit)
            // Corner drag snaps to whole slots of the 2-row × 4-column grid.
            val slot = HomePresentation.slotSpan(x, y)
            resizeDraft = slot.columns to slot.rows
          }, onResizeEnd = {
            resizeDraft?.takeIf { it != startSpan }?.let { span ->
              resizeSnapshot?.let { expected ->
                HomeSlots.resizeForEditing(expected, widget.key, span)?.let { onChanged(expected, it) }
                    ?: context.showHomeLayoutRefusal("No room to resize here. Move it or free a spot first.")
              }
            }
            resizeDraft = null; resizeSnapshot = null; resizeAccum = Offset.Zero
          }, onResizeCancel = { resizeDraft = null; resizeSnapshot = null; resizeAccum = Offset.Zero })
        }
      }
      if (showTimers) ApprovedHomeTimersPage { showTimers = false }
    }
    }
}

/**
 * Customize-only drag grip: touching it and moving starts the drag at once, with no long press.
 * A long press anywhere on the card still works; the grip is the reliable, discoverable handle.
 */
@Composable
private fun ApprovedWidgetDragGrip(drag: Modifier, modifier: Modifier = Modifier) {
  val dots = ApprovedHomePalette.Text
  Box(modifier.size(64.dp).clip(CircleShape).background(ApprovedHomePalette.Chrome).approvedOutline(CircleShape)
      .semantics { contentDescription = "Drag to move" }.then(drag),
      contentAlignment = Alignment.Center) {
    Canvas(Modifier.size(26.dp)) {
      val r = 2.6.dp.toPx()
      for (column in 0..1) for (row in 0..2) {
        drawCircle(dots, r, androidx.compose.ui.geometry.Offset(
            size.width * (.32f + column * .36f), size.height * (.18f + row * .32f)))
      }
    }
  }
}

@Composable
private fun ApprovedWidgetAction(label: String, modifier: Modifier, onClick: () -> Unit) {
  Box(modifier.heightIn(min = 52.dp)
      .tvFocusable(RoundedCornerShape(12.dp), focusScale = 1f, onClick = onClick)
      .padding(horizontal = 6.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
    Text(label, color = ApprovedHomePalette.Accent, fontSize = 18.sp, lineHeight = 26.sp, maxLines = 1,
        overflow = TextOverflow.Ellipsis)
  }
}

@Composable
private fun ApprovedWidgetGrid(
    visible: List<HomeWidgetStore.HomeWidget>,
    gridModifier: Modifier,
    widgets: List<HomeWidgetStore.HomeWidget>,
    editMode: Boolean,
    host: AppWidgetHost,
    manager: AppWidgetManager,
    onChanged: HomeWidgetChange,
    onEnterEdit: () -> Unit,
    onRemove: (HomeWidgetStore.HomeWidget) -> Unit,
    pageIndex: Int,
    fixed: List<ApprovedWidgetPacking.Placement>?,
) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val spans = remember(visible) { HomePresentation.spans(visible) }
  var move by remember { mutableStateOf<HomeWidgetStore.HomeWidget?>(null) }
  val addFlow = LocalHomeAddFlow.current
  val emptySlots = remember(widgets, pageIndex, editMode) {
    if (editMode && addFlow != null) HomeSlots.emptySlots(widgets, pageIndex) else emptyList()
  }
  val addDecor: (@Composable BoxScope.(ApprovedSlotGeometry) -> Unit)? =
      if (addFlow == null || (!editMode && addFlow.ring == null)) null
      else { geometry -> HomeAddDecor(geometry, addFlow, pageIndex, emptySlots, HomeWidgetPreviewRenderer) }
  // A shared note (Share to Portal) takes the first empty slot on page 1 outside Customize.
  val note = LocalHomeNote.current
  val noteSlot = remember(widgets, pageIndex, editMode, note?.note?.id) {
    if (note == null || editMode || pageIndex != 0) null else HomeSlots.emptySlots(widgets, 0).firstOrNull()
  }
  val decor: (@Composable BoxScope.(ApprovedSlotGeometry) -> Unit)? =
      if (addDecor == null && noteSlot == null) null
      else { geometry ->
        addDecor?.invoke(this, geometry)
        if (note != null && noteSlot != null) {
          HomeNoteCard(note, geometry.cell(noteSlot.column, noteSlot.row * 2, 1, 2))
        }
      }
  ApprovedPackedWidgetGrid(visible, spans, fixed, editMode, gridModifier, decor = decor,
      reservedLeftHalfRows = noteSlot?.takeIf { it.column < CompactWidgetGrid.FOLDED_COLUMNS }?.let { it.row * 2 + 2 } ?: 0,
      canDrop = { key, column, row ->
        HomeSlots.moveForEditing(widgets, key, HomeWidgetStore.Slot(pageIndex, column, row)) != null
      },
      onDrop = { key, column, row ->
        val moved = HomeSlots.moveForEditing(widgets, key, HomeWidgetStore.Slot(pageIndex, column, row))
        if (moved == null) context.showHomeLayoutRefusal("That spot is taken. Move or resize the other widget first.")
        else if (moved != widgets) onChanged(widgets, moved)
      }) { widget, cell, span, drag, grip ->
    ApprovedWidgetCell(widget, cell, widgets, editMode, host, manager, onChanged, onEnterEdit,
        onRemove, span.columns to span.rows, onMove = { move = widget }, dragHandle = drag, gripHandle = grip)
  }
  move?.let { selected ->
    val spots = remember(selected, widgets) { HomeSlots.freeSpotsForEditing(widgets, selected.key) }
    val pages = remember(widgets) { HomePresentation.page(HomeSlots.forEditing(widgets), 0).count }
    ApprovedHomeDialog("Move ${customWidgetLabel(selected.kind)}", onDismiss = { move = null }) {
      Text(if (spots.isEmpty()) "No empty spot fits this size. Make it smaller or free a spot first."
          else "Choose an empty spot, including on another page.",
          color = ApprovedHomePalette.Secondary, fontSize = 23.sp)
      spots.forEach { spot ->
        val label = if (spot.page >= pages) "New page · row ${spot.row + 1} · column ${spot.column + 1}"
            else "Page ${spot.page + 1} · row ${spot.row + 1} · column ${spot.column + 1}"
        ApprovedHomeTextButton(label) {
          HomeSlots.moveForEditing(widgets, selected.key, spot)?.let { onChanged(widgets, it) }
          move = null
        }
      }
    }
  }
}

/** Short, non-blocking explanation when Customize refuses a move or resize that would overlap. */
private fun android.content.Context.showHomeLayoutRefusal(message: String) {
  android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
}

/** Home and Customize keep the same page, identities, measurements and placement rules. */
@Composable
private fun ApprovedWidgetPage(
    displayed: HomePresentation.Page,
    widgets: List<HomeWidgetStore.HomeWidget>,
    host: AppWidgetHost,
    manager: AppWidgetManager,
    onChanged: HomeWidgetChange,
    onEnterEdit: () -> Unit,
    onRemove: (HomeWidgetStore.HomeWidget) -> Unit,
    editMode: Boolean = false,
) {
  val pageWidgets = remember(displayed, widgets) { HomePresentation.pageWidgets(widgets, displayed) }
  ApprovedWidgetGrid(pageWidgets, Modifier.fillMaxSize(), widgets, editMode, host, manager,
      onChanged, onEnterEdit, onRemove, pageIndex = displayed.index, fixed = displayed.placements)
}

/** Bounded pages share the same grid in Home and Customize. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ApprovedWidgetArea(
    widgets: List<HomeWidgetStore.HomeWidget>,
    page: HomePresentation.Page,
    editMode: Boolean,
    host: AppWidgetHost,
    manager: AppWidgetManager,
    onChanged: HomeWidgetChange,
    onEnterEdit: () -> Unit,
    onPageChanged: (Int) -> Unit,
    navigation: ApprovedHomePagerNavigation,
    preserveCounterFocus: Boolean,
    modifier: Modifier = Modifier,
    extraPage: Boolean = false,
) {
  var remove by remember { mutableStateOf<HomeWidgetStore.HomeWidget?>(null) }
  val onRemove: (HomeWidgetStore.HomeWidget) -> Unit = remember { { remove = it } }
  val configuration = androidx.compose.ui.platform.LocalConfiguration.current
  val isTv = configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK ==
      android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
  if (!isTv) {
    val prepared = remember(widgets, extraPage) {
      List(HomePresentation.editingPage(widgets, 0, extraPage).count) {
        HomePresentation.editingPage(widgets, it, extraPage)
      }
    }
    val placing = LocalHomeAddFlow.current?.draft != null
    val homeActive = LocalApprovedLauncherPageActive.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val currentPage = rememberUpdatedState(page)
    val pageChanged = rememberUpdatedState(onPageChanged)
    val slide = remember { Animatable(page.index.toFloat()) }
    // The page the last slide finished on. Page work (producers, lifecycle, resume reads) and the
    // next neighbor's composition follow it, so they start after the slide, not in its first frame.
    var settled by remember { mutableIntStateOf(page.index) }
    val swipeThreshold = with(androidx.compose.ui.platform.LocalDensity.current) { 64.dp.toPx() }
    LaunchedEffect(page.index, page.count) {
      if (!preserveCounterFocus) focus.clearFocus(force = true)
      // Removed widgets or numbered Move can skip pages outside the retained neighbors.
      if (slide.value > prepared.lastIndex || abs(slide.value - page.index) > 1f)
        slide.snapTo(page.index.toFloat())
      else slide.animateTo(page.index.toFloat(), tween(180, easing = EaseOutCubic))
      settled = page.index
    }
    BoxWithConstraints(modifier.clipToBounds().semantics { contentDescription = "Home widgets" }
        .then(if (!homeActive || page.count < 2 || placing) Modifier else Modifier.pointerInput(page.count) {
          var distance = 0f
          detectHorizontalDragGestures(
              onDragStart = { distance = 0f },
              onDragCancel = { distance = 0f },
              onDragEnd = {
                if (abs(distance) >= swipeThreshold) {
                  val selected = currentPage.value
                  val next = HomePresentation.pageAfterSwipe(selected, swipeRight = distance > 0f)
                  if (next != selected.index) pageChanged.value(next)
                }
                distance = 0f
              },
          ) { change, delta ->
            change.consume()
            distance += delta
          }
        })) {
      val widthPx = with(androidx.compose.ui.platform.LocalDensity.current) { maxWidth.toPx() }
      for (index in HomePagerWindow.composed(page.index, settled, prepared.lastIndex)) key(index) {
        val displayed = prepared[index]
        val selected = index == page.index
        // Input, focus and semantics follow the target at once; page work waits for the settle.
        val active = homeActive && selected
        val working = homeActive && HomePagerWindow.working(index, settled)
        // Deferred placement caches the display list; the viewport clips off-screen pages.
        Box(Modifier.fillMaxSize().absoluteOffset {
          IntOffset((HomePresentation.pageOffset(index, slide.value) * widthPx).roundToInt(), 0)
        }.then(if (active) Modifier else Modifier.clearAndSetSemantics { })
            .pointerInput(active) {
              if (!active) awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
              }
            }
            .focusProperties { canFocus = active }) {
          ApprovedWidgetPageScope(working) {
            ApprovedWidgetPage(displayed, widgets, host, manager, onChanged,
                onEnterEdit, onRemove, editMode && selected)
          }
        }
      }
    }
  } else {
    val pager = rememberPagerState(initialPage = page.index) { page.count }
    SideEffect { navigation.reflectParent(page.index) }
    LaunchedEffect(page.index) {
      if (pager.currentPage != page.index) pager.scrollToPage(page.index)
      navigation.acknowledge(pager.currentPage)
    }
    val pageChanged = rememberUpdatedState(onPageChanged)
    LaunchedEffect(pager) {
      snapshotFlow { pager.currentPage }.drop(1).collect { index ->
        if (index == pager.currentPage)
          navigation.observe(index)?.let { pageChanged.value(it) }
      }
    }
    HorizontalPager(pager, modifier.clipToBounds().semantics { contentDescription = "Home widgets" }, userScrollEnabled = false,
        beyondViewportPageCount = 1) { index ->
      val active = LocalApprovedLauncherPageActive.current && index == pager.currentPage
      ApprovedWidgetPageScope(active) {
        ApprovedWidgetPage(HomePresentation.editingPage(widgets, index, extraPage), widgets, host, manager,
            onChanged, onEnterEdit, onRemove, editMode && active)
      }
    }
  }
  remove?.let { widget ->
    ApprovedHomeDialog("Remove ${customWidgetLabel(widget.kind)}?", onDismiss = { remove = null }) {
      Text("You can add it again from the widget library.", color = ApprovedHomePalette.Secondary, fontSize = 23.sp)
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        ApprovedHomeTextButton("Cancel") { remove = null }
        ApprovedHomeTextButton("Remove") {
          synchronized(RemoteCompanionStore.mutationLock) {
            if (onChanged(widgets, HomeWidgetStore.without(widgets, widget.key)) && widget.isAppWidget)
              runCatching { host.deleteAppWidgetId(widget.appWidgetId) }
          }
          remove = null
        }
      }
    }
  }
}

@Composable
private fun ApprovedHostedWidget(
    widget: HomeWidgetStore.HomeWidget,
    host: AppWidgetHost,
    manager: AppWidgetManager,
    editing: Boolean,
) {
  val active = LocalApprovedLauncherPageActive.current && LocalApprovedWidgetPageInput.current
  val info = remember(widget.appWidgetId) { manager.getAppWidgetInfo(widget.appWidgetId) }
  if (info == null) {
    Column(Modifier.padding(30.dp)) {
      Text("Widget unavailable", color = ApprovedHomePalette.Text, fontSize = 26.sp, lineHeight = 35.sp)
      Text(widget.providerPackage, color = ApprovedHomePalette.Secondary, fontSize = 22.sp,
          lineHeight = 30.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(top = 12.dp))
    }
  } else {
    BoxWithConstraints(Modifier.fillMaxSize()) {
      val width = maxWidth.value.roundToInt()
      val height = maxHeight.value.roundToInt()
      LaunchedEffect(widget.appWidgetId, width, height) {
        manager.updateAppWidgetOptions(widget.appWidgetId, Bundle().apply {
          putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
          putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width)
          putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
          putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
        })
      }
      AndroidView(factory = { host.createView(it, widget.appWidgetId, info).apply {
        setAppWidget(widget.appWidgetId, info)
        setPadding(0, 0, 0, 0)
      } }, update = {
        it.isEnabled = active && !editing
        it.descendantFocusability = if (active && !editing) android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
            else android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        if (!active) it.findFocus()?.clearFocus()
      }, modifier = Modifier.fillMaxSize())
      if (editing) Box(Modifier.fillMaxSize().background(Color(0x22000000)))
    }
  }
}

@Composable
internal fun ApprovedHomeDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
  if (!LocalApprovedLauncherPageActive.current || !LocalApprovedWidgetPageInput.current) return
  androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss,
      properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
    CompositionLocalProvider(LocalApprovedHomePopup provides true) {
    ApprovedHomeSurface(Modifier.widthIn(max = 880.dp).fillMaxWidth(.8f).heightIn(max = 1096.dp), radius = 28.dp) {
      Column(Modifier.padding(36.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Text(title, color = ApprovedHomePalette.Text, fontSize = 32.sp, lineHeight = 42.sp,
              fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
          ApprovedHomeTextButton("Close", onClick = onDismiss)
        }
        content()
      }
    }
    }
  }
}

/** Paging state is local: changing pages does not invalidate the launcher chrome or favorites. */
@Composable
private fun ApprovedHomePagedWidgets(
    widgets: List<HomeWidgetStore.HomeWidget>,
    editMode: Boolean,
    compact: Boolean,
    host: AppWidgetHost,
    manager: AppWidgetManager,
    onChanged: HomeWidgetChange,
    onEnterEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
  var pageIndex by remember { mutableStateOf(0) }
  var counterFocused by remember { mutableStateOf(false) }
  val navigation = remember { ApprovedHomePagerNavigation() }
  val addFlow = LocalHomeAddFlow.current
  val savedPages = remember(widgets) { HomePresentation.page(widgets, 0).count }
  val targetPage = addFlow?.targetPage
  // An empty page after the last while adding there ("+ new page" or a full layout).
  val extraPage = editMode && addFlow != null && (addFlow.newPage || (targetPage ?: -1) >= savedPages)
  val page = remember(widgets, pageIndex, extraPage) { HomePresentation.editingPage(widgets, pageIndex, extraPage) }
  LaunchedEffect(page.index) { pageIndex = page.index }
  val changePage: (Int) -> Unit = { index ->
    navigation.request(index)
    pageIndex = index
  }
  // The draft is placed on its target page: follow it there.
  LaunchedEffect(targetPage, extraPage) { if (targetPage != null && targetPage != pageIndex) changePage(targetPage) }
  val pageFull = remember(widgets, page.index, editMode) {
    editMode && addFlow != null && HomeSlots.emptySlots(widgets, page.index).isEmpty()
  }
  Column(modifier) {
    ApprovedWidgetArea(widgets, page, editMode, host, manager, onChanged,
        onEnterEdit = onEnterEdit, onPageChanged = changePage, navigation = navigation,
        preserveCounterFocus = counterFocused,
        modifier = Modifier.weight(1f).fillMaxWidth(), extraPage = extraPage)
    // A slim row under the widgets for page dots (and Add widget while customizing). No heading or
    // "All apps" — the dock's Apps tab already does that. The row is omitted when it would be empty.
    val showDots = HomeRefinements.showsPageIndicator(page.count)
    if (showDots || editMode) {
      Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(if (editMode) 52.dp else 40.dp)) {
        if (editMode) ApprovedHomeTextButton("Add widget", modifier = Modifier.align(Alignment.CenterStart)) {
          // Same picker as tapping a slot: the first empty slot here, or a new page when full.
          addFlow?.openPicker(HomeSlots.firstEmptySlot(widgets, page.index))
        }
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
          if (showDots) ApprovedWidgetPageIndicator(page.index, page.count, changePage,
              onFocusChanged = { counterFocused = it })
          // Fallback (approved): a full page offers "+ new page" after the last dot.
          if (pageFull && addFlow?.draft == null) ApprovedHomeTextButton("+ New page") {
            addFlow?.newPage = true
            changePage(savedPages)
          }
        }
      }
    } else {
      Spacer(Modifier.height(10.dp))
    }
  }
}

/** Page dots plus "n of m"; focusable with Left/Right and accessibility actions to change page. */
@Composable
private fun ApprovedWidgetPageIndicator(
    index: Int,
    count: Int,
    changePage: (Int) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
  Row(modifier, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
    Row(Modifier.clip(RoundedCornerShape(18.dp)).semantics {
          contentDescription = "Widget page ${index + 1} of $count"
          customActions = buildList {
            if (index > 0) add(CustomAccessibilityAction("Previous widget page") {
              changePage(index - 1); true
            })
            if (index < count - 1) add(CustomAccessibilityAction("Next widget page") {
              changePage(index + 1); true
            })
          }
        }.onPreviewKeyEvent {
          if (it.type != KeyEventType.KeyDown) false
          else {
            val target = when (it.key) {
              Key.DirectionRight -> index + 1
              Key.DirectionLeft -> index - 1
              else -> return@onPreviewKeyEvent false
            }.coerceIn(0, count - 1)
            if (target == index) false
            else {
              changePage(target)
              true
            }
          }
        }.onFocusChanged { onFocusChanged(it.isFocused) }.focusable()
        .padding(horizontal = 14.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      repeat(count) { dot ->
        val active = dot == index
        Box(Modifier.height(8.dp).width(if (active) 22.dp else 8.dp).clip(RoundedCornerShape(4.dp))
            .background(if (active) ApprovedHomePalette.Text else ApprovedHomePalette.Secondary.copy(alpha = .45f))
            .clickable(onClickLabel = "Widget page ${dot + 1}") { changePage(dot) })
      }
      Spacer(Modifier.width(6.dp))
      Text("${index + 1} of $count", color = ApprovedHomePalette.Secondary, fontSize = 20.sp,
          lineHeight = 26.sp)
    }
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

// Minimal weather condition text without network deps
internal fun weatherConditionText(code: Int): String = when (code) {
  0 -> "Clear Sky"
  in 1..3 -> "Partly Cloudy"
  in 45..48 -> "Foggy"
  in 51..67 -> "Rainy"
  in 71..77 -> "Snowy"
  in 80..82 -> "Showers"
  in 95..99 -> "Thunderstorm"
  else -> "Cloudy"
}

// ─────────────────────────────────────────────────────────────────────────────
// WiFi signal indicator
// ─────────────────────────────────────────────────────────────────────────────

private const val RSSI_UNAVAILABLE = -127

@Composable
private fun wifiLevel(): Int {
  val context = androidx.compose.ui.platform.LocalContext.current
  return produceState(initialValue = -1) {
    while (true) {
      val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
      value = if (wifiManager != null) {
        val rssi = wifiManager.connectionInfo?.rssi ?: RSSI_UNAVAILABLE
        if (rssi == RSSI_UNAVAILABLE) {
          -1
        } else {
          @Suppress("DEPRECATION")
          WifiManager.calculateSignalLevel(rssi, 5)
        }
      } else {
        -1
      }
      delay(10_000L)
    }
  }.value
}

@Composable
private fun WifiBars(level: Int) {
  Row(
      horizontalArrangement = Arrangement.spacedBy(2.dp),
      verticalAlignment = Alignment.Bottom,
  ) {
    for (i in 0..3) {
      val height = (4 + i * 3).dp
      val color = if (level != -1 && i < level) Color.White else Color.White.copy(alpha = 0.25f)
      Box(
          modifier = Modifier
              .width(3.dp)
              .height(height)
              .background(color, RoundedCornerShape(1.dp))
      )
    }
  }
}

@Composable
private fun FadingDivider() {
  Box(
      modifier = Modifier
          .fillMaxHeight()
          .padding(horizontal = 10.dp),
      contentAlignment = Alignment.Center
  ) {
    Box(
        modifier = Modifier
            .width(1.5.dp)
            .fillMaxHeight()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.30f),
                        Color.Transparent
                    )
                )
            )
    )
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Liquid Glass composables
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Bare Liquid Glass surface — transparent glass, FLAT (no gradient): a single even translucent
 * DARK tint so the card reads a touch darker than the wallpaper (gives it body and makes the white
 * text pop), while still letting the wallpaper show through. A thin light border defines the glass
 * edge. No drop shadow (it bleeds through the translucent fill and muddies the centre), no gradient.
 */
@Composable
private fun LiquidGlass(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = Glass.Radius,
    content: @Composable BoxScope.() -> Unit = {},
) {
  // Thin alias over the ONE shared glass surface (ui/components/Glass.kt) — colors/radius live
  // there so every card in the app changes together.
  GlassCard(
      modifier = modifier, cornerRadius = cornerRadius, content = content)
}

/** Liquid Glass widget tile (rounded 22dp). */
@Composable
private fun LiquidGlassWidget(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
  LiquidGlass(modifier = modifier, cornerRadius = 22.dp, content = content)
}

/** App icon in the grid with scale animation on tap and reorderable drag handle. */
@Composable
private fun LiquidGlassAppIconContent(
    app: AppEntry,
    editMode: Boolean,
    isDragging: Boolean,
    dragHandleModifier: Modifier,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onRemove: () -> Unit,
) {
  val scale = remember { Animatable(1f) }

  Box(
      modifier = Modifier
          .fillMaxWidth()
          .jiggle(editMode && !isDragging, app.component.packageName.hashCode()),
      contentAlignment = Alignment.Center
  ) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
              scaleX = if (isDragging) 1.05f else scale.value
              scaleY = if (isDragging) 1.05f else scale.value
              shadowElevation = if (isDragging) 16.dp.toPx() else 0f
              shape = RoundedCornerShape(26.dp)
              clip = false
              // Disable Offscreen compositing while dragging for perf
              compositingStrategy = if (isDragging) CompositingStrategy.Auto else CompositingStrategy.Offscreen
            }
            .then(dragHandleModifier)
            .then(
                if (!editMode) {
                  // Non-edit mode: standard tap gestures with press-scale
                  Modifier.pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                          scale.animateTo(0.96f, tween(90))
                          tryAwaitRelease()
                          scale.animateTo(1f, spring())
                        },
                        onTap = { onClick() },
                        onLongPress = { onLongPress() },
                    )
                  }
                } else {
                  // In edit mode the drag handle owns the gesture; a plain tap does nothing.
                  Modifier
                }
            ),
    ) {
      // Pad the wrapper so the minus badge sits at the tile's top-left corner and is FULLY
      // visible (not clipped by the grid cell). The tile is inset by the overhang amount.
      Box(modifier = Modifier.padding(top = 11.dp, start = 11.dp)) {
        // iOS-style icon: rounded-squircle corners, soft drop shadow (3D depth), and a glossy
        // top highlight sheen — applied uniformly so even square drawables read as premium
        // rounded tiles.
        val iconShape = RoundedCornerShape(26.dp)
        Box(
            modifier = Modifier
                .size(110.dp)
                .shadow(elevation = 8.dp, shape = iconShape, clip = false)
                .clip(iconShape),
            contentAlignment = Alignment.Center,
        ) {
          Image(
              bitmap = app.icon,
              contentDescription = app.label,
              modifier = Modifier.size(110.dp),
              filterQuality = FilterQuality.High,
          )
          // Glossy top-down sheen for the "candy"/3D finish.
          Box(
              modifier = Modifier.matchParentSize().background(
                  Brush.verticalGradient(
                      colors = listOf(Color(0x33FFFFFF), Color(0x0FFFFFFF), Color.Transparent),
                      endY = 130f,
                  )
              )
          )
        }
        if (editMode && app.uninstallable) {
          // Badge sits at the tile's top-left corner, fully inside the padded wrapper (so it
          // isn't clipped) — straddling the edge visually. Offset into the padding room.
          MinusBadge(
              modifier = Modifier.align(Alignment.TopStart).offset(x = (-6).dp, y = (-6).dp),
          ) { onRemove() }
        }
      }
      Spacer(Modifier.height(6.dp))
      Text(
          text = app.label,
          color = Color.White.copy(alpha = 0.82f),
          fontSize = 22.sp,
          fontWeight = FontWeight.Medium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          textAlign = TextAlign.Center,
          modifier = Modifier.widthIn(max = 110.dp),
      )
    }
  }
}

/**
 * Full-screen "time's up" alarm overlay with an iOS-style slide-to-stop. Blocks touches from
 * reaching the grid beneath, so the only way out is the slider (or the 60s auto-silence).
 */
@Composable
private fun TimerAlarmOverlay(onStop: () -> Unit) {
  val noRipple = remember { MutableInteractionSource() }
  Box(
      contentAlignment = Alignment.Center,
      modifier =
          Modifier.fillMaxSize()
              .background(Glass.Scrim)
              // Swallow taps so nothing behind the alarm reacts.
              .clickable(interactionSource = noRipple, indication = null) {},
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text("⏰", fontSize = 68.sp)
      Spacer(Modifier.size(18.dp))
      Text("Timer", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
      Text(
          "Time's up",
          color = Color(0xFFBFBFBF),
          fontSize = 18.sp,
          modifier = Modifier.padding(top = 6.dp),
      )
      Spacer(Modifier.size(44.dp))
      SlideToStop(onStop = onStop)
    }
  }
}

/** A draggable thumb on a track; sliding it to the far end fires [onStop] (else it snaps back). */
@Composable
private fun SlideToStop(onStop: () -> Unit) {
  val density = androidx.compose.ui.platform.LocalDensity.current
  val trackWidth = 320.dp
  val thumbSize = 56.dp
  val inset = 4.dp
  val maxOffset =
      with(density) { (trackWidth - thumbSize - inset * 2).toPx() }.coerceAtLeast(0f)
  val insetPx = with(density) { inset.roundToPx() }
  var offsetX by remember { mutableStateOf(0f) }
  Box(
      modifier =
          Modifier.width(trackWidth)
              .height(64.dp)
              .clip(RoundedCornerShape(32.dp))
              .background(Color(0x33FFFFFF)),
      contentAlignment = Alignment.CenterStart,
  ) {
    Text(
        "Slide to stop",
        color = Color(0xFFE8E8E8),
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.align(Alignment.Center).alpha(1f - (offsetX / maxOffset).coerceIn(0f, 1f)),
    )
    Box(
        modifier =
            Modifier.offset { IntOffset(offsetX.roundToInt() + insetPx, 0) }
                .size(thumbSize)
                .clip(CircleShape)
                .background(Color(0xFFE53935))
                .pointerInput(Unit) {
                  detectDragGestures(
                      onDragEnd = { if (offsetX >= maxOffset * 0.9f) onStop() else offsetX = 0f },
                      onDragCancel = { offsetX = 0f },
                      onDrag = { change, dragAmount ->
                        change.consume()
                        offsetX = (offsetX + dragAmount.x).coerceIn(0f, maxOffset)
                      },
                  )
                },
        contentAlignment = Alignment.Center,
    ) {
      Text("›", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
    }
  }
}

private const val UPDATE_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000 // 6 hours

// --- tile sizing ----------------------------------------------------------------
// The grid's tile edge, provided once at the top of the tree so every tile
// (apps, folders, built-ins, the drag ghost) follows the user's size setting.
// Standard is the original 6-column/88dp look; Large is 5 columns of 110dp tiles,
// closer to the stock Portal launcher.
private val LocalTileDp = compositionLocalOf { 88.dp }

private fun tileDpFor(size: String): Dp =
    when (size) {
      ImmortalSettings.SIZE_XL -> 140.dp
      ImmortalSettings.SIZE_LARGE -> 110.dp
      else -> 88.dp
    }

private fun gridColumnsFor(size: String): Int =
    when (size) {
      ImmortalSettings.SIZE_XL -> 4
      ImmortalSettings.SIZE_LARGE -> 5
      else -> 6
    }

private fun customWidgetLabel(kind: String): String =
    when (kind) {
      HomeWidgetStore.KIND_WEATHER -> "Weather"
      HomeWidgetStore.KIND_WORLD_CLOCK -> "World Clock"
      HomeWidgetStore.KIND_TIMERS -> "Timers"
      HomeWidgetStore.KIND_CALENDAR -> "Calendar"
      HomeWidgetStore.KIND_ALARM -> "Alarm"
      HomeWidgetStore.KIND_CRYPTO -> "Crypto"
      HomeWidgetStore.KIND_TODO -> "Household list"
      HomeWidgetStore.KIND_ROUTINES -> "Routines"
      HomeWidgetStore.KIND_CHORES -> "Chores"
      HomeWidgetStore.KIND_CALLS -> "Calls"
      HomeWidgetStore.KIND_NEWS -> "News"
      HomeWidgetStore.KIND_STOCKS -> "Stocks"
      HomeWidgetStore.KIND_OPTIONS -> "Options"
      HomeWidgetStore.KIND_HA -> "Home Assistant"
      HomeWidgetStore.KIND_TODAY -> "Today"
      HomeWidgetStore.KIND_QUICK_CONTROLS -> "Quick Controls"
      HomeWidgetStore.KIND_PHOTO_MEMORY -> "Photo Memory"
      HomeWidgetStore.KIND_WIFI -> "Wi-Fi"
      else -> "Widget"
    }

@Composable
private fun HeaderBar(onScreensaver: () -> Unit) {
  var now by remember { mutableStateOf(Date()) }
  androidx.compose.runtime.LaunchedEffect(Unit) {
    while (true) {
      now = Date()
      delay(1000)
    }
  }
  val context = androidx.compose.ui.platform.LocalContext.current
  // The unit preference is re-read on resume and keys the fetch loop, so flipping
  // °F/°C in Immortal Settings updates the header the moment the user returns.
  var weatherUnit by remember { mutableStateOf(ImmortalSettings.load(context).weatherUnit) }
  // The clock format is likewise re-read on resume so flipping Auto/12h/24h in
  // Immortal Settings updates the header the moment the user returns home.
  var use24Hour by remember { mutableStateOf(ImmortalSettings.use24HourClock(context)) }
  // The "hey" button only appears when Millennium is installed; re-checked on
  // resume so it shows up the moment the user finishes provisioning without
  // needing to relaunch the launcher.
  var heyPkg by remember { mutableStateOf(heyPackage(context)) }
  // The header mini-player toggle is re-read on resume so flipping it in Immortal
  // Settings shows/hides the player the moment the user returns home.
  var showMiniPlayer by remember { mutableStateOf(ImmortalSettings.load(context).showMiniPlayer) }
  // Live now-playing from the device's media session. The hub notifies off-main, so
  // hop back to the main thread before touching Compose state.
  var nowPlaying by remember { mutableStateOf(NowPlayingHub.current) }
  val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
  DisposableEffect(Unit) {
    val l = NowPlayingHub.Listener { s -> mainHandler.post { nowPlaying = s } }
    NowPlayingHub.addListener(l) // replays current immediately
    onDispose { NowPlayingHub.removeListener(l) }
  }
  OnResume {
        weatherUnit = ImmortalSettings.load(context).weatherUnit
        use24Hour = ImmortalSettings.use24HourClock(context)
        heyPkg = heyPackage(context)
        showMiniPlayer = ImmortalSettings.load(context).showMiniPlayer
  }
  val weather by produceState(initialValue = "", weatherUnit) {
    // Retry soon on failure (e.g. a transient geolocation rate-limit), then
    // refresh periodically. Location is cached after the first success.
    while (true) {
      val w = withContext(Dispatchers.IO) { Weather.fetch(context) }
      if (w.isNotBlank()) {
        value = w
        delay(30L * 60 * 1000) // refresh every 30 min
      } else {
        delay(60L * 1000) // retry in 1 min
      }
    }
  }
  val battery = batteryState()

  // Quick-connect: the remote header button enables the remote and pops a QR/PIN modal.
  var showPair by remember { mutableStateOf(false) }
  var pairUrl by remember { mutableStateOf<String?>(null) }
  var pairPin by remember { mutableStateOf<String?>(null) }

  // Layout: the big clock anchors the left; the weather and date stack on the
  // right, right-aligned, so the header reads as a balanced pair of blocks. The
  // clock and the right-hand stack are centred against each other.
  Row(
      modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    // Clock anchors the top-left corner; the action buttons sit just to its right
    // (now that there's a group of them, leading with the buttons looked off).
    Text(
        SimpleDateFormat(if (use24Hour) "H:mm" else "h:mm", Locale.getDefault()).format(now),
        color = Color.White,
        fontSize = 72.sp,
        fontWeight = FontWeight.Light,
        lineHeight = 72.sp,
    )
    Spacer(Modifier.size(28.dp))
    // Screensaver entry — the stock launcher's stacked-photo icon so the affordance
    // reads the same as the Portal users already know.
    Surface(
        color = Color(0x33FFFFFF),
        shape = androidx.compose.foundation.shape.CircleShape,
        modifier =
            Modifier.size(56.dp).tvFocusable(androidx.compose.foundation.shape.CircleShape) {
              onScreensaver()
            },
    ) {
      Box(contentAlignment = Alignment.Center) { StackedPhotoIcon() }
    }
    // Remote — one-tap "control from your phone": turn the remote on (if off) and show a
    // QR/PIN modal, so a phone pairs without digging into Settings.
    Spacer(Modifier.size(14.dp))
    Surface(
        color = Color(0x33FFFFFF),
        shape = androidx.compose.foundation.shape.CircleShape,
        modifier =
            Modifier.size(56.dp).tvFocusable(androidx.compose.foundation.shape.CircleShape) {
              val (u, p) = enableRemoteAndMintPin(context)
              pairUrl = u
              pairPin = p
              showPair = true
            },
    ) {
      Box(contentAlignment = Alignment.Center) { RemoteGlyph() }
    }
    // "Hey" button — push-to-talk for the active assistant. The launcher stays
    // dumb: it just broadcasts the trigger; Millennium owns assistant selection,
    // the premium gate and the falcon mic handoff. Only shown when Millennium is
    // installed (so a bare launcher has no dead button).
    heyPkg?.let { pkg ->
      Spacer(Modifier.size(14.dp))
      Surface(
          color = Color(0x33FFFFFF),
          shape = androidx.compose.foundation.shape.CircleShape,
          modifier =
              Modifier.size(56.dp).tvFocusable(
                  shape = androidx.compose.foundation.shape.CircleShape,
                  onLongClick = { openHeyPicker(context, pkg) },
              ) {
                fireHey(context, pkg)
              },
      ) {
        Box(contentAlignment = Alignment.Center) { MicGlyph() }
      }
    }
    // Mini-player — sits in the left action cluster, only while something's playing.
    // When shown it takes the flexible space (so its text uses the header width before
    // it scrolls); otherwise a weight spacer pushes the weather/date to the right.
    val np = nowPlaying
    if (showMiniPlayer && np != null && np.active && np.source != SOURCE_YOUTUBE) {
      Spacer(Modifier.size(16.dp))
      MiniPlayer(np, modifier = Modifier.weight(1f).padding(end = 16.dp)) {
        context.startActivity(Intent(context, HomeActivity::class.java).putExtra(APPROVED_HOME_MUSIC, true))
      }
    } else {
      Spacer(Modifier.weight(1f))
    }
    Column(horizontalAlignment = Alignment.End) {
      Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(18.dp),
      ) {
        // Charge level is OPTIONAL — only Portal Go has a battery; mains-powered
        // Portals report no battery present, so we render nothing for them.
        if (battery.present) {
          BatteryIndicator(percent = battery.percent, charging = battery.charging)
        }
        if (weather.isNotBlank()) {
          Text(weather, color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Light)
        }
      }
      Text(
          DateFormatter.format(now, "EEEEMMMMd"),
          color = Color(0xFFAAAAAA),
          fontSize = 20.sp,
          fontWeight = FontWeight.Normal,
          letterSpacing = 0.5.sp,
          modifier = Modifier.padding(top = 6.dp),
      )
    }
  }

  if (showPair) {
    androidx.compose.ui.window.Dialog(onDismissRequest = { showPair = false }) {
      com.immortal.launcher.ui.components.DarkDialogSurface {
        Column(modifier = Modifier.padding(20.dp)) {
          Text("Control from your phone", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
          Text(
              "Scan with a phone on the same Wi-Fi, or open the address and enter the code.",
              color = Color(0xFF9A9A9A),
              fontSize = 13.sp,
              modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
          )
          RemotePairCard(pairUrl, pairPin)
          Spacer(Modifier.size(16.dp))
          PairDoneButton { showPair = false }
        }
      }
    }
  }
}

/**
 * Home-header mini-player: premium card with rounded album art, glass-style card background,
 * a subtle left PrimeBlue border indicating active playback, and refined typography.
 * The art area is the play/pause touch target. Driven by [NowPlayingHub].
 */
/**
 * Left-column "Now Playing" card. Subscribes to [NowPlayingHub] and renders the current track with
 * a tap-to-play/pause art target; self-hides (renders nothing) when nothing is playing, matching the
 * other self-hiding dashboard cards. Replaces the old always-present "Tap to play" stub.
 */
@Composable
private fun NowPlayingCard(modifier: Modifier = Modifier) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
  var state by remember { mutableStateOf(NowPlayingHub.current) }
  DisposableEffect(Unit) {
    val l = NowPlayingHub.Listener { s -> mainHandler.post { state = s } }
    NowPlayingHub.addListener(l) // replays current immediately
    onDispose { NowPlayingHub.removeListener(l) }
  }
  val np = state ?: return // nothing playing → card hides entirely

  var urlArt by remember(np.artUrl, np.artBitmap) { mutableStateOf<android.graphics.Bitmap?>(null) }
  LaunchedEffect(np.artUrl, np.artBitmap) {
    if (np.artBitmap == null && np.artUrl.isNotBlank()) {
      MediaArt.deliverUri(context, np.artUrl) { urlArt = it }
    } else urlArt = null
  }
  val cover = np.artBitmap ?: urlArt

  LiquidGlassWidget(modifier = modifier.height(80.dp)) {
    Row(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      LiquidGlass(
          modifier = Modifier.size(48.dp).tvFocusable(RoundedCornerShape(8.dp)) {
            NowPlayingHub.playPause()
          },
          cornerRadius = 8.dp,
      ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          if (cover != null) {
            Image(
                bitmap = cover.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
          } else {
            Text("♫", color = Color.White, fontSize = 18.sp)
          }
        }
      }
      Column(modifier = Modifier.weight(1f)) {
        Text(
            np.title.ifBlank { "Now Playing" },
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (np.artist.isNotBlank()) {
          Text(
              np.artist,
              color = Color.White.copy(alpha = 0.55f),
              fontSize = 11.sp,
              fontWeight = FontWeight.Light,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ApprovedHomeHeaderPlayer(modifier: Modifier = Modifier, onOpenMusic: () -> Unit) {
  // Playback/position notifications invalidate only the player, not the widget dashboard.
  var state by remember { mutableStateOf(NowPlayingHub.current) }
  val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
  DisposableEffect(Unit) {
    val listener = homeNowPlayingListener({ mainHandler.post(it) }) { state = it }
    NowPlayingHub.addListener(listener)
    onDispose { NowPlayingHub.removeListener(listener) }
  }
  state?.takeIf { it.active && it.source != SOURCE_YOUTUBE }?.let { MiniPlayer(it, modifier, onOpenMusic) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MiniPlayer(state: NowPlayingState, modifier: Modifier = Modifier, onOpenMusic: () -> Unit) {
  val context = androidx.compose.ui.platform.LocalContext.current
  // Some media apps hand cover art as a URI rather than an embedded bitmap. Resolve it off the
  // main thread (content:// or http) so the header shows the cover too — the screensaver and the
  // phone remote already do this via [MediaArt]; the mini-player was the one place that didn't.
  var urlArt by remember(state.artUrl, state.artBitmap) { mutableStateOf<android.graphics.Bitmap?>(null) }
  androidx.compose.runtime.LaunchedEffect(state.artUrl, state.artBitmap) {
    if (state.artBitmap == null && state.artUrl.isNotBlank()) {
      MediaArt.deliverUri(context, state.artUrl) { urlArt = it }
    } else urlArt = null
  }
  val cover = state.artBitmap ?: urlArt
  Row(
      modifier =
          modifier
              .clip(RoundedCornerShape(16.dp))
              .background(ApprovedHomePalette.Chrome)
              .border(1.dp, ApprovedHomePalette.Border, RoundedCornerShape(16.dp))
              .padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    // Album art with 8dp rounded corners; tapping toggles playback.
    Box(
        modifier =
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .tvFocusable(RoundedCornerShape(8.dp)) { NowPlayingHub.playPause() },
        contentAlignment = Alignment.Center,
    ) {
      val bmp = cover
      if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(56.dp),
        )
      } else {
        ApprovedHomeIcon("music", Modifier.size(40.dp), ApprovedHomePalette.Secondary)
      }
      // Subtle dark scrim + play/pause glyph overlay on the art.
      Box(
          Modifier.size(56.dp).background(Color(0x3D000000)),
          contentAlignment = Alignment.Center,
      ) {
        PlayPauseGlyph(playing = state.state == PlaybackState.PLAYING)
      }
    }
    Spacer(Modifier.size(12.dp))
    Column(modifier = Modifier.weight(1f).semantics { contentDescription = "Open music player" }
        .tvFocusable { onOpenMusic() }) {
      Text(
          state.title,
          color = ApprovedHomePalette.Text,
          fontSize = 26.sp,
          lineHeight = 35.sp,
          fontWeight = FontWeight.Medium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
      )
      if (state.artist.isNotBlank()) {
        Text(
            state.artist,
            color = ApprovedHomePalette.Secondary,
            fontSize = 22.sp,
            lineHeight = 30.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
      }
    }
  }
}

@Composable
private fun PlayPauseGlyph(playing: Boolean) {
  Canvas(modifier = Modifier.size(30.dp)) {
    val w = size.minDimension
    if (playing) {
      // Two slim, fully-rounded bars with a clear gap between them — close-set square
      // bars blur into a single block from across the room.
      val barW = w * 0.13f
      val top = w * 0.24f
      val barH = w * 0.52f
      val r = CornerRadius(barW * 0.5f, barW * 0.5f)
      drawRoundRect(Color.White, topLeft = Offset(w * 0.30f, top), size = Size(barW, barH), cornerRadius = r)
      drawRoundRect(Color.White, topLeft = Offset(w * 0.57f, top), size = Size(barW, barH), cornerRadius = r)
    } else {
      drawPath(
          Path().apply {
            moveTo(w * 0.32f, w * 0.22f)
            lineTo(w * 0.32f, w * 0.78f)
            lineTo(w * 0.80f, w * 0.50f)
            close()
          },
          Color.White,
      )
    }
  }
}

/** What the forecast widget currently has to show. */
private sealed interface ForecastState {
  /** First fetch still in flight — render nothing so the bar doesn't flash. */
  object Loading : ForecastState
  /** Tried and failed with nothing cached — show a friendly note instead. */
  object Unavailable : ForecastState
  data class Ready(val forecast: Weather.Forecast) : ForecastState
}

// The two swipeable forecast pages, in left-to-right order.
private const val PAGE_HOURLY = 0
private const val PAGE_DAILY = 1

/**
 * Optional home-screen forecast, shown full-width below the app grid when enabled in
 * Immortal Settings ▸ Weather. One network call fetches both views; the user swipes
 * left/right between the hourly and 7-day pages. [mode] picks which page shows first
 * (and jumps to it if the setting changes). [fahrenheit] only keys a re-fetch when the
 * unit changes — the unit itself is resolved inside [Weather.fetchForecast].
 *
 * Failure handling: the fetch retries every minute until it succeeds. While the very
 * first attempt is in flight nothing is drawn (no flash). If it can't be reached and
 * we have no forecast yet, a quiet "unavailable" note replaces the data; once a
 * forecast has loaded, a later failed refresh keeps the last good one on screen rather
 * than blanking it.
 */
@Composable
private fun WeatherWidget(mode: String, fahrenheit: Boolean) {
  val context = androidx.compose.ui.platform.LocalContext.current
  // Keyed on the unit only: switching pages is a local swipe, not a re-fetch.
  val state by
      produceState<ForecastState>(initialValue = ForecastState.Loading, fahrenheit) {
        while (true) {
          val f = withContext(Dispatchers.IO) { Weather.fetchForecast(context) }
          if (f != null) {
            value = ForecastState.Ready(f)
            delay(30L * 60 * 1000) // refresh every 30 min
          } else {
            // Keep showing the last good forecast if we have one; only surface the
            // note when there's nothing to display.
            if (value !is ForecastState.Ready) value = ForecastState.Unavailable
            delay(60L * 1000) // retry in 1 min
          }
        }
      }
  if (state is ForecastState.Loading) return

  val startPage = if (mode == ImmortalSettings.WIDGET_DAILY) PAGE_DAILY else PAGE_HOURLY
  val pagerState = rememberPagerState(initialPage = startPage) { 2 }
  // Follow the setting: if the user changes the default in Immortal Settings, jump to
  // that page when they come back (no-op on first composition, where it already matches).
  LaunchedEffect(mode) {
    if (pagerState.currentPage != startPage) pagerState.scrollToPage(startPage)
  }

  val ready = state as? ForecastState.Ready
  Surface(
      color = Color(0x14FFFFFF),
      shape = RoundedCornerShape(20.dp),
      modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
  ) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
      // Header: the current page's title, plus a two-dot indicator hinting the swipe.
      Row(
          modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
            when {
              ready == null -> "Forecast"
              pagerState.currentPage == PAGE_DAILY -> "7-day forecast"
              else -> "Hourly forecast"
            },
            color = Color(0xFFBFBFBF),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (ready != null) PageDots(selected = pagerState.currentPage, count = 2)
      }
      if (ready != null) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
          // Each page's cells share the width evenly, spanning the whole card.
          Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            if (page == PAGE_HOURLY) {
              ready.forecast.hours.forEach { HourCell(it, Modifier.weight(1f)) }
            } else {
              ready.forecast.days.forEach { DayCell(it, Modifier.weight(1f)) }
            }
          }
        }
      } else {
        // Unavailable: no connection / location yet. Retries quietly in the
        // background, so no action is needed from the user.
        Text(
            "Forecast unavailable. It'll appear once your Portal is back online.",
            color = Color(0xFF9A9A9A),
            fontSize = 14.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
      }
    }
  }
}

/** Small page-position dots, hinting the forecast can be swiped between its pages. */
@Composable
private fun PageDots(selected: Int, count: Int) {
  Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
    repeat(count) { i ->
      val isActive = i == selected
      Box(
          modifier =
              Modifier.size(if (isActive) 6.dp else 4.dp)
                  .clip(androidx.compose.foundation.shape.CircleShape)
                  .background(if (isActive) PrimeBlue else Color(0x55FFFFFF)))
    }
  }
}

@Composable
private fun HourCell(h: Weather.HourForecast, modifier: Modifier = Modifier) {
  Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = modifier.padding(vertical = 4.dp),
  ) {
    Text(h.label, color = Color(0xFFCFCFCF), fontSize = 13.sp, maxLines = 1)
    Text(Weather.emoji(h.code), fontSize = 26.sp, modifier = Modifier.padding(vertical = 6.dp))
    Text("${h.temp}°", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
  }
}

@Composable
private fun DayCell(d: Weather.DayForecast, modifier: Modifier = Modifier) {
  Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = modifier.padding(vertical = 4.dp),
  ) {
    Text(d.label, color = Color(0xFFCFCFCF), fontSize = 13.sp, maxLines = 1)
    Text(Weather.emoji(d.code), fontSize = 26.sp, modifier = Modifier.padding(vertical = 6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      Text("${d.hi}°", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
      Text("${d.lo}°", color = Color(0xFF9A9A9A), fontSize = 16.sp)
    }
  }
}

// --- "Hey" assistant trigger -------------------------------------------------
// Millennium publishes an exported receiver for this action; the launcher only
// fires it. Release build preferred, debug fallback for sideloaded test devices.
private const val HEY_TRIGGER_ACTION = "com.millennium.TRIGGER_ASSISTANT"
private val HEY_PACKAGES = listOf("com.millennium", "com.millennium.debug")

/** The installed Millennium ("hey") package, release preferred, or null if absent. */
private fun heyPackage(context: android.content.Context): String? =
    HEY_PACKAGES.firstOrNull { pkg ->
      try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
      } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
        false
      }
    }

/** Ask Millennium to activate the user's active assistant (same path as a wake word). */
private fun fireHey(context: android.content.Context, pkg: String) {
  context.sendBroadcast(Intent(HEY_TRIGGER_ACTION).setPackage(pkg))
}

private fun jarvisInstalled(context: android.content.Context): Boolean =
    runCatching { context.packageManager.getPackageInfo(JarvisHandoff.PACKAGE, 0); true }.getOrDefault(false)

/** The header mic when Millennium isn't installed: Jarvis's floating card, same chrome as the Millennium mic. */
@Composable
private fun JarvisHeaderButton(size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
  Box(Modifier.size(size).clip(CircleShape).background(ApprovedHomePalette.Chrome).approvedOutline(CircleShape)
      .semantics { contentDescription = "Ask Jarvis" }
      .tvFocusable(CircleShape, onClick = onClick),
      contentAlignment = Alignment.Center) { MicGlyph(ApprovedHomePalette.Text) }
}

internal fun triggerJarvis(context: android.content.Context) {
  if (!JarvisHandoff.launch(context))
    android.widget.Toast.makeText(context, "Jarvis couldn’t be opened.",
        android.widget.Toast.LENGTH_LONG).show()
}

/** Millennium's assistant picker (premium: choose which assistant to talk to). */
private const val HEY_PICKER_ACTIVITY = "com.millennium.ui.HeyPickerActivity"

/** Long-press: open Millennium's picker. Falls back to a normal trigger if the
 *  installed Millennium predates the picker (so the gesture is never a dead end). */
private fun openHeyPicker(context: android.content.Context, pkg: String) {
  val ok =
      runCatching {
            context.startActivity(
                Intent()
                    .setClassName(pkg, HEY_PICKER_ACTIVITY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
          }
          .isSuccess
  if (!ok) fireHey(context, pkg)
}

/** White line-art microphone glyph for the header "hey" button. */
@Composable
private fun MicGlyph(tint: Color = Color.White) {
  Canvas(modifier = Modifier.size(28.dp)) {
    val w = size.minDimension
    val s = w * 0.08f
    val stroke =
        Stroke(
            width = s,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round,
        )
    // Capsule mic body.
    drawRoundRect(
        color = tint,
        topLeft = Offset(w * 0.38f, w * 0.16f),
        size = Size(w * 0.24f, w * 0.40f),
        cornerRadius = CornerRadius(w * 0.12f, w * 0.12f),
        style = stroke,
    )
    // Cradle arc hugging the bottom of the body.
    drawArc(
        color = tint,
        startAngle = 20f,
        sweepAngle = 140f,
        useCenter = false,
        topLeft = Offset(w * 0.26f, w * 0.24f),
        size = Size(w * 0.48f, w * 0.48f),
        style = stroke,
    )
    // Stem + base.
    drawLine(tint, Offset(w * 0.5f, w * 0.72f), Offset(w * 0.5f, w * 0.84f), strokeWidth = s)
    drawLine(tint, Offset(w * 0.38f, w * 0.84f), Offset(w * 0.62f, w * 0.84f), strokeWidth = s)
  }
}

/** White line-art phone glyph for the header "remote" (control from your phone) button. */
@Composable
private fun RemoteGlyph() {
  Canvas(modifier = Modifier.size(28.dp)) {
    val w = size.minDimension
    val s = w * 0.08f
    val stroke =
        Stroke(
            width = s,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round,
        )
    // Phone body (portrait rounded rect).
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(w * 0.34f, w * 0.12f),
        size = Size(w * 0.32f, w * 0.66f),
        cornerRadius = CornerRadius(w * 0.10f, w * 0.10f),
        style = stroke,
    )
    // Speaker slit near the top, home dot near the bottom.
    drawLine(Color.White, Offset(w * 0.44f, w * 0.22f), Offset(w * 0.56f, w * 0.22f), strokeWidth = s)
    drawCircle(color = Color.White, radius = w * 0.045f, center = Offset(w * 0.5f, w * 0.67f))
  }
}

/** White line-art photo glyph (single frame), matching the stock screensaver. */
@Composable
private fun StackedPhotoIcon() {
  Canvas(modifier = Modifier.size(30.dp)) {
    val w = size.minDimension
    val s = w * 0.075f // stroke width
    val stroke =
        Stroke(
            width = s,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round,
        )
    // Photo frame outline.
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(w * 0.16f, w * 0.20f),
        size = Size(w * 0.68f, w * 0.60f),
        cornerRadius = CornerRadius(w * 0.14f, w * 0.14f),
        style = stroke,
    )
    // Sun.
    drawCircle(
        color = Color.White,
        radius = w * 0.075f,
        center = Offset(w * 0.36f, w * 0.40f),
        style = Stroke(width = s),
    )
    // Mountains.
    val path =
        androidx.compose.ui.graphics.Path().apply {
          moveTo(w * 0.20f, w * 0.72f)
          lineTo(w * 0.40f, w * 0.48f)
          lineTo(w * 0.52f, w * 0.60f)
          lineTo(w * 0.62f, w * 0.50f)
          lineTo(w * 0.80f, w * 0.72f)
        }
    drawPath(path, color = Color.White, style = stroke)
  }
}

/** Bottom-right Manage / Done toggle. */
@Composable
private fun EditButton(editMode: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
  Surface(
      color = if (editMode) Color(0xFFE53935) else Color(0xCC2B2B2B),
      shape = androidx.compose.foundation.shape.CircleShape,
      modifier =
          modifier.size(60.dp).tvFocusable(androidx.compose.foundation.shape.CircleShape) {
            onClick()
          },
  ) {
    Box(contentAlignment = Alignment.Center) {
      Text(if (editMode) "✓" else "✎", color = Color.White, fontSize = 28.sp)
    }
  }
}

/** Edit-mode "clean up" button — re-packs every tile into a tidy ordered grid. */
@Composable
private fun TidyButton(onClick: () -> Unit) {
  val path = remember { PathParser().parsePathString(ICON_WIDGETS).toPath() }
  Surface(
      color = Color(0xCC2B2B2B),
      shape = androidx.compose.foundation.shape.CircleShape,
      modifier =
          Modifier.size(60.dp).tvFocusable(androidx.compose.foundation.shape.CircleShape) {
            onClick()
          },
  ) {
    Box(contentAlignment = Alignment.Center) {
      Canvas(Modifier.size(26.dp)) {
        val s = size.minDimension / 24f
        scale(s, s, pivot = Offset.Zero) { drawPath(path, Color.White) }
      }
    }
  }
}

private data class BatteryReading(val present: Boolean, val percent: Int, val charging: Boolean)

/** Reads the device battery, updating live. Returns present=false on devices
 * without a battery (Portal+, Portal TV, Portal Mini), so callers can hide it. */
@Composable
private fun batteryState(): BatteryReading {
  val context = androidx.compose.ui.platform.LocalContext.current
  var reading by remember { mutableStateOf(BatteryReading(false, 0, false)) }
  DisposableEffect(Unit) {
    fun parse(i: Intent?): BatteryReading {
      if (i == null) return BatteryReading(false, 0, false)
      val present = i.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)
      val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
      val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
      val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
      val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
      val charging =
          status == BatteryManager.BATTERY_STATUS_CHARGING ||
              status == BatteryManager.BATTERY_STATUS_FULL
      return BatteryReading(present && pct >= 0, pct.coerceIn(0, 100), charging)
    }
    val receiver =
        object : android.content.BroadcastReceiver() {
          override fun onReceive(c: Context, i: Intent) {
            reading = parse(i)
          }
        }
    // Registering for the sticky ACTION_BATTERY_CHANGED returns the current value.
    val sticky =
        context.registerReceiver(receiver, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    reading = parse(sticky)
    onDispose { runCatching { context.unregisterReceiver(receiver) } }
  }
  return reading
}

/** Minimal drawn battery glyph + percent, green while charging, red when low. */
@Composable
private fun BatteryIndicator(percent: Int, charging: Boolean) {
  Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Canvas(modifier = Modifier.size(width = 36.dp, height = 18.dp)) {
      val cap = 3.dp.toPx()
      val bodyW = size.width - cap
      val stroke = 2.dp.toPx()
      drawRoundRect(
          color = Color.White,
          topLeft = Offset(stroke / 2, stroke / 2),
          size = Size(bodyW - stroke, size.height - stroke),
          cornerRadius = CornerRadius(4f, 4f),
          style = Stroke(width = stroke),
      )
      drawRoundRect(
          color = Color.White,
          topLeft = Offset(bodyW, size.height * 0.3f),
          size = Size(cap, size.height * 0.4f),
          cornerRadius = CornerRadius(2f, 2f),
      )
      val inset = stroke + 2.dp.toPx()
      val fillColor =
          when {
            charging -> Color(0xFF4CAF50)
            percent <= 15 -> Color(0xFFE53935)
            else -> Color.White
          }
      drawRoundRect(
          color = fillColor,
          topLeft = Offset(inset, inset),
          size =
              Size(
                  ((bodyW - inset * 2) * (percent / 100f)).coerceAtLeast(0f),
                  size.height - inset * 2,
              ),
          cornerRadius = CornerRadius(2f, 2f),
      )
    }
    Text("$percent%", color = Color.White, fontSize = 22.sp)
    if (charging) Text("⚡", color = Color(0xFF4CAF50), fontSize = 16.sp)
  }
}

@Composable
private fun PortalHomeTile(onClick: () -> Unit) {
  // Bridge to Meta's stock launcher — the only context allowed to open the
  // trusted-caller apps (Contacts, Camera, Photos), so this is how the user
  // reaches calling. Tapping Portal's home button returns to Immortal.
  BuiltInTile(
      label = "Calls",
      background = Color(0xFF1FA463),
      glyph = ICON_CALL,
      onClick = onClick,
  )
}

// Material-style glyph paths (24x24 viewport), rendered crisply as vectors.
private const val ICON_CALL =
    "M6.62 10.79c1.44 2.83 3.76 5.14 6.59 6.59l2.2-2.2c.27-.27.67-.36 1.02-.24 1.12.37 2.33.57 3.57.57.55 0 1 .45 1 1V20c0 .55-.45 1-1 1-9.39 0-17-7.61-17-17 0-.55.45-1 1-1h3.5c.55 0 1 .45 1 1 0 1.25.2 2.45.57 3.57.11.35.03.74-.25 1.02l-2.2 2.2z"
private const val ICON_DOWNLOAD = "M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z"
private const val ICON_IMAGE =
    "M21 19V5c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2zM8.5 13.5l2.5 3.01L14.5 12l4.5 6H5l3.5-4.5z"
private const val ICON_WIDGETS =
    "M3 3h8v8H3V3zm10 0h8v5h-8V3zM3 13h5v8H3v-8zm7 0h11v8H10v-8z"
private const val ICON_HELP =
    "M11 18h2v-2h-2v2zm1-16C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8zm0-14c-2.21 0-4 1.79-4 4h2c0-1.1.9-2 2-2s2 .9 2 2c0 2-3 1.75-3 5h2c0-2.25 3-2.5 3-5 0-2.21-1.79-4-4-4z"
private const val ICON_GEAR =
    "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"

/** A built-in launcher tile: a rounded colour tile with a centered white vector
 * glyph, styled to sit naturally beside real app icons. */
@Composable
private fun BuiltInTile(
    label: String,
    background: Color,
    glyph: String,
    onClick: () -> Unit,
) {
  val path = remember(glyph) { PathParser().parsePathString(glyph).toPath() }
  val tileDp = LocalTileDp.current
  Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier.fillMaxWidth().padding(4.dp).tvFocusable(RoundedCornerShape(22.dp)) { onClick() },
  ) {
    GlassCard(
        modifier = Modifier.size(tileDp),
        cornerRadius = 20.dp,
    ) {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(46.dp * (tileDp / 88.dp))) {
          val s = size.minDimension / 24f
          scale(s, s, pivot = Offset.Zero) { drawPath(path, Color.White) }
        }
      }
    }
    Spacer(Modifier.size(8.dp))
    Text(
        label,
        color = ContentSecondary,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        textAlign = TextAlign.Center,
    )
  }
}



@Composable
private fun StoreTile(onClick: () -> Unit) {
  BuiltInTile(
      label = "App Store",
      background = Color(0xFF2D6CDF),
      glyph = ICON_DOWNLOAD,
      onClick = onClick,
  )
}

@Composable
private fun ToolsTile(onClick: () -> Unit) {
  BuiltInTile(
      label = "Tools",
      background = Color(0xFF5A5F6A),
      glyph = ICON_WIDGETS,
      onClick = onClick,
  )
}

@Composable
private fun AddWidgetButton(onClick: () -> Unit) {
  Surface(
      color = Color(0xFF5B6BC0),
      shape = RoundedCornerShape(30.dp),
      modifier = Modifier.width(124.dp).height(60.dp).tvFocusable(RoundedCornerShape(30.dp)) {
        onClick()
      },
  ) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 20.dp),
    ) {
      Text("+", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Light)
      Text("Widget", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
  }
}

@Composable
private fun WidgetTile(
    widget: HomeWidgetStore.HomeWidget,
    host: AppWidgetHost,
    manager: AppWidgetManager,
    editMode: Boolean,
    tileDp: Dp,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
) {
  val info = remember(widget.appWidgetId) {
    if (widget.isAppWidget) manager.getAppWidgetInfo(widget.appWidgetId) else null
  }
  val height =
      tileDp * widget.spanY.toFloat() +
          20.dp * (widget.spanY - 1).coerceAtLeast(0).toFloat()

  LaunchedEffect(widget, tileDp) {
    if (widget.isAppWidget) updateWidgetSizeOptions(manager, widget, tileDp)
  }

  Box(modifier = modifier.fillMaxWidth().height(height).padding(4.dp)) {
    Surface(
        color = Color(0xFF252527),
        shape = RoundedCornerShape(20.dp),
        modifier =
            Modifier.fillMaxSize()
                .alpha(if (dimmed) 0.3f else 1f)
                .jiggle(editMode && !dimmed, widget.key.hashCode()),
    ) {
      if (!widget.isAppWidget) {
        ImmortalWidgetContent(widget = widget, modifier = Modifier.fillMaxSize())
      } else if (info != null) {
        AndroidView(
            factory = {
              host.createView(it, widget.appWidgetId, info).apply {
                setAppWidget(widget.appWidgetId, info)
                setPadding(0, 0, 0, 0)
                isFocusable = true
                isFocusableInTouchMode = true
                descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
              }
            },
            update = { view ->
              view.setAppWidget(widget.appWidgetId, info)
              view.isEnabled = !editMode
            },
            modifier = Modifier.fillMaxSize(),
        )
      } else {
        Box(
            Modifier.fillMaxSize().background(Color(0xFF303033)),
            contentAlignment = Alignment.Center,
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            WidgetGlyph()
            Text(
                "Widget unavailable",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                widget.providerPackage,
                color = Color(0xFF9A9A9A),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp, start = 18.dp, end = 18.dp),
            )
          }
        }
      }
    }
    if (editMode) {
      // Dim/scrim only — the drag scrim and ✕ / resize controls are added by the enclosing
      // WidgetCell so they layer correctly above the hosted widget's own touch handling.
      Box(
          Modifier.fillMaxSize()
              .jiggle(!dimmed, widget.key.hashCode())
              .clip(RoundedCornerShape(20.dp))
              .background(Color(0x66000000)))
    }
  }
}

/** Add-flow ring after Add, and the bottom area the place-and-size bar covers. */
private const val ADD_RING_MS = 1_200L
private val ADD_BAR_AREA = 206.dp

/** Saves [widget] exactly at [slot] with [span] if the current saved layout still has room. */
private fun addIntoSlot(context: Context, widget: HomeWidgetStore.HomeWidget, slot: HomeWidgetStore.Slot,
    span: Pair<Int, Int>): Boolean = synchronized(RemoteCompanionStore.mutationLock) {
  val current = HomeWidgetStore.load(context)
  val updated = HomeSlots.addAt(current, widget, slot, span) ?: return@synchronized false
  HomeWidgetStore.replaceIfUnchanged(context, current, updated)
}

/** A keyboard-free duration picker (±steppers, remote/touch friendly) for a custom timer. */
@Composable
internal fun CustomTimerDialog(onStart: (Long) -> Unit, onDismiss: () -> Unit) {
  var minutes by remember { mutableStateOf(5) }
  var seconds by remember { mutableStateOf(0) }
  ApprovedHomeDialog("Custom timer", onDismiss) {
      Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
          DurationStepper("min", minutes, step = 1) { minutes = (minutes + it).coerceIn(0, 180) }
          Text(":", color = ApprovedHomePalette.Text, fontSize = 48.sp, lineHeight = 60.sp)
          DurationStepper("sec", seconds, step = 5) { seconds = (seconds + it + 60) % 60 }
        }
        Spacer(Modifier.size(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          TimerChip("Cancel") { onDismiss() }
          TimerChip("Start ${minutes}:%02d".format(seconds), enabled = minutes > 0 || seconds > 0) {
            onStart((minutes * 60 + seconds) * 1000L)
          }
        }
      }
  }
}

@Composable
private fun DurationStepper(label: String, value: Int, step: Int, onChange: (Int) -> Unit) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(label.uppercase(Locale.getDefault()), color = ApprovedHomePalette.Secondary,
        fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.size(8.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      StepButton("−") { onChange(-step) }
      Text("%02d".format(value), color = ApprovedHomePalette.Text, fontSize = 48.sp,
          lineHeight = 60.sp, fontWeight = FontWeight.Normal)
      StepButton("+") { onChange(step) }
    }
  }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
  Surface(
      color = ApprovedHomePalette.Chrome,
      shape = RoundedCornerShape(16.dp),
      modifier = Modifier.size(68.dp).tvFocusable(RoundedCornerShape(16.dp)) { onClick() },
  ) {
    Box(contentAlignment = Alignment.Center) {
      Text(label, color = ApprovedHomePalette.Accent, fontSize = 26.sp, lineHeight = 35.sp)
    }
  }
}

@Composable
private fun TimerChip(label: String, enabled: Boolean = true, onClick: () -> Unit) {
  Surface(
      color = ApprovedHomePalette.Chrome,
      shape = RoundedCornerShape(16.dp),
      modifier = Modifier.heightIn(min = 52.dp)
          .tvFocusable(RoundedCornerShape(16.dp), enabled = enabled) { onClick() },
  ) {
    Text(
        label,
        color = if (enabled) ApprovedHomePalette.Accent else ApprovedHomePalette.Secondary,
        fontSize = 22.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
    )
  }
}

@Composable
private fun WidgetPickerOverlay(
    providers: List<WidgetProviderEntry>,
    status: String?,
    onPick: (WidgetProviderEntry) -> Unit,
    onDismiss: () -> Unit,
) {
  val noRipple = remember { MutableInteractionSource() }
  BackHandler { onDismiss() }
  val gridFocus = remember { FocusRequester() }
  LaunchedEffect(providers) { runCatching { gridFocus.requestFocus() } }

  Box(
      contentAlignment = Alignment.Center,
      modifier =
          Modifier.fillMaxSize()
              .onPreviewKeyEvent { e ->
                if (e.key == Key.Back || e.key == Key.Escape) {
                  if (e.type == KeyEventType.KeyUp) onDismiss()
                  true
                } else false
              }
              .background(Glass.Scrim)
              .clickable(interactionSource = noRipple, indication = null) { onDismiss() },
  ) {
    com.immortal.launcher.ui.components.DarkDialogSurface(
        modifier =
            Modifier.widthIn(max = 920.dp)
                .fillMaxWidth(0.78f)
                .heightIn(max = 620.dp)
                .clickable(interactionSource = noRipple, indication = null) {},
    ) {
      Column(modifier = Modifier.padding(28.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Column(modifier = Modifier.weight(1f)) {
            Text("Add widget", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Choose a widget provider installed on this Portal.",
                color = Color(0xFF9A9A9A),
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
          }
          Surface(
              color = Color(0x33FFFFFF),
              shape = CircleShape,
              modifier = Modifier.size(44.dp).tvFocusable(CircleShape) { onDismiss() },
          ) {
            Box(contentAlignment = Alignment.Center) { Text("✕", color = Color.White, fontSize = 20.sp) }
          }
        }
        if (status != null) {
          Text(
              status,
              color = Color(0xFF8AB4F8),
              fontSize = 13.sp,
              modifier = Modifier.padding(top = 14.dp),
          )
        }
        Spacer(Modifier.size(20.dp))
        if (providers.isEmpty()) {
          Box(
              modifier = Modifier.fillMaxWidth().height(220.dp),
              contentAlignment = Alignment.Center,
          ) {
            Text(
                "No widget providers are installed.",
                color = Color(0xFFB8B8B8),
                fontSize = 16.sp,
            )
          }
        } else {
          LazyVerticalGrid(
              columns = GridCells.Fixed(3),
              horizontalArrangement = Arrangement.spacedBy(14.dp),
              verticalArrangement = Arrangement.spacedBy(14.dp),
              modifier = Modifier.focusRequester(gridFocus).focusGroup(),
          ) {
            items(providers, key = { it.info?.provider?.flattenToString() ?: "custom:${it.customKind}" }) { provider ->
              WidgetProviderTile(provider = provider, onClick = { onPick(provider) })
            }
          }
        }
      }
    }
  }
}

@Composable
private fun WidgetProviderTile(provider: WidgetProviderEntry, onClick: () -> Unit) {
  Surface(
      color = Color(0xFF29292C),
      shape = RoundedCornerShape(18.dp),
      modifier = Modifier.tvFocusable(RoundedCornerShape(18.dp)) { onClick() },
  ) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Surface(
          color = Color(0xFF38383C),
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier.size(58.dp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          if (provider.icon != null) {
            Image(
                bitmap = provider.icon,
                contentDescription = null,
                modifier = Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)),
            )
          } else if (provider.customKind != null) {
            CustomWidgetGlyph(provider.customKind)
          } else {
            WidgetGlyph()
          }
        }
      }
      Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
        Text(
            provider.label,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            provider.packageLabel,
            color = Color(0xFF9A9A9A),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp),
        )
        Text(
            "${provider.spanX} x ${provider.spanY}",
            color = Color(0xFF7C7C7C),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 5.dp),
        )
      }
    }
  }
}

@Composable
private fun CustomWidgetGlyph(kind: String) {
  val emoji =
      when (kind) {
        HomeWidgetStore.KIND_WEATHER -> "☁"
        HomeWidgetStore.KIND_WORLD_CLOCK -> "◷"
        HomeWidgetStore.KIND_TIMERS -> "⏱"
        HomeWidgetStore.KIND_CRYPTO -> "₿"
        HomeWidgetStore.KIND_TODO -> "✓"
        HomeWidgetStore.KIND_ROUTINES -> "★"
        HomeWidgetStore.KIND_CHORES -> "★"
        HomeWidgetStore.KIND_CALLS -> "☎"
        HomeWidgetStore.KIND_NEWS -> "📰"
        HomeWidgetStore.KIND_STOCKS -> "📈"
        HomeWidgetStore.KIND_OPTIONS -> "Δ"
        HomeWidgetStore.KIND_HA -> "🏠"
        HomeWidgetStore.KIND_WIFI -> "📶"
        else -> "+"
      }
  Text(emoji, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun WidgetGlyph() {
  val path = remember { PathParser().parsePathString(ICON_WIDGETS).toPath() }
  Canvas(Modifier.size(28.dp)) {
    val s = size.minDimension / 24f
    scale(s, s, pivot = Offset.Zero) { drawPath(path, Color.White) }
  }
}

/**
 * iOS-style "jiggle": a gentle continuous wobble applied to editable tiles while Manage mode is on.
 * Each tile is given a [seed] so they fall slightly out of phase (rather than wobbling in lockstep).
 * The animation only exists while [enabled]; it leaves composition when Manage mode ends so the
 * long-lived launcher isn't animating in the background.
 */
@Composable
private fun Modifier.jiggle(enabled: Boolean, seed: Int): Modifier {
  if (!enabled) return this
  val transition = rememberInfiniteTransition(label = "jiggle")
  val angle by
      transition.animateFloat(
          initialValue = -1.0f,
          targetValue = 1.0f,
          animationSpec =
              infiniteRepeatable(
                  animation = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                  repeatMode = RepeatMode.Reverse,
                  initialStartOffset = StartOffset(0),
              ),
          label = "jiggle-angle",
      )
  val bob by
      transition.animateFloat(
          initialValue = -0.8f,
          targetValue = 0.8f,
          animationSpec =
              infiniteRepeatable(
                  animation = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                  repeatMode = RepeatMode.Reverse,
                  initialStartOffset = StartOffset(0),
              ),
          label = "jiggle-bob",
      )
  return this.graphicsLayer {
    rotationZ = angle
    translationY = bob
    compositingStrategy = CompositingStrategy.Offscreen
  }
}

/**
 * Round red delete affordance with a crisply-drawn, perfectly-centered ✕ (the unicode glyph
 * isn't vertically centered inside its line box, so it's drawn as two crossing strokes instead).
 */
@Composable
private fun RemoveBadge(size: Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
  Surface(
      color = Color(0xFFE53935),
      shape = CircleShape,
      modifier = modifier.size(size).tvFocusable(CircleShape) { onClick() },
  ) {
    Canvas(Modifier.fillMaxSize()) {
      val cx = this.size.width / 2f
      val cy = this.size.height / 2f
      val arm = this.size.minDimension * 0.21f
      val stroke = this.size.minDimension * 0.11f
      drawLine(
          Color.White,
          Offset(cx - arm, cy - arm),
          Offset(cx + arm, cy + arm),
          strokeWidth = stroke,
          cap = StrokeCap.Round,
      )
      drawLine(
          Color.White,
          Offset(cx - arm, cy + arm),
          Offset(cx + arm, cy - arm),
          strokeWidth = stroke,
          cap = StrokeCap.Round,
      )
    }
  }
}

/**
 * Gray minus badge for iOS-style edit mode on app icons.
 */
@Composable
private fun MinusBadge(modifier: Modifier = Modifier, onClick: () -> Unit) {
  Surface(
      color = Color(0xE6C7C7CC),
      shape = CircleShape,
      modifier = modifier
          .size(32.dp)
          .semantics { contentDescription = "Remove item" }
          .clickable(
              interactionSource = remember { MutableInteractionSource() },
              indication = null
          ) { onClick() },
  ) {
    Canvas(Modifier.fillMaxSize()) {
      val cx = this.size.width / 2f
      val cy = this.size.height / 2f
      val armWidth = this.size.minDimension * 0.30f
      val stroke = this.size.minDimension * 0.12f
      drawLine(
          Color.White,
          Offset(cx - armWidth, cy),
          Offset(cx + armWidth, cy),
          strokeWidth = stroke,
          cap = StrokeCap.Round,
      )
    }
  }
}

/**
 * Resize control for widgets: PRESS-AND-DRAG the bottom-right handle to resize (iOS/Android style).
 * Reports the accumulated drag delta continuously via [onResizeDrag]; the caller snaps to grid
 * sizes. [onResizeStart]/[onResizeEnd] bracket the gesture so the caller can capture the start span
 * and reset its accumulator. The drag consumes its pointer events so it never triggers the widget
 * body's reorder handle.
 */
@Composable
private fun ResizeBadge(
    modifier: Modifier = Modifier,
    onResizeStart: () -> Unit,
    onResizeDrag: (dragDelta: Offset) -> Unit,
    onResizeEnd: () -> Unit,
    onResizeCancel: () -> Unit,
) {
  // Keep latest callbacks so the long-lived pointerInput lambda always calls the current versions.
  val startState = rememberUpdatedState(onResizeStart)
  val dragState = rememberUpdatedState(onResizeDrag)
  val endState = rememberUpdatedState(onResizeEnd)
  val cancelState = rememberUpdatedState(onResizeCancel)
  Surface(
      color = Color(0xE6C7C7CC),
      shape = CircleShape,
      modifier = modifier
          .size(48.dp)
          .semantics { contentDescription = "Resize widget" }
          .pointerInput(Unit) {
            var resizing = false
            try {
              detectDragGestures(
                  onDragStart = { resizing = true; startState.value() },
                  onDragEnd = { resizing = false; endState.value() },
                  onDragCancel = { resizing = false; cancelState.value() },
              ) { change, dragAmount ->
                // Consume so the reorderable grid's drag handle does not also react to this pointer.
                change.consume()
                dragState.value(dragAmount)
              }
            } finally {
              if (resizing) cancelState.value()
            }
          },
  ) {
    Canvas(Modifier.fillMaxSize().padding(8.dp)) {
      // Double-headed diagonal resize arrow (↖ ↘): a diagonal shaft with an arrowhead at each end.
      val d = this.size.minDimension
      val stroke = d * 0.09f
      val m = d * 0.30f                 // arrow tip inset from edges
      val tl = Offset(m, m)             // top-left tip
      val br = Offset(d - m, d - m)     // bottom-right tip
      val head = d * 0.14f              // arrowhead leg length
      // shaft
      drawLine(Color.White, tl, br, strokeWidth = stroke, cap = StrokeCap.Round)
      // top-left arrowhead (two legs)
      drawLine(Color.White, tl, Offset(tl.x + head, tl.y), strokeWidth = stroke, cap = StrokeCap.Round)
      drawLine(Color.White, tl, Offset(tl.x, tl.y + head), strokeWidth = stroke, cap = StrokeCap.Round)
      // bottom-right arrowhead (two legs)
      drawLine(Color.White, br, Offset(br.x - head, br.y), strokeWidth = stroke, cap = StrokeCap.Round)
      drawLine(Color.White, br, Offset(br.x, br.y - head), strokeWidth = stroke, cap = StrokeCap.Round)
    }
  }
}

/**
 * Wraps a tile whose own content does NOT swallow touches (built-ins, folders, apps) with a
 * dragged-dim. The drag itself is handled by the stable container detector that wraps the pager, so
 * the gesture survives a page flip (letting a tile be dragged onto another page). Hosted widgets
 * still use their own in-page scrim handle (see [WidgetCell]), since their AndroidView swallows it.
 */
@Composable
private fun HomeTileFrame(
    modifier: Modifier,
    dragged: Boolean,
    content: @Composable () -> Unit,
) {
  Box(modifier = modifier.fillMaxWidth().alpha(if (dragged) 0f else 1f)) { content() }
}

/** Widget tile + the ✕ remove badge in edit mode. Drag is handled by the stable container detector
 * (which grabs widgets via the pointer Initial pass), so widgets drag and move across pages like
 * apps. Widgets are not user-resizable — they keep the size their provider declares. */
@Composable
private fun WidgetCell(
    widget: HomeWidgetStore.HomeWidget,
    host: AppWidgetHost,
    manager: AppWidgetManager,
    editMode: Boolean,
    tileDp: Dp,
    dimmed: Boolean,
    modifier: Modifier,
    onRemove: () -> Unit,
) {
  Box(modifier = modifier) {
    WidgetTile(
        widget = widget,
        host = host,
        manager = manager,
        editMode = editMode,
        tileDp = tileDp,
        dimmed = dimmed,
    )
    if (editMode) {
      RemoveBadge(
          size = 30.dp,
          modifier = Modifier.align(Alignment.TopEnd).offset(x = 7.dp, y = (-7).dp),
          onClick = onRemove,
      )
    }
  }
}

@Composable
private fun AppTile(
    app: AppEntry,
    editMode: Boolean,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    onDelete: () -> Unit = {},
    onLongPress: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
  Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      // In Manage mode the body tap is inert (drag to reorder, ✕ to remove); the
      // icon launches normally otherwise. A long-press enters Manage mode (iOS-style).
      modifier =
          modifier.fillMaxWidth().padding(4.dp).tvFocusable(
              RoundedCornerShape(22.dp),
              enabled = !editMode,
              onLongClick = onLongPress,
          ) {
            onClick()
          },
  ) {
    // The icon (and its delete badge) jiggle as a unit in Manage mode; the label stays still.
    Box(modifier = Modifier.jiggle(editMode && !dimmed, app.component.packageName.hashCode())) {
      Image(
          bitmap = app.icon,
          contentDescription = app.label,
          modifier =
              Modifier.size(LocalTileDp.current)
                  .alpha(if (dimmed) 0.3f else 1f),
      )
      if (editMode) {
        // Sits at the icon's outer top-right corner (nudged off the icon, iOS-style).
        RemoveBadge(
            size = 26.dp,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 9.dp, y = (-9).dp),
            onClick = onDelete,
        )
      }
    }
    Spacer(Modifier.size(8.dp))
    Text(
        app.label,
        color = ContentSecondary,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
    )
  }
}

// --- data ---------------------------------------------------------------------

private fun List<HomeWidgetStore.HomeWidget>.filterLiveWidgets(
    manager: AppWidgetManager,
    host: AppWidgetHost,
): List<HomeWidgetStore.HomeWidget> {
  val live = filter { !it.isAppWidget || manager.getAppWidgetInfo(it.appWidgetId) != null }
  if (live.size != size) {
    val liveIds = live.map { it.appWidgetId }.toSet()
    filter { it.isAppWidget && it.appWidgetId !in liveIds }.forEach {
      runCatching { host.deleteAppWidgetId(it.appWidgetId) }
    }
  }
  return live
}

private fun updateWidgetSizeOptions(
    manager: AppWidgetManager,
    widget: HomeWidgetStore.HomeWidget,
    tileDp: Dp,
) {
  val width =
      (tileDp.value * widget.spanX + 16f * (widget.spanX - 1).coerceAtLeast(0)).roundToInt()
  val height =
      (tileDp.value * widget.spanY + 20f * (widget.spanY - 1).coerceAtLeast(0)).roundToInt()
  runCatching {
    manager.updateAppWidgetOptions(
        widget.appWidgetId,
        Bundle().apply {
          putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
          putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width)
          putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
          putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
        })
  }
}

@Composable
private fun ApprovedHeaderButton(icon: String, label: String, size: Dp = 68.dp, onClick: () -> Unit) =
    ApprovedHeaderButton(icon, label, lit = false, size = size, onClick = onClick)

/** [lit]: inverted fill (the music button while a song is loaded). [size]: 56 dp in the compact tier. */
@Composable
private fun ApprovedHeaderButton(icon: String, label: String, lit: Boolean, size: Dp = 68.dp, onClick: () -> Unit) {
  Box(Modifier.size(size).clip(CircleShape)
      .background(if (lit) ApprovedHomePalette.Text else ApprovedHomePalette.Chrome).approvedOutline(CircleShape)
      .semantics { contentDescription = label }
      .tvFocusable(CircleShape, ringColor = ApprovedHomePalette.Accent, onClick = onClick),
      contentAlignment = Alignment.Center) {
    ApprovedHomeIcon(icon, tint = if (lit) ApprovedHomePalette.Background else ApprovedHomePalette.Text)
  }
}

/** A single day wash keeps chrome readable over every selected wallpaper; night is unchanged. */
@Composable
private fun ApprovedHomeBackground() {
  val context = androidx.compose.ui.platform.LocalContext.current
  var wallpaper by remember { mutableStateOf(WallpaperConfig.load(context)) }
  OnResume { wallpaper = WallpaperConfig.load(context) }
  val palette = com.immortal.launcher.ui.theme.LocalApprovedPalette.current
  Box(Modifier.fillMaxSize()) {
    if (wallpaper.mode == WallpaperConfig.SKY) {
      Box(Modifier.fillMaxSize()) {
        ApprovedHomeSky()
        ApprovedHomeBackgroundEffects(wallpaper.grain)
      }
    } else if (wallpaper.mode != WallpaperConfig.DUSK_AURORA) {
      HomeBackground(Modifier.fillMaxSize())
    } else {
      // Frozen revision04 has a static teal-to-ember background, with no idle animation.
      Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
          0f to Color(0xFF11212F), .7f to Color(0xFF213B42), 1f to Color(0xFF69453D)))) {
        Canvas(Modifier.fillMaxSize()) {
          drawRect(Brush.radialGradient(listOf(Color(0x5539646B), Color.Transparent),
              center = Offset(size.width * .3f, size.height * .6f), radius = size.width * .55f))
        }
        ApprovedHomeBackgroundEffects(wallpaper.grain)
      }
    }
    if (!palette.dark) Box(Modifier.matchParentSize().background(palette.background.copy(alpha = .92f)))
  }
}

@Composable
private fun ApprovedHomeBackgroundEffects(grain: Boolean) {
  val context = androidx.compose.ui.platform.LocalContext.current
  if (grain) {
    val noise = remember {
      val random = java.util.Random(4)
      val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
      val pixels = IntArray(128 * 128) {
        val value = random.nextInt(256)
        android.graphics.Color.argb(28, value, value, value)
      }
      bitmap.setPixels(pixels, 0, 128, 0, 0, 128, 128)
      bitmap
    }
    Canvas(Modifier.fillMaxSize()) {
      drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint().apply {
          shader = android.graphics.BitmapShader(noise, android.graphics.Shader.TileMode.REPEAT,
              android.graphics.Shader.TileMode.REPEAT)
        }
        canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint)
      }
    }
  }
  val warmth = pollingState(refreshMs = 60_000, retryMs = 60_000) { DisplayControl.currentWarmAlpha(context) }
  if ((warmth.value ?: 0f) > 0f)
    Box(Modifier.fillMaxSize().background(Color(0xFFFFB46A).copy(alpha = warmth.value ?: 0f)))
}

@OptIn(ExperimentalFoundationApi::class)
private val FAVORITE_TILE_HEIGHT = 72.dp
private val FAVORITE_ICON_SIZE = 46.dp
/** Upright Mini: six ~112 dp tiles, so the label sits under the icon instead of beside it. */
private val COMPACT_FAVORITE_TILE_HEIGHT = 88.dp

private fun favoriteTileHeight(layout: LayoutClass): Dp =
    if (layout.compactPortrait) COMPACT_FAVORITE_TILE_HEIGHT else FAVORITE_TILE_HEIGHT

@Composable
private fun ApprovedFavoriteTile(app: AppEntry, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit,
                                 layout: LayoutClass = LayoutClass.LARGE) {
  if (layout.compactPortrait) {
    ApprovedHomeSurface(modifier.height(COMPACT_FAVORITE_TILE_HEIGHT), radius = 16.dp) {
      Column(Modifier.fillMaxSize().combinedClickable(onClick = onClick, onLongClick = onLongClick)
          .padding(horizontal = 6.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center) {
        Image(app.icon, contentDescription = null, modifier = Modifier.size(40.dp))
        Text(app.label, color = ApprovedHomePalette.Text, fontSize = 16.sp, lineHeight = 20.sp,
            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp))
      }
    }
    return
  }
  ApprovedHomeSurface(modifier.height(FAVORITE_TILE_HEIGHT), radius = 16.dp) {
    Row(Modifier.fillMaxSize().combinedClickable(onClick = onClick, onLongClick = onLongClick)
        .padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
      Image(app.icon, contentDescription = null, modifier = Modifier.size(FAVORITE_ICON_SIZE))
      Text(app.label, color = ApprovedHomePalette.Text, fontSize = if (layout.compactLandscape) 19.sp else 22.sp,
          lineHeight = 30.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

@Composable
private fun ApprovedAppActions(
    app: AppEntry,
    assignments: Map<String, String>,
    favorite: Boolean,
    onLaunch: () -> Unit,
    onFavorite: () -> Unit,
    onFolder: (String) -> Unit,
    onUninstall: () -> Unit,
    onDismiss: () -> Unit,
) {
  val context = androidx.compose.ui.platform.LocalContext.current
  var confirmUninstall by remember { mutableStateOf(false) }
  var editingFolder by remember { mutableStateOf(false) }
  var newFolder by remember { mutableStateOf("") }
  ApprovedHomeDialog(app.label, onDismiss) {
    if (confirmUninstall) {
      Text("Uninstall ${app.label}? Android will ask you to confirm.", color = ApprovedHomePalette.Secondary, fontSize = 23.sp)
      ApprovedHomeTextButton("Cancel") { confirmUninstall = false }
      ApprovedHomeTextButton("Uninstall", onClick = onUninstall)
    } else if (editingFolder) {
      Text("Move to folder", color = ApprovedHomePalette.Text, fontSize = 26.sp)
      (assignments.values + Curation.folders.values).filter { it.isNotBlank() }.distinct().sorted().forEach { name ->
        ApprovedHomeTextButton(name, Modifier.fillMaxWidth()) { onFolder(name) }
      }
      androidx.compose.material3.OutlinedTextField(newFolder, { newFolder = it }, singleLine = true,
          placeholder = { Text("New folder name") }, modifier = Modifier.fillMaxWidth())
      ApprovedHomeTextButton("Create folder", enabled = newFolder.isNotBlank()) { onFolder(newFolder.trim()) }
      ApprovedHomeTextButton("Remove from folder") { onFolder("") }
    } else {
      ApprovedHomeTextButton("Open", onClick = onLaunch)
      ApprovedHomeTextButton(if (favorite) "Remove from favorites" else "Add to favorites", onClick = onFavorite)
      ApprovedHomeTextButton("Move to folder") { editingFolder = true }
      if (app.component.packageName != context.packageName) {
        ApprovedHomeTextButton("App info") {
          runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
              Uri.parse("package:${app.component.packageName}"))) }; onDismiss()
        }
        ApprovedHomeTextButton("Close app") {
          runCatching { (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
              .killBackgroundProcesses(app.component.packageName) }; onDismiss()
        }
      }
      if (app.uninstallable) ApprovedHomeTextButton("Uninstall") { confirmUninstall = true }
    }
  }
}

@Composable
private fun ApprovedWidgetLibrary(
    advanced: Boolean,
    configured: List<HomeWidgetStore.HomeWidget>,
    providers: List<WidgetProviderEntry>?,
    room: String,
    connected: Boolean?,
    onAdd: (String) -> Unit,
    onProvider: (WidgetProviderEntry) -> Unit,
    onDismiss: () -> Unit,
    onHome: () -> Unit,
    onCustomize: () -> Unit,
    onRefresh: () -> Unit,
    onPair: () -> Unit,
    onSleep: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
  var showProviders by rememberSaveable { mutableStateOf(false) }
  val kinds = remember(configured) { configured.map { it.kind }.toSet() }
  val builtIns = remember {
    listOf(
        Triple(HomeWidgetStore.KIND_TODAY, "Today", "calendar"),
        Triple(HomeWidgetStore.KIND_HA, "Home Assistant", "ha_widget"),
        Triple(HomeWidgetStore.KIND_CALENDAR, "Calendar", "calendar"),
        Triple(HomeWidgetStore.KIND_WEATHER, "Weather", "weather"),
        Triple(HomeWidgetStore.KIND_ALARM, "Alarm", "alarm"),
        Triple(HomeWidgetStore.KIND_WORLD_CLOCK, "World clock", "clock"),
        Triple(HomeWidgetStore.KIND_CRYPTO, "Crypto", "calculator"),
        Triple(HomeWidgetStore.KIND_QUICK_CONTROLS, "Quick controls", "tools"),
        Triple(HomeWidgetStore.KIND_PHOTO_MEMORY, "Photo memory", "photo"),
        Triple(HomeWidgetStore.KIND_TIMERS, "Timers", "timer"),
        Triple(HomeWidgetStore.KIND_TODO, "Household list", "notes"),
        Triple(HomeWidgetStore.KIND_WIFI, "Wi-Fi", "wifi"),
        Triple(HomeWidgetStore.KIND_CHORES, "Chores", "tools"),
        Triple(HomeWidgetStore.KIND_CALLS, "Calls", "calls"),
        Triple(HomeWidgetStore.KIND_NEWS, "News", "globe"),
        Triple(HomeWidgetStore.KIND_STOCKS, "Stocks", "calculator"),
        Triple(HomeWidgetStore.KIND_OPTIONS, "Options", "calculator"),
        Triple(HomeWidgetStore.KIND_APP_WIDGET, "Android app widget", "apps"),
    )
  }
  val choices = remember(advanced, kinds, builtIns) {
    builtIns.filter { advanced || it.first != HomeWidgetStore.KIND_OPTIONS }
        .map { (kind, title, icon) ->
          ApprovedHomeLibraryCard(kind, title, icon, if (kind in kinds) "On your home" else "Add to your home")
        }
  }
  val providerByKey = remember(providers) {
    providers.orEmpty().associateBy { it.info!!.provider.flattenToString() }
  }
  val providerCards = remember(providerByKey) {
    providerByKey.map { (key, entry) ->
      ApprovedHomeLibraryCard(key, entry.label, "apps", entry.packageLabel, entry.icon)
    }
  }
  ApprovedHomeWidgetLibraryScreen(
      room, connected, if (showProviders) providerCards else choices, showProviders, providers == null,
      onSelect = { key ->
        if (showProviders) providerByKey[key]?.let(onProvider)
        else if (key == HomeWidgetStore.KIND_APP_WIDGET) showProviders = true
        else onAdd(key)
      },
      onBack = { if (showProviders) showProviders = false else onDismiss() },
      onHome = onHome, onCustomize = onCustomize, onRefresh = onRefresh,
      onPair = onPair, onSleep = onSleep, onSearch = onSearch, onSettings = onSettings,
  )
}

/** Minute ticking is scoped to this chrome; the dashboard never reads this clock state. */
@Composable
private fun ApprovedHomeGreeting(
    welcome: WelcomeConfig.Settings,
    use24: Boolean,
    compact: Boolean = false,
    layout: LayoutClass = LayoutClass.LARGE,
) {
  val now by produceState(initialValue = Date()) {
    while (true) {
      delay(60_000L - System.currentTimeMillis() % 60_000L)
      value = Date()
    }
  }
  val timeFmt = remember(use24) { SimpleDateFormat(if (use24) "H:mm" else "h:mm", Locale.getDefault()) }
  val dayFmt = remember { SimpleDateFormat("EEEE", Locale.getDefault()) }
  val dateFmt = remember { SimpleDateFormat("MMMM d", Locale.getDefault()) }
  val zone = TimeZone.getDefault()
  timeFmt.timeZone = zone
  dayFmt.timeZone = zone
  dateFmt.timeZone = zone
  val hour = java.util.Calendar.getInstance(zone).apply { time = now }.get(java.util.Calendar.HOUR_OF_DAY)
  val greeting = when (hour) {
    in 5..11 -> welcome.greetingMorning
    in 12..16 -> welcome.greetingAfternoon
    in 17..21 -> welcome.greetingEvening
    else -> welcome.greetingNight
  }
  val greetingText = if (welcome.userName.isBlank()) greeting.trimEnd('.', '!') + "."
      else greeting.trimEnd('.', '!') + ", " + welcome.userName
  if (layout.compact) {
    CompactHomeGreeting(greetingText, timeFmt.format(now), dayFmt.format(now), dateFmt.format(now),
        offline = compact, portrait = layout.compactPortrait)
    return
  }
  // Minimum, not fixed, height: a fixed 96dp clipped the subtitle's descenders on device.
  Row(Modifier.fillMaxWidth().heightIn(min = if (compact) 68.dp else 96.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text(greetingText, color = ApprovedHomePalette.Text, fontSize = 52.sp, lineHeight = 58.sp,
          fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      if (!compact) Text("Everything within reach.", color = ApprovedHomePalette.Secondary, fontSize = 24.sp,
          lineHeight = 32.sp, modifier = Modifier.padding(top = 4.dp))
    }
    Text(timeFmt.format(now), color = ApprovedHomePalette.Text, fontSize = 80.sp, lineHeight = 88.sp,
        fontWeight = FontWeight.Normal, letterSpacing = (-2).sp)
    Text(dayFmt.format(now) + "\n" + dateFmt.format(now), color = ApprovedHomePalette.Secondary,
        fontSize = 24.sp, lineHeight = 34.sp, modifier = Modifier.padding(start = 24.dp))
  }
}

/**
 * The compact greeting. Upright (800 dp): the greeting on its own line above the clock and date,
 * so neither truncates the other. Turned (1280×740 dp): one row, smaller, to leave the widgets height.
 * Every line is single-line; nothing can wrap mid-word.
 */
@Composable
private fun CompactHomeGreeting(greeting: String, time: String, day: String, date: String, offline: Boolean,
                                portrait: Boolean) {
  if (portrait) {
    Column(Modifier.fillMaxWidth()) {
      Text(greeting, color = ApprovedHomePalette.Text, fontSize = 36.sp, lineHeight = 44.sp,
          fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(time, color = ApprovedHomePalette.Text, fontSize = 56.sp, lineHeight = 64.sp,
            fontWeight = FontWeight.Normal, letterSpacing = (-1.5).sp, maxLines = 1, softWrap = false)
        Text("$day, $date", color = ApprovedHomePalette.Secondary, fontSize = 22.sp, lineHeight = 30.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 20.dp))
      }
      if (!offline) Text("Everything within reach.", color = ApprovedHomePalette.Secondary, fontSize = 20.sp,
          lineHeight = 28.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  } else {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(greeting, color = ApprovedHomePalette.Text, fontSize = 40.sp, lineHeight = 48.sp,
          fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
      Text(time, color = ApprovedHomePalette.Text, fontSize = 60.sp, lineHeight = 66.sp,
          fontWeight = FontWeight.Normal, letterSpacing = (-1.5).sp, maxLines = 1, softWrap = false,
          modifier = Modifier.padding(start = 24.dp))
      Text(day + "\n" + date, color = ApprovedHomePalette.Secondary, fontSize = 20.sp, lineHeight = 26.sp,
          maxLines = 2, softWrap = false, modifier = Modifier.padding(start = 20.dp))
    }
  }
}
