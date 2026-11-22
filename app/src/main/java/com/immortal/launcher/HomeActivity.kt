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
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.drop

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
  // One instance, read inside LauncherScreen: setContent does not read the tab, so a switch recomposes only its readers.
  private val launcherTabReader: () -> ApprovedLauncherTab = { launcherTabState }

  /** Media keys drive the YouTube player (it has no MediaSession); see [MusicMediaKeys]. */
  override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
      MusicMediaKeys.dispatch(event) || super.dispatchKeyEvent(event)

  /** Opens the photo frame as this app's Activity at idle, so "Hey Jarvis" keeps working ([IdleFramePolicy]). */
  private val idleFrame = IdleFrameTimer(this)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Cold start: the RenderThread's one-time EGL setup overlaps the first composition.
    RenderThreadWarmup.start()
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
            selectedTab = launcherTabReader,
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
                    cn.className == HaDashboardActivity::class.java.name) {
                  startActivity(Intent(this@HomeActivity, HaDashboardActivity::class.java))
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
    idleFrame.resumed()
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
    idleFrame.paused()
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

  // Every touch and key that reaches Home (Activity calls this from its dispatch); restarts the idle frame's count.
  override fun onUserInteraction() {
    super.onUserInteraction()
    idleFrame.interacted()
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
