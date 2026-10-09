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
  override fun onCreate() {
    super.onCreate()
    // Arm first, before any other init: as the home app, an uncaught crash makes Android drop our
    // default-home role (→ "Select Home app" chooser). This relaunches us straight back so the
    // user never sees it. Installed up front so a crash during the rest of onCreate is covered too.
    CrashGuard.install(this)
    // Weak window tracking is dormant until an operator starts a bounded Fleet capture.
    FramePerformanceCapture.init(this)
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
    // Second presence source: Meta's own camera CV, read from its logcat heartbeat. Fills
    // the hub's pinned-frame blind spot (always-on frames never sleep, so the dream/sleep
    // proxy can't see the room empty). No-op without the ADB-granted READ_LOGS.
    MetaPresenceReader(this).start()
    // Read the device's native media session (whatever's playing) into NowPlayingHub
    // for the screensaver card + header mini-player. Dormant until our notification
    // listener is enabled — done at provisioning (`cmd notification allow_listener`);
    // the reader attaches the moment that listener binds.
    MediaSessionReader.init(this)
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

    // Keep our accessibility service enabled — baseline launcher infrastructure now (it backs
    // the Calls→stock-home bridge, the phone remote, and the quick-button cluster). No-op
    // without WRITE_SECURE_SETTINGS, so it effectively turns on only once provisioned.
    SettingsGuard.reconcileBarWatch(this)

    // Arm the overnight screen-off window (and apply it if we're inside it now).
    SleepScheduler.applyOvernightNow(this)

    // Arm the ambient chime / spoken-time / golden-hour alarms per the user's config.
    ChimeScheduler.reschedule(this)

    // Arm the next sunrise wake-light alarm per the user's config.
    SunriseScheduler.reschedule(this)

    // Arm the next wake-up alarm per the user's config.
    AlarmScheduler.reschedule(this, cancelSnooze = false)

    // Bring up the WiFi fleet agent if provisioning enabled it (no-op otherwise).
    FleetAgentService.ensureRunning(this)

    // Start the multi-room now-playing relay if this Portal is set up as a Snapcast
    // speaker (no-op until the user configures a server). Surfaces the group's track on
    // the now-playing card even when the Music Assistant app isn't running here.
    MultiRoomService.sync(this)

    // Publish this Portal to Home Assistant over MQTT if the user configured a broker
    // (no-op otherwise). Off by default.
    MqttService.sync(this)
  }
}
