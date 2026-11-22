/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.immortal.launcher.jarvis.wake.HearthpaneWake

/**
 * Home's idle timer: [IdleFrameTiming.LEAD_MS] before the system screen timeout, it opens the photo frame as
 * [PhotoFramePreviewActivity] (when [IdleFramePolicy] allows), so Hearthpane stays in front and "Hey Jarvis" keeps
 * hearing the room. Otherwise it does nothing and the system dream runs as before. The Android shell around the
 * policy: Home reports [resumed], [interacted] and [paused]; one main-thread runnable, no polling while busy (it
 * re-arms for the remaining idle time on each tick), a [RECHECK_MS] re-check while a skip reason holds.
 */
internal class IdleFrameTimer(private val home: Activity) {
  private val main = Handler(Looper.getMainLooper())
  private var delayMs: Long? = null
  private var lastActivityAt = 0L
  private var lastSkip: IdleFramePolicy.Skip? = null
  private val tick = Runnable { onTick() }

  /** Home is in front: read the screen timeout (it can change in Settings) and start counting. */
  fun resumed() {
    lastActivityAt = SystemClock.uptimeMillis()
    lastSkip = null
    delayMs = IdleFrameTiming.idleDelayMs(screenOffTimeoutMs())
    schedule(delayMs ?: return)
  }

  /** A touch or key on Home: idle restarts. Cheap (one field); the pending tick re-arms for the remainder. */
  fun interacted() {
    lastActivityAt = SystemClock.uptimeMillis()
  }

  fun paused() {
    main.removeCallbacks(tick)
  }

  private fun schedule(ms: Long) {
    main.removeCallbacks(tick)
    main.postDelayed(tick, ms)
  }

  private fun onTick() {
    val delay = delayMs ?: return
    val remaining = IdleFrameTiming.remainingMs(delay, SystemClock.uptimeMillis() - lastActivityAt)
    if (remaining > 0) return schedule(remaining)
    when (val decision = IdleFramePolicy.decide(facts())) {
      IdleFramePolicy.Decision.Launch -> launch()
      is IdleFramePolicy.Decision.Wait -> {
        if (decision.skip != lastSkip) Log.i(TAG, "idle; frame not opened: ${decision.skip.log}")
        lastSkip = decision.skip
        schedule(RECHECK_MS)
      }
    }
  }

  private fun launch() {
    val started = runCatching {
      home.startActivity(Intent(home, PhotoFramePreviewActivity::class.java)
          // To the household this IS the screensaver: a tap honours "open when dismissed", as on the dream.
          .putExtra(PhotoFramePreviewActivity.EXTRA_LAUNCH_DISMISS_APP, true))
      // The dream's own fade in, not a screen-change slide.
      @Suppress("DEPRECATION") home.overridePendingTransition(android.R.anim.fade_in, 0)
    }.onFailure { Log.w(TAG, "idle frame could not be opened", it) }.isSuccess
    if (!started) return schedule(RECHECK_MS)
    Log.i(TAG, "idle; opened the photo frame (Hearthpane stays in front)")
    // What the dream's own start reported, so MQTT, Music follows you and the companion see the same states.
    PresenceHub.onDreamStarted(home)
  }

  private fun facts(): IdleFramePolicy.Facts {
    val cfg = ScreensaverConfig.load(home)
    return IdleFramePolicy.Facts(
        screensaverEnabled = cfg.enabled,
        systemDreamIsOurs = systemDreamIsOurs(),
        overnight = SleepScheduler.isOvernightNow(home),
        frameHoldsScreen = DreamPolicy.holdScreenOn(cfg.presenceMode, DreamPolicy.hasBattery(home), cfg.batterySaver,
            DreamPolicy.isPowered(home)),
        roomEmpty = IdleFramePolicy.roomEmpty(PresenceHub.current),
        screenInteractive = home.getSystemService(PowerManager::class.java)?.isInteractive != false,
        dreaming = HearthpaneWake.dreaming(),
        homeFocused = home.hasWindowFocus() && !home.isFinishing,
        homeKeepsScreenOn = homeKeepsScreenOn(),
        screenOwnedElsewhere = RoutineScreenState.ownsScreen() || InstallAutoConfirm.isArmed() ||
            YtVideoScreenState.ownsScreen(),
    )
  }

  /** Read only: Hearthpane never writes these here ([SettingsGuard.reaffirmScreensaver] owns them). */
  private fun systemDreamIsOurs(): Boolean = runCatching {
    val resolver = home.contentResolver
    if (Settings.Secure.getInt(resolver, "screensaver_enabled", 0) != 1) return@runCatching false
    val ours = ComponentName(home, PhotoDreamService::class.java)
    Settings.Secure.getString(resolver, "screensaver_components").orEmpty().split(',')
        .any { ComponentName.unflattenFromString(it.trim()) == ours }
  }.getOrDefault(false)

  private fun screenOffTimeoutMs(): Long = runCatching {
    Settings.System.getInt(home.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT).toLong()
  }.getOrDefault(0L)

  private fun homeKeepsScreenOn(): Boolean {
    val window = home.window ?: return false
    if (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0) return true
    return window.decorView.keepsScreenOn()
  }

  private companion object {
    const val TAG = "ImmortalIdleFrame"
    /** While a skip reason holds (music playing, a dialog open), look again this often. */
    const val RECHECK_MS = 30_000L
  }
}

/** Any view in this tree asks to keep the screen on ([KeepInteractiveScreenAwake] sets it on the Compose view). */
private fun View.keepsScreenOn(): Boolean {
  if (keepScreenOn) return true
  if (this !is ViewGroup) return false
  for (i in 0 until childCount) if (getChildAt(i).keepsScreenOn()) return true
  return false
}
