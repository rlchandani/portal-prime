/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

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
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.immortal.launcher.ui.components.GlassCard
import com.immortal.launcher.ui.components.Glass
import com.immortal.launcher.ui.components.OnResume
import com.immortal.launcher.ui.components.WidgetPlaceholder
import com.immortal.launcher.ui.components.launchSafely
import com.immortal.launcher.ui.components.pollingState
import com.immortal.launcher.ui.theme.LocalAdvancedSettings
import com.immortal.launcher.ui.theme.ContentPrimary
import com.immortal.launcher.ui.theme.ContentSecondary
import com.immortal.launcher.ui.theme.PrimeBlue
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import org.json.JSONObject

internal data class AppEntry(
    val label: String,
    val component: ComponentName,
    val icon: ImageBitmap,
    val folder: String? = null,
    val uninstallable: Boolean = false,
)

private data class WidgetProviderEntry(
    val label: String,
    val packageLabel: String,
    val icon: ImageBitmap?,
    val info: AppWidgetProviderInfo?,
    val spanX: Int,
    val spanY: Int,
    val customKind: String? = null,
)

private data class PendingWidgetAdd(
    val appWidgetId: Int,
    val provider: WidgetProviderEntry,
)

/** Home's immutable inventory snapshots retain their icons across Activity resumes/recreation.
 * The application receiver survives Home being stopped; no Activity is retained by this cache.
 */
private object ApprovedHomeInventory {
  val revision = MutableStateFlow(0L)
  val apps = HomePresentationInventoryCache<AppEntry>()
  val providers = HomePresentationInventoryCache<WidgetProviderEntry>()
  private var registered = false

  @Synchronized fun invalidate() {
    apps.invalidate()
    providers.invalidate()
    revision.value++
  }

  @Synchronized fun register(context: Context) {
    if (registered) return
    val filter = android.content.IntentFilter().apply {
      addAction(Intent.ACTION_PACKAGE_ADDED)
      addAction(Intent.ACTION_PACKAGE_REMOVED)
      addAction(Intent.ACTION_PACKAGE_CHANGED)
      addAction(Intent.ACTION_PACKAGE_REPLACED)
      addDataScheme("package")
    }
    val receiver = object : android.content.BroadcastReceiver() {
      override fun onReceive(c: Context, intent: Intent) { invalidate() }
    }
    registered = runCatching {
      context.applicationContext.registerReceiver(receiver, filter)
      true
    }.getOrDefault(false)
  }
}

/** Package changes refresh every consumer of the shared app artwork inventory. */
internal val approvedIconInventoryRevision: StateFlow<Long>
  get() = ApprovedHomeInventory.revision

/** A dashboard address changes the shortcut inventory, without another package scan on each frame. */
internal fun invalidateApprovedDashboardInventory() = ApprovedHomeInventory.invalidate()

/** Home warms this inventory before Apps is opened; exclude Home's injected shortcuts. */
internal fun peekApprovedInstalledApps(context: Context): List<AppEntry>? =
    ApprovedHomeInventory.apps.peek()?.let { approvedInstalledEntries(context, it) }

internal fun approvedInstalledEntries(context: Context, inventory: List<AppEntry>): List<AppEntry> =
    inventory.filter {
      it.component.packageName != context.packageName && it.component != CALLS_COMPONENT
    }

/** Reuse the same already-rendered shortcut artwork as Home; never decode from composition. */
internal fun peekApprovedIconInventory(): List<AppEntry>? = ApprovedHomeInventory.apps.peek()

internal fun approvedShortcutIcons(context: Context, inventory: List<AppEntry>): Map<String, ImageBitmap> {
  if (inventory.isEmpty()) return emptyMap()
  // Warm Apps prepares this map from composition; build targets once for the whole inventory.
  val store = ComponentName(context, StoreActivity::class.java)
  val music = ComponentName(context, WebAppActivity::class.java)
  val dashboard = ComponentName(context, HomeDashboardActivity::class.java)
  val settings = ComponentName(context, ImmortalSettingsActivity::class.java)
  return inventory.mapNotNull { app ->
    val key = when (app.component) {
      store -> "store"
      music -> "music"
      dashboard -> "dashboard"
      settings -> "settings"
      CALLS_COMPONENT -> "calls"
      else -> null
    }
    key?.let { it to app.icon }
  }.toMap()
}

/** Run on IO; cold Home and Apps requests share one package/icon scan. */
internal fun loadApprovedIconInventory(context: Context): List<AppEntry> {
  ApprovedHomeInventory.register(context)
  return ApprovedHomeInventory.apps.load { loadApps(context.applicationContext) }
}

internal fun loadApprovedInstalledApps(context: Context): List<AppEntry> =
    approvedInstalledEntries(context, loadApprovedIconInventory(context))

private const val HOME_APP_WIDGET_HOST_ID = 0x4611
private const val SAVED_LAUNCHER_TAB = "approved_saved_launcher_tab"

// Sentinel component for the injected "Calls" grid tile — no real Activity backs it; onLaunch
// recognizes this and bridges to the stock Portal calling surface via launchStockHome().
private val CALLS_COMPONENT = ComponentName("com.immortal.launcher", "__immortal_calls_bridge__")

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
  private var pendingResumeIntent: Intent? = null
  private var cancelWebViewStartup: (() -> Unit)? = null

  /** Read-only navigation diagnostics; never simulate Android lifecycle callbacks to read state. */
  internal fun approvedCurrentTab(): ApprovedLauncherTab = launcherTab

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // A restored Activity carries its old launch Intent; only a new launch or onNewIntent
    // delivery can identify Meta's bootstrap redirect.
    pendingResumeIntent = if (savedInstanceState == null) intent else null
    launcherTab = ApprovedLauncherTab.fromId(savedInstanceState?.getString(SAVED_LAUNCHER_TAB))
        ?: ApprovedLauncherTab.HOME
    consumeLauncherTabIntent(intent)
    consumeWidgetLibraryIntent(intent)
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
            selectedTab = launcherTab,
            onSelectTab = { launcherTab = it },
            onLaunch = { cn ->
              runCatching {
                if (cn == CALLS_COMPONENT) {
                  // The "Calls" tile: bridge to the stock Portal calling surface (trusted caller).
                  launchStockHome()
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
                    cn.className == WebAppActivity::class.java.name) {
                  // The Music tile: YouTube Music's PWA in the kiosk WebView (no GMS client).
                  startActivity(
                      Intent(this@HomeActivity, WebAppActivity::class.java)
                          .putExtra(WebAppActivity.EXTRA_URL, WebAppActivity.URL_YT_MUSIC)
                          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
    // allow the photo frame to resume its normal screensaver behaviour.
    DreamPolicy.clearBridge(this)
    SettingsGuard.reaffirmScreensaver(this)
    // The user is back on the launcher: end the idle session and, inside the overnight window,
    // arm the touch-renewed "you have the device" session. SleepScheduler owns that policy.
    SleepScheduler.onReturnedToLauncher(this)
    ApprovedHomeNavigation.resumed(this)
    if (BuildConfig.PORTAL_WEBVIEW_BACKGROUND_STARTUP) {
      cancelWebViewStartup?.invoke()
      cancelWebViewStartup =
          WebViewStartupPreparation.scheduleForHome(applicationContext, window.decorView)
    }
  }

  override fun onPause() {
    cancelWebViewStartup?.invoke()
    cancelWebViewStartup = null
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
    onChanged: (List<HomeWidgetStore.HomeWidget>) -> Unit,
    onEnterEdit: () -> Unit,
    onRemove: (HomeWidgetStore.HomeWidget) -> Unit,
    displaySpan: Pair<Int, Int> = widget.spanX to widget.spanY,
    onMove: () -> Unit = {},
    dragHandle: Modifier = Modifier,
) {
    key(widget.key) {
      val context = LocalContext.current
      var showTimers by remember { mutableStateOf(false) }
      var widthPx by remember { mutableStateOf(0) }
      var heightPx by remember { mutableStateOf(0) }
      var resizeAccum by remember { mutableStateOf(Offset.Zero) }
      var startSpan by remember { mutableStateOf(widget.spanX to widget.spanY) }
      var resizeDraft by remember { mutableStateOf<Pair<Int, Int>?>(null) }
      var columnUnit by remember { mutableStateOf(1f) }
      var rowUnit by remember { mutableStateOf(1f) }
      val density = androidx.compose.ui.platform.LocalDensity.current
      val gapPx = with(density) { 24.dp.toPx() }
      val resizeRtl = androidx.compose.ui.platform.LocalLayoutDirection.current ==
          androidx.compose.ui.unit.LayoutDirection.Rtl
      LaunchedEffect(editMode) { resizeDraft = null; resizeAccum = Offset.Zero }
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
          Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
              .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
              .background(ApprovedHomePalette.Card).padding(start = 6.dp, top = 6.dp, bottom = 6.dp, end = 58.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically) {
            ApprovedWidgetAction("Move", Modifier.weight(1f), onClick = onMove)
            ApprovedWidgetAction("Size", Modifier.weight(1f)) {
              val next = HomeWidgetStore.nextWidgetSpan(displaySpan)
              onChanged(widgets.map { if (it.key == widget.key) it.copy(
                  spanX = next.first, spanY = next.second, explicitSize = true) else it })
            }
            ApprovedWidgetAction("Settings", Modifier.weight(1f)) {
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
                  HomeWidgetStore.KIND_PHOTO_MEMORY -> "screensaver"
                  HomeWidgetStore.KIND_QUICK_CONTROLS -> "quickbar"
                  else -> widget.kind
                })
              }
            }
            ApprovedWidgetAction("Remove", Modifier.weight(1f)) { onRemove(widget) }
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
            resizeAccum = Offset.Zero
            resizeDraft = displaySpan
            columnUnit = ((widthPx - gapPx * (displaySpan.first - 1)) / displaySpan.first).coerceAtLeast(1f) + gapPx
            rowUnit = ((heightPx - gapPx * (displaySpan.second - 1)) / displaySpan.second).coerceAtLeast(1f) + gapPx
          }, onResizeDrag = { delta ->
            resizeAccum += Offset(if (resizeRtl) -delta.x else delta.x, delta.y)
            fun steps(value: Float, unit: Float): Int = (kotlin.math.sign(value) *
                kotlin.math.floor(kotlin.math.abs(value / unit) + .35f)).toInt()
            val x = (startSpan.first + steps(resizeAccum.x, columnUnit)).coerceIn(1, 4)
            val y = (startSpan.second + steps(resizeAccum.y, rowUnit)).coerceIn(1, 4)
            resizeDraft = x to y
          }, onResizeEnd = {
            resizeDraft?.takeIf { it != startSpan }?.let { span ->
              onChanged(widgets.map { if (it.key == widget.key) it.copy(
                  spanX = span.first, spanY = span.second, explicitSize = true) else it })
            }
            resizeDraft = null; resizeAccum = Offset.Zero
          }, onResizeCancel = { resizeDraft = null; resizeAccum = Offset.Zero })
        }
      }
      if (showTimers) ApprovedHomeTimersPage { showTimers = false }
    }
}

@Composable
private fun ApprovedWidgetAction(label: String, modifier: Modifier, onClick: () -> Unit) {
  Box(modifier.heightIn(min = 52.dp)
      .tvFocusable(RoundedCornerShape(12.dp), focusScale = 1f, onClick = onClick)
      .padding(horizontal = 6.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
    Text(label, color = ApprovedHomePalette.Accent, fontSize = 18.sp, lineHeight = 26.sp, maxLines = 1)
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
    onChanged: (List<HomeWidgetStore.HomeWidget>) -> Unit,
    onEnterEdit: () -> Unit,
    onRemove: (HomeWidgetStore.HomeWidget) -> Unit,
) {
  val spans = remember(visible) { HomePresentation.spans(visible) }
  var move by remember { mutableStateOf<HomeWidgetStore.HomeWidget?>(null) }
  ApprovedPackedWidgetGrid(visible, spans, editMode, gridModifier,
      onMove = { source, target -> onChanged(HomePresentation.move(widgets, source, target)) }) { widget, cell, span, drag ->
    ApprovedWidgetCell(widget, cell, widgets, editMode, host, manager, onChanged, onEnterEdit,
        onRemove, span.columns to span.rows, onMove = { move = widget }, dragHandle = drag)
  }
  move?.let { selected ->
    ApprovedHomeDialog("Move ${customWidgetLabel(selected.kind)}", onDismiss = { move = null }) {
      Text("Choose a position, including on another page.", color = ApprovedHomePalette.Secondary, fontSize = 23.sp)
      widgets.forEachIndexed { index, target ->
        if (target.key != selected.key) ApprovedHomeTextButton("${index + 1}. ${customWidgetLabel(target.kind)}") {
          onChanged(HomePresentation.move(widgets, selected.key, target.key)); move = null
        }
      }
    }
  }
}

/** Home and Customize keep the same page, identities, measurements and placement rules. */
@Composable
private fun ApprovedWidgetPage(
    displayed: HomePresentation.Page,
    widgets: List<HomeWidgetStore.HomeWidget>,
    host: AppWidgetHost,
    manager: AppWidgetManager,
    onChanged: (List<HomeWidgetStore.HomeWidget>) -> Unit,
    onEnterEdit: () -> Unit,
    onRemove: (HomeWidgetStore.HomeWidget) -> Unit,
    editMode: Boolean = false,
) {
  val pageWidgets = remember(displayed, widgets) { HomePresentation.pageWidgets(widgets, displayed) }
  ApprovedWidgetGrid(pageWidgets, Modifier.fillMaxSize(), widgets, editMode, host, manager,
      onChanged, onEnterEdit, onRemove)
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
    onChanged: (List<HomeWidgetStore.HomeWidget>) -> Unit,
    onEnterEdit: () -> Unit,
    onPageChanged: (Int) -> Unit,
    navigation: ApprovedHomePagerNavigation,
    preserveCounterFocus: Boolean,
    modifier: Modifier = Modifier,
) {
  var remove by remember { mutableStateOf<HomeWidgetStore.HomeWidget?>(null) }
  val onRemove: (HomeWidgetStore.HomeWidget) -> Unit = remember { { remove = it } }
  val configuration = androidx.compose.ui.platform.LocalConfiguration.current
  val isTv = configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK ==
      android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
  if (!isTv) {
    val prepared = remember(widgets) {
      List(HomePresentation.page(widgets, 0).count) { HomePresentation.page(widgets, it) }
    }
    val homeActive = LocalApprovedLauncherPageActive.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val currentPage = rememberUpdatedState(page)
    val pageChanged = rememberUpdatedState(onPageChanged)
    val slide = remember { Animatable(page.index.toFloat()) }
    val swipeThreshold = with(androidx.compose.ui.platform.LocalDensity.current) { 64.dp.toPx() }
    LaunchedEffect(page.index, page.count) {
      if (!preserveCounterFocus) focus.clearFocus(force = true)
      // Removed widgets or numbered Move can skip pages outside the retained neighbors.
      if (slide.value > prepared.lastIndex || abs(slide.value - page.index) > 1f)
        slide.snapTo(page.index.toFloat())
      else slide.animateTo(page.index.toFloat(), tween(180, easing = EaseOutCubic))
    }
    BoxWithConstraints(modifier.clipToBounds().semantics { contentDescription = "Home widgets" }
        .then(if (!homeActive || page.count < 2) Modifier else Modifier.pointerInput(page.count) {
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
      val first = (page.index - 1).coerceAtLeast(0)
      val last = (page.index + 1).coerceAtMost(prepared.lastIndex)
      for (index in first..last) key(index) {
        val displayed = prepared[index]
        val selected = index == page.index
        val active = homeActive && selected
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
          CompositionLocalProvider(LocalApprovedWidgetPageInput provides active) {
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
      CompositionLocalProvider(LocalApprovedWidgetPageInput provides active) {
        ApprovedWidgetPage(HomePresentation.page(widgets, index), widgets, host, manager,
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
          if (widget.isAppWidget) runCatching { host.deleteAppWidgetId(widget.appWidgetId) }
          onChanged(HomeWidgetStore.without(widgets, widget.key))
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
private fun ApprovedHomeDialog(
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
    onChanged: (List<HomeWidgetStore.HomeWidget>) -> Unit,
    onEnterEdit: () -> Unit,
    onAddWidget: () -> Unit,
    onAllApps: () -> Unit,
    modifier: Modifier = Modifier,
) {
  var pageIndex by remember { mutableStateOf(0) }
  var counterFocused by remember { mutableStateOf(false) }
  val navigation = remember { ApprovedHomePagerNavigation() }
  val page = remember(widgets, pageIndex) { HomePresentation.page(widgets, pageIndex) }
  LaunchedEffect(page.index) { pageIndex = page.index }
  val changePage: (Int) -> Unit = { index ->
    navigation.request(index)
    pageIndex = index
  }
  Column(modifier) {
    ApprovedWidgetArea(widgets, page, editMode, host, manager, onChanged,
        onEnterEdit = onEnterEdit, onPageChanged = changePage, navigation = navigation,
        preserveCounterFocus = counterFocused,
        modifier = Modifier.weight(1f).fillMaxWidth())
    Spacer(Modifier.height(if (compact) 16.dp else 26.dp))
    Row(Modifier.fillMaxWidth().height(if (compact) 44.dp else 52.dp),
        verticalAlignment = Alignment.CenterVertically) {
      Text("Favorite apps", color = ApprovedHomePalette.Text, fontSize = 26.sp, lineHeight = 35.sp,
          fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
      if (editMode) ApprovedHomeTextButton("Add widget", onClick = onAddWidget)
      Text("${page.index + 1} of ${page.count}", color = ApprovedHomePalette.Text, fontSize = 24.sp,
          modifier = Modifier.padding(horizontal = 20.dp).semantics {
            contentDescription = "Widget page ${page.index + 1} of ${page.count}"
            customActions = buildList {
              if (page.index > 0) add(CustomAccessibilityAction("Previous widget page") {
                changePage(page.index - 1); true
              })
              if (page.index < page.count - 1) add(CustomAccessibilityAction("Next widget page") {
                changePage(page.index + 1); true
              })
            }
          }.onPreviewKeyEvent {
            if (it.type != KeyEventType.KeyDown) false
            else {
              val target = when (it.key) {
                Key.DirectionRight -> page.index + 1
                Key.DirectionLeft -> page.index - 1
                else -> return@onPreviewKeyEvent false
              }.coerceIn(0, page.count - 1)
              if (target == page.index) false
              else {
                changePage(target)
                true
              }
            }
          }.onFocusChanged { counterFocused = it.isFocused }.focusable())
      ApprovedHomeTextButton("All apps", onClick = onAllApps)
    }
  }
}

@Composable
private fun LauncherScreen(
    widgetLibraryRequest: Int,
    appsSearchOpenRequest: Int,
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
    mutableStateOf(HomeWidgetStore.load(context))
  }
  OnResume { homeWidgets = HomeWidgetStore.load(context) }
  val saveWidgets: (List<HomeWidgetStore.HomeWidget>) -> Unit = {
    homeWidgets = it
    HomeWidgetStore.save(context, it)
  }
  val host = remember { AppWidgetHost(context, HOME_APP_WIDGET_HOST_ID) }
  val manager = remember { AppWidgetManager.getInstance(context) }
  var pending by remember { mutableStateOf<PendingWidgetAdd?>(null) }
  val completeAdd: () -> Unit = {
    pending?.let { addition ->
      addition.provider.info?.let { info ->
        saveWidgets(HomeWidgetStore.withAdded(homeWidgets, HomeWidgetStore.HomeWidget(
            appWidgetId = addition.appWidgetId,
            providerPackage = info.provider.packageName, providerClass = info.provider.className,
            spanX = addition.provider.spanX, spanY = addition.provider.spanY)))
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
    val component = addition?.provider?.info?.configure
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
      initialValue = ApprovedHomeInventory.providers.peek(), inventoryRevision, showLibrary) {
    if (showLibrary) {
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
  BackHandler(enabled = editMode) { editMode = false }
  BackHandler(enabled = selectedTab != ApprovedLauncherTab.HOME) { onSelectTab(ApprovedLauncherTab.HOME) }
  CompositionLocalProvider(
      com.immortal.launcher.ui.theme.LocalApprovedPalette provides
          if (com.immortal.launcher.ui.theme.LocalApprovedPalette.current.dark) ApprovedHomeTabPalette
          else com.immortal.launcher.ui.theme.ApprovedLightPalette,
      LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = FontFamily.Default)) {
    Box(Modifier.fillMaxSize()) {
      // A single cached display list for the static/minute-updated wallpaper.
      Box(Modifier.matchParentSize().graphicsLayer {}) { ApprovedHomeBackground() }
      Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(108.dp).padding(horizontal = 56.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("immortal", color = ApprovedHomePalette.Text, fontSize = 30.sp, lineHeight = 40.sp,
                fontWeight = FontWeight.Medium, letterSpacing = (-.6).sp)
            Box(Modifier.padding(horizontal = 12.dp).width(1.dp).height(40.dp).background(ApprovedHomePalette.Border))
            Text(room, color = ApprovedHomePalette.Secondary, fontSize = 22.sp)
          }
          ApprovedHeaderButton("screensaver", "Start screensaver", onStartScreensaver)
          Spacer(Modifier.weight(1f))
          ApprovedHomeIcon("wifi", Modifier.size(22.dp))
          Text(when (connected) { true -> "Connected"; false -> "Offline"; else -> "Connecting" }, color = ApprovedHomePalette.Secondary, fontSize = 20.sp)
          if (selectedTab == ApprovedLauncherTab.HOME)
            ApprovedHomeTextButton(if (editMode) "Done" else "Customize") { editMode = !editMode }
          ApprovedHeaderButton("remote", "Connect your phone") {
            val (url, pin) = enableRemoteAndMintPin(context)
            pairUrl = url; pairPin = pin; showPair = true
          }
          ApprovedHeaderButton("sleep", "Sleep screen") { showSleep = true }
          ApprovedThemeButton()
          val hey by produceState<String?>(null, installedApps) { value = withContext(Dispatchers.IO) { heyPackage(context) } }
          hey?.let { pkg ->
            Box(Modifier.size(68.dp).clip(CircleShape).background(ApprovedHomePalette.Chrome)
                .semantics { contentDescription = "Voice assistant" }
                .tvFocusable(CircleShape, onLongClick = { openHeyPicker(context, pkg) }) { fireHey(context, pkg) },
                contentAlignment = Alignment.Center) { MicGlyph(ApprovedHomePalette.Text) }
          }
          if (settings.showMiniPlayer) ApprovedHomeHeaderPlayer(Modifier.width(300.dp))
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
        ApprovedHomeTabs(selectedTab, Modifier.weight(1f).fillMaxWidth()) { tab ->
          when (tab) {
          ApprovedLauncherTab.HOME -> {
          ApprovedHomeWidgetListening(host, enabled = homeWidgets.any { it.isAppWidget })
          Column(Modifier.fillMaxSize().padding(horizontal = 56.dp).padding(top = if (connected == false) 8.dp else 20.dp)) {
            ApprovedHomeGreeting(welcome, use24, compact = connected == false)
            Spacer(Modifier.height(if (connected == false) 16.dp else 28.dp))
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
            ApprovedHomePagedWidgets(homeWidgets, editMode, connected == false, host, manager, saveWidgets,
                onEnterEdit = { editMode = true }, onAddWidget = { showLibrary = true },
                onAllApps = { onSelectTab(ApprovedLauncherTab.APPS) },
                modifier = Modifier.weight(1f).fillMaxWidth())
            Spacer(Modifier.height(if (connected == false) 6.dp else 16.dp))
            Row(Modifier.fillMaxWidth().height(88.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
              favorites.forEach { app ->
                ApprovedFavoriteTile(app, Modifier.weight(1f), { onLaunch(app.component) }, { appMenu = app })
              }
              repeat(6 - favorites.size) { Spacer(Modifier.weight(1f)) }
            }
          }
          }
          ApprovedLauncherTab.APPS -> ApprovedAppsScreen(
              onExit = { onSelectTab(ApprovedLauncherTab.HOME) }, embedded = true,
              searchRequest = appsSearchRequest,
              openSearchRequest = appsSearchOpenRequest,
              onSelectTab = { id -> ApprovedLauncherTab.fromId(id)?.let(onSelectTab) })
          ApprovedLauncherTab.TOOLS -> ApprovedToolsScreen(
              onExit = { onSelectTab(ApprovedLauncherTab.HOME) }, embedded = true,
              libraryRequest = toolsLibraryRequest)
          }
        }
        Spacer(Modifier.height(24.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
          ApprovedHomeDock(selectedTab.id) { destination ->
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
        Spacer(Modifier.height(16.dp))
      }
      if (showLibrary) ApprovedWidgetLibrary(LocalAdvancedSettings.current, homeWidgets, providers, room, connected,
          onAdd = { kind ->
            val spanY = if (kind in listOf(HomeWidgetStore.KIND_TODAY, HomeWidgetStore.KIND_QUICK_CONTROLS,
                HomeWidgetStore.KIND_PHOTO_MEMORY)) 1 else 2
            saveWidgets(HomeWidgetStore.withAdded(homeWidgets, HomeWidgetStore.custom(kind, spanY = spanY)))
            showLibrary = false
          }, onProvider = { provider ->
            val info = provider.info
            if (info != null) {
              val id = host.allocateAppWidgetId()
              pending = PendingWidgetAdd(id, provider)
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
              order = if (favorites.any { it.component == app.component }) all + id else listOf(id) + all
              UserLayout.saveOrder(context, order); appMenu = null
            }, onFolder = { target ->
              assignments = assignments + (app.component.packageName to target)
              UserLayout.save(context, assignments); appMenu = null
            }, onUninstall = { appMenu = null; onUninstall(app.component.packageName) },
            onDismiss = { appMenu = null })
      }
    }
  }

}

// Minimal weather condition text without network deps
private fun weatherConditionText(code: Int): String = when (code) {
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

private fun estimateWidgetSpan(minDp: Int, tileDp: Dp): Int {
  if (minDp <= 0) return HomeWidgetStore.DEFAULT_SPAN_X
  val tile = tileDp.value.coerceAtLeast(1f)
  return HomeWidgetStore.normalizeSpan(((minDp + tile - 1f) / tile).toInt().coerceAtLeast(1))
}

private fun customWidgetLabel(kind: String): String =
    when (kind) {
      HomeWidgetStore.KIND_WEATHER -> "Weather"
      HomeWidgetStore.KIND_WORLD_CLOCK -> "World Clock"
      HomeWidgetStore.KIND_TIMERS -> "Timers"
      HomeWidgetStore.KIND_CALENDAR -> "Calendar"
      HomeWidgetStore.KIND_ALARM -> "Alarm"
      HomeWidgetStore.KIND_CRYPTO -> "Crypto"
      HomeWidgetStore.KIND_TODO -> "To-Do"
      HomeWidgetStore.KIND_NEWS -> "News"
      HomeWidgetStore.KIND_STOCKS -> "Stocks"
      HomeWidgetStore.KIND_OPTIONS -> "Options"
      HomeWidgetStore.KIND_HA -> "Home Assistant"
      HomeWidgetStore.KIND_TODAY -> "Today"
      HomeWidgetStore.KIND_QUICK_CONTROLS -> "Quick Controls"
      HomeWidgetStore.KIND_PHOTO_MEMORY -> "Photo Memory"
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
    if (showMiniPlayer && np != null && np.active) {
      Spacer(Modifier.size(16.dp))
      MiniPlayer(np, modifier = Modifier.weight(1f).padding(end = 16.dp))
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
    urlArt =
        if (np.artBitmap == null && np.artUrl.isNotBlank())
            withContext(Dispatchers.IO) { MediaArt.resolveUri(context, np.artUrl) }
        else null
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
private fun ApprovedHomeHeaderPlayer(modifier: Modifier = Modifier) {
  // Playback/position notifications invalidate only the player, not the widget dashboard.
  var state by remember { mutableStateOf(NowPlayingHub.current) }
  val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
  DisposableEffect(Unit) {
    val listener = NowPlayingHub.Listener { update -> mainHandler.post { state = update } }
    NowPlayingHub.addListener(listener)
    onDispose { NowPlayingHub.removeListener(listener) }
  }
  state?.takeIf { it.active }?.let { MiniPlayer(it, modifier) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MiniPlayer(state: NowPlayingState, modifier: Modifier = Modifier) {
  val context = androidx.compose.ui.platform.LocalContext.current
  // Some media apps hand cover art as a URI rather than an embedded bitmap. Resolve it off the
  // main thread (content:// or http) so the header shows the cover too — the screensaver and the
  // phone remote already do this via [MediaArt]; the mini-player was the one place that didn't.
  var urlArt by remember(state.artUrl, state.artBitmap) { mutableStateOf<android.graphics.Bitmap?>(null) }
  androidx.compose.runtime.LaunchedEffect(state.artUrl, state.artBitmap) {
    urlArt =
        if (state.artBitmap == null && state.artUrl.isNotBlank())
            withContext(Dispatchers.IO) { MediaArt.resolveUri(context, state.artUrl) }
        else null
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
    Column(modifier = Modifier.weight(1f)) {
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

private fun triggerJarvis(context: android.content.Context) {
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
private const val ICON_REFRESH =
    "M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z"
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


/** Always-present Updates tile. Neutral + refresh glyph when up to date; blue +
 * download glyph (with a badge) when an update is ready. Shows transient status
 * text during a check or install. */
@Composable
private fun UpdatesTile(update: UpdateInfo?, status: String?, onClick: () -> Unit) {
  val available = update != null
  val label = status ?: if (available) "Update ready" else "Up to date"
  val background = if (available) Color(0xFF2D6CDF) else Color(0xFF2B2B2B)
  val glyph = if (available) ICON_DOWNLOAD else ICON_REFRESH
  val path = remember(glyph) { PathParser().parsePathString(glyph).toPath() }
  val tileDp = LocalTileDp.current
  Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier.fillMaxWidth().padding(4.dp).tvFocusable(RoundedCornerShape(22.dp)) { onClick() },
  ) {
    Box {
      Surface(
          color = background,
          shape = RoundedCornerShape(20.dp),
          modifier = Modifier.size(tileDp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Canvas(Modifier.size(44.dp * (tileDp / 88.dp))) {
            val s = size.minDimension / 24f
            scale(s, s, pivot = Offset.Zero) { drawPath(path, Color.White) }
          }
        }
      }
      if (available) {
        Surface(
            color = Color(0xFFE53935),
            shape = androidx.compose.foundation.shape.CircleShape,
            modifier = Modifier.size(18.dp).align(Alignment.TopEnd),
        ) {}
      }
    }
    Spacer(Modifier.size(8.dp))
    Text(
        label,
        color = Color.White,
        fontSize = 15.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
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

@Composable
private fun ImmortalWidgetContent(
    widget: HomeWidgetStore.HomeWidget,
    modifier: Modifier = Modifier,
    onEnterEdit: (() -> Unit)? = null,
) {
  when (widget.kind) {
    HomeWidgetStore.KIND_WEATHER -> ApprovedHomeWeather(modifier)
    HomeWidgetStore.KIND_WORLD_CLOCK -> ImmortalWorldClockWidget(modifier)
    HomeWidgetStore.KIND_TIMERS -> ImmortalTimersWidget(modifier)
    HomeWidgetStore.KIND_CALENDAR -> ApprovedHomeCalendar(modifier)
    HomeWidgetStore.KIND_ALARM -> ImmortalAlarmWidget(modifier)
    HomeWidgetStore.KIND_CRYPTO -> ImmortalCryptoWidget(modifier, maxRows = listRows(widget.spanY))
    HomeWidgetStore.KIND_TODO -> ImmortalTodoWidget(modifier, maxRows = listRows(widget.spanY))
    HomeWidgetStore.KIND_NEWS -> ImmortalNewsWidget(modifier, maxRows = listRows(widget.spanY))
    HomeWidgetStore.KIND_STOCKS -> ImmortalStocksWidget(modifier, maxRows = listRows(widget.spanY))
    HomeWidgetStore.KIND_OPTIONS -> ImmortalOptionsWidget(modifier, maxRows = listRows(widget.spanY))
    HomeWidgetStore.KIND_HA -> ImmortalHaWidget(modifier, maxTileRows = widget.spanY.coerceIn(1, 4))
    // The former fixed dashboard cards, now regular widgets. They keep their self-sizing card
    // look; the grid cell just gives them room.
    HomeWidgetStore.KIND_TODAY -> TodayStripWidget(modifier)
    HomeWidgetStore.KIND_QUICK_CONTROLS -> QuickControlsWidget(modifier)
    HomeWidgetStore.KIND_PHOTO_MEMORY -> ApprovedHomePhoto(modifier, onEnterEdit)
    else -> UnknownImmortalWidget(widget.kind, modifier)
  }
}

/**
 * How many list rows a scrolling widget should show at a given vertical span. One grid unit fits a
 * title + ~2 rows; each extra unit adds ~3. Clamped so a 1-tall widget still shows a couple of rows
 * and a 4-tall one doesn't run away.
 */
private fun listRows(spanY: Int): Int = (spanY * 3).coerceIn(2, 12)

@Composable
private fun ImmortalWidgetShell(
    title: String,
    openPage: String,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val icon = when (openPage) {
    "calendar" -> "calendar"
    "alarm" -> "alarm"
    "ha" -> "ha_widget"
    "world" -> "clock"
    "crypto", "stocks", "options" -> "calculator"
    "timers" -> "timer"
    "todo" -> "notes"
    "news" -> "globe"
    else -> "tools"
  }
  Column(modifier = modifier.padding(30.dp)) {
    ApprovedHomeWidgetHeading(title, icon) {
      if (onOpen != null) onOpen() else context.openApprovedHomeSettings(openPage)
    }
    Spacer(Modifier.height(22.dp))
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) { content() }
  }

}

/**
 * "On this day" photo-memory strip: a glass row with a small thumbnail from the user's screensaver
 * photo folder and a caption; tapping opens the full photo-frame. Only shown when a local photo
 * folder is configured and readable — otherwise it stays hidden (no empty chrome).
 */
@Composable
private fun PhotoMemoryStrip(modifier: Modifier = Modifier) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val onOpen: () -> Unit = { context.launchSafely(PhotoFramePreviewActivity::class.java) }
  val photo by
      produceState<ImageBitmap?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
          runCatching {
            val cfg = ScreensaverConfig.load(context)
            val folder = cfg.folderPath
            if (cfg.usesFolder && folder != null && LocalMedia.isAccessible(folder)) {
              LocalMedia.enumerate(folder, includeVideo = false, max = 60)
                  .filterNot { it.isVideo }
                  .randomOrNull()
                  ?.let { android.graphics.BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
            } else null
          }.getOrNull()
        }
      }
  val bmp = photo ?: return // no readable local photos → hide the strip entirely

  // Bare content — the widget grid cell provides the glass surface.
  Box(modifier = modifier) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onOpen() }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Image(
          bitmap = bmp,
          contentDescription = null,
          contentScale = androidx.compose.ui.layout.ContentScale.Crop,
          modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)),
      )
      Column(Modifier.weight(1f)) {
        Text("On this day", color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text("A memory from your photos", color = Color.White.copy(alpha = 0.9f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
      Text("›", color = Color.White.copy(alpha = 0.5f), fontSize = 24.sp)
    }
  }
}

/**
 * Home quick-controls: a glass grid of tap targets for the smart-home stuff a Portal owner reaches
 * for — a Hey Portal voice tile (launches the on-device assistant), and Home Assistant shortcuts
 * that open the HA app (the one-way MQTT bridge can't toggle devices, so we hand off to HA itself).
 * Tiles whose target isn't installed are hidden, so the grid only shows what actually works here.
 */
@Composable
private fun QuickControlsWidget(modifier: Modifier = Modifier) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val pm = context.packageManager
  var refresh by remember { mutableStateOf(0) }
  OnResume { refresh++ }
  val installed by produceState(initialValue = emptySet<String>(), refresh) {
    value = withContext(Dispatchers.IO) {
      listOf("io.homeassistant.companion.android", "io.homeassistant.companion.android.minimal", "com.portal.assistant")
          .filter { runCatching { pm.getLaunchIntentForPackage(it) != null }.getOrDefault(false) }.toSet()
    }
  }
  val haPackage = installed.firstOrNull { it.startsWith("io.homeassistant.") }
  val haInstalled = haPackage != null
  val assistantInstalled = "com.portal.assistant" in installed
  val dashboardConfigured = remember(refresh) { HomeDashboardConfig.configured(context) }
  fun launch(pkg: String) {
    runCatching { pm.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) } }
  }

  data class QC(val icon: String, val label: String, val description: String, val onTap: () -> Unit)
  val tiles = buildList {
    if (assistantInstalled) add(QC("mic", "Hey Jarvis", "Open your assistant") { triggerJarvis(context) })
    if (dashboardConfigured) {
      add(QC("ha", "Home dashboard", "Open your dashboard") {
        HomeDashboardActivity.openDashboard(context)
      })
    } else if (haInstalled) {
      add(QC("bulb", "Lights", "Open Home Assistant") { launch(haPackage.orEmpty()) })
      add(QC("film", "Scenes", "Open Home Assistant") { launch(haPackage.orEmpty()) })
      add(QC("weather", "Climate", "Open Home Assistant") { launch(haPackage.orEmpty()) })
      add(QC("ha", "Home", "Open Home Assistant") { launch(haPackage.orEmpty()) })
    }
  }
  ImmortalWidgetShell(title = "Quick controls", openPage = "display", modifier = modifier) {
    if (tiles.isEmpty()) {
      Text("Add an assistant or Home Assistant app to use quick controls.",
          color = ApprovedHomePalette.Secondary, fontSize = 22.sp, lineHeight = 30.sp)
    } else {
      tiles.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
          row.forEach { qc ->
            Column(Modifier.weight(1f).heightIn(min = 150.dp).clip(RoundedCornerShape(17.dp))
                .background(ApprovedHomePalette.Chrome).tvFocusable { qc.onTap() }.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
              ApprovedHomeIcon(qc.icon, Modifier.size(32.dp), ApprovedHomePalette.Text)
              Text(qc.label, color = ApprovedHomePalette.Text, fontSize = 23.sp, lineHeight = 30.sp,
                  fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
              Text(qc.description, color = ApprovedHomePalette.Secondary, fontSize = 20.sp, lineHeight = 27.sp)
            }
          }
          if (row.size == 1) Spacer(Modifier.weight(1f))
        }
      }
    }
  }

}

/**
 * The glanceable "Today" strip for the left column: a frosted-glass card with three quick rows —
 * next calendar event, weather feels-like + hi/lo, and the moon phase with today's sunrise/sunset.
 * All data is already fetched elsewhere (weather + sun times keyless from Open-Meteo; moon phase is
 * pure date math). Re-reads the clock format on resume so times match the user's 12/24h setting.
 */
@Composable
private fun TodayStripWidget(modifier: Modifier = Modifier) {
  val context = androidx.compose.ui.platform.LocalContext.current
  var use24 by remember { mutableStateOf(ImmortalSettings.use24HourClock(context)) }
  var fahrenheit by remember { mutableStateOf(ImmortalSettings.useFahrenheit(context)) }
  OnResume {
        use24 = ImmortalSettings.use24HourClock(context)
        fahrenheit = ImmortalSettings.useFahrenheit(context)
  }
  val current by
      produceHomeState<Weather.Current?>(initialValue = null, fahrenheit) {
        while (true) {
          value = withContext(Dispatchers.IO) { Weather.fetchCurrent(context) }
          homeRefreshDelay(if (value == null) 60_000L else 30L * 60 * 1000)
        }
      }
  val forecast by
      produceHomeState<Weather.Forecast?>(initialValue = null, fahrenheit) {
        value = withContext(Dispatchers.IO) { Weather.fetchForecast(context) }
      }
  val sun by
      produceHomeState<Weather.SunTimes?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { Weather.fetchSunTimes(context) }
      }
  var calendarUrl by remember { mutableStateOf(ScreensaverConfig.load(context).calendarUrl) }
  OnResume { calendarUrl = ScreensaverConfig.load(context).calendarUrl }
  val nextCalendarRefresh = remember(calendarUrl) { longArrayOf(0L) }
  val nextEvent by produceHomeState<CalendarFeed.Event?>(initialValue = null, calendarUrl) {
    while (true) {
      val remaining = nextCalendarRefresh[0] - android.os.SystemClock.elapsedRealtime()
      if (remaining > 0) delay(remaining)
      val url = calendarUrl
      value = if (url.isNullOrBlank()) null else withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        CalendarFeed.fetch(url).filter { it.endMillis >= now }.minByOrNull { it.startMillis }
      }
      nextCalendarRefresh[0] = android.os.SystemClock.elapsedRealtime() + 30L * 60 * 1000
    }
  }


  // Nearest upcoming countdown (birthday/trip) from Settings > Countdown, plus the next alarm and
  // open to-do count. Re-read on resume so newly-added items show. Each is null/absent when empty,
  // so the dashboard rows self-hide rather than showing dead chrome.
  var countdown by remember { mutableStateOf<Pair<String, String>?>(null) }
  var alarmLine by remember { mutableStateOf<Pair<String, String>?>(null) }
  var todoOpen by remember { mutableStateOf(0) }
  val refreshTodayLines = {
    countdown = runCatching {
      CountdownConfig.loadSorted(context)
          .firstOrNull { it.daysUntil() >= 0 }
          ?.let { "${it.emoji} ${it.label}" to it.phrase() }
    }.getOrNull()
    alarmLine = runCatching {
      val cfg = AlarmConfig.load(context)
      val next = if (cfg.enabled) AlarmConfig.nextTrigger(cfg) else null
      next?.let {
        val fmt = SimpleDateFormat(if (use24) "EEE HH:mm" else "EEE h:mm a", Locale.getDefault())
        cfg.label.ifBlank { "Alarm" } to fmt.format(java.util.Date(it))
      }
    }.getOrNull()
    todoOpen = runCatching { TodoStore.load(context).count { !it.done } }.getOrDefault(0)
  }
  LaunchedEffect(Unit) { refreshTodayLines() }
  OnResume { refreshTodayLines() }

  val today = forecast?.days?.firstOrNull()
  val (_, moonName) = remember { moonPhase(System.currentTimeMillis()) }
  val sunPair = sun?.formatted(use24)
  val endFormat = remember(use24) { SimpleDateFormat(if (use24) "HH:mm" else "h:mm a", Locale.getDefault()) }
  ImmortalWidgetShell(title = "Today", openPage = "calendar", modifier = modifier) {
    nextEvent?.let { event ->
      Text(calendarEventTime(event, use24), color = ApprovedHomePalette.Secondary,
          fontSize = 18.sp, lineHeight = 24.sp)
      Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(Modifier.width(4.dp).height(67.dp).clip(RoundedCornerShape(2.dp)).background(ApprovedHomePalette.Accent))
        Column {
          Text(event.title, color = ApprovedHomePalette.Text, fontSize = 24.sp, lineHeight = 32.sp,
              fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
          Text(if (event.allDay) "All-day" else "Until ${endFormat.format(Date(event.endMillis))}",
              color = ApprovedHomePalette.Secondary, fontSize = 21.sp, lineHeight = 29.sp,
              modifier = Modifier.padding(top = 6.dp))
        }
      }
    } ?: if (calendarUrl.isNullOrBlank()) {
      Text("Add your calendar", color = ApprovedHomePalette.Text, fontSize = 26.sp, lineHeight = 34.sp)
      Text("See your upcoming events here.", color = ApprovedHomePalette.Secondary,
          fontSize = 22.sp, lineHeight = 30.sp, modifier = Modifier.padding(top = 12.dp))
      ApprovedHomeTextButton("Add calendar") { context.openApprovedHomeSettings("calendar-url") }
    } else Text("No upcoming events", color = ApprovedHomePalette.Secondary, fontSize = 22.sp, lineHeight = 30.sp)
    Spacer(Modifier.height(20.dp))
    Text(buildString {
      append(current?.let { "Feels like ${it.temp}°" } ?: "Weather unavailable")
      sunPair?.let { append(" · Sunrise ${it.first}") }
      if (sunPair == null) today?.let { append(" · H: ${it.hi}° · L: ${it.lo}°") }
      append("\n$moonName")
      sunPair?.let { append(" · Sunset ${it.second}") }
    }, color = ApprovedHomePalette.Secondary, fontSize = 22.sp, lineHeight = 32.sp)
    alarmLine?.let { (label, whenStr) ->
      Spacer(Modifier.height(14.dp)); TodayRow("⏰", label, whenStr)
    }
    if (todoOpen > 0) {
      Spacer(Modifier.height(14.dp)); TodayRow("✓", if (todoOpen == 1) "1 to-do" else "$todoOpen to-dos", "open")
    }
    countdown?.let { (label, phrase) ->
      Spacer(Modifier.height(14.dp)); TodayRow("⏳", label, phrase)
    }
  }

}

@Composable
private fun TodayRow(glyph: String, text: String, trailing: String?) {
  Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(glyph, color = ApprovedHomePalette.Text, fontSize = 26.sp, lineHeight = 35.sp,
        modifier = Modifier.width(30.dp), textAlign = TextAlign.Center)
    Text(
        text,
        color = ApprovedHomePalette.Text,
        fontSize = 26.sp,
        lineHeight = 35.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
    )
    if (trailing != null) {
      Text(trailing, color = ApprovedHomePalette.Secondary, fontSize = 22.sp, lineHeight = 30.sp,
          maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
    }
  }
}

/**
 * Moon phase (glyph + name) from the epoch, via the standard synodic-month approximation
 * (29.53059 days from a known new moon). Pure date math — no network, good to ~1 day.
 */
private fun moonPhase(nowMillis: Long): Pair<String, String> {
  val synodic = 29.530588853
  val knownNew = 947182440000L // 2000-01-06 18:14 UTC, a reference new moon
  val days = (nowMillis - knownNew).toDouble() / 86_400_000.0
  val age = ((days % synodic) + synodic) % synodic
  return when {
    age < 1.84 -> "🌑" to "New moon"
    age < 5.53 -> "🌒" to "Waxing crescent"
    age < 9.22 -> "🌓" to "First quarter"
    age < 12.91 -> "🌔" to "Waxing gibbous"
    age < 16.61 -> "🌕" to "Full moon"
    age < 20.30 -> "🌖" to "Waning gibbous"
    age < 23.99 -> "🌗" to "Last quarter"
    age < 27.68 -> "🌘" to "Waning crescent"
    else -> "🌑" to "New moon"
  }
}

@Composable
private fun ImmortalWeatherWidget(modifier: Modifier = Modifier) {
  val context = androidx.compose.ui.platform.LocalContext.current
  var fahrenheit by remember { mutableStateOf(ImmortalSettings.useFahrenheit(context)) }
  // Forecast mode from Settings > Appearance > Weather widget: Off / Hourly / Daily. Re-read on
  // resume so a change applies when the user returns home.
  var forecastMode by remember { mutableStateOf(ImmortalSettings.load(context).weatherWidget) }
  var use24 by remember { mutableStateOf(ImmortalSettings.use24HourClock(context)) }
  OnResume {
        fahrenheit = ImmortalSettings.useFahrenheit(context)
        forecastMode = ImmortalSettings.load(context).weatherWidget
        use24 = ImmortalSettings.use24HourClock(context)
  }
  val current by
      produceHomeState<Weather.Current?>(initialValue = null, fahrenheit) {
        while (true) {
          value = withContext(Dispatchers.IO) { Weather.fetchCurrent(context) }
          homeRefreshDelay(if (value == null) 60_000L else 30L * 60 * 1000)
        }
      }
  val forecast by
      produceHomeState<Weather.Forecast?>(initialValue = null, fahrenheit) {
        value = withContext(Dispatchers.IO) { Weather.fetchForecast(context) }
      }
  val sunTimes by
      produceHomeState<Weather.SunTimes?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { Weather.fetchSunTimes(context) }
      }
  val code = current?.code ?: 0
  val phase = skyPhase()
  val sky = weatherSky(code, phase)
  // The widget paints its own sky (Dusk Aurora design): a multi-stop vertical gradient that tracks
  // condition + time of day — night sky fading into a warm horizon at dusk — with a glowing
  // sun/moon orb when skies are clear. City + big temp top-left, hourly strip along the bottom.
  Box(
      modifier =
          modifier
              // 22dp matches the grid cell's LiquidGlass exactly, so the painted sky fills the
              // glass card edge-to-edge with no rim peeking through.
              .clip(RoundedCornerShape(22.dp))
              .background(Brush.verticalGradient(sky))
              .padding(18.dp),
  ) {
    Column(Modifier.fillMaxSize()) {
      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
          Text(
              current?.city?.ifBlank { "Weather" } ?: "Weather",
              color = Color.White,
              fontSize = 18.sp,
              fontWeight = FontWeight.SemiBold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
          )
          Text(
              current?.let { "${it.temp}°" } ?: "—",
              color = Color.White,
              fontSize = 46.sp,
              fontWeight = FontWeight.Light,
          )
          // Always-present hero subline: condition · today's H/L · sunset — so the card reads as a
          // premium weather hero even when the forecast strip mode is Off.
          val today = forecast?.days?.firstOrNull()
          val condition = weatherConditionText(code)
          val sunsetLabel = sunTimes?.formatted(use24)?.second
          Text(
              buildString {
                append(condition)
                today?.let { append("  ·  H:${it.hi}° L:${it.lo}°") }
                sunsetLabel?.let { append("  ·  ↓$it") }
              },
              color = Color(0xE6FFFFFF),
              fontSize = 14.sp,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.padding(top = 2.dp),
          )
        }
        // Clear skies get a glowing sun/moon orb (the Dusk Aurora "paints its own sky" look);
        // anything with real weather keeps the condition glyph.
        if (code <= 1) SkyOrb(night = phase == SkyPhase.NIGHT) else Text(Weather.emoji(code), fontSize = 34.sp)
      }
      Spacer(Modifier.weight(1f))
      // Forecast strip honors the Weather-widget mode: Off shows just the current conditions,
      // Hourly shows the next 5 hours, Daily shows the next 5 days with high/low.
      when (forecastMode) {
        ImmortalSettings.WIDGET_HOURLY -> {
          val hours = forecast?.hours?.take(5).orEmpty()
          if (hours.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              hours.forEach { h ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f),
                ) {
                  Text(h.label, color = Color(0xCCFFFFFF), fontSize = 11.sp, maxLines = 1)
                  Text(Weather.emoji(h.code), fontSize = 18.sp, modifier = Modifier.padding(vertical = 3.dp))
                  Text("${h.temp}°", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
              }
            }
          }
        }
        ImmortalSettings.WIDGET_DAILY -> {
          val days = forecast?.days?.take(5).orEmpty()
          if (days.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              days.forEach { d ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f),
                ) {
                  Text(d.label, color = Color(0xCCFFFFFF), fontSize = 11.sp, maxLines = 1)
                  Text(Weather.emoji(d.code), fontSize = 18.sp, modifier = Modifier.padding(vertical = 3.dp))
                  Text("${d.hi}°", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                  Text("${d.lo}°", color = Color(0x99FFFFFF), fontSize = 11.sp)
                }
              }
            }
          }
        }
        else -> {} // WIDGET_OFF: current conditions only, no forecast strip.
      }
    }
  }
}

/** Time-of-day bucket for the widget's painted sky. */
private enum class SkyPhase { DAY, DUSK, NIGHT }

private fun skyPhase(): SkyPhase {
  val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
  return when (h) {
    in 8..17 -> SkyPhase.DAY
    in 5..7, in 18..20 -> SkyPhase.DUSK // dawn shares the dusk palette
    else -> SkyPhase.NIGHT
  }
}

/**
 * The widget's sky: a multi-stop vertical gradient per condition + phase (Dusk Aurora design).
 * Dusk is the signature — deep night blue melting into a warm ember horizon; weather conditions
 * mute it toward grey. Colors match mocks/ipados (weatherw / .hero .sun).
 */
private fun weatherSky(code: Int, phase: SkyPhase): List<Color> {
  val precip = code in 51..99
  val grey = code == 3 || code in 45..48
  return when (phase) {
    SkyPhase.DUSK ->
        when {
          precip -> listOf(Color(0xFF232C3D), Color(0xFF3A4457), Color(0xFF5D5350))
          grey -> listOf(Color(0xFF27303F), Color(0xFF434D5C), Color(0xFF6B6058))
          else -> listOf(Color(0xFF16294F), Color(0xFF2C4470), Color(0xFF8A5F52), Color(0xFFC98A5E))
        }
    SkyPhase.NIGHT ->
        when {
          precip -> listOf(Color(0xFF1C2330), Color(0xFF333E4E))
          grey -> listOf(Color(0xFF1D2634), Color(0xFF37424F))
          else -> listOf(Color(0xFF0A1226), Color(0xFF16294F), Color(0xFF27436E))
        }
    SkyPhase.DAY ->
        when {
          code in 71..77 -> listOf(Color(0xFF7E93A8), Color(0xFFBFCEDC)) // snow
          code in 51..67 || code in 80..82 -> listOf(Color(0xFF45607A), Color(0xFF7791A6)) // rain
          code in 95..99 -> listOf(Color(0xFF3A4A5C), Color(0xFF5E7286)) // thunder
          code == 3 -> listOf(Color(0xFF5E7E98), Color(0xFF93ABC0)) // overcast
          code in 45..48 -> listOf(Color(0xFF7E8E9C), Color(0xFFAEBBC6)) // fog
          else -> listOf(Color(0xFF2E6BB8), Color(0xFF4F97DC), Color(0xFF8FC3EE)) // clear
        }
  }
}

/** A glowing sun (or moon) orb for clear skies — radial core + soft halo, drawn not emoji. */
@Composable
private fun SkyOrb(night: Boolean) {
  androidx.compose.foundation.Canvas(Modifier.size(44.dp)) {
    val r = size.minDimension / 2f
    val c = center
    if (night) {
      drawCircle(
          Brush.radialGradient(
              listOf(Color(0x66AFC6FF), Color(0x00AFC6FF)), center = c, radius = r),
          radius = r, center = c)
      drawCircle(
          Brush.radialGradient(
              listOf(Color(0xFFF4F6FB), Color(0xFFC9D4E8)), center = c.copy(x = c.x - r * 0.15f), radius = r * 0.62f),
          radius = r * 0.55f, center = c)
    } else {
      drawCircle(
          Brush.radialGradient(
              listOf(Color(0x80FFC96B), Color(0x00FFC96B)), center = c, radius = r),
          radius = r, center = c)
      drawCircle(
          Brush.radialGradient(
              listOf(Color(0xFFFFF3D6), Color(0xFFFFCF7D), Color(0xFFFF9D4D)),
              center = c.copy(x = c.x - r * 0.12f, y = c.y - r * 0.15f), radius = r * 0.62f),
          radius = r * 0.55f, center = c)
    }
  }
}

@Composable
private fun ImmortalWorldClockWidget(modifier: Modifier = Modifier) {
  val context = androidx.compose.ui.platform.LocalContext.current
  var zones by remember { mutableStateOf(ImmortalSettings.worldClockZones(context)) }
  var use24 by remember { mutableStateOf(ImmortalSettings.use24HourClock(context)) }
  OnResume {
        zones = ImmortalSettings.worldClockZones(context)
        use24 = ImmortalSettings.use24HourClock(context)
  }
  val now by produceHomeState(initialValue = Date()) {
    while (true) {
      value = Date()
      delay(60_000L - System.currentTimeMillis() % 60_000L)
    }
  }
  val format = remember(use24) { SimpleDateFormat(if (use24) "HH:mm" else "h:mm a", Locale.getDefault()) }
  val selected = remember(zones) { zones.take(4) }
  ImmortalWidgetShell(title = "World clock", openPage = "world", modifier = modifier) {
    if (selected.isEmpty()) ApprovedHomeWidgetMessage("Add cities to see their local time.")
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
      selected.forEach { zone ->
        val display = remember(zone, now, use24) {
          format.timeZone = TimeZone.getTimeZone(zone)
          val weekday = SimpleDateFormat("EEEE", Locale.getDefault()).apply { timeZone = format.timeZone }
          format.format(now) to "${worldClockCity(zone)} · ${weekday.format(now)}"
        }
        Column {
          Text(display.first, color = ApprovedHomePalette.Text, fontSize = 48.sp, lineHeight = 100.sp,
              fontWeight = FontWeight.Normal, letterSpacing = (-1).sp)
          Text(display.second, color = ApprovedHomePalette.Secondary,
              fontSize = 22.sp, lineHeight = 30.sp, modifier = Modifier.padding(top = 12.dp))
        }
      }
    }
  }

}

/** Short city label from a tz id, e.g. "America/New_York" → "New York". */
private fun worldClockCity(zoneId: String): String =
    zoneId.substringAfterLast('/').replace('_', ' ')

/** The zone's local wall time, e.g. "12:42 AM" / "00:42". */
private fun worldClockLocalTime(zoneId: String, now: Date, use24: Boolean): String {
  val fmt = SimpleDateFormat(if (use24) "HH:mm" else "h:mm a", Locale.getDefault())
  fmt.timeZone = TimeZone.getTimeZone(zoneId)
  return fmt.format(now)
}


/** A small analog clock face for [zoneId], white by day / dark by night, with an orange seconds hand. */
@Composable
private fun AnalogClock(zoneId: String, now: Date, modifier: Modifier = Modifier) {
  val cal = remember(zoneId) { java.util.Calendar.getInstance(TimeZone.getTimeZone(zoneId)) }
  cal.time = now
  val hour = cal.get(java.util.Calendar.HOUR)
  val minute = cal.get(java.util.Calendar.MINUTE)
  val second = cal.get(java.util.Calendar.SECOND)
  val night = cal.get(java.util.Calendar.HOUR_OF_DAY) !in 6..18
  val face = if (night) Color(0xFF1F1F22) else Color.White
  val ink = if (night) Color.White else Color(0xFF1A1A1A)
  val tick = if (night) Color(0x66FFFFFF) else Color(0x55000000)
  Canvas(modifier) {
    val r = size.minDimension / 2f
    val c = Offset(size.width / 2f, size.height / 2f)
    drawCircle(face, radius = r, center = c)
    // Hour ticks.
    for (i in 0 until 12) {
      val a = Math.toRadians(i * 30.0)
      val sin = kotlin.math.sin(a).toFloat()
      val cos = kotlin.math.cos(a).toFloat()
      drawLine(
          tick,
          start = Offset(c.x + sin * r * 0.82f, c.y - cos * r * 0.82f),
          end = Offset(c.x + sin * r * 0.94f, c.y - cos * r * 0.94f),
          strokeWidth = r * 0.05f,
      )
    }
    fun hand(angleDeg: Double, length: Float, width: Float, color: Color) {
      val a = Math.toRadians(angleDeg)
      drawLine(
          color,
          start = c,
          end = Offset(c.x + (kotlin.math.sin(a) * length).toFloat(), c.y - (kotlin.math.cos(a) * length).toFloat()),
          strokeWidth = width,
          cap = StrokeCap.Round,
      )
    }
    hand((hour % 12 + minute / 60.0) * 30.0, r * 0.5f, r * 0.10f, ink)
    hand((minute + second / 60.0) * 6.0, r * 0.78f, r * 0.07f, ink)
    hand(second * 6.0, r * 0.85f, r * 0.03f, Color(0xFFFF9500))
    drawCircle(Color(0xFFFF9500), radius = r * 0.06f, center = c)
  }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ImmortalTimersWidget(modifier: Modifier = Modifier) {
  val context = androidx.compose.ui.platform.LocalContext.current
  var state by remember { mutableStateOf(TimerStore.load(context)) }
  var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
  var showCustom by remember { mutableStateOf(false) }
  var showTimersPage by remember { mutableStateOf(false) }
  // Live updates if the timer is changed elsewhere (e.g. another Timers widget instance).
  DisposableEffect(Unit) {
    val l: () -> Unit = {
      state = TimerStore.load(context)
      nowMs = System.currentTimeMillis()
    }
    TimerStore.addListener(l)
    onDispose { TimerStore.removeListener(l) }
  }
  // Tick the countdown while it's running. When it hits zero we stop ticking but DON'T clear —
  // the exact alarm ([TimerAlarmReceiver]) flips the timer to ringing, which the listener picks up.
  val active = LocalApprovedLauncherPageActive.current
  LaunchedEffect(state.running, state.endsAt, active) {
    if (!active) return@LaunchedEffect
    while (state.running) {
      nowMs = System.currentTimeMillis()
      if (state.remaining(nowMs) <= 0L) break
      delay(1_000)
    }
  }
  val remainingMs = state.remaining(nowMs)
  val mins = (remainingMs / 60_000).toInt()
  val secs = ((remainingMs / 1000) % 60).toInt()
  ImmortalWidgetShell(title = "Timers", openPage = "timers", modifier = modifier,
      onOpen = { showTimersPage = true }) {
    Text(
        when {
          state.ringing -> "Time's up"
          remainingMs > 0 -> "%d:%02d".format(mins, secs)
          else -> "Ready"
        },
        color = ApprovedHomePalette.Text,
        fontSize = 48.sp,
        lineHeight = 100.sp,
        fontWeight = FontWeight.Normal,
    )
    Text(if (state.ringing) "Stop the alarm" else "Set a timer",
        color = ApprovedHomePalette.Secondary, fontSize = 22.sp, lineHeight = 30.sp,
        modifier = Modifier.padding(top = 12.dp, bottom = 16.dp))
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
      if (state.ringing) {
        TimerChip("Stop") { TimerAlarm.stop(context) }
      } else if (remainingMs > 0) {
        TimerChip(if (state.running) "Pause" else "Start") {
          if (state.running) TimerStore.pause(context) else TimerStore.resume(context)
        }
        TimerChip("Reset") {
          TimerStore.clear(context)
        }
      } else {
        listOf(5, 10, 30).forEach { minutes ->
          TimerChip("${minutes}m") { TimerStore.start(context, minutes * 60_000L) }
        }
        TimerChip("Custom") { showCustom = true }
      }
    }
  }
  if (showCustom) {
    CustomTimerDialog(
        onStart = { ms ->
          if (ms > 0) TimerStore.start(context, ms)
          showCustom = false
        },
        onDismiss = { showCustom = false },
    )
  }
  if (showTimersPage) ApprovedHomeTimersPage { showTimersPage = false }
}

/**
 * Home widget for the wake-up alarm ([AlarmConfig]). Shows the next fire time + label when armed,
 * or "No alarm" when off; a chip toggles it on/off, and tapping the body opens the alarm editor.
 * Re-reads config on resume so edits in the editor reflect here.
 */
@Composable
private fun ImmortalAlarmWidget(modifier: Modifier = Modifier) {
  val context = androidx.compose.ui.platform.LocalContext.current
  var cfg by remember { mutableStateOf(AlarmConfig.load(context)) }
  var use24 by remember { mutableStateOf(ImmortalSettings.use24HourClock(context)) }
  OnResume {
    cfg = AlarmConfig.load(context)
    use24 = ImmortalSettings.use24HourClock(context)
  }
  val next = remember(cfg) { AlarmConfig.nextTrigger(cfg) }
  val open: () -> Unit = { context.openApprovedHomeSettings("alarm") }
  // Preserve the editable alarm time and inline enabled control within the approved card.
  ImmortalWidgetShell(title = "Alarm", openPage = "alarm", modifier = modifier.tvFocusableRow { open() }) {
    val is12 = !use24
    val h12 = if (cfg.hour % 12 == 0) 12 else cfg.hour % 12
    Row(verticalAlignment = Alignment.Bottom) {
      Text(
          if (is12) "%d:%02d".format(h12, cfg.minute) else "%02d:%02d".format(cfg.hour, cfg.minute),
          color = if (cfg.enabled) ApprovedHomePalette.Text else ApprovedHomePalette.Secondary,
          fontSize = 48.sp,
          lineHeight = 100.sp,
          fontWeight = FontWeight.Normal,
          letterSpacing = (-1).sp,
      )
      if (is12) {
        Text(
            if (cfg.hour < 12) " AM" else " PM",
            color = ApprovedHomePalette.Secondary,
            fontSize = 22.sp,
            lineHeight = 30.sp,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.padding(bottom = 5.dp),
        )
      }
    }
    Text(
        when {
          cfg.enabled && next != null -> {
            val mins = ((next - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
            val lead = cfg.label.ifBlank { "Weekdays" }
            "$lead · in ${mins / 60} hr ${mins % 60} min"
          }
          else -> "No alarm set"
        },
        color = ApprovedHomePalette.Secondary,
        fontSize = 22.sp,
        lineHeight = 30.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 12.dp),
    )
    Spacer(Modifier.height(20.dp))
    Box(
        modifier = Modifier
            .size(width = 64.dp, height = 38.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(if (cfg.enabled) ApprovedHomePalette.Accent else ApprovedHomePalette.Chrome)
            .clickable {
              AlarmConfig.setEnabled(context, !cfg.enabled)
              AlarmScheduler.reschedule(context)
              cfg = AlarmConfig.load(context)
            },
    ) {
      Box(
          Modifier
              .align(if (cfg.enabled) Alignment.CenterEnd else Alignment.CenterStart)
              .padding(horizontal = 4.dp)
              .size(30.dp)
              .clip(CircleShape)
              .background(ApprovedHomePalette.Text))
    }
  }
}

/**
 * Crypto price widget: a compact list of the user's tracked coins (symbol · price · 24h change),
 * refreshed from CoinGecko on the standard 30-min success / 1-min retry cadence. Tapping opens the
 * coin editor. Change is green/red like the stock convention. Re-reads the coin list on resume so
 * an edit applies on return home.
 */
@Composable
private fun ImmortalCryptoWidget(modifier: Modifier = Modifier, maxRows: Int = 5) {
  val context = androidx.compose.ui.platform.LocalContext.current
  var coins by remember { mutableStateOf(CryptoConfig.load(context)) }
  OnResume { coins = CryptoConfig.load(context) }
  val quotes by
      produceHomeState<List<CryptoFeed.Quote>?>(initialValue = null, coins) {
        while (true) {
          value = withContext(Dispatchers.IO) { CryptoFeed.fetch(context, coins) }
          homeRefreshDelay(if (value.isNullOrEmpty()) 60_000L else 30L * 60 * 1000)
        }
      }
  val open: () -> Unit = { context.openApprovedHomeSettings("crypto") }
  ImmortalWidgetShell(title = "Crypto", openPage = "crypto", modifier = modifier.tvFocusableRow { open() }) {
    val rows = quotes
    when {
      rows == null -> ApprovedHomeWidgetMessage("Loading…")
      rows.isEmpty() -> ApprovedHomeWidgetMessage("Add coins to track")
      else ->
          Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            rows.take(maxRows).forEach { q -> CryptoRow(q) }
          }
    }
  }
}

/** One coin line: symbol · 7d sparkline · price · tinted change pill (Dusk Aurora). */
@Composable
private fun CryptoRow(q: CryptoFeed.Quote) {
  Column(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
      Text(q.symbol, color = ApprovedHomePalette.Text, fontSize = 26.sp, lineHeight = 35.sp,
          fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f))
      Text(formatUsd(q.usd), color = ApprovedHomePalette.Text, fontSize = 26.sp, lineHeight = 35.sp,
          maxLines = 1)
    }
    if (q.spark.size >= 2 || q.change24h != null) {
      Row(Modifier.fillMaxWidth().padding(top = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.CenterVertically) {
        if (q.spark.size >= 2) Sparkline(q.spark, up = (q.change24h ?: 0.0) >= 0,
            modifier = Modifier.size(width = 64.dp, height = 22.dp))
        q.change24h?.let { ChangePill(it) }
      }
    }
  }
}

/**
 * A tinted percent-change pill: soft green/red fill with a saturated label (the mock's `.chg`).
 * Fixed min-width keeps columns of pills aligned.
 */
@Composable
private fun ChangePill(pct: Double, modifier: Modifier = Modifier) {
  val up = pct >= 0
  val palette = com.immortal.launcher.ui.theme.LocalApprovedPalette.current
  val tint = if (up) palette.green else if (palette.dark) Color(0xFFFFC8B9) else palette.error
  Text(
      "%s%.1f%%".format(if (up) "+" else "−", kotlin.math.abs(pct)),
      color = tint,
      fontSize = 18.sp,
      lineHeight = 24.sp,
      fontWeight = FontWeight.Medium,
      textAlign = androidx.compose.ui.text.style.TextAlign.End,
      modifier =
          modifier
              .clip(RoundedCornerShape(7.dp))
              .background(tint.copy(alpha = .14f))
              .padding(horizontal = 8.dp, vertical = 4.dp)
              .widthIn(min = 56.dp),
  )
}

/** A tiny price polyline, tinted by direction — the widget-row mini chart from the mock. */
@Composable
private fun Sparkline(points: List<Double>, up: Boolean, modifier: Modifier = Modifier) {
  val palette = com.immortal.launcher.ui.theme.LocalApprovedPalette.current
  val color = if (up) palette.green else if (palette.dark) Color(0xFFFFC8B9) else palette.error
  Canvas(modifier) {
    val min = points.min()
    val max = points.max()
    val range = (max - min).takeIf { it > 0 } ?: 1.0
    val stepX = size.width / (points.size - 1)
    val path = androidx.compose.ui.graphics.Path()
    points.forEachIndexed { i, p ->
      val x = i * stepX
      val y = (size.height * (1f - ((p - min) / range).toFloat())).coerceIn(0f, size.height)
      if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(
        path,
        color,
        style = androidx.compose.ui.graphics.drawscope.Stroke(
            width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
  }
}

/** Compact USD formatting: whole dollars for big prices, cents for sub-$100 (e.g. altcoins). */
private fun formatUsd(v: Double): String =
    when {
      v >= 1000 -> "$%,.0f".format(v)
      v >= 1 -> "$%,.2f".format(v)
      else -> "$%.4f".format(v)
    }

/**
 * Local checklist widget ([TodoStore]). Rows are tappable to toggle done (strikethrough + dimmed);
 * a footer shows the open count and a "+" opens the editor for adding items / clearing completed.
 * Purely local — no network. Re-reads on resume so edits made in the editor reflect here, and
 * toggles persist immediately (optimistic local state).
 */
@Composable
private fun ImmortalTodoWidget(modifier: Modifier = Modifier, maxRows: Int = 5) {
  val context = androidx.compose.ui.platform.LocalContext.current
  var items by remember { mutableStateOf(TodoStore.load(context)) }
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) items = TodoStore.load(context) }
    lifecycleOwner.lifecycle.addObserver(obs)
    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
  }
  val open: () -> Unit = { context.openApprovedHomeSettings("todo") }
  val openCount = items.count { !it.done }
  val visibleItems = remember(items, maxRows) { items.sortedBy { it.done }.take(maxRows) }
  ImmortalWidgetShell(title = "To-do", openPage = "todo", modifier = modifier.tvFocusableRow { open() }) {
    if (items.isEmpty()) {
      ApprovedHomeWidgetMessage("No items — tap to add")
    } else {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Open items first, then completed — the actionable list stays on top.
        visibleItems.forEach { item ->
          key(item.id) { TodoRow(item) {
            TodoStore.toggle(context, item.id)
            items = TodoStore.load(context)
          } }
        }
      }
      Spacer(Modifier.size(16.dp))
      Text(
          if (openCount == 0) "All done 🎉" else "$openCount to do",
          color = ApprovedHomePalette.Secondary,
          fontSize = 22.sp,
          lineHeight = 30.sp,
      )
    }
  }
}

/** One checklist line: a check glyph + text, struck through and dimmed when done. Tap toggles. */
@Composable
private fun TodoRow(item: TodoStore.TodoItem, onToggle: () -> Unit) {
  Row(
      modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
          .tvFocusableRow { onToggle() }.padding(vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(
        if (item.done) "☑" else "☐",
        color = if (item.done) ApprovedHomePalette.Accent else ApprovedHomePalette.Secondary,
        fontSize = 26.sp,
        lineHeight = 35.sp,
    )
    Text(
        item.text,
        color = if (item.done) ApprovedHomePalette.Secondary else ApprovedHomePalette.Text,
        fontSize = 26.sp,
        lineHeight = 35.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textDecoration = if (item.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
        modifier = Modifier.weight(1f),
    )
  }
}

/**
 * News headlines widget: aggregates the latest titles across the user's configured RSS/Atom feeds
 * ([NewsConfig]) on the standard 30-min / 1-min cadence. Tapping a headline opens its article link
 * in the browser; tapping the chrome (or when empty) opens the feed editor. Re-reads feeds on resume.
 */
@Composable
private fun ImmortalNewsWidget(modifier: Modifier = Modifier, maxRows: Int = 5) {
  val context = androidx.compose.ui.platform.LocalContext.current
  var feeds by remember { mutableStateOf(NewsConfig.load(context)) }
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) feeds = NewsConfig.load(context) }
    lifecycleOwner.lifecycle.addObserver(obs)
    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
  }
  val headlines by
      produceHomeState<List<NewsFeed.Headline>?>(initialValue = null, feeds) {
        while (true) {
          value =
              withContext(Dispatchers.IO) {
                // Interleave feeds so no single source dominates the (short) list.
                feeds.map {
                  kotlinx.coroutines.currentCoroutineContext().ensureActive()
                  NewsFeed.fetch(context, it.url)
                }.let(::interleave)
              }
          homeRefreshDelay(if (value.isNullOrEmpty()) 60_000L else 30L * 60 * 1000)
        }
      }
  val openEditor: () -> Unit = { context.openApprovedHomeSettings("news") }
  val openArticle: (String?) -> Unit = { link ->
    if (link.isNullOrBlank()) openEditor()
    else runCatching {
      context.startActivity(
          Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
  }
  ImmortalWidgetShell(title = "News", openPage = "news", modifier = modifier.tvFocusableRow { openEditor() }) {
    val rows = headlines
    when {
      feeds.isEmpty() -> ApprovedHomeWidgetMessage("Add a feed to see headlines")
      rows == null -> ApprovedHomeWidgetMessage("Loading…")
      rows.isEmpty() -> ApprovedHomeWidgetMessage("No headlines")
      else ->
          Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            rows.take(maxRows).forEach { h ->
              Text(
                  h.title,
                  color = ApprovedHomePalette.Text,
                  fontSize = 26.sp,
                  lineHeight = 35.sp,
                  maxLines = 2,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.fillMaxWidth().tvFocusableRow { openArticle(h.link) },
              )
            }
          }
    }
  }
}

/** Round-robin merge of per-feed headline lists so each source is represented near the top. */
private fun interleave(lists: List<List<NewsFeed.Headline>>): List<NewsFeed.Headline> {
  val out = ArrayList<NewsFeed.Headline>()
  val max = lists.maxOfOrNull { it.size } ?: 0
  for (i in 0 until max) {
    for (l in lists) l.getOrNull(i)?.let { out.add(it) }
  }
  return out
}

/**
 * Stocks quote widget ([StocksConfig] + [AlpacaConfig]). Lists tracked tickers with last price and
 * daily change (green/red) on the 30-min/1-min cadence. Requires Alpaca credentials — shows a
 * "connect" prompt until configured. Tapping opens the editor. Data is IEX real-time (free tier).
 */
@Composable
private fun ImmortalStocksWidget(modifier: Modifier = Modifier, maxRows: Int = 5) {
  val context = androidx.compose.ui.platform.LocalContext.current
  var tickers by remember { mutableStateOf(StocksConfig.load(context)) }
  var configured by remember { mutableStateOf(AlpacaConfig.isConfigured(context)) }
  OnResume {
        tickers = StocksConfig.load(context)
        configured = AlpacaConfig.isConfigured(context)
  }
  val quotes by
      produceHomeState<List<AlpacaClient.StockQuote>?>(initialValue = null, tickers, configured) {
        while (true) {
          value = withContext(Dispatchers.IO) { AlpacaClient.fetchStockQuotes(context, tickers) }
          homeRefreshDelay(if (value.isNullOrEmpty()) 60_000L else 30L * 60 * 1000)
        }
      }
  val open: () -> Unit = { context.openApprovedHomeSettings("stocks") }
  ImmortalWidgetShell(title = "Stocks", openPage = "stocks", modifier = modifier.tvFocusableRow { open() }) {
    val rows = quotes
    when {
      !configured -> ApprovedHomeWidgetMessage("Tap to connect Alpaca")
      tickers.isEmpty() -> ApprovedHomeWidgetMessage("Add tickers to track")
      rows == null -> ApprovedHomeWidgetMessage("Loading…")
      rows.isEmpty() -> ApprovedHomeWidgetMessage("No quotes")
      else ->
          Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            rows.take(maxRows).forEach { q -> StockRow(q) }
          }
    }
  }
}

/** One ticker line: symbol · last price · tinted daily-change pill (Dusk Aurora). */
@Composable
private fun StockRow(q: AlpacaClient.StockQuote) {
  Column(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
      Text(q.symbol, color = ApprovedHomePalette.Text, fontSize = 26.sp, lineHeight = 35.sp,
          fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f))
      Text("$%,.2f".format(q.last), color = ApprovedHomePalette.Text, fontSize = 26.sp,
          lineHeight = 35.sp, maxLines = 1)
    }
    ChangePill(q.changePct, Modifier.padding(top = 8.dp))
  }
}

/**
 * Options scanner widget ([OptionsScanConfig] + [AlpacaConfig]). Lists option contracts across the
 * configured underlyings whose Alpaca-computed delta falls in the user's band (default 0.20–0.30) —
 * a premium-selling shortlist. Refreshes on a slower 60-min/2-min cadence (option chains are heavier
 * and free data is 15-min delayed anyway). Requires Alpaca credentials; tap opens the editor.
 */
@Composable
private fun ImmortalOptionsWidget(modifier: Modifier = Modifier, maxRows: Int = 5) {
  val context = androidx.compose.ui.platform.LocalContext.current
  var tickers by remember { mutableStateOf(OptionsScanConfig.tickers(context)) }
  var configured by remember { mutableStateOf(AlpacaConfig.isConfigured(context)) }
  var type by remember { mutableStateOf(OptionsScanConfig.type(context)) }
  var deltaMin by remember { mutableStateOf(OptionsScanConfig.deltaMin(context)) }
  var deltaMax by remember { mutableStateOf(OptionsScanConfig.deltaMax(context)) }
  var expiryDays by remember { mutableStateOf(OptionsScanConfig.expiryDays(context)) }
  OnResume {
        tickers = OptionsScanConfig.tickers(context)
        configured = AlpacaConfig.isConfigured(context)
        type = OptionsScanConfig.type(context)
        deltaMin = OptionsScanConfig.deltaMin(context)
        deltaMax = OptionsScanConfig.deltaMax(context)
        expiryDays = OptionsScanConfig.expiryDays(context)
  }
  val hits by
      produceHomeState<List<OptionsScanner.Hit>?>(initialValue = null, tickers, configured, type, deltaMin, deltaMax, expiryDays) {
        while (true) {
          value =
              withContext(Dispatchers.IO) {
                val worker = kotlinx.coroutines.currentCoroutineContext()
                OptionsScanner.scan(context, tickers, type, deltaMin, deltaMax, expiryDays,
                    checkCancelled = { worker.ensureActive() })
              }
          homeRefreshDelay(if (value.isNullOrEmpty()) 120_000L else 60L * 60 * 1000)
        }
      }
  val open: () -> Unit = { context.openApprovedHomeSettings("options") }
  ImmortalWidgetShell(title = "Options", openPage = "options",
      modifier = modifier.tvFocusableRow { open() }) {
    Text("${"%.2f–%.2f".format(deltaMin, deltaMax)} delta band",
        color = ApprovedHomePalette.Secondary, fontSize = 18.sp, lineHeight = 24.sp,
        modifier = Modifier.padding(bottom = 12.dp))
    val rows = hits
    when {
      !configured -> ApprovedHomeWidgetMessage("Tap to connect Alpaca")
      tickers.isEmpty() -> ApprovedHomeWidgetMessage("Add tickers to scan")
      rows == null -> ApprovedHomeWidgetMessage("Scanning…")
      rows.isEmpty() -> ApprovedHomeWidgetMessage("No contracts in band")
      else ->
          Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            rows.take(maxRows).forEach { h -> OptionRow(h) }
          }
    }
  }
}

/** One scanner row: "AAPL 300C 12/20" · delta · mid price. */
@Composable
private fun OptionRow(h: OptionsScanner.Hit) {
  val label = "${h.underlying} ${strikeLabel(h.strike)}${if (h.call) "C" else "P"} ${expiryShort(h.expiry)}"
  Column(Modifier.fillMaxWidth()) {
    Text(label, color = ApprovedHomePalette.Text, fontSize = 26.sp, lineHeight = 35.sp,
        fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
      Text("Δ%.2f".format(kotlin.math.abs(h.delta)), color = ApprovedHomePalette.Accent,
          fontSize = 18.sp, lineHeight = 24.sp)
      h.mid?.let { Text("$%.2f".format(it), color = ApprovedHomePalette.Secondary,
          fontSize = 22.sp, lineHeight = 30.sp) }
    }
  }
}

/** Whole-dollar strike when integral (300), else two decimals (302.50). */
private fun strikeLabel(strike: Double): String =
    if (strike == strike.toLong().toDouble()) "${strike.toLong()}" else "%.2f".format(strike)

/** "2024-12-20" -> "12/20". */
private fun expiryShort(iso: String): String =
    iso.split("-").let { if (it.size == 3) "${it[1]}/${it[2]}" else iso }

/**
 * Home Assistant control widget ([HaConfig] + [HaClient]). Renders a tile per pinned entity showing
 * its on/off state; tapping toggles it via HA's REST service call with optimistic UI (flip locally,
 * then reconcile with the fetched truth). Polls state every 30s. Requires a base URL + long-lived
 * token — shows a "connect" prompt until configured. This is the read/control path, distinct from
 * the publish-only MQTT integration.
 */
@Composable
private fun ImmortalHaWidget(modifier: Modifier = Modifier, maxTileRows: Int = 3) {
  val context = androidx.compose.ui.platform.LocalContext.current
  var dashboardConfigured by remember { mutableStateOf(HomeDashboardConfig.configured(context)) }
  OnResume { dashboardConfigured = HomeDashboardConfig.configured(context) }
  if (dashboardConfigured) {
    ImmortalWidgetShell(title = "Home Assistant", openPage = "ha", modifier = modifier,
        onOpen = { HomeDashboardActivity.openDashboard(context) }) {
      Text("Your home dashboard", color = ApprovedHomePalette.Text, fontSize = 26.sp,
          lineHeight = 34.sp, fontWeight = FontWeight.Medium)
      Text("Open your rooms, devices and scenes.", color = ApprovedHomePalette.Secondary,
          fontSize = 22.sp, lineHeight = 30.sp, modifier = Modifier.padding(top = 14.dp))
      ApprovedHomeTextButton("Open dashboard", onClick = {
        HomeDashboardActivity.openDashboard(context)
      })
    }
    return
  }
  val scope = androidx.compose.runtime.rememberCoroutineScope()
  var entities by remember { mutableStateOf(HaConfig.entities(context)) }
  var configured by remember { mutableStateOf(HaConfig.isConfigured(context)) }
  OnResume {
        entities = HaConfig.entities(context)
        configured = HaConfig.isConfigured(context)
  }
  // A local override map lets a tap flip the tile instantly (optimistic) before the poll confirms.
  var optimistic by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
  val ids = remember(entities) { entities.map { it.entityId } }
  val states by
      produceHomeState<List<HaClient.EntityState>?>(initialValue = null, ids, configured) {
        while (true) {
          value = withContext(Dispatchers.IO) {
            val worker = kotlinx.coroutines.currentCoroutineContext()
            HaClient.fetchStates(context, ids, checkCancelled = { worker.ensureActive() })
          }
          optimistic = emptyMap() // truth arrived; drop optimistic overrides
          homeRefreshDelay(30_000L)
        }
      }
  val open: () -> Unit = { context.openApprovedHomeSettings("ha") }
  val openDashboard: () -> Unit = { context.openApprovedHomeSettings("ha-dashboard") }
  ImmortalWidgetShell(title = "Home Assistant", openPage = "ha",
      modifier = modifier.tvFocusableRow { open() }) {
    when {
      !configured -> {
        Text("Connect your home", color = ApprovedHomePalette.Text, fontSize = 26.sp, lineHeight = 34.sp)
        ApprovedHomeTextButton("Set up Home Assistant", onClick = open)
      }
      entities.isEmpty() -> ApprovedHomeWidgetMessage("Add entities to control")
      else -> {
        val byId = remember(states) { (states ?: emptyList()).associateBy { it.entityId } }
        val visiblePairs = remember(entities, maxTileRows) { entities.chunked(2).take(maxTileRows) }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
          // Two tiles per row; row count scales with the widget's height.
          visiblePairs.forEach { pair ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
              pair.forEach { ent ->
                val fetched = byId[ent.entityId]
                val domain = ent.entityId.substringBefore('.', "")
                // Scenes are momentary (no on/off) — a tap just runs them.
                val momentary = domain == "scene"
                // Read-only entities (sensors, weather, presence) show a value, no control.
                val readonly = fetched?.readable == true
                val on = optimistic[ent.entityId] ?: fetched?.on ?: false
                val known = optimistic.containsKey(ent.entityId) || fetched != null
                val displayName = ent.name.ifBlank { fetched?.friendlyName ?: ent.entityId }
                if (readonly) {
                  HaSensorTile(
                      name = displayName,
                      value = fetched?.displayValue?.ifBlank { "—" } ?: "—",
                      modifier = Modifier.weight(1f).tvFocusableRow { openDashboard() },
                  )
                } else {
                  HaTile(
                      name = displayName,
                      on = on,
                      known = known || momentary,
                      momentary = momentary,
                      modifier = Modifier.weight(1f),
                  ) {
                    if (momentary) {
                      scope.launch { withContext(Dispatchers.IO) { HaClient.activate(context, ent.entityId) } }
                    } else {
                      optimistic = optimistic + (ent.entityId to !on)
                      scope.launch { withContext(Dispatchers.IO) { HaClient.toggle(context, ent.entityId) } }
                    }
                  }
                }
              }
              if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
          }
        }
      }
    }
  }
}

/**
 * One HA control tile: name + status line. Toggle entities show On/Off (dimmed while unknown);
 * momentary entities (scenes) show a "Tap to run" affordance and a purple accent, since they have
 * no persistent state.
 */
@Composable
private fun HaTile(
    name: String,
    on: Boolean,
    known: Boolean,
    momentary: Boolean = false,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
  val palette = com.immortal.launcher.ui.theme.LocalApprovedPalette.current
  val bg = when {
    momentary -> ApprovedHomePalette.Selected
    on -> if (palette.dark) palette.selected
        else Color(0xFFD7EEE2)
    else -> ApprovedHomePalette.Chrome
  }
  val fg = ApprovedHomePalette.Text
  val fgSub = ApprovedHomePalette.Secondary
  // Mock anatomy: glyph on its own line up top, then name over the state line — the tile reads
  // as an object (a lamp, a scene) rather than a labelled button.
  Column(
      modifier = modifier.heightIn(min = 150.dp).clip(RoundedCornerShape(17.dp)).background(bg)
          .tvFocusableRow { onToggle() }
          .padding(20.dp),
      verticalArrangement = Arrangement.SpaceBetween,
  ) {
    ApprovedHomeIcon(if (momentary) "tools" else "bulb", Modifier.size(32.dp),
        if (palette.dark && known && on && !momentary) palette.green else fg)
    Column {
      Text(name, color = fg, fontSize = 23.sp, lineHeight = 30.sp,
          fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(
          when {
            momentary -> "Scene"
            !known -> "—"
            on -> "On"
            else -> "Off"
          },
          color = fgSub,
          fontSize = 20.sp,
          lineHeight = 27.sp,
          modifier = Modifier.padding(top = 6.dp),
      )
    }
  }
}

/** A read-only HA value tile: big value + name underneath (sensor / weather / presence). No control. */
@Composable
private fun HaSensorTile(name: String, value: String, modifier: Modifier = Modifier) {
  Column(
      modifier = modifier.heightIn(min = 150.dp).clip(RoundedCornerShape(17.dp))
          .background(ApprovedHomePalette.Chrome)
          .padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(value, color = ApprovedHomePalette.Text, fontSize = 26.sp, lineHeight = 35.sp,
        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(name, color = ApprovedHomePalette.Secondary, fontSize = 22.sp, lineHeight = 30.sp,
        maxLines = 2, overflow = TextOverflow.Ellipsis)
  }
}

/** A keyboard-free duration picker (±steppers, remote/touch friendly) for a custom timer. */
@Composable
private fun CustomTimerDialog(onStart: (Long) -> Unit, onDismiss: () -> Unit) {
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
          TimerChip("Start ${minutes}:%02d".format(seconds)) {
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
private fun TimerChip(label: String, onClick: () -> Unit) {
  Surface(
      color = ApprovedHomePalette.Chrome,
      shape = RoundedCornerShape(16.dp),
      modifier = Modifier.heightIn(min = 52.dp).tvFocusable(RoundedCornerShape(16.dp)) { onClick() },
  ) {
    Text(
        label,
        color = ApprovedHomePalette.Accent,
        fontSize = 22.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
    )
  }
}

@Composable
private fun UnknownImmortalWidget(kind: String, modifier: Modifier = Modifier) {
  Box(modifier.padding(30.dp), contentAlignment = Alignment.Center) {
    Text(kind, color = ApprovedHomePalette.Secondary, fontSize = 22.sp, lineHeight = 30.sp)
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
        HomeWidgetStore.KIND_NEWS -> "📰"
        HomeWidgetStore.KIND_STOCKS -> "📈"
        HomeWidgetStore.KIND_OPTIONS -> "Δ"
        HomeWidgetStore.KIND_HA -> "🏠"
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

private fun loadApps(context: Context): List<AppEntry> {
  val pm = context.packageManager
  val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
  val candidates = pm.queryIntentActivities(intent, 0).filter {
    val pkg = it.activityInfo.packageName
    pkg != context.packageName && !Curation.isHidden(pkg, "")
  }
  return prepareHomeAppIcons(
          candidates,
          packageName = { it.activityInfo.packageName },
          rawLabel = { it.loadLabel(pm).toString() },
          hiddenLabel = { it in Curation.hiddenLabels },
          prepare = { ri, rawLabel ->
            val ai = ri.activityInfo
            val drawable = Curation.iconOverride(ai.packageName)
                ?.let { resId -> androidx.core.content.ContextCompat.getDrawable(context, resId) }
                ?: ri.loadIcon(pm)
            val bmp: Bitmap = IconRenderer.renderAppIcon(context, drawable, 144)
            val appInfo = ai.applicationInfo
            val isUninstallable = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                                  (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            AppEntry(
                label = Curation.displayLabel(ai.packageName, rawLabel),
                component = ComponentName(ai.packageName, ai.name),
                icon = bmp.asImageBitmap(),
                folder = Curation.folderFor(ai.packageName),
                uninstallable = isUninstallable,
            )
          },
      )
      .sortedBy { it.label.lowercase(Locale.getDefault()) }
      .let { apps ->
        // Inject our own iOS-style settings as a grid tile. Our app is excluded from the
        // normal query (pkg == this launcher), and ImmortalSettingsActivity is exported=false
        // with no LAUNCHER filter, so it's launched via the special-case in onLaunch below.
        val settingsIcon = Curation.iconOverride("com.android.settings")
            ?.let { resId -> androidx.core.content.ContextCompat.getDrawable(context, resId) }
            ?.let { IconRenderer.renderAppIcon(context, it, 144).asImageBitmap() }
        // Inject the Immortal App Store too (StoreActivity — also our own, excluded from the query).
        val storeIcon = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.icon_store_tile)
            ?.let { IconRenderer.renderAppIcon(context, it, 144).asImageBitmap() }
        // Inject a "Calls" tile that bridges to the stock Portal calling surface (the only trusted
        // caller). It has no real Activity — launch is special-cased in onLaunch via CALLS_COMPONENT.
        val callIcon = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_call_tile)
            ?.let { IconRenderer.renderAppIcon(context, it, 144).asImageBitmap() }
        // Inject "Tools" (ToolsActivity — our own, exported=false, launched via the onLaunch
        // special-case). It's the entry point to timers, notes, converter, cameras, countdown editor.
        val toolsIcon = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_tools_tile)
            ?.let { IconRenderer.renderAppIcon(context, it, 144).asImageBitmap() }
        // Inject "Music" (WebAppActivity → the YouTube Music PWA — there's no installable
        // client without GMS, so the web app IS the client on a Portal).
        val musicIcon = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_music_tile)
            ?.let { IconRenderer.renderAppIcon(context, it, 144).asImageBitmap() }
        var out = apps
        if (HomeDashboardConfig.configured(context)) {
          val dashboardIcon = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.icon_ha_tile)
              ?.let { IconRenderer.renderAppIcon(context, it, 144).asImageBitmap() }
          if (dashboardIcon != null) out = out + AppEntry(
              label = "Home dashboard",
              component = ComponentName(context, HomeDashboardActivity::class.java),
              icon = dashboardIcon,
          )
        }
        if (musicIcon != null) {
          out = out + AppEntry(
              label = "Music",
              component = ComponentName(context.packageName, WebAppActivity::class.java.name),
              icon = musicIcon,
              uninstallable = false,
          )
        }
        if (callIcon != null) {
          out = out + AppEntry(
              label = "Calls",
              component = CALLS_COMPONENT,
              icon = callIcon,
              uninstallable = false,
          )
        }
        if (toolsIcon != null) {
          out = out + AppEntry(
              label = "Tools",
              component = ComponentName(context.packageName, ToolsActivity::class.java.name),
              icon = toolsIcon,
              uninstallable = false,
          )
        }
        if (settingsIcon != null) {
          out = out + AppEntry(
              label = "Settings",
              component = ComponentName(context.packageName, ImmortalSettingsActivity::class.java.name),
              icon = settingsIcon,
              uninstallable = false,
          )
        }
        if (storeIcon != null) {
          out = out + AppEntry(
              label = "App Store",
              component = ComponentName(context.packageName, StoreActivity::class.java.name),
              icon = storeIcon,
              uninstallable = false,
          )
        }
        out
      }
}

private fun loadWidgetProviders(context: Context, tileDp: Dp): List<WidgetProviderEntry> {
  val pm = context.packageManager
  val density = context.resources.displayMetrics.densityDpi
  val custom =
      listOf(
          WidgetProviderEntry(
              label = "Weather",
              packageLabel = "Immortal",
              icon = null,
              info = null,
              spanX = 2,
              spanY = 2,
              customKind = HomeWidgetStore.KIND_WEATHER,
          ),
          WidgetProviderEntry(
              label = "World Clock",
              packageLabel = "Immortal",
              icon = null,
              info = null,
              spanX = 2,
              spanY = 2,
              customKind = HomeWidgetStore.KIND_WORLD_CLOCK,
          ),
          WidgetProviderEntry(
              label = "Timers",
              packageLabel = "Immortal",
              icon = null,
              info = null,
              spanX = 2,
              spanY = 2,
              customKind = HomeWidgetStore.KIND_TIMERS,
          ),
      )
  val android =
      AppWidgetManager.getInstance(context)
      .installedProviders
      .mapNotNull { info ->
        runCatching {
              val packageLabel =
                  pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0))
                      .toString()
              val icon =
                  runCatching { info.loadIcon(context, density) }
                      .getOrNull()
                      ?: runCatching { pm.getApplicationIcon(info.provider.packageName) }.getOrNull()
              val iconBitmap = icon?.toBitmap(96, 96)?.asImageBitmap()
              WidgetProviderEntry(
                  label = info.loadLabel(pm).toString().ifBlank { packageLabel },
                  packageLabel = packageLabel,
                  icon = iconBitmap,
                  info = info,
                  spanX =
                      estimateWidgetSpan(appWidgetMinDp(context, info.minWidth), tileDp)
                          .coerceAtLeast(HomeWidgetStore.DEFAULT_SPAN_X),
                  spanY =
                      estimateWidgetSpan(appWidgetMinDp(context, info.minHeight), tileDp)
                          .coerceAtLeast(HomeWidgetStore.DEFAULT_SPAN_Y),
              )
            }
            .getOrNull()
      }
      .sortedWith(
          compareBy(
              { it.packageLabel.lowercase(Locale.getDefault()) },
              { it.label.lowercase(Locale.getDefault()) }))
  return custom + android
}

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

private fun appWidgetMinDp(context: Context, raw: Int): Int {
  if (raw <= 0) return 0
  // Android 9/10 on Portal reports AppWidgetProviderInfo sizes as raw complex
  // dimension values (for example, 10241 == 40dp). Newer framework builds may
  // already hand back dp integers, so only decode values that are clearly not
  // plain dp sizes.
  if (raw < 1000) return raw
  val metrics = context.resources.displayMetrics
  return (TypedValue.complexToDimensionPixelSize(raw, metrics) / metrics.density).roundToInt()
}

@Composable
private fun ApprovedHeaderButton(icon: String, label: String, onClick: () -> Unit) {
  Box(Modifier.size(68.dp).clip(CircleShape).background(ApprovedHomePalette.Chrome)
      .semantics { contentDescription = label }
      .tvFocusable(CircleShape, ringColor = ApprovedHomePalette.Accent, onClick = onClick),
      contentAlignment = Alignment.Center) {
    ApprovedHomeIcon(icon, tint = ApprovedHomePalette.Text)
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
@Composable
private fun ApprovedFavoriteTile(app: AppEntry, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
  ApprovedHomeSurface(modifier.height(88.dp), radius = 18.dp) {
    Row(Modifier.fillMaxSize().combinedClickable(onClick = onClick, onLongClick = onLongClick)
        .padding(14.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
      Image(app.icon, contentDescription = null, modifier = Modifier.size(56.dp))
      Text(app.label, color = ApprovedHomePalette.Text, fontSize = 22.sp, lineHeight = 30.sp,
          fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        Triple(HomeWidgetStore.KIND_TODO, "To-do", "notes"),
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
private fun ApprovedHomeGreeting(welcome: WelcomeConfig.Settings, use24: Boolean, compact: Boolean = false) {
  val now by produceState(initialValue = Date()) {
    while (true) {
      delay(60_000L - System.currentTimeMillis() % 60_000L)
      value = Date()
    }
  }
  val timeFmt = remember(use24) { SimpleDateFormat(if (use24) "H:mm" else "h:mm", Locale.getDefault()) }
  val dayFmt = remember { SimpleDateFormat("EEEE", Locale.getDefault()) }
  val dateFmt = remember { SimpleDateFormat("MMMM d", Locale.getDefault()) }
  val hour = java.util.Calendar.getInstance().apply { time = now }.get(java.util.Calendar.HOUR_OF_DAY)
  val greeting = when (hour) {
    in 5..11 -> welcome.greetingMorning
    in 12..16 -> welcome.greetingAfternoon
    in 17..21 -> welcome.greetingEvening
    else -> welcome.greetingNight
  }
  val greetingText = if (welcome.userName.isBlank()) greeting.trimEnd('.', '!') + "."
      else greeting.trimEnd('.', '!') + ", " + welcome.userName
  Row(Modifier.fillMaxWidth().height(if (compact) 68.dp else 108.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text(greetingText, color = ApprovedHomePalette.Text, fontSize = 52.sp, lineHeight = 64.sp,
          fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      if (!compact) Text("Everything within reach.", color = ApprovedHomePalette.Secondary, fontSize = 24.sp,
          lineHeight = 34.sp, modifier = Modifier.padding(top = 10.dp))
    }
    Text(timeFmt.format(now), color = ApprovedHomePalette.Text, fontSize = 80.sp, lineHeight = 88.sp,
        fontWeight = FontWeight.Normal, letterSpacing = (-2).sp)
    Text(dayFmt.format(now) + "\n" + dateFmt.format(now), color = ApprovedHomePalette.Secondary,
        fontSize = 24.sp, lineHeight = 34.sp, modifier = Modifier.padding(start = 24.dp))
  }
}
