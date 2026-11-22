/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log

/**
 * One starting dream's handover to [PhotoFramePreviewActivity] ([DreamHandoverPolicy]). Main thread. The dream shows
 * only the matte the frame itself starts on; the Activity opens behind it and runs the frame's normal start (same
 * first photo, same fade). Once the Activity has committed its first frame, [finishDream] removes the dream window,
 * so the switch is invisible. If that frame never comes, [fallBack] lets the dream show the frame as before.
 */
internal class DreamHandover(
    private val context: Context,
    private val finishDream: () -> Unit,
    private val fallBack: () -> Unit,
) {
  private val main = Handler(Looper.getMainLooper())
  private var active = false
  private val onDrawn: () -> Unit = { frameDrawn() }
  private val timeout = Runnable {
    if (!active) return@Runnable
    end()
    Log.w(TAG, "handover: the frame drew nothing in ${DreamHandoverTiming.FIRST_FRAME_TIMEOUT_MS} ms; the dream shows it")
    fallBack()
  }

  fun decide(pinnedOrigin: Boolean): DreamHandoverPolicy.Decision {
    val cfg = ScreensaverConfig.load(context)
    return DreamHandoverPolicy.decide(DreamHandoverPolicy.Facts(
        screensaverEnabled = cfg.enabled,
        overnight = SleepScheduler.isOvernightNow(context),
        frameHoldsScreen = DreamPolicy.holdScreenOn(cfg.presenceMode, DreamPolicy.hasBattery(context),
            cfg.batterySaver, DreamPolicy.isPowered(context)),
        roomEmpty = IdleFramePolicy.roomEmpty(PresenceHub.current),
        screenInteractive = context.getSystemService(PowerManager::class.java)?.isInteractive != false,
        screenOwnedElsewhere = RoutineScreenState.ownsScreen() || InstallAutoConfirm.isArmed() ||
            YtVideoScreenState.ownsScreen(),
        pinned = pinnedOrigin || PinnedAppScreensaver.hasPin(),
        bridged = DreamHandoverPolicy.bridged(DreamPolicy.inStockHandoff,
            System.currentTimeMillis() - DreamPolicy.bridgeAt),
    ))
  }

  /** Opens the frame Activity behind the dream. False when it could not be started (the dream shows the frame). */
  fun begin(): Boolean {
    active = true
    ImmortalDreamNavigation.awaitPreviewFrame(onDrawn)
    val started = runCatching {
      context.startActivity(Intent(context, PhotoFramePreviewActivity::class.java)
          // It IS the screensaver: a tap honours "open when dismissed", as on the dream.
          .putExtra(PhotoFramePreviewActivity.EXTRA_LAUNCH_DISMISS_APP, true)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or
              Intent.FLAG_ACTIVITY_NO_ANIMATION))
    }.onFailure { Log.w(TAG, "handover: the frame could not be opened", it) }.isSuccess
    if (!started) {
      end()
      return false
    }
    Log.i(TAG, "handover: frame opening behind the dream")
    main.postDelayed(timeout, DreamHandoverTiming.FIRST_FRAME_TIMEOUT_MS)
    return true
  }

  /** The dream is ending for another reason (a tap, the screen going off): stop waiting. */
  fun cancel() {
    if (active) end()
  }

  private fun frameDrawn() {
    if (!active) return
    end()
    // Keeps the screen on across the instant the dream is gone and the frame's keep-screen-on not yet counted.
    runCatching {
      @Suppress("DEPRECATION")
      context.getSystemService(PowerManager::class.java)
          ?.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, WAKE_TAG)
          ?.apply { setReferenceCounted(false) }
          ?.acquire(DreamHandoverTiming.WAKE_BRIDGE_MS)
    }.onFailure { Log.w(TAG, "handover: no wake bridge", it) }
    Log.i(TAG, "handover: frame drawn; dream finishing (Hearthpane stays in front)")
    finishDream()
  }

  private fun end() {
    active = false
    main.removeCallbacks(timeout)
    ImmortalDreamNavigation.cancelPreviewFrameWait(onDrawn)
  }

  private companion object {
    const val TAG = "ImmortalDream"
    const val WAKE_TAG = "Hearthpane:dreamHandover"
  }
}
