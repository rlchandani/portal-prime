/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.immortal.launcher.ui.components.OnResume
import com.immortal.launcher.settings.SettingsDomains
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Immortal's own settings (weather unit, home-screen tile size), reached from the
 * "Immortal" tile in the launcher's Settings folder. The launcher re-reads these
 * on resume, so changes apply the moment the user returns home.
 *
 * Rendered with an iOS Settings aesthetic: light grey background (#f2f2f7), white grouped
 * cards (12dp radius), blue toggles, uppercase section headers.
 */
class ImmortalSettingsActivity : ComponentActivity() {
  internal var settingsUiProbe: SettingsUiProbeStore? = null
    private set
  internal fun settingsUiRequestedRoute() = SettingsUiProbeRoute(route.category, route.page, route.control)
  internal fun settingsUiHistoryDepth() = history.entries.size
  private var entryReport: SettingsEntryReport? = null
  private var entryFirstDraw: SettingsEntryFirstDrawObservation? = null

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
  private var history by mutableStateOf(ApprovedSettingsHistory.initial(ApprovedSettingsRoute()))
  private val route: ApprovedSettingsRoute get() = history.current

  private fun readRoute(intent: Intent) = ApprovedSettingsRoute(
      intent.getStringExtra(APPROVED_SETTINGS_PAGE),
      intent.getStringExtra(APPROVED_SETTINGS_CATEGORY) ?: "appearance",
      intent.getStringExtra(APPROVED_SETTINGS_CONTROL),
  )

  private fun navigateLocal(next: ApprovedSettingsRoute) {
    history = history.navigate(next)
    syncRouteIntent()
  }

  private fun permittedHistoryRoute(next: ApprovedSettingsRoute, advanced: Boolean): Boolean =
      advanced || (next.category != "advanced" &&
          next.page?.let(com.immortal.launcher.settings.SettingsPresentation::isAdvancedPage) != true)

  private fun backLocal() {
    val advanced = ImmortalSettings.advancedSettings(this)
    history = history.back { permittedHistoryRoute(it, advanced) }
    syncRouteIntent()
  }

  private fun forwardLocal() {
    val advanced = ImmortalSettings.advancedSettings(this)
    history = history.forward { permittedHistoryRoute(it, advanced) }
    syncRouteIntent()
  }

  /** Remote and physical Back use the same visited-page history as the header. */
  internal fun handleRemoteSettingsBack(): Boolean {
    val advanced = ImmortalSettings.advancedSettings(this)
    if (history.back { permittedHistoryRoute(it, advanced) } == history) return false
    backLocal()
    return true
  }

  private fun syncRouteIntent() {
    val next = route
    // Keep intent fallbacks in shared rows in sync, including clearing an old search highlight.
    setIntent(Intent(intent).apply {
      removeExtra(APPROVED_SETTINGS_PAGE)
      removeExtra(APPROVED_SETTINGS_CONTROL)
      putExtra(APPROVED_SETTINGS_CATEGORY, next.category)
      next.page?.let { putExtra(APPROVED_SETTINGS_PAGE, it) }
      next.control?.let { putExtra(APPROVED_SETTINGS_CONTROL, it) }
    })
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    entryReport = FramePerformanceCapture.beginSettingsCreate(intent)
    super.onCreate(savedInstanceState)
    if (BuildConfig.PORTAL_SETTINGS_UI_DIAGNOSTICS) {
      val index = approvedSettingsSearchIndex()
      settingsUiProbe = SettingsUiProbeStore(
          ApprovedSettingsCategories.map { it.id }.toSet(),
          (ApprovedSettingsPages.map { it.id } + index.map { it.page }).toSet(),
          index.map { it.id }.filter { it.isNotEmpty() }.toSet())
    }
    val restored = savedInstanceState?.getParcelableArrayList<Bundle>("approved_settings_history")
        ?.map { ApprovedSettingsRoute(it.getString("page"), it.getString("category") ?: "appearance",
            it.getString("control")) }
    history = if (restored.isNullOrEmpty()) ApprovedSettingsHistory.initial(readRoute(intent)) else {
      val entries = restored.takeLast(32)
      val removed = restored.size - entries.size
      val position = ((savedInstanceState?.getInt("approved_settings_history_position", restored.lastIndex) ?: restored.lastIndex) - removed)
          .coerceIn(0, entries.lastIndex)
      ApprovedSettingsHistory(entries, position)
    }
    syncRouteIntent()
    setContent {
      PortalPrimeTheme(darkTheme = false) {
        val navigate = remember { ::navigateLocal }
        val back = remember { ::backLocal }
        val forward = remember { ::forwardLocal }
        val viewports = remember { ApprovedSettingsViewportMemory() }
        val advanced = rememberApprovedAdvanced()
        val previous = history.back { permittedHistoryRoute(it, advanced) }.takeIf { it != history }
        val next = history.forward { permittedHistoryRoute(it, advanced) }.takeIf { it != history }
        fun routeTitle(target: ApprovedSettingsRoute): String =
            target.page?.let { approvedSettingsPage(it)?.title }
                ?: ApprovedSettingsCategories.firstOrNull { it.id == target.category }?.title.orEmpty()
        val controls = ApprovedSettingsHistoryControls(
            onBack = previous?.let { back }, onForward = next?.let { forward },
            backTitle = previous?.current?.let(::routeTitle), forwardTitle = next?.current?.let(::routeTitle),
        )
        CompositionLocalProvider(
            LocalApprovedRequestedControl provides route.control,
            LocalApprovedSettingsNavigate provides navigate,
            LocalApprovedSettingsBack provides back,
            LocalApprovedSettingsHistoryControls provides controls,
            LocalApprovedSettingsViewportMemory provides viewports,
        ) {
          ImmortalSettingsScreen(route)
        }
      }
    }
    observeEntryFirstDraw()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    history = ApprovedSettingsHistory.initial(readRoute(intent))
  }

  override fun onSaveInstanceState(outState: Bundle) {
    outState.putParcelableArrayList("approved_settings_history", ArrayList(history.entries.map { entry ->
      Bundle().apply {
        putString("page", entry.page); putString("category", entry.category); putString("control", entry.control)
      }
    }))
    outState.putInt("approved_settings_history_position", history.position)
    super.onSaveInstanceState(outState)
  }
}

@Composable
private fun ImmortalSettingsScreen(route: ApprovedSettingsRoute) {
  val context = LocalContext.current
  val navigate = rememberApprovedSettingsNavigator()
  val category = ApprovedSettingsCategories.firstOrNull { it.id == route.category }
      ?: ApprovedSettingsCategories.first()
  val backToCategory = rememberApprovedSettingsBack()
  val historyControls = LocalApprovedSettingsHistoryControls.current
  BackHandler(enabled = historyControls?.onBack != null, onBack = backToCategory)
  var prerequisite by remember(route) { mutableStateOf<String?>(null) }
  LaunchedEffect(route) {
    route.control?.takeIf { it.isNotBlank() }?.let { control ->
      prerequisite = withContext(Dispatchers.IO) { approvedSettingsPrerequisite(context, control) }
    }
  }
  // A retained detail must keep its own route, including its search highlight. Creating the
  // composable lambda inside remember prevents a newer route from updating the old lambda.
  val routeContent: @Composable () -> Unit = remember(route, navigate) {
    { ApprovedSettingsRouteContent(route, navigate) }
  }
  ApprovedSettingsShell(
      category = category.id,
      title = route.page?.let { approvedSettingsPage(it)?.title } ?: category.title,
      subtitle = if (route.page == null) category.subtitle else "",
      onBack = if (route.page != null) backToCategory else null,
      page = route.page,
      lazyDetail = if (route.page == "screensaver")
          screensaverSettingsDetail("${route.detailKey}/${route.control.orEmpty()}") else null,
      content = routeContent,
  )
  prerequisite?.let { message ->
    ApprovedSettingsDialog("Setting prerequisite", onDismiss = { prerequisite = null }, help = message) {
      ApprovedSettingsTextButton("Review parent settings") { prerequisite = null }
    }
  }
}

@Composable
private fun ApprovedSettingsRouteContent(
    route: ApprovedSettingsRoute,
    navigate: (ApprovedSettingsRoute) -> Unit,
) {
  val category = ApprovedSettingsCategories.firstOrNull { it.id == route.category }
      ?: ApprovedSettingsCategories.first()
  key(route.detailKey, route.control) {
    if (route.page != null) {
      ApprovedNativeSettingsPage(route.page)
    } else {
      val advanced = rememberApprovedAdvanced()
      if (category.id == "advanced") {
        if (!advanced) {
          ApprovedSettingsAdvancedGate(LocalApprovedEnableAdvanced.current)
        } else {
          category.pages.mapNotNull(::approvedSettingsPage).groupBy { it.category }.forEach { (group, pages) ->
            SectionLabel(ApprovedSettingsCategories.firstOrNull { it.id == group }?.title ?: "Device")
            Card {
              pages.forEachIndexed { index, destination ->
                if (index > 0) Divider()
                NavRow(destination.title, modifier = if (BuildConfig.PORTAL_SETTINGS_UI_DIAGNOSTICS)
                    Modifier.settingsUiTarget("nav", destination.id, route.detailKey, control = route.control) else Modifier) {
                  navigate(approvedSettingsRoute(destination.id, category = category.id))
                }
              }
            }
          }
        }
      } else {
        ApprovedSettingsSectionLabel("Settings")
        Card {
          category.pages.forEachIndexed { index, id ->
            if (index > 0) Divider()
            NavRow(approvedSettingsPage(id)?.title ?: id,
                modifier = if (BuildConfig.PORTAL_SETTINGS_UI_DIAGNOSTICS)
                    Modifier.settingsUiTarget("nav", id, route.detailKey, control = route.control) else Modifier) {
              navigate(approvedSettingsRoute(id, category = category.id))
            }
          }
        }
      }
    }
  }
}

/** Shared bodies retain their original registry, validators and side effects. */
@Composable
private fun ApprovedNativeSettingsPage(page: String) {
  val context = LocalContext.current
  val navigate = rememberApprovedSettingsNavigator()
  val back = rememberApprovedSettingsBack()
  val application = context.applicationContext
  val immortal = remember(application) { DeferredSettingsSnapshot { ImmortalSettings.load(application) } }
  val ss = remember(application) { DeferredSettingsSnapshot { ScreensaverConfig.load(application) } }
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(application, page, lifecycleOwner, immortal, ss) {
    var active = false
    var stopObserving: (() -> Unit)? = null
    fun stop() {
      active = false
      stopObserving?.invoke()
      stopObserving = null
    }
    fun start() {
      if (active) return
      active = true
      // Subscribe before readback so a remote write cannot fall between the two operations.
      stopObserving = when (page) {
        "appearance", "weather" -> ImmortalSettings.observeSnapshot(application) {
          immortal.refreshIfLoaded()
        }
        "calendar" -> ScreensaverConfig.observeCalendarSnapshot(application) {
          ss.refreshIfLoaded()
        }
        else -> null
      }
      immortal.refreshIfLoaded()
      ss.refreshIfLoaded()
    }
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_RESUME -> start()
        Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> stop()
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) start()
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      stop()
    }
  }
  fun applyImmortal(key: String, value: Any) {
    SettingsDomains.immortal.apply(context, JSONObject().put(key, value))
    immortal.refreshIfLoaded()
  }
  when (page) {
    "display" -> DisplaySection()
    // Screensaver uses the same single lazy list as its standalone wrapper via lazyDetail.
    "screensaver" -> Unit
    "sources" -> ScreensaverSourcesContent(onDone = back)
    "dismiss" -> ScreensaverDismissContent()
    "styles" -> ScreensaverModeContent()
    "faces" -> FacePickerContent()
    "clock" -> ClockSettingsContent()
    "chime" -> ChimeSettingsContent()
    "almanac" -> AlmanacSettingsContent()
    "welcome" -> WelcomeSettingsContent()
    "sunrise" -> SunriseContent()
    "sleep" -> SleepSettingsContent()
    "alarm" -> AlarmContent()
    "world" -> WorldClockContent()
    "countdowns" -> CountdownSettingsContent()
    "crypto" -> CryptoSettingsContent()
    "todo" -> TodoSettingsContent()
    "news" -> NewsSettingsContent()
    "stocks" -> StocksSettingsContent()
    "options" -> OptionsSettingsContent()
    "alpaca" -> ApprovedEditorsAlpacaForm(onCancel = back)
    "multiroom" -> MultiRoomContent()
    "mqtt" -> MqttContent()
    "health" -> DeviceHealthContent()
    "boot" -> BootAppsContent()
    "ha" -> HaSettingsContent()
    "intercom" -> IntercomContent()
    "appearance" -> {
      val domain = SettingsDomains.immortal
      val exclude = domain.specs.map { it.key }.toSet() -
          setOf("tileSize", "showMiniPlayer", "hideStatusBar", "clockFormat", "settingsTheme")
      val onApply: (String, Any) -> Unit = { k, v -> applyImmortal(k, v) }
      if (BuildConfig.PORTAL_SETTINGS_DIRECT_APPEARANCE_ROWS) {
        // Keep the retained detail's existing scroll owner; avoid a second list for these rows.
        SettingsRows(domain, immortal.value, exclude, onApply)
      } else {
        SettingsList(domain, immortal.value, exclude, onApply = onApply)
      }
    }
    "weather" -> SettingsList(SettingsDomains.immortal, immortal.value,
        exclude = SettingsDomains.immortal.specs.map { it.key }.toSet() -
            setOf("weatherUnit", "weatherWidget")) { k, v -> applyImmortal(k, v) }
    "wallpaper" -> WallpaperSection()
    "calendar" -> SettingsList(SettingsDomains.calendar, ss.value) { k, v ->
      SettingsDomains.calendar.apply(context, JSONObject().put(k, v))
      ss.refreshIfLoaded()
    }
    "quickbar" -> QuickButtonsSection()
    "touch" -> ApprovedTouchSounds()
    "device" -> DeviceNameSection()
    "widgets" -> {
      SectionLabel("Home dashboard")
      Card {
        NavRow("Customize home", subLabel = "Touch and hold a widget on Home to move, resize or remove it.") {
          context.startActivity(Intent(context, HomeActivity::class.java))
        }
      }
      SectionLabel("Widget settings")
      Card {
        listOf("weather", "ha", "calendar", "alarm", "world", "crypto", "countdowns", "todo", "news", "stocks", "sources")
            .forEachIndexed { index, id ->
              if (index > 0) Divider()
              NavRow(approvedSettingsPage(id)?.title ?: id) {
                navigate(approvedSettingsRoute(id))
              }
            }
      }
      if (rememberApprovedAdvanced()) {
        SectionLabel("Advanced options")
        Card { NavRow("Options scanner") { navigate(approvedSettingsRoute("options")) } }
      }
    }
    "system" -> {
      SectionLabel("Android settings")
      Card {
        listOf(
            "Wi-Fi" to Settings.ACTION_WIFI_SETTINGS,
            "Bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
            "Date & time" to Settings.ACTION_DATE_SETTINGS,
            "Language & region" to Settings.ACTION_LOCALE_SETTINGS,
            "Accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
            "Storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
            "App permissions" to Settings.ACTION_APPLICATION_SETTINGS,
            "All Android Settings" to Settings.ACTION_SETTINGS,
        ).forEachIndexed { index, (title, action) ->
          if (index > 0) Divider()
          NavRow(title, subLabel = "Opens the existing system screen") {
            AndroidSettingsNavigation.open(context, title, action)
          }
        }
        Divider()
        NavRow("Portal Settings", subLabel = "Opens the existing Portal settings") {
          AndroidSettingsNavigation.openPortal(context)
        }
      }
    }
    "managed" -> ApprovedManagedSettingsPage()
    "debug" -> {
      Card {
        IosDebugRow(context)
        Divider()
        NavRow("About & device health") { navigate(approvedSettingsRoute("health")) }
      }
    }
    "speed" -> Card {
      NavRow("Speed test", subLabel = "Open Tools to measure your connection.") {
        context.openApprovedHomeTab(ApprovedLauncherTab.TOOLS)
      }
    }
  }
}

@Composable
private fun ApprovedTouchSounds() {
  val context = LocalContext.current
  val writes = remember(context.applicationContext) { SystemSoundsWrites.queue(context) }
  val completion = rememberCoroutineScope()
  var snapshot by remember(context) { mutableStateOf<SystemTouchSoundsSnapshot?>(null) }
  var failed by remember(context) { mutableStateOf(false) }
  var applying by remember(context) { mutableStateOf(false) }
  var editRevision by remember(context) { mutableStateOf(0) }
  val lifecycleOwner = LocalLifecycleOwner.current
  val pageActive = LocalApprovedLauncherPageActive.current
  suspend fun readSounds() = loadSystemTouchSounds {
    SystemTouchSoundsSnapshot(SystemSounds.canWrite(context), SystemSounds.touchSoundsEnabled(context))
  }
  LaunchedEffect(context, lifecycleOwner, pageActive) {
    if (!pageActive) return@LaunchedEffect
    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      val revision = editRevision
      writes.awaitIdle()
      val loaded = readSounds()
      if (revision == editRevision) {
        failed = loaded == null
        if (loaded != null) snapshot = loaded
      }
    }
  }
  val current = snapshot
  if (current == null) {
    FooterNote(if (failed) "Touch sound change unavailable" else "Loading touch sound settings…")
    return
  }
  SectionLabel("Options")
  Card {
    if (!current.canWrite) {
      NavRow("Allow touch sound control", value = "Grant") { SystemSounds.requestWriteAccess(context) }
    } else {
      ToggleRow("Touch sounds", current.enabled, enabled = !applying) { checked ->
        if (!applying) {
          applying = true
          failed = false
          editRevision++
          snapshot = current.copy(enabled = checked)
          writes.submit(checked)
          completion.launch {
            try {
              writes.awaitIdle()
              val loaded = readSounds()
              failed = loaded == null || loaded.enabled != checked
              // A failed read-back must not leave an optimistic value presented as real state.
              snapshot = loaded
            } finally { applying = false }
          }
        }
      }
    }
  }
  if (applying) FooterNote("Applying touch sound setting…")
  if (failed) FooterNote("Touch sound change unavailable")
  FooterNote("Plays the system tap sound on touch. Android may ask you to allow modifying system settings.")
}

@Composable
private fun ApprovedManagedSettingsPage() {
  val context = LocalContext.current
  com.immortal.launcher.settings.SettingsRegistry.domains.forEach { raw ->
    @Suppress("UNCHECKED_CAST")
    val domain = raw as com.immortal.launcher.settings.SettingsDomain<Any>
    val snapshot = domain.load(context)
    val controls = domain.specs.filter { "${domain.id}.${it.key}" in ApprovedManagedControls }
    if (controls.isNotEmpty()) {
      SectionLabel(domain.title)
      Card {
        controls.forEachIndexed { index, spec ->
          if (index > 0) Divider()
          val meta = spec.metaJson(context, snapshot)
          Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = Sizes.rowMinHeight)
              .padding(horizontal = 28.dp, vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            ApprovedSettingsLabel(meta?.optString("title") ?: spec.key,
                "Managed by the existing app · preserved without a new switch", Modifier.weight(1f))
            Text(meta?.optString("display")?.takeIf { it.isNotBlank() }
                ?: meta?.opt("value")?.toString().orEmpty(), color = IosSecondary, fontSize = Sizes.rowValue)
          }
        }
      }
    }
  }
  FooterNote("Legacy appearance values remain stored and unchanged.")
}

// ---------------------------------------------------------------------------
// Debug toggle row (uses a local pref; only relevant in debug builds)
// ---------------------------------------------------------------------------

private const val PREFS_DEBUG = "immortal_debug"

@Composable
private fun IosDebugRow(context: android.content.Context) {
  val prefs = remember { context.getSharedPreferences(PREFS_DEBUG, android.content.Context.MODE_PRIVATE) }
  var debug by remember { mutableStateOf(prefs.getBoolean("debug_mode", false)) }
  ToggleRow(
      title = "Debug Mode",
      checked = debug,
      onChange = {
        debug = it
        prefs.edit().putBoolean("debug_mode", it).apply()
      },
  )
}

// ---------------------------------------------------------------------------
// Detail-panel building blocks used by the grouped sections
// ---------------------------------------------------------------------------

/**
 * A single iOS-style nav card that launches a sub-screen Activity (Chime, Sunrise, Multi-room,
 * Welcome, Countdown, MQTT, Remote, Boot apps, Sleep timer, World clock). Kept light-themed to
 * match the two-panel detail area; the launched Activity draws its own full-screen page.
 */
@Composable
private fun SubScreenNavCard(
    title: String,
    subtitle: String,
    iconColor: Color,
    iconSymbol: String,
    onOpen: () -> Unit,
) {
  Card {
    NavRow(
        title = title,
        subLabel = subtitle,
        iconColor = iconColor,
        iconSymbol = iconSymbol,
        onClick = onOpen,
    )
  }
}

private data class BrightnessSnapshot(
    val canWrite: Boolean,
    val brightness: Int,
    val autoBrightness: Boolean,
)

private data class DisplaySnapshot(
    val canWrite: Boolean,
    val brightness: Int,
    val autoBrightness: Boolean,
    val volume: Int,
    val nightShift: DisplayControl.NightShift,
)

/**
 * The "Display" detail: screen brightness + auto-brightness (real system settings via
 * [DisplayControl], needing the WRITE_SETTINGS grant), and Night Shift — a launcher-drawn warm tint
 * over a nightly window. Brightness rows gate on the write permission; if it's not held we show a
 * "grant" nav row that opens the system screen.
 */
@Composable
private fun DisplaySection() {
  val context = LocalContext.current
  val writes = remember(context.applicationContext) { DisplayWrites.queue(context) }
  var snapshot by remember(context) { mutableStateOf<DisplaySnapshot?>(null) }
  var loadFailed by remember(context) { mutableStateOf(false) }
  var editRevision by remember(context) { mutableStateOf(0) }
  var brightnessRevision by remember(context) { mutableStateOf(0) }
  var confirmedBrightness by remember(context) { mutableStateOf<BrightnessSnapshot?>(null) }
  var brightnessError by remember(context) { mutableStateOf<String?>(null) }
  var brightnessReadback by remember(context) { mutableStateOf<Job?>(null) }
  var latestBrightnessEdit by remember(context) { mutableStateOf<DisplayEdit?>(null) }
  val scope = rememberCoroutineScope()
  val lifecycleOwner = LocalLifecycleOwner.current
  val pageActive = LocalApprovedLauncherPageActive.current
  LaunchedEffect(context, lifecycleOwner, pageActive) {
    if (!pageActive) return@LaunchedEffect
    // The retained pane owner follows both the parent lifecycle and pane activation. There is
    // one initial/resume load, and leaving RESUMED cancels publication of an unfinished read.
    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      loadFailed = false
      val revision = editRevision
      val loaded = withContext(Dispatchers.IO) {
        // A pane can resume while its final drag value is still being applied in the background.
        writes.awaitIdle()
        runCatching {
          DisplaySnapshot(
              canWrite = DisplayControl.canWrite(context),
              brightness = DisplayControl.brightnessPercent(context),
              autoBrightness = DisplayControl.autoBrightness(context),
              volume = DisplayControl.mediaVolumePercent(context),
              nightShift = DisplayControl.nightShift(context),
          )
        }.getOrNull()
      }
      loadFailed = loaded == null
      // Keep real cached values during a refresh, but never replace a newer slider/toggle edit
      // with a system read that started before that edit.
      if (loaded != null && revision == editRevision) {
        snapshot = loaded
        confirmedBrightness = BrightnessSnapshot(loaded.canWrite, loaded.brightness, loaded.autoBrightness)
        brightnessError = null
      }
    }
  }
  val display = snapshot
  if (display == null) {
    // Until the first snapshot arrives there are no writable rows or invented control values.
    FooterNote(if (loadFailed) "Display settings could not be loaded. Reopen this page to try again."
        else "Loading display settings…")
    return
  }
  fun updateDisplay(update: (DisplaySnapshot) -> DisplaySnapshot) {
    snapshot = snapshot?.let(update)
    editRevision++
  }
  fun beginBrightnessEdit(edit: DisplayEdit) {
    latestBrightnessEdit = edit
    brightnessReadback?.cancel()
    brightnessRevision++
    brightnessError = null
  }
  fun reconcileBrightness(expected: DisplayEdit) {
    brightnessReadback?.cancel()
    val revision = brightnessRevision
    brightnessReadback = scope.launch {
      val actual = withContext(Dispatchers.IO) {
        writes.awaitIdle()
        runCatching {
          BrightnessSnapshot(
              DisplayControl.canWrite(context),
              DisplayControl.brightnessPercent(context),
              DisplayControl.autoBrightness(context),
          )
        }.getOrNull()
      }
      // A later brightness edit owns the UI; volume and tint edits must not invalidate this read.
      if (revision != brightnessRevision) return@launch
      val observed = actual ?: confirmedBrightness
      if (observed != null) {
        snapshot = snapshot?.copy(canWrite = observed.canWrite, brightness = observed.brightness,
            autoBrightness = observed.autoBrightness)
      }
      if (actual == null) {
        brightnessError = "Brightness change could not be verified."
      } else {
        confirmedBrightness = actual
        val applied = actual.canWrite && when (expected) {
          is DisplayEdit.Brightness -> {
            // Android stores 0..255; match the same integer quantization as DisplayControl.
            val raw = (expected.percent.coerceIn(1, 100) * 255 / 100).coerceIn(1, 255)
            !actual.autoBrightness && actual.brightness == (raw * 100f / 255f).toInt()
          }
          is DisplayEdit.AutoBrightness -> actual.autoBrightness == expected.enabled
          is DisplayEdit.Volume -> false
        }
        brightnessError = if (applied) null else "Brightness change could not be applied."
      }
    }
  }
  fun updateNightShift(update: (DisplayControl.NightShift) -> DisplayControl.NightShift) {
    updateDisplay { it.copy(nightShift = update(it.nightShift)) }
  }
  val brightness = display.brightness
  val volume = display.volume
  val ns = display.nightShift

  ApprovedSettingsSectionLabel("Brightness")
  Card {
    if (!display.canWrite) {
      NavRow(
          title = "Allow brightness control",
          value = "Grant",
      ) { DisplayControl.requestWriteAccess(context) }
    } else {
      // Brightness slider row.
      Row(
          modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = Sizes.rowMinHeight)
              .padding(horizontal = 16.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("Brightness", color = IosLabel, fontSize = Sizes.rowTitle, modifier = Modifier.width(240.dp))
        androidx.compose.material3.Slider(
            value = brightness.toFloat(),
            onValueChange = {
              val percent = it.toInt()
              // A manual brightness write also turns adaptive brightness off.
              beginBrightnessEdit(DisplayEdit.Brightness(percent))
              updateDisplay { current -> current.copy(brightness = percent, autoBrightness = false) }
              writes.submit(DisplayEdit.Brightness(percent))
            },
            onValueChangeFinished = {
              // Finishing an older drag must not supersede a newer auto-brightness toggle.
              val edit = latestBrightnessEdit
              if (edit is DisplayEdit.Brightness) reconcileBrightness(edit)
            },
            valueRange = 1f..100f,
            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = IosBlue,
                activeTrackColor = IosBlue, inactiveTrackColor = IosDivider),
            modifier = Modifier.weight(1f).semantics { contentDescription = "Adjust brightness" },
        )
        Text("$brightness%", color = IosSecondary, fontSize = Sizes.rowValue,
            modifier = Modifier.width(90.dp), textAlign = TextAlign.End)
      }
      Divider()
      ToggleRow(
          title = "Auto-brightness",
          checked = display.autoBrightness,
          onChange = { on ->
            beginBrightnessEdit(DisplayEdit.AutoBrightness(on))
            updateDisplay { it.copy(autoBrightness = on) }
            writes.submit(DisplayEdit.AutoBrightness(on))
            reconcileBrightness(DisplayEdit.AutoBrightness(on))
          },
      )
    }
  }
  brightnessError?.let {
    Text(it, color = IosRed, fontSize = Sizes.rowSub,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
  }
  FooterNote("Adjust the screen brightness, or let it adapt to the room automatically.")

  SectionLabel("Volume")
  Card {
    Row(
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = Sizes.rowMinHeight)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("Media", color = IosLabel, fontSize = Sizes.rowTitle, modifier = Modifier.width(240.dp))
      androidx.compose.material3.Slider(
          value = volume.toFloat(),
          onValueChange = {
            val percent = it.toInt()
            updateDisplay { current -> current.copy(volume = percent) }
            writes.submit(DisplayEdit.Volume(percent))
          },
          valueRange = 0f..100f,
          colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = IosBlue,
              activeTrackColor = IosBlue, inactiveTrackColor = IosDivider),
          modifier = Modifier.weight(1f).semantics { contentDescription = "Adjust media volume" },
      )
      Text("$volume%", color = IosSecondary, fontSize = Sizes.rowValue,
          modifier = Modifier.width(90.dp), textAlign = TextAlign.End)
    }
  }
  FooterNote("Sets the media playback volume (music, videos, chimes).")

  SectionLabel("Night Shift")
  Card {
    ToggleRow(
        title = "Night Shift",
        checked = ns.enabled,
        onChange = { on ->
          DisplayControl.setNightShiftEnabled(context, on)
          updateNightShift { it.copy(enabled = on) }
        },
    )
    if (ns.enabled) {
      Divider()
      Stepper(
          label = "From",
          valueText = minuteLabel(ns.startMin),
          onMinus = {
            val next = stepSettingNumber(ns.startMin, -30, 0, 1439, wrap = true)
            DisplayControl.setNightShiftStart(context, next)
            updateNightShift { it.copy(startMin = next) }
          },
          onPlus = {
            val next = stepSettingNumber(ns.startMin, 30, 0, 1439, wrap = true)
            DisplayControl.setNightShiftStart(context, next)
            updateNightShift { it.copy(startMin = next) }
          },
      )
      Divider()
      Stepper(
          label = "Until",
          valueText = minuteLabel(ns.endMin),
          onMinus = {
            val next = stepSettingNumber(ns.endMin, -30, 0, 1439, wrap = true)
            DisplayControl.setNightShiftEnd(context, next)
            updateNightShift { it.copy(endMin = next) }
          },
          onPlus = {
            val next = stepSettingNumber(ns.endMin, 30, 0, 1439, wrap = true)
            DisplayControl.setNightShiftEnd(context, next)
            updateNightShift { it.copy(endMin = next) }
          },
      )
      Divider()
      Stepper(
          label = "Warmth",
          valueText = "${ns.strength}%",
          onMinus = {
            DisplayControl.setNightShiftStrength(context, ns.strength - 10)
            updateNightShift { it.copy(strength = (ns.strength - 10).coerceIn(0, 100)) }
          },
          onPlus = {
            DisplayControl.setNightShiftStrength(context, ns.strength + 10)
            updateNightShift { it.copy(strength = (ns.strength + 10).coerceIn(0, 100)) }
          },
      )
    }
  }
  FooterNote("Warms the screen with an amber tint during the night window, easier on the eyes after dark.")
}

/** "h:mm a" / "HH:mm"-ish label for a minute-of-day (uses 12-hour with AM/PM for readability). */
private fun minuteLabel(min: Int): String {
  val h = (min / 60) % 24
  val m = min % 60
  val ampm = if (h < 12) "AM" else "PM"
  val h12 = when { h == 0 -> 12; h > 12 -> h - 12; else -> h }
  return "%d:%02d %s".format(h12, m, ampm)
}

/** Device-name edits use the registry; saved readback stays current without replacing an open draft. */
@Composable
private fun DeviceNameSection() {
  val context = LocalContext.current.applicationContext
  var settings by remember(context) { mutableStateOf(FleetConfig.loadName(context)) }
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(context, lifecycleOwner) {
    var stopObserving: (() -> Unit)? = null
    fun stop() {
      stopObserving?.invoke()
      stopObserving = null
    }
    fun start() {
      if (stopObserving == null) {
        stopObserving = FleetConfig.observeName(context) { settings = FleetConfig.loadName(context) }
        settings = FleetConfig.loadName(context)
      }
    }
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_RESUME -> start()
        Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> stop()
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) start()
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      stop()
    }
  }
  SettingsList(SettingsDomains.fleet, settings) { key, value ->
    SettingsDomains.fleet.apply(context, JSONObject().put(key, value))
  }
}

// ---------------------------------------------------------------------------
// Screensaver helpers
// ---------------------------------------------------------------------------

private fun screensaverSourcePath(s: ScreensaverConfig.Settings): String = when (s.source) {
  ScreensaverConfig.SOURCE_FOLDER -> s.folderPath ?: ""
  ScreensaverConfig.SOURCE_URL -> s.albumUrl ?: ""
  ScreensaverConfig.SOURCE_IMMICH -> s.immichUrl ?: ""
  ScreensaverConfig.SOURCE_SMB -> s.smbHost?.let { "$it/${s.smbShare ?: ""}" } ?: ""
  ScreensaverConfig.SOURCE_DAV -> s.davUrl ?: ""
  ScreensaverConfig.SOURCE_WEBURL -> s.webUrl ?: ""
  else -> ""
}

/**
 * Music Assistant connection and the Snapcast now-playing relay. Credentials and the raw player
 * identity are applied through the same registry as the phone remote. Back returns to settings.
 */
@Composable
internal fun MultiRoomScreen(onBack: () -> Unit) {
  SettingsScaffold(
      title = "Multi-room audio",
      subtitle = "Connect the native music player and Snapcast now-playing to Music Assistant.",
      onBack = onBack,
  ) { MultiRoomContent() }
}

/** Inlinable body of the Multi-room audio screen — rendered by [MultiRoomScreen] (standalone
 *  Activity host) and by the two-panel settings detail panel. */
@Composable
internal fun MultiRoomContent() {
  val context = LocalContext.current
  var baseline by remember(context) {
    mutableStateOf(synchronized(SettingsDomains.immortal) { ImmortalSettings.load(context) })
  }
  var enabled by remember(context) { mutableStateOf(baseline.multiRoomEnabled) }
  var host by remember(context) { mutableStateOf(baseline.snapcastHost) }
  var maPort by remember(context) { mutableStateOf(baseline.maPort.toString()) }
  // Saved tokens are never copied into the editable field; blank means keep the saved secret.
  var maToken by remember(context) { mutableStateOf("") }
  var maPlayer by remember(context) { mutableStateOf(baseline.maPlayerId) }
  var error by remember { mutableStateOf<String?>(null) }
  fun applyConnection(): Boolean {
    val port = maPort.toIntOrNull()
    val portSpec = SettingsDomains.immortal.specs.first { it.key == "maPort" }
        as com.immortal.launcher.settings.IntSpec<*>
    if (port == null || port !in portSpec.min..portSpec.max) {
      error = "Enter a port from ${portSpec.min} to ${portSpec.max}."
      return false
    }
    val values = MultiRoomConnectionValues(baseline.snapcastHost, baseline.maPort,
        baseline.maUsername, baseline.maPassword, baseline.maApiToken, baseline.maPlayerId)
    val changes = values.editedValues(host, port, baseline.maUsername, baseline.maPassword, maToken, maPlayer)
    // Use the registry's validators before writing any field in the native Apply batch.
    listOf("maApiToken", "maPlayerId").forEach { key ->
      val value = changes[key] as? String ?: return@forEach
      val spec = SettingsDomains.immortal.specs.first { it.key == key }
          as com.immortal.launcher.settings.StringSpec<*>
      if (!spec.applyWhen(value)) {
        error = spec.help
        return false
      }
    }
    val stored = synchronized(SettingsDomains.immortal) {
      if (changes.isNotEmpty()) SettingsDomains.immortal.apply(context, JSONObject(changes))
      ImmortalSettings.load(context)
    }
    baseline = stored
    enabled = stored.multiRoomEnabled
    host = stored.snapcastHost
    maPort = stored.maPort.toString()
    maToken = ""
    maPlayer = stored.maPlayerId
    error = null
    return true
  }

  // Chain the text fields so the keyboard's "Next" jumps to the following field instead of
  // closing. The current adapters use API tokens; legacy credentials remain stored only.
  val focusManager = LocalFocusManager.current
  val portFocus = remember { FocusRequester() }
  val tokenFocus = remember { FocusRequester() }
  val playerFocus = remember { FocusRequester() }

  Column {
      Card {
        Column(modifier = Modifier.padding(18.dp)) {
          Text("Setting it up", color = IosLabel, fontSize = Sizes.rowTitle)
          Text(
              "Connect Music Assistant for browsing and playback; use Snapcast for multi-room audio:",
              color = IosSecondary,
              fontSize = Sizes.rowSub,
              modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
          )
          SetupStep(
              "1",
              "Install and set up Music Assistant as a server on your home network. New to Music " +
                  "Assistant? Learn more at music-assistant.io")
          SetupStep(
              "2",
              "Install Snapcast from the Calder App Store, and point it at your Music Assistant " +
                  "server.")
          SetupStep(
              "3", "Turn on the toggle below, then apply your server address, API token and raw " +
                  "Music Assistant player ID.")
        }
      }
      Spacer(Modifier.size(26.dp))
      Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text("Show what the group is playing", color = IosLabel, fontSize = Sizes.rowTitle)
            Text(
                "Enables the native Music Assistant connection and shows the Snapcast group's track " +
                    "on the now-playing card.",
                color = IosSecondary,
                fontSize = Sizes.rowSub,
                modifier = Modifier.padding(top = 2.dp),
            )
          }
          ApprovedSettingsSwitch(
              checked = enabled,
              onCheckedChange = { on ->
                SettingsDomains.immortal.apply(context, JSONObject().put("multiRoomEnabled", on))
                enabled = ImmortalSettings.load(context).multiRoomEnabled
              },
              modifier = Modifier.semantics { contentDescription = "Show what the group is playing" },
          )
        }
        if (enabled) {
          Divider()
          Row(
              modifier = Modifier.fillMaxWidth().padding(18.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            OutlinedTextField(
                colors = iosTextFieldColors(),
                value = host,
                onValueChange = {
                  host = it
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { portFocus.requestFocus() }),
                label = { Text("Music Assistant / Snapcast server IP") },
                modifier = Modifier.weight(1f),
            )
            ActionPill("Apply", modifier = Modifier.padding(start = 12.dp)) {
                      applyConnection()
            }
          }
          // Music Assistant API port — 8095 by default; only change it if MA's web server moved.
          OutlinedTextField(
              colors = iosTextFieldColors(),
              value = maPort,
              onValueChange = {
                maPort = it.filter(Char::isDigit).take(5)
              },
              singleLine = true,
              keyboardOptions =
                  KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
              keyboardActions = KeyboardActions(onNext = { tokenFocus.requestFocus() }),
              label = { Text("Music Assistant port (default 8095)") },
              modifier =
                  Modifier.fillMaxWidth()
                      .padding(start = 18.dp, end = 18.dp, top = 4.dp)
                      .focusRequester(portFocus),
          )
          OutlinedTextField(
              colors = iosTextFieldColors(),
              value = maToken,
              onValueChange = { maToken = it },
              singleLine = true,
              visualTransformation = PasswordVisualTransformation(),
              keyboardOptions =
                  KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
              keyboardActions = KeyboardActions(onNext = { playerFocus.requestFocus() }),
              label = { Text("Music Assistant API token") },
              placeholder = {
                Text(if (baseline.maApiToken.isNotBlank()) "Saved token — leave blank to keep" else "API token")
              },
              modifier =
                  Modifier.fillMaxWidth()
                      .padding(start = 18.dp, end = 18.dp, top = 8.dp)
                      .focusRequester(tokenFocus),
          )
          OutlinedTextField(
              colors = iosTextFieldColors(),
              value = maPlayer,
              onValueChange = { maPlayer = it },
              singleLine = true,
              keyboardOptions =
                  KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done),
              keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
              label = { Text("Music Assistant player ID") },
              placeholder = { Text("Raw player_id; blank leaves selection unset") },
              modifier =
                  Modifier.fillMaxWidth()
                      .padding(start = 18.dp, end = 18.dp, top = 8.dp)
                      .focusRequester(playerFocus),
          )
          Text(
              "API tokens are required for this Music Assistant connection. Leave the token blank " +
                  "to keep it saved. Use the raw player_id, not a display name (up to 256 printable " +
                  "characters). YouTube and other music-provider credentials stay on your Music " +
                  "Assistant server.",
              color = IosSecondary,
              fontSize = Sizes.rowSub,
              modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 8.dp),
          )
          Row(
              modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            ActionPill("Test connection") { if (applyConnection()) MaControl.testLogin(context) }
            val auth = MultiRoomStatus.maAuth
            if (auth.isNotBlank()) {
              Text(
                  auth,
                  color = if (auth.endsWith("✓")) IosGreen else IosRed,
                  fontSize = Sizes.rowSub,
                  modifier = Modifier.padding(start = 14.dp),
              )
            }
          }
          // Live relay status — gives Apply visible feedback (Connecting… → Connected).
          error?.let { Text(it, color = IosRed, fontSize = Sizes.rowSub, modifier = Modifier.padding(18.dp)) }
          Text(
              MultiRoomStatus.text.ifBlank { "Starting…" },
              color = IosBlue,
              fontSize = Sizes.rowSub,
              modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 16.dp),
          )
        }
      }
      Text(
          "Join this Portal to the same Snapcast group as your other rooms, then point it " +
              "at your Music Assistant server. Audio is played by the Snapcast app; timing depends " +
              "on the server, network and speaker setup.",
          color = IosSecondary,
          fontSize = Sizes.rowSub,
          modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp),
      )
    }
}

/**
 * "Quick buttons" section: a centered overlay button cluster at the top of the screen (v1: an
 * app switcher). Enable it, and choose whether it shows only while the top bar is revealed
 * (default) or always.
 */
@Composable
private fun QuickButtonsSection() {
  val context = LocalContext.current
  var settings by remember(context) { mutableStateOf(QuickBarConfig.load(context)) }
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(context, lifecycleOwner) {
    var stopObserving: (() -> Unit)? = null
    fun stop() {
      stopObserving?.invoke()
      stopObserving = null
    }
    fun start() {
      if (stopObserving == null) {
        stopObserving = QuickBarConfig.observe(context) { settings = QuickBarConfig.load(context) }
        settings = QuickBarConfig.load(context)
      }
    }
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_RESUME -> start()
        Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> stop()
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) start()
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      stop()
    }
  }

  ApprovedSettingsSectionLabel("Quick buttons")
  // Rendered from the `quickbar` registry domain (the same specs the phone remote uses), so the
  // toggle and its gated "Always show" can't drift from the remote's version. Apply routes through
  // the domain so its onApplied (reconcile + overlay refresh) fires here too.
  SettingsList(SettingsDomains.quickbar, settings) { k, v ->
    SettingsDomains.quickbar.apply(context, JSONObject().put(k, v))
  }
  Text(
      "Needs the accessibility-based top-bar watch enabled during setup. The switcher shows your " +
          "recently used apps; tap one to switch.",
      color = IosSecondary,
      fontSize = Sizes.rowSub,
      modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp),
  )
}

/**
 * The Home Assistant (MQTT) subpage: publish this Portal to Home Assistant as auto-
 * discovered entities (presence, screen, battery, now-playing, controls) over a local
 * MQTT broker. Off by default; Back returns to the main settings page.
 */
@Composable
internal fun MqttScreen(onBack: () -> Unit) {
  SettingsScaffold(
      title = "MQTT publishing",
      subtitle =
          "Publish this Portal to Home Assistant as auto-discovered entities — presence, " +
              "screen, battery, now-playing, and controls — over your MQTT broker.",
      onBack = onBack,
  ) { MqttContent() }
}

/** Inlinable body of the Home Assistant (MQTT) screen — rendered by [MqttScreen] (standalone
 *  Activity host) and by the two-panel settings detail panel. */
@Composable
internal fun MqttContent() {
  val context = LocalContext.current
  var enabled by remember { mutableStateOf(MqttConfig.isEnabled(context)) }
  fun readConnection() = MqttConnectionValues(MqttConfig.host(context), MqttConfig.port(context),
      MqttConfig.username(context), MqttConfig.password(context), MqttConfig.useTls(context))
  var baseline by remember { mutableStateOf(synchronized(SettingsDomains.mqtt) { readConnection() }) }
  var host by remember { mutableStateOf(baseline.host) }
  var port by remember { mutableStateOf(baseline.port.toString()) }
  var user by remember { mutableStateOf(baseline.username) }
  var pass by remember { mutableStateOf(baseline.password) }
  var useTls by remember { mutableStateOf(baseline.useTls) }
  var error by remember { mutableStateOf<String?>(null) }
  // MqttStatus is a plain holder updated off the main thread, so poll it for live
  // "Connecting… → Connected" feedback (Compose won't recompose on its writes).
  var status by remember { mutableStateOf(MqttStatus.text) }
  LaunchedEffect(enabled) {
    while (enabled) {
      status = MqttStatus.text
      kotlinx.coroutines.delay(800)
    }
  }

  val focusManager = LocalFocusManager.current
  val portFocus = remember { FocusRequester() }
  val userFocus = remember { FocusRequester() }
  val passFocus = remember { FocusRequester() }

  fun apply(): Boolean {
    val chosenPort = port.toIntOrNull()
    val portSpec = SettingsDomains.mqtt.specs.first { it.key == "port" }
        as com.immortal.launcher.settings.IntSpec<*>
    if (chosenPort == null || chosenPort !in portSpec.min..portSpec.max) {
      error = "Enter a port from ${portSpec.min} to ${portSpec.max}."
      return false
    }
    val stored = synchronized(SettingsDomains.mqtt) {
      // Submit only deliberate edits; untouched fields may have newer companion values.
      val changes = baseline.editedValues(host, chosenPort, user, pass, useTls)
      if (changes.isNotEmpty()) {
        val body = JSONObject()
        changes.forEach { (key, value) -> body.put(key, value) }
        SettingsDomains.mqtt.apply(context, body)
      }
      readConnection()
    }
    // No-op Apply refreshes the form without writing or reconnecting the broker.
    baseline = stored
    host = stored.host
    port = stored.port.toString()
    user = stored.username
    pass = stored.password
    useTls = stored.useTls
    enabled = MqttConfig.isEnabled(context)
    error = null
    return true
  }

  Column {
      Card {
        Column(modifier = Modifier.padding(18.dp)) {
          Text("Setting it up", color = IosLabel, fontSize = Sizes.rowTitle)
          SetupStep(
              "1",
              "In Home Assistant, add the Mosquitto broker add-on (Settings → Add-ons) and the " +
                  "MQTT integration. New to MQTT? See home-assistant.io/integrations/mqtt")
          SetupStep(
              "2", "Turn on the toggle below and enter your broker's address (and login, if any).")
          SetupStep(
              "3",
              "This Portal appears automatically under Settings → Devices as a new MQTT device — " +
                  "no YAML needed.")
        }
      }
      Spacer(Modifier.size(26.dp))
      Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text("Publish to Home Assistant", color = IosLabel, fontSize = Sizes.rowTitle)
            Text(
                "Exposes this Portal's state and controls as Home Assistant entities over MQTT.",
                color = IosSecondary,
                fontSize = Sizes.rowSub,
                modifier = Modifier.padding(top = 2.dp),
            )
          }
          ApprovedSettingsSwitch(
              checked = enabled,
              onCheckedChange = { on ->
                SettingsDomains.mqtt.apply(context, JSONObject().put("enabled", on))
                enabled = MqttConfig.isEnabled(context)
              },
              modifier = Modifier.semantics { contentDescription = "Publish to Home Assistant" },
          )
        }
        if (enabled) {
          Divider()
          Row(
              modifier = Modifier.fillMaxWidth().padding(18.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            OutlinedTextField(
                colors = iosTextFieldColors(),
                value = host,
                onValueChange = {
                  host = it
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { portFocus.requestFocus() }),
                label = { Text("MQTT broker IP / host") },
                modifier = Modifier.weight(1f),
            )
            ActionPill("Apply", modifier = Modifier.padding(start = 12.dp)) { apply() }
          }
          OutlinedTextField(
              colors = iosTextFieldColors(),
              value = port,
              onValueChange = {
                port = it.filter { ch -> ch.isDigit() }
              },
              singleLine = true,
              keyboardOptions =
                  KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
              keyboardActions = KeyboardActions(onNext = { userFocus.requestFocus() }),
              label = { Text("Port (default 1883, or 8883 for TLS)") },
              modifier =
                  Modifier.fillMaxWidth()
                      .padding(start = 18.dp, end = 18.dp, top = 4.dp)
                      .focusRequester(portFocus),
          )
          OutlinedTextField(
              colors = iosTextFieldColors(),
              value = user,
              onValueChange = {
                user = it
              },
              singleLine = true,
              keyboardOptions =
                  KeyboardOptions(
                      capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Next),
              keyboardActions = KeyboardActions(onNext = { passFocus.requestFocus() }),
              label = { Text("Username (optional)") },
              modifier =
                  Modifier.fillMaxWidth()
                      .padding(start = 18.dp, end = 18.dp, top = 8.dp)
                      .focusRequester(userFocus),
          )
          OutlinedTextField(
              colors = iosTextFieldColors(),
              value = pass,
              onValueChange = {
                pass = it
              },
              singleLine = true,
              visualTransformation = PasswordVisualTransformation(),
              keyboardOptions =
                  KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
              keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus(); apply() }),
              label = { Text("Password (optional)") },
              modifier =
                  Modifier.fillMaxWidth()
                      .padding(start = 18.dp, end = 18.dp, top = 8.dp)
                      .focusRequester(passFocus),
          )
          Row(
              modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text("Use TLS / SSL", color = IosLabel, fontSize = Sizes.rowTitle)
              Text(
                  "Encrypt the connection. Default ports switch automatically; custom ports are kept.",
                  color = IosSecondary,
                  fontSize = Sizes.rowSub,
                  modifier = Modifier.padding(top = 2.dp),
              )
            }
            ApprovedSettingsSwitch(
                checked = useTls,
                onCheckedChange = { on ->
                  useTls = on
                  // The registry selects defaults using the current saved port.
                  apply()
                },
                modifier = Modifier.semantics { contentDescription = "Use TLS / SSL" },
            )
          }
          // Certificate validation is auto-managed (on by default, settings-reimagining Phase 5):
          // no on-device row. It stays applied and the phone remote can still turn it off for a
          // self-signed broker on a trusted network.
          // Live connection status — gives Apply visible feedback (Connecting… → Connected).
          error?.let { Text(it, color = IosRed, fontSize = Sizes.rowSub, modifier = Modifier.padding(18.dp)) }
          Text(
              status.ifBlank { "Starting…" },
              color = IosBlue,
              fontSize = Sizes.rowSub,
              modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 16.dp),
          )
        }
      }
      Text(
          "Connects to a broker on your LAN over plain MQTT or TLS. Your Portal shows up in " +
              "Home Assistant automatically as a device with presence, screen, battery, " +
              "now-playing and a few controls — no configuration.yaml editing.",
          color = IosSecondary,
          fontSize = Sizes.rowSub,
          modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp),
      )
    }
}

/** A curated set of cities for the world-clock picker (label → IANA tz id). */
private val WORLD_CLOCK_CITIES =
    listOf(
        "Cupertino" to "America/Los_Angeles",
        "Denver" to "America/Denver",
        "New York" to "America/New_York",
        "São Paulo" to "America/Sao_Paulo",
        "London" to "Europe/London",
        "Paris" to "Europe/Paris",
        "Berlin" to "Europe/Berlin",
        "Cape Town" to "Africa/Johannesburg",
        "Dubai" to "Asia/Dubai",
        "Mumbai" to "Asia/Kolkata",
        "Singapore" to "Asia/Singapore",
        "Tokyo" to "Asia/Tokyo",
        "Sydney" to "Australia/Sydney",
    )

/** The world-clock locations picker. Tapping a city toggles it; the widget shows the first four in
 * selection order. Back returns to the main settings page. */
@Composable
internal fun WorldClockScreen(onBack: () -> Unit) {
  SettingsScaffold(
      title = "World clock",
      subtitle =
          "Choose locations in display order. The widget fits complete clocks, up to four.",
      onBack = onBack,
  ) { WorldClockContent() }
}

/** Inlinable body of the World clock screen — rendered by [WorldClockScreen] (standalone Activity
 *  host) and by the two-panel settings detail panel. */
@Composable
internal fun WorldClockContent() {
  val context = LocalContext.current
  var selected by remember { mutableStateOf(ImmortalSettings.worldClockZones(context)) }

  Column {
      Card {
        LazyColumn(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp - 400).coerceAtLeast(400).dp)) {
        itemsIndexed(WORLD_CLOCK_CITIES, key = { _, city -> city.second }) { i, (label, zone) ->
          if (i > 0) Divider()
          val on = zone in selected
          val rank = selected.indexOf(zone)
          Row(
              modifier =
                  Modifier.fillMaxWidth()
                      .defaultMinSize(minHeight = Sizes.rowMinHeight)
                      .tvFocusableRow(focusFill = IosFocusTint) {
                        selected = if (on) selected - zone else selected + zone
                        ImmortalSettings.setWorldClockZones(context, selected)
                      }
                      .semantics {
                        role = Role.Switch
                        toggleableState = ToggleableState(on)
                      }
                      .padding(horizontal = 28.dp, vertical = 22.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(label, color = IosLabel, fontSize = Sizes.rowTitle)
              Text(
                  zone.replace('_', ' '),
                  color = IosSecondary,
                  fontSize = Sizes.rowSub,
                  modifier = Modifier.padding(top = 2.dp),
              )
            }
            if (on && rank in 0..3) {
              Text(
                  "#${rank + 1}",
                  color = IosBlue,
                  fontSize = Sizes.rowSub,
                  fontWeight = FontWeight.SemiBold,
                  modifier = Modifier.padding(end = 14.dp),
              )
            } else if (on) {
              Text("hidden", color = IosSecondary, fontSize = Sizes.rowSub, modifier = Modifier.padding(end = 14.dp))
            }
            ApprovedSettingsSwitch(on)
          }
        }
      }
      }
      Text(
          "Tip: resize the widget to see more clocks. Re-pick a city to move it to the end of the order.",
          color = IosSecondary,
          fontSize = Sizes.rowSub,
          modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp),
      )
    }
}

private data class BootAppOption(val pkg: String, val label: String, val icon: ImageBitmap)
/** Launchable apps except our launcher and retired features, for the boot-launch picker. */
private fun loadLaunchableApps(context: Context): List<BootAppOption> {
  val pm = context.packageManager
  val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
  return pm.queryIntentActivities(intent, 0)
      .filter {
        it.activityInfo.packageName != context.packageName &&
            it.activityInfo.packageName !in Curation.retiredPackages
      }
      .mapNotNull { ri ->
        runCatching {
              BootAppOption(
                  pkg = ri.activityInfo.packageName,
                  label = ri.loadLabel(pm).toString(),
                  icon = ri.loadIcon(pm).toBitmap(96, 96).asImageBitmap())
            }
            .getOrNull()
      }
      .distinctBy { it.pkg }
      .sortedBy { it.label.lowercase(Locale.getDefault()) }
}

/**
 * The "Device health" subpage: a live status of every special permission provisioning
 * grants (screen-off device admin, notification access, install, overlay, secure settings),
 * with — for anything that's missing — what's degraded and how to fix it. A diagnostic to
 * point a struggling user at. Replaces the old destructive "turn off device admin" button;
 * the uninstall path it served is kept as a clearly-warned advanced action at the bottom.
 */
@Composable
internal fun DeviceHealthScreen(onBack: () -> Unit) {
  SettingsScaffold(
      title = "Device health",
      subtitle = "Check the permissions and control access your Portal needs.",
      onBack = onBack,
  ) { DeviceHealthContent() }
}

/** Inlinable body of the Device health screen — rendered by [DeviceHealthScreen] (standalone
 *  Activity host) and by the two-panel settings detail panel. */
@Composable
internal fun DeviceHealthContent() {
  val context = LocalContext.current.applicationContext
  val advanced = com.immortal.launcher.ui.theme.LocalAdvancedSettings.current
  val owner = LocalLifecycleOwner.current
  var refresh by remember { mutableStateOf(0) }
  var confirmingDisable by remember(advanced) { mutableStateOf(false) }
  var disablingAdmin by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  var recheckRequested by remember { mutableStateOf(false) }
  var reconnectingControls by remember { mutableStateOf(false) }
  val health by produceState(DeviceHealthStatus(), context, owner, refresh, advanced) {
    owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      confirmingDisable = false
      value = value.beginCheck()
      value = value.received(loadDeviceHealth { DevicePermissions.snapshot(context) })
      recheckRequested = false
      // Query on request/resume, cancel on departure, and retain completed rows while checking.
      awaitCancellation()
    }
  }
  val checking = health.checking || recheckRequested || reconnectingControls
  fun recheck() {
    // Set synchronously so burst input cannot enqueue parallel permission queries.
    if (health.checking || recheckRequested || reconnectingControls) return
    recheckRequested = true
    confirmingDisable = false
    refresh++
  }
  fun reconnectControls() {
    // Admit once from a fresh completed result; never repair while checking stale rows.
    if (reconnectingControls || recheckRequested || !health.canReconnectControls) return
    reconnectingControls = true
    confirmingDisable = false
    scope.launch {
      try {
        // Revalidate at the action boundary: permission or connection may have changed.
        val requested = withContext(Dispatchers.IO) {
          runCatching {
            if (SettingsGuard.canWriteSecureSettings(context) && !RemoteInput.available()) {
              SettingsGuard.ensureBarWatchConnected(context)
              true
            } else false
          }.getOrDefault(false)
        }
        // The existing repair re-adds the service after 2 seconds. One delayed check,
        // cancelled when this page leaves composition, avoids a polling loop.
        if (requested) kotlinx.coroutines.delay(2400)
        recheckRequested = true
        refresh++
      } finally {
        reconnectingControls = false
      }
    }
  }
  val snapshot = health.snapshot
  if (snapshot == null) {
    Card {
      Column(Modifier.padding(28.dp).semantics {
        contentDescription = if (checking) "Checking device health" else "Device health check unavailable"
      }) {
        Text(if (checking) "Checking device health…" else "Could not check device health.",
            color = IosSecondary, fontSize = Sizes.rowTitle)
        if (!checking) ApprovedSettingsTextButton("Try again") { recheck() }
      }
    }
    return
  }
  val checks = snapshot.checks
  val issues = checks.count { !it.granted }
  val adminActive = snapshot.adminActive

  Column {
      // Summary banner — green when healthy, amber when something needs attention.
      // Light semantic tints (dark green/amber would clash with the light background).
      Surface(
          color = when {
            checking -> IosCard
            health.failed || issues > 0 -> Color(0xFFFDF1DD)
            else -> Color(0xFFE6F7EA)
          },
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = when {
              checking -> "Checking device health"
              health.failed -> "Device health check unavailable"
              else -> "Device health checked"
            }
          },
      ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
          Text(
              when {
                reconnectingControls -> "Reconnecting controls…"
                checking -> "Checking device health…"
                health.failed -> "Could not recheck device health. Last results are shown."
                issues == 0 -> "✓  Device checks ready."
                else -> "!  $issues device check${if (issues == 1) " needs" else "s need"} attention."
              },
              color = when {
                checking -> IosSecondary
                health.failed || issues > 0 -> Color(0xFFB26A00)
                else -> Color(0xFF1E7B34)
              },
              fontSize = Sizes.rowTitle,
              modifier = Modifier.weight(1f),
          )
          ActionPill(if (checking) "Checking…" else if (health.failed) "Try again" else "Check again",
              enabled = !checking, modifier = Modifier.semantics { if (checking) disabled() }) { recheck() }
        }
      }
      Spacer(Modifier.size(20.dp))

      Card {
        checks.forEachIndexed { i, c ->
          if (i > 0) Divider()
          HealthRow(c, advanced, reconnectingControls,
              repairEnabled = !checking && !health.failed, onReconnect = ::reconnectControls)
        }
      }

      if (issues > 0) {
        Spacer(Modifier.size(22.dp))
        SectionLabel("How to fix")
        Text(
            if (checks.filter { !it.granted }.all { it.repair == DevicePermissions.Repair.RECONNECT_CONTROLS })
              "Use Reconnect controls above. This page will check the connection after reconnecting."
            else
              "Run Calder setup from a connected computer to restore missing system permissions, " +
                  "then choose Check again. Any available Reconnect controls action can be used here.",
            color = IosSecondary,
            fontSize = Sizes.rowSub,
            lineHeight = 20.sp,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp),
        )
      }

      // Advanced: deactivating the screen-off admin is the clean path to uninstall Immortal
      // (the shell can't force-remove a non-test admin). Tucked away and clearly warned — it
      // turns off screen-off until re-provisioned.
      if (advanced && adminActive) {
        Spacer(Modifier.size(28.dp))
        Text(
            "Prepare to uninstall Calder",
            color = IosSecondary,
            fontSize = Sizes.rowSub,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Text(
            "Remove Calder’s screen-lock permission before uninstalling. This stops automatic " +
                "and remote screen-off until Calder setup restores the permission.",
            color = IosSecondary,
            fontSize = Sizes.rowSub,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
        )
        Text(
            if (disablingAdmin) "Removing screen-lock permission…"
            else if (confirmingDisable) "Tap again to confirm — automatic screen-off will stop"
            else "Remove screen-lock permission",
            color = if (checking || health.failed) IosSecondary else IosRed,
            fontSize = Sizes.actionButton,
            fontWeight = if (confirmingDisable) FontWeight.SemiBold else FontWeight.Normal,
            modifier =
                Modifier.tvFocusable(RoundedCornerShape(8.dp), enabled = !disablingAdmin && !checking && !health.failed) {
                      // Reject burst input immediately, before the disabled row can recompose.
                      if (!advanced || disablingAdmin || recheckRequested || reconnectingControls || !health.canRemoveAdmin) return@tvFocusable
                      // Two-tap arm: a single stray remote press shouldn't tear down device admin
                      // (which silently breaks screensaver sleep + the HA screen-off control).
                      if (confirmingDisable) {
                        confirmingDisable = false
                        disablingAdmin = true
                        scope.launch {
                          try {
                            withContext(Dispatchers.IO) { ScreenControl.deactivateAdmin(context) }
                            refresh++
                          } finally {
                            disablingAdmin = false
                          }
                        }
                      } else {
                        confirmingDisable = true
                      }
                    }
                    .padding(start = 4.dp, top = 2.dp, bottom = 4.dp),
        )
      }
    }
}

@Composable
private fun HealthRow(c: DevicePermissions.Check, advanced: Boolean,
    reconnectingControls: Boolean, repairEnabled: Boolean, onReconnect: () -> Unit) {
  Row(
      modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 14.dp),
      verticalAlignment = Alignment.Top,
  ) {
    Text(
        if (c.granted) "✓" else "!",
        color = if (c.granted) IosGreen else Color(0xFFB26A00),
        fontSize = Sizes.rowTitle,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(end = 14.dp, top = 1.dp),
    )
    Column(modifier = Modifier.weight(1f)) {
      Text(c.title, color = IosLabel, fontSize = Sizes.rowTitle)
      Text(
          c.enables,
          color = IosSecondary,
          fontSize = Sizes.rowSub,
          lineHeight = 28.sp,
          modifier = Modifier.padding(top = 2.dp),
      )
      if (!c.granted) {
        Text(
            "Without it: ${c.degraded}",
            color = Color(0xFFB26A00),
            fontSize = Sizes.rowSub,
            lineHeight = 28.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            c.fix,
            color = IosSecondary,
            fontSize = Sizes.rowSub,
            lineHeight = 28.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (c.repair == DevicePermissions.Repair.RECONNECT_CONTROLS) {
          Spacer(Modifier.height(10.dp))
          ActionPill(if (reconnectingControls) "Reconnecting…" else "Reconnect controls",
              enabled = repairEnabled, onClick = onReconnect)
        }
        if (advanced && c.technicalFix.isNotBlank()) {
          Text("Setup command: adb shell ${c.technicalFix}", color = IosSecondary,
              fontSize = Sizes.rowSub, lineHeight = 28.sp,
              fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
              modifier = Modifier.padding(top = 6.dp))
        }
      }
    }
  }
}

/**
 * The "Start on boot" subpage: pick which installed apps Immortal relaunches after a
 * reboot — the same per-device list provisioning seeds (so e.g. the Music Assistant /
 * Sendspin player, which has no boot receiver, comes back on its own). Toggling a row
 * writes the list straight to the file [BootLaunch] reads on boot. Back returns to the
 * main settings page (handled by the parent via [onBack]).
 */
@Composable
internal fun BootAppsScreen(onBack: () -> Unit) {
  SettingsScaffold(
      title = "Start on boot",
      subtitle = "Pick which installed apps Calder relaunches after a reboot.",
      onBack = onBack,
  ) { BootAppsContent() }
}

/** Inlinable body of the Start-on-boot screen — rendered by [BootAppsScreen] (standalone Activity
 *  host) and by the two-panel settings detail panel. Owns the boot-launch selection set (the
 *  Activity used to hold it) so it's self-contained wherever it's rendered. */
@Composable
internal fun BootAppsContent() {
  val context = LocalContext.current.applicationContext
  val owner = LocalLifecycleOwner.current
  val pageActive = LocalApprovedLauncherPageActive.current
  var startupSettings by remember(context) { mutableStateOf(ImmortalSettings.load(context)) }
  OnResume { startupSettings = ImmortalSettings.load(context) }
  DisposableEffect(context, pageActive) {
    val stop = if (pageActive) ImmortalSettings.observeSnapshot(context) {
      startupSettings = ImmortalSettings.load(context)
    } else null
    onDispose { stop?.invoke() }
  }
  var apps by remember(context) { mutableStateOf<List<BootAppOption>?>(null) }
  var selected by remember(context) { mutableStateOf<Set<String>>(emptySet()) }
  var loading by remember(context) { mutableStateOf(true) }
  var applying by remember(context) { mutableStateOf(false) }
  var problem by remember(context) { mutableStateOf<String?>(null) }
  var refresh by remember(context) { mutableStateOf(0) }
  val scope = rememberCoroutineScope()
  val writeLock = remember(context) { Mutex() }
  LaunchedEffect(context, owner, pageActive, refresh) {
    if (!pageActive) return@LaunchedEffect
    owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      loading = true
      try {
        val loaded = withContext(Dispatchers.IO) {
          writeLock.withLock {
            val saved = BootLaunch.readPackages(context).toSet()
            loadLaunchableApps(context) to saved
          }
        }
        apps = loaded.first
        selected = loaded.second
        problem = null
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        problem = "Could not load startup apps."
      } finally {
        loading = false
      }
      awaitCancellation()
    }
  }
  val canEdit = apps != null && !loading && !applying && problem == null
  val onSelectionChange: (String, Boolean) -> Unit = { pkg, removeOnly ->
    if (canEdit && !applying && !loading && problem == null) {
      applying = true
      scope.launch {
        try {
          val confirmed = withContext(Dispatchers.IO) {
            writeLock.withLock {
              val saved = BootLaunch.readPackages(context).toSet()
              // Removing an unavailable entry must never re-add it after another writer removed it.
              val chosen = if (removeOnly || pkg in saved) saved - pkg else saved + pkg
              if (chosen != saved) check(BootLaunch.setPackages(context, chosen.toList()))
              BootLaunch.readPackages(context).toSet().also { check(it == chosen) }
            }
          }
          selected = confirmed
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          // Keep the last confirmed values and require a fresh read before editing again.
          problem = "Could not save your choice."
        } finally {
          applying = false
        }
      }
    }
  }
  val listedPackages = remember(apps) { apps.orEmpty().map { it.pkg }.toSet() }
  val unlisted = remember(selected, listedPackages) { selected.filterNot { it in listedPackages } }
  Column(Modifier.semantics {
    contentDescription = when {
      applying -> "Saving startup apps"
      loading -> "Startup apps loading"
      problem != null -> "Startup apps unavailable"
      else -> "Startup apps ready"
    }
  }) {
    val domain = SettingsDomains.immortal
    SettingsRows(domain, startupSettings,
        exclude = domain.specs.map { it.key }.toSet() - "stayInStartupApp") { key, value ->
      domain.apply(context, JSONObject().put(key, value))
      startupSettings = ImmortalSettings.load(context)
    }
    problem?.let { message ->
      FooterNote(message)
      if (apps != null) FooterNote("Last confirmed choices are shown.")
      if (!loading && !applying) ApprovedSettingsTextButton("Try again") {
        loading = true
        refresh++
      }
    }
    if (applying) FooterNote("Saving your choice…")
    if (loading && apps != null) FooterNote("Refreshing startup apps…")
    Card {
      val list = apps
      if (list == null) {
        Text(if (loading) "Loading apps…" else "Try again to read your saved choices.",
            color = IosSecondary, fontSize = Sizes.rowSub, modifier = Modifier.padding(18.dp))
      } else if (list.isEmpty()) {
        Text("No other apps installed yet.", color = IosSecondary,
            fontSize = Sizes.rowSub, modifier = Modifier.padding(18.dp))
      } else {
        LazyColumn(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp - 400).coerceAtLeast(400).dp)) {
          itemsIndexed(list, key = { _, app -> app.pkg }) { i, app ->
            if (i > 0) Divider()
            Row(
                modifier = Modifier.fillMaxWidth()
                    .defaultMinSize(minHeight = Sizes.rowMinHeight)
                    .tvFocusableRow(focusFill = IosFocusTint, enabled = canEdit) { onSelectionChange(app.pkg, false) }
                    .semantics {
                      role = Role.Switch
                      toggleableState = ToggleableState(app.pkg in selected)
                    }
                    .padding(horizontal = 28.dp, vertical = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
              Image(bitmap = app.icon, contentDescription = null, modifier = Modifier.size(42.dp))
              Text(app.label, color = IosLabel, fontSize = Sizes.rowTitle,
                  modifier = Modifier.weight(1f).padding(start = 14.dp))
              ApprovedSettingsSwitch(app.pkg in selected, enabled = canEdit)
            }
          }
        }
      }
    }
    if (apps != null && unlisted.isNotEmpty()) {
      SectionLabel("Saved apps not listed")
      FooterNote("These startup choices are saved, but their apps aren't shown above. " +
          "Remove a choice to stop trying to start it after a reboot.")
      Card {
        LazyColumn(Modifier.heightIn(max = 280.dp)) {
          itemsIndexed(unlisted, key = { _, pkg -> pkg }) { index, pkg ->
            if (index > 0) Divider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp)) {
              Text(pkg, color = IosLabel, fontSize = Sizes.rowSub,
                  maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                  modifier = Modifier.weight(1f))
              ActionPill("Remove", enabled = canEdit,
                  modifier = Modifier.semantics { contentDescription = "Remove startup entry $pkg" }) {
                onSelectionChange(pkg, true)
              }
            }
          }
        }
      }
    }
    Text("These apps relaunch automatically after a reboot — handy for players like Music " +
        "Assistant that don't restart themselves.", color = IosSecondary,
        fontSize = Sizes.rowSub, modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp))
  }
}

private val WallpaperChoices: List<Pair<String, String>> =
    listOf(WallpaperConfig.DUSK_AURORA to "Dusk Aurora", WallpaperConfig.DARK to "Dark") +
        WallpaperConfig.GRADIENTS.map { (id, _) ->
          id to id.removePrefix("g_").replaceFirstChar { it.uppercase() }
        } + WallpaperConfig.PHOTOS +
        listOf(WallpaperConfig.SCREENSAVER to "Screensaver", WallpaperConfig.SKY to "Sky",
            WallpaperConfig.STARFIELD to "Star field")

@Composable
private fun WallpaperSection() {
  val application = LocalContext.current.applicationContext
  var configuration by remember(application) { mutableStateOf(WallpaperConfig.load(application)) }
  OnResume { configuration = WallpaperConfig.load(application) }
  val pageActive = LocalApprovedLauncherPageActive.current
  var previewRevision by remember { mutableStateOf(0) }
  var previewsLoading by remember { mutableStateOf(true) }
  val thumbnails by produceState<Map<String, android.graphics.Bitmap>?>(null, application, pageActive, previewRevision) {
    if (!pageActive) return@produceState
    if (value?.size == WallpaperConfig.PHOTOS.size) {
      previewsLoading = false
      return@produceState
    }
    previewsLoading = true
    try {
      value = loadWallpaperPreviews(WallpaperConfig.PHOTOS.map { it.first },
          decode = { decodeWallpaperPreview(application, it) }, discard = { it.recycle() },
          existing = value.orEmpty())
    } finally {
      if (currentCoroutineContext().isActive) previewsLoading = false
    }
  }
  val selectedLabel = WallpaperChoices.firstOrNull { it.first == configuration.mode }?.second ?: "Custom"
  Card {
    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Background", color = IosLabel, fontSize = Sizes.rowTitle, modifier = Modifier.weight(1f))
        Text(selectedLabel, color = IosSecondary, fontSize = Sizes.rowSub,
            modifier = Modifier.semantics { contentDescription = "Current background: $selectedLabel" })
      }
      Text("Choose a color, photo, or a background that changes through the day.",
          color = IosSecondary, fontSize = Sizes.rowSub)
      BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = (maxWidth / 180.dp).toInt().coerceIn(1, 6)
        Column(Modifier.fillMaxWidth().selectableGroup().semantics {
          contentDescription = when {
            previewsLoading -> "Wallpaper previews loading"
            thumbnails?.size == WallpaperConfig.PHOTOS.size -> "Wallpaper previews ready"
            else -> "Wallpaper previews unavailable"
          }
        }, verticalArrangement = Arrangement.spacedBy(14.dp)) {
          WallpaperChoices.chunked(columns).forEach { choices ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              choices.forEach { (id, label) ->
                val isPhoto = id.startsWith(WallpaperConfig.PHOTO_PREFIX)
                val thumb = thumbnails?.get(id)
                WallpaperSwatch(label, selected = configuration.mode == id,
                    enabled = !isPhoto || thumb != null, modifier = Modifier.weight(1f), onClick = {
                      WallpaperConfig.setMode(application, id)
                      configuration = WallpaperConfig.load(application)
                    }) { WallpaperChoicePreview(id, thumb) }
              }
              repeat(columns - choices.size) { Spacer(Modifier.weight(1f)) }
            }
          }
        }
      }
      if (previewsLoading) {
        Text("Loading photo previews…", color = IosSecondary, fontSize = Sizes.rowSub)
      } else if (thumbnails?.size != WallpaperConfig.PHOTOS.size) {
        Text("Some photo previews could not be loaded.", color = IosSecondary, fontSize = Sizes.rowSub)
        ApprovedSettingsTextButton("Try again") {
          if (!previewsLoading) {
            previewsLoading = true
            previewRevision++
          }
        }
      }
      Divider()
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text("Film grain", color = IosLabel, fontSize = Sizes.rowTitle)
          Text("Adds a subtle grain texture over the wallpaper.", color = IosSecondary,
              fontSize = Sizes.rowSub, modifier = Modifier.padding(top = 2.dp))
        }
        ApprovedSettingsSwitch(checked = configuration.grain, onCheckedChange = {
          WallpaperConfig.setGrain(application, it)
          configuration = WallpaperConfig.load(application)
        }, modifier = Modifier.semantics { contentDescription = "Film grain" })
      }
    }
  }
}

@Composable
private fun WallpaperChoicePreview(id: String, thumbnail: android.graphics.Bitmap?) {
  val colors = when (id) {
    WallpaperConfig.DUSK_AURORA -> listOf(0xFF050810, 0xFF12234A, 0xFF27436E, 0xFFC98A5E)
    WallpaperConfig.SCREENSAVER -> listOf(0xFF2C3E50, 0xFF4CA1AF)
    WallpaperConfig.SKY -> listOf(0xFF3A7BD5, 0xFF8FD3F4)
    WallpaperConfig.STARFIELD -> listOf(0xFF05060F, 0xFF0C1430)
    else -> WallpaperConfig.gradientColors(id)
  }
  when {
    thumbnail != null -> {
      val image = remember(thumbnail) { thumbnail.asImageBitmap() }
      Image(bitmap = image, contentDescription = null, modifier = Modifier.fillMaxSize())
    }
    colors != null -> Spacer(Modifier.fillMaxSize().background(Brush.verticalGradient(colors.map { Color(it) })))
    id == WallpaperConfig.DARK -> Spacer(Modifier.fillMaxSize().background(com.immortal.launcher.ui.theme.ApprovedDarkPalette.background))
    else -> Spacer(Modifier.fillMaxSize().background(Color(0xFF333740)))
  }
}

@Composable
private fun WallpaperSwatch(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    preview: @Composable () -> Unit,
) {
  Column(horizontalAlignment = Alignment.CenterHorizontally,
      modifier = modifier.tvFocusable(RoundedCornerShape(16.dp), enabled = enabled) { onClick() }
          .semantics {
            role = Role.RadioButton
            this.selected = selected
            contentDescription = "Wallpaper $label"
          }) {
    Box(Modifier.fillMaxWidth().aspectRatio(240f / 145f).clip(RoundedCornerShape(16.dp))
        .border(if (selected) 3.dp else 1.dp, if (selected) IosBlue else IosDivider, RoundedCornerShape(16.dp))) {
      preview()
    }
    Text(label, color = (if (selected) IosBlue else IosSecondary).copy(alpha = if (enabled) 1f else .55f),
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        fontSize = Sizes.rowSub, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
  }
}

/**
 * A thin translucent scroll indicator on the container's right edge, matching the vertical
 * scrollbar shown in the approved settings mock. Only drawn when the content overflows; the
 * thumb height and position track [state] proportionally. Purely decorative — no drag.
 */
private fun Modifier.verticalScrollbar(
    state: ScrollState,
    width: Dp = 4.dp,
    color: Color = Color(0x33000000),
): Modifier = drawWithContent {
  drawContent()
  val max = state.maxValue
  if (max <= 0) return@drawWithContent // no overflow → no bar
  val viewport = size.height
  // Thumb proportional to visible/total; total content = viewport + maxValue.
  val total = viewport + max
  val thumbH = (viewport / total * viewport).coerceAtLeast(24.dp.toPx())
  val track = viewport - thumbH
  val thumbY = if (max > 0) (state.value.toFloat() / max) * track else 0f
  val w = width.toPx()
  drawRoundRect(
      color = color,
      topLeft = Offset(size.width - w - 2.dp.toPx(), thumbY),
      size = Size(w, thumbH),
      cornerRadius = CornerRadius(w / 2, w / 2),
  )
}
