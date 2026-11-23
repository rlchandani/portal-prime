/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * Process-wide hooks. As the home app our process is effectively persistent, so a
 * runtime-registered receiver here behaves like a manifest one without the
 * background-broadcast restrictions. DREAMING_STOPPED is a presence observation only;
 * PhotoDreamService's own stop callback owns automatic photo-frame recovery.
 */
class ImmortalApp : Application() {
  override fun attachBaseContext(base: Context) {
    super.attachBaseContext(base)
    // Earliest app hook (before content providers and onCreate): start warming the page cache
    // with our compiled code so a cold boot's class linking doesn't fault it in 4 KB at a time.
    StartupPrefetch.start(base)
    // Start the SharedPreferences loads that setup and Home's first frame read, in parallel, so
    // the main thread doesn't wait on each file in turn (StartupPrefsWarmup).
    StartupPrefsWarmup.start(base)
  }

  override fun onCreate() {
    super.onCreate()
    // Scratch-build diagnostics only (always off in release/performance); installed before
    // CrashGuard so its own startup I/O is observed too.
    StrictModeDiagnostics.installIfEnabled()
    // Arm first, before any other init: as the home app, an uncaught crash makes Android drop our
    // default-home role (→ "Select Home app" chooser). This relaunches us straight back so the
    // user never sees it. Installed up front so a crash during the rest of onCreate is covered too.
    CrashGuard.install(this)
    installBeforeFirstFrame()
    // Everything else waits for the first frame (or DeferredStartup's fallback when no activity
    // starts). Entry points that can run first call DeferredStartup.ensureStarted().
    DeferredStartup.arm(this, deferredSteps())
  }

  /**
   * What a first frame, a dream, a receiver or a Fleet request may read before the deferred
   * steps run: Fleet routes (map puts), guest mode, dream/presence state, activity-lifecycle
   * observers that must see Home's own callbacks, the status bar, and every alarm re-arm.
   */
  private fun installBeforeFirstFrame() {
    // Household features: phone-provisioned key route (the signed bus itself starts deferred).
    RemoteFeatureRoutes.registerHouseholdKey()
    // Control Center on the paired phone: /remote/controlcenter* (the HA token stays here).
    CcRemoteRoute.install()
    MusicShare.install()
    YtRemoteRoute.install()
    OtaRoutes.install()
    // Re-arms the next picture-routine alarm.
    RoutinesFeature.install(this)
    ShareFeature.install(this)
    // Loads guest mode: Home and the dream must not show household content while it is on.
    GuestFeature.install(this)
    // Resumed-activity count: lets the post-replace Home restore see that Home really came back.
    OwnActivityResumes.install(this)
    // Weak window tracking is dormant until an operator starts a bounded Fleet capture.
    FramePerformanceCapture.init(this)
    // Compact Portals only: covers the right turn the platform's rotation ignores (sensor dormant elsewhere).
    RotationFollower.install(this)
    // Restore bridge state persisted by HomeActivity.launchStockHome. If Android killed our
    // process while the stock Portal home was in the foreground and a new process is now
    // starting to handle a PhotoDreamService instance, DreamPolicy would otherwise see
    // default (zeroed) values and classify the dream-stop as REDREAM — relaunching the photo
    // frame over the in-progress call. Must run before the DREAMING_STOPPED receiver below.
    DreamPolicy.initFromPrefs(this)
    // The shared presence source of truth: owns DREAMING_STARTED / SCREEN_OFF / POWER and
    // exposes one PresenceState for the screensaver (in-process) and the Snapcast companion
    // (broadcast). DREAMING_STOPPED stays here because it also drives the frame relaunch, and
    // we feed its verdict into the hub from DreamPolicy.
    PresenceHub.init(this)
    // Who's there?: opt-in on-device recognition. Off until an adult enables it; only listens to
    // presence here, the camera is opened later and only from a resumed Home.
    WhosThere.install(this)
    // "Hey Jarvis" on Hearthpane screens: Jarvis can't hear behind us on Android 10, so we listen for it — only
    // while one of our screens is resumed and nothing else needs the mic or camera. Idle until then.
    // Its activity counter must see Home's own start.
    com.immortal.launcher.jarvis.wake.HearthpaneWake.install(this)
    val receiver =
        object : BroadcastReceiver() {
          override fun onReceive(c: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_DREAMING_STOPPED) {
              DreamPolicy.onDreamingStopped(c)
            }
          }
        }
    registerReceiver(receiver, IntentFilter(Intent.ACTION_DREAMING_STOPPED))

    // Apply the user's status-bar choice (default hidden — the wall-frame look).
    SettingsGuard.applyStatusBar(this)

    // Arm the overnight screen-off window (and apply it if we're inside it now).
    SleepScheduler.applyOvernightNow(this)

    // Arm the ambient chime / spoken-time / golden-hour alarms per the user's config.
    ChimeScheduler.reschedule(this)

    // Arm the next sunrise wake-light alarm per the user's config.
    SunriseScheduler.reschedule(this)

    // Arm the next wake-up alarm per the user's config.
    AlarmScheduler.reschedule(this, cancelSnooze = false)

    // Restore kitchen timer ownership, including a due timer waking this cold process.
    TimerConfig.rearmAll(this, dropExpired = false)
  }

  /** In the original onCreate order; each runs once, one main-thread message apiece. */
  private fun deferredSteps(): List<StartupStep> = listOf(
      // Music surfaces control Android media sessions through one shared transport.
      StartupStep("MediaSessionMusicTransport") { MediaSessionMusicTransport.install() },
      // Signed Portal↔Portal bus, then the household features on it.
      StartupStep("HouseholdBus") { HouseholdBus.start(this) },
      StartupStep("JarvisMemory") { com.immortal.launcher.memory.JarvisMemory.warm(this) },
      StartupStep("HouseholdList") { HouseholdListFeature.install(this) },
      StartupStep("DinnerCall") { DinnerCallFeature.install(this) },
      // Hearthpane Calling: Portal↔Portal video calls and drop-ins over the signed household bus.
      StartupStep("Calling") { Calling.install(this) },
      // Retired cross-home calling: deletes its leftover vault, roster and Keystore keys once.
      StartupStep("RetiredHomeLinkCleanup") { RetiredHomeLinkCleanup.install(this) },
      StartupStep("SharedTimers") { SharedTimersFeature.install(this) },
      StartupStep("Chores") { ChoresFeature.install(this) },
      StartupStep("Doorbell") { DoorbellFeature.install(this) },
      // Home Control Center: background alert subscription for Portal-wide banners (if enabled).
      StartupStep("ControlCenter") { ControlCenter.install(this) },
      // Leak/smoke/CO/gas takeovers seen by another Portal show here too (deduped per alert).
      StartupStep("CcAlertRelay") { CcAlertRelay.install(this) },
      StartupStep("MusicHandoff") { MusicHandoff.install(this) },
      // Music round: household "Recently played", the quiet-hours music cap and the sleep timer.
      StartupStep("MusicHistory") { MusicHistoryFeature.install(this) },
      StartupStep("MusicNightGuard") { MusicNightGuard.install(this) },
      StartupStep("MusicSleepDriver") { MusicSleepDriver.install(this) },
      // Household settings sync: household-scoped settings stay the same on every Portal.
      StartupStep("SettingsSync") { SettingsSync.install(this) },
      // Package events invalidate read metadata without scans or work on this main thread.
      StartupStep("InstalledPackageVersions") { InstalledPackageVersions.register(this) },
      // One memory sample every 15 minutes on its own background thread; flags a restart, never acts.
      StartupStep("MemoryHealth") { MemoryHealth.start() },
      // Second presence source: Meta's own camera CV, read from its logcat heartbeat. Fills
      // the hub's pinned-frame blind spot (always-on frames never sleep, so the dream/sleep
      // proxy can't see the room empty). No-op without the ADB-granted READ_LOGS.
      StartupStep("MetaPresenceReader") { MetaPresenceReader(this).start() },
      // "Music follows you": needs the bus, the music handoff and the presence hub above.
      StartupStep("MusicFollowMe") { MusicFollowMe.install(this) },
      // People in rooms on the Control Center map: presence, recognitions (policy-gated) for the plan.
      StartupStep("RoomSignals") { RoomSignals.install(this) },
      // Personal reminders: household replica synced like chores; due ones chime only for their person.
      StartupStep("Reminders") { RemindersFeature.install(this) },
      // Read the device's native media session (whatever's playing) into NowPlayingHub
      // for the screensaver card + header mini-player. Dormant until our notification
      // listener is enabled — done at provisioning (`cmd notification allow_listener`);
      // the reader attaches the moment that listener binds.
      StartupStep("MediaSessionReader") { MediaSessionReader.init(this) },
      // Keep our accessibility service enabled — baseline launcher infrastructure now (it backs
      // the Calls→stock-home bridge, the phone remote, and the quick-button cluster). No-op
      // without WRITE_SECURE_SETTINGS, so it effectively turns on only once provisioned.
      StartupStep("ReconcileBarWatch") { SettingsGuard.reconcileBarWatch(this) },
      // Bring up the WiFi fleet agent if provisioning enabled it (no-op otherwise).
      StartupStep("FleetAgent") { FleetAgentService.ensureRunning(this) },
      // Wireless Hearthpane updates from GitHub Releases: check soon after start, then every 6 h.
      StartupStep("OtaUpdater") { OtaUpdater.start(this) },
      // Start the multi-room now-playing relay if this Portal is set up as a Snapcast
      // speaker (no-op until the user configures a server). Surfaces the group's track on
      // the now-playing card even when the Music Assistant app isn't running here.
      StartupStep("MultiRoom") { MultiRoomService.sync(this) },
      // Publish this Portal to Home Assistant over MQTT if the user configured a broker
      // (no-op otherwise). Off by default.
      StartupStep("Mqtt") { MqttService.sync(this) },
      // Baseline profile for OTA installs, well after startup (replaces androidx's initializer).
      StartupStep("ProfileInstall") { BackgroundProfileInstall.schedule(this) },
      // First Music/Calls open and screensaver start: their one-time store loads, off main.
      StartupStep("FirstOpenWarmup") { FirstOpenWarmup.start(this) },
  )

  /** Shrinks the decoded-image caches under memory pressure or once our UI is hidden. */
  override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    ImageCacheTrim.onTrimMemory(level)
  }
}
