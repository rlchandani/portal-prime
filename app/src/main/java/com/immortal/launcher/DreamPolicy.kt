/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * Cooperates with the Portal build's presence-driven power policy.
 *
 * How this build behaves (measured on device):
 *  - At the screen timeout, Meta's PowerManager decides AMBIENT vs SLEEP from the
 *    presence service: someone around → start the dream; empty room → real sleep.
 *    (`screensaver_activate_on_sleep` is ignored.)
 *  - A running dream is force-woken at `last-activity + min(screen_off + 120s,
 *    sleep_timeout)` — dreams are transient by design; stock SuperFrame caught
 *    that wake, so we must too.
 *
 * Immortal's policy:
 *  - When the system bounces the dream (not a user tap, not a power-button
 *    sleep), instantly relaunch the same frame as [PhotoFramePreviewActivity].
 *  - The frame HOLDS the screen on mains-powered Portals (and on the Go while
 *    charging, or with the battery saver off) → permanent photo frame.
 *  - On the Go on battery with the saver on, the frame does NOT hold the screen:
 *    each screen timeout becomes a fresh presence decision — someone around →
 *    the dream takes over again (visually identical, so it reads as one
 *    continuous frame); empty room → the device truly sleeps.
 */
object DreamPolicy {
  private const val TAG = "ImmortalDream"
  private const val PREFS = "dream_bridge"
  private const val KEY_BRIDGE_AT = "bridge_at"
  private const val KEY_IN_STOCK_HANDOFF = "in_stock_handoff"
  private const val KEY_PORTAL_HOME_HANDOFF = "portal_home_handoff"
  private val bridgeRequests = AtomicLong()

  /** Set by [PhotoDreamService] just before finish() on a user tap. */
  @Volatile var userExitAt: Long = 0L

  /**
   * Set by [HomeActivity] when it deliberately bridges to the stock launcher (the
   * Calls tile). The stock launcher cold-starts into its own idle "dream" face and
   * immediately stops it, firing ACTION_DREAMING_STOPPED — which would otherwise
   * make us relaunch our photo frame over the top of the stock home and trap the
   * user there (the reported "Calls kicks me back into Immortal"). While a bridge
   * is in flight we suppress that relaunch.
   *
   * Persisted to SharedPreferences via [markBridge]/[clearBridge] so that if the
   * Immortal process is killed while the stock home is in the foreground (Android
   * reclaims it as a non-foreground launcher), a freshly-spawned process still sees
   * the bridge and returns SUPPRESSED instead of REDREAM.
   */
  @Volatile var bridgeAt: Long = 0L

  /**
   * True from the moment the Calls tile bridges to the stock launcher until the
   * user comes back to Immortal (cleared in [HomeActivity.onResume]). While set, we
   * never relaunch the holding photo-frame Activity, so it can't slam over the stock
   * home while the user is in a call flow. The system Dream still shows photos on
   * idle as usual — we only skip the aggressive "permanent frame" relaunch.
   */
  @Volatile var inStockHandoff: Boolean = false
  /** Only a Calls handoff may recover Meta's bootstrap redirect; Jarvis also uses suppression. */
  @Volatile var portalHomeHandoff: Boolean = false

  /**
   * Called from [ImmortalApp.onCreate] to restore bridge state after a process restart.
   * Without this, a new process spawned for a dream-service invocation sees default
   * (zeroed) values and classifies the dream stop as REDREAM, relaunching the photo
   * frame over the in-progress call.
   */
  fun initFromPrefs(context: Context) {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    bridgeAt = prefs.getLong(KEY_BRIDGE_AT, 0L)
    inStockHandoff = prefs.getBoolean(KEY_IN_STOCK_HANDOFF, false)
    portalHomeHandoff = inStockHandoff && prefs.getBoolean(KEY_PORTAL_HOME_HANDOFF, false)
    if (inStockHandoff) Log.i(TAG, "restored bridge from prefs: bridgeAt=$bridgeAt inStockHandoff=true")
  }

  private fun beginBridge(portalHome: Boolean): Pair<Long, Long> {
    val ticket = bridgeRequests.incrementAndGet()
    val now = System.currentTimeMillis()
    bridgeAt = now
    inStockHandoff = true
    portalHomeHandoff = portalHome
    return ticket to now
  }

  private fun persistBridge(context: Context, now: Long, portalHome: Boolean): Boolean =
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
          .putLong(KEY_BRIDGE_AT, now).putBoolean(KEY_IN_STOCK_HANDOFF, true)
          .putBoolean(KEY_PORTAL_HOME_HANDOFF, portalHome).commit()

  /** Existing Meta handoffs flush before launch; every writer shares the same disk lane. */
  fun markBridge(context: Context, portalHome: Boolean = false) {
    val (_, now) = beginBridge(portalHome)
    val app = context.applicationContext ?: context
    DreamBridgePersistence.commit { persistBridge(app, now, portalHome) }
  }

  /** Jarvis taps stay responsive; completion runs on Main only after the durable commit. */
  fun markBridgeAsync(context: Context, completed: (Long, Boolean) -> Unit): Long {
    val (ticket, now) = beginBridge(false)
    val app = context.applicationContext ?: context
    DreamBridgePersistence.submit({ persistBridge(app, now, false) }) { completed(ticket, it) }
    return ticket
  }

  fun isCurrentBridge(ticket: Long): Boolean = bridgeRequests.get() == ticket

  /** A cancelled preparation cannot clear a newer intentional handoff. Main-owned admission. */
  fun clearBridgeIfCurrent(context: Context, ticket: Long) {
    if (isCurrentBridge(ticket)) clearBridge(context)
  }

  /** Clear visible state now; persist behind earlier marks without UI-thread disk work. */
  fun clearBridge(context: Context) {
    bridgeRequests.incrementAndGet()
    inStockHandoff = false
    bridgeAt = 0L
    portalHomeHandoff = false
    val app = context.applicationContext ?: context
    DreamBridgePersistence.submit({
      app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
          .remove(KEY_BRIDGE_AT).remove(KEY_IN_STOCK_HANDOFF).remove(KEY_PORTAL_HOME_HANDOFF).commit()
    })
  }

  fun hasBattery(context: Context): Boolean =
      runCatching {
            context
                .registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false) == true
          }
          .getOrDefault(false)

  fun isPowered(context: Context): Boolean =
      runCatching {
            (context
                .registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
          }
          .getOrDefault(true)

  /**
   * Pure decision (unit-tested): should the frame hold the screen on?
   *
   *  - [FrameMode.PRESENCE] never holds: we hand control back to the Portal's presence policy
   *    at every screen timeout, so it sleeps the screen when the room empties and re-dreams
   *    when someone returns. This is the shared screensaver/music baseline.
   *  - [FrameMode.ALWAYS_ON] holds on mains / while charging / with the saver off → a permanent
   *    frame, at the cost of masking the presence proxy (presence then reads UNKNOWN; the music
   *    defers to Home Assistant — see [PresenceHub] and docs/design/multi-room-audio.md → *Presence*).
   *
   * Note on what we CAN'T do: we can't read Meta's presence signal directly — it's front-camera
   * CV behind a platform-signature permission (proven by the SuperFrame APK teardown). But the
   * dream/sleep lifecycle is *derived* from it and is observable, which is exactly what
   * PRESENCE mode rides. [SleepScheduler] still offers a presence-free idle timeout and an
   * overnight window via device-admin lockNow() on top of either mode.
   */
  internal fun holdScreenOn(
      mode: FrameMode,
      hasBattery: Boolean,
      batterySaver: Boolean,
      powered: Boolean,
  ): Boolean =
      when (mode) {
        FrameMode.PRESENCE -> false
        FrameMode.ALWAYS_ON -> !hasBattery || !batterySaver || powered
      }

  /**
   * Observe the system broadcast for presence only. It contains no component identity: Meta's
   * own dream can stop while the user is navigating, so this signal must never open our frame.
   */
  fun onDreamingStopped(context: Context) =
      handleDreamStopped(context, recoverImmortalFrame = false)

  /** Only [PhotoDreamService]'s lifecycle callback proves that our screensaver was interrupted. */
  internal fun onImmortalDreamingStopped(context: Context, pinnedOrigin: Boolean = false) =
      handleDreamStopped(context, recoverImmortalFrame = true, pinnedOrigin = pinnedOrigin)

  private fun handleDreamStopped(context: Context, recoverImmortalFrame: Boolean, pinnedOrigin: Boolean = false) {
    if (!ScreensaverConfig.load(context).enabled) {
      Log.i(TAG, "screensaver disabled; not relaunching frame")
      return
    }
    val now = System.currentTimeMillis()
    val pm = context.getSystemService(PowerManager::class.java)
    val verdict =
        classifyDreamStop(
            userExitAgoMs = now - userExitAt,
            bridgeAgoMs = now - bridgeAt,
            inStockHandoff = inStockHandoff,
            interactive = pm?.isInteractive == true,
        )
    Log.i(TAG, "dream stopped; source = ${if (recoverImmortalFrame) "Immortal" else "system"}; verdict = $verdict")
    // Single source of truth: tell the hub the presence consequence (it broadcasts to the
    // companion). SUPPRESSED (a Calls handoff) leaves presence untouched.
    PresenceHub.onDreamStopped(context, verdict)
    if (!recoverImmortalFrame || verdict != DreamStopVerdict.REDREAM) return
    // Inside the overnight window the SleepScheduler owns the screen. In dark mode, relaunching the
    // frame would wake the screen just to blank it again — a flash every time a stray sibling/system
    // dream cycles overnight (issue #73). Let the scheduler re-blank in place instead.
    if (SleepScheduler.handleRedreamDuringOvernight(context)) return
    if (pinnedOrigin || PinnedAppScreensaver.hasPin()) {
      // An accessibility overlay preserves the underlying external task. Never fall back to
      // the launcher Activity if attaching this curtain fails: that would displace the app.
      if (!PinnedAppScreensaver.showFrame()) Log.w(TAG, "pinned frame recovery unavailable")
      return
    }
    runCatching {
      context.startActivity(
          Intent(context, PhotoFramePreviewActivity::class.java)
              // The continuation frame IS the screensaver to the user, so a tap on it must honour
              // the "open when dismissed" target like the dream's own tap handler (issue #146).
              .putExtra(PhotoFramePreviewActivity.EXTRA_LAUNCH_DISMISS_APP, true)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
        .onFailure { Log.w(TAG, "frame relaunch failed", it) }
  }
}
