/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.service.dreams.DreamService
import android.util.Log
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import java.lang.ref.WeakReference

/**
 * Native photo-frame screensaver. Reproduces the stock Portal idle screen
 * (clock / battery / date / weather over a full-screen photo feed) without
 * touching Meta's APK. Set as the screensaver via
 * `settings put secure screensaver_components <pkg>/.PhotoDreamService`.
 *
 * All UI/logic lives in [PhotoFrameController], shared with
 * [PhotoFramePreviewActivity].
 */
class PhotoDreamService : DreamService() {
  private lateinit var frame: PhotoFrameController
  private var pinnedOrigin = false
  private var frameStopped = false
  private var lostPanelShown = false
  private var nightBrightness: NightWindowBrightness? = null

  override fun onWindowAttributesChanged(attrs: WindowManager.LayoutParams) {
    super.onWindowAttributesChanged(attrs)
    val dreamWindow = window ?: return
    val decor = dreamWindow.decorView
    // DreamService's callback is empty on Android 9/10; unlike Activity, setting
    // Window.attributes alone never delivers our brightness ramp to WindowManager.
    // Attribute changes also occur before attach, when no system view exists yet.
    if (decor.isAttachedToWindow) dreamWindow.windowManager.updateViewLayout(decor, attrs)
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    frameStopped = false
    lostPanelShown = false
    pinnedOrigin = PinnedAppScreensaver.hasPin()
    ImmortalDreamNavigation.attached(this)
    PinnedAppScreensaver.nativeDreamAttached()
    // DreamService installs Android's separate Dream window animation. It can keep a
    // departing photo surface over the already-resumed Home. Reveal the retained task
    // directly; slideshow motion remains owned by PhotoFrameController.
    window?.setWindowAnimations(0)
    Log.i(TAG, "attached: dream starting; windowAnimations=${window?.attributes?.windowAnimations}")
    // Interactive so we receive touch and can exit on tap (verified on device).
    // Fullscreen for an immersive frame — tap-to-exit is the way out.
    isInteractive = true
    isFullscreen = true
    isScreenBright = true
    // A DreamService window is NOT hardware-accelerated by default, and a WebView renders BLANK
    // without HW acceleration — which is why the new WebView engine showed nothing here (only the
    // software-rendered clock overlay) while the preview Activity worked. Force it on so the engine
    // paints. (The classic ImageView renderer worked either way — ImageViews render in software.)
    window?.setFlags(
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
    frame = PhotoFrameController(this)
    nightBrightness = window?.let { NightWindowBrightness(this, it).also { brightness -> brightness.setActive(true) } }
    frame.onNightClockChanged = { night ->
      nightBrightness?.setNight(night, ScreensaverConfig.load(this@PhotoDreamService).nightClockBrightness)
    }
    frame.onExit = {
      exitPinnedDream(
          pinnedOrigin = pinnedOrigin,
          pinnedNow = PinnedAppScreensaver.hasPin(),
          admitPinnedReturn = PinnedAppScreensaver::nativeDreamTapped,
          showLostSession = ::showLostSession,
          launchConfiguredApp = {
            DreamPolicy.userExitAt = System.currentTimeMillis()
            ScreensaverDismiss.launchChosenApp(this)
          },
          retireContinuation = {
            DreamPolicy.userExitAt = System.currentTimeMillis()
            val retired = ImmortalDreamNavigation.dismissContinuationForTap(this)
            Log.i(TAG, "onExit: retained continuation retired=$retired")
          },
          finishDream = {
            Log.i(TAG, "onExit (tap) -> finish()")
            DreamPolicy.userExitAt = System.currentTimeMillis()
            if (pinnedOrigin || PinnedAppScreensaver.hasPin()) SleepScheduler.onReturnedToApp(this)
            pauseFrameIfReady()
            finish()
          },
      )
    }
    val root = frame.view
    // Tap dismisses, horizontal swipe changes photo (handled by the controller).
    // Touch events are logged so spurious/phantom touches that end the dream are
    // visible in logcat (they masquerade as a user tap otherwise).
    root.setOnTouchListener { _, ev ->
      Log.i(TAG, "touch action=${ev.actionMasked} x=${ev.x} y=${ev.y}")
      frame.onTouch(ev)
      true
    }
    setContentView(root)
    window?.let { FramePerformanceCapture.track(it, "PhotoDreamService") }
    frame.start()
  }

  override fun onDreamingStarted() {
    super.onDreamingStarted()
    Log.i(TAG, "onDreamingStarted")
    // The system broadcast may precede a cold process start; the owned callback is authoritative.
    PresenceHub.onDreamStarted(this)
    // A screensaver session is running: start (or keep) the idle screen-off countdown.
    SleepScheduler.onScreensaverStarted(this)
  }

  override fun onDreamingStopped() {
    Log.i(TAG, "onDreamingStopped")
    pauseFrameIfReady()
    super.onDreamingStopped()
    DreamPolicy.onImmortalDreamingStopped(this, pinnedOrigin)
  }

  override fun onWakeUp() {
    Log.i(TAG, "onWakeUp (system or finish())")
    pauseFrameIfReady()
    super.onWakeUp()
  }

  override fun onDetachedFromWindow() {
    ImmortalDreamNavigation.detached(this)
    PinnedAppScreensaver.nativeDreamDetached()
    Log.i(TAG, "detached: dream ending")
    window?.let { FramePerformanceCapture.untrack(it) }
    stopFrameOnce()
    super.onDetachedFromWindow()
  }

  private fun showLostSession() {
    if (lostPanelShown || !this::frame.isInitialized) return
    lostPanelShown = true
    val root = frame.view as FrameLayout
    root.setOnTouchListener(null)
    root.addView(pinnedSessionLostPanel(this) {
      openApprovedHomeTab(ApprovedLauncherTab.HOME)
    }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    stopFrameOnce()
  }

  /** Freeze the last photo until window removal; clearing bitmaps here would flash black. */
  private fun pauseFrameIfReady() {
    nightBrightness?.setActive(false)
    if (!frameStopped && this::frame.isInitialized) frame.setHostActive(false)
  }

  private fun stopFrameOnce() {
    nightBrightness?.close()
    nightBrightness = null
    if (!frameStopped && this::frame.isInitialized) {
      frameStopped = true
      frame.onExit = null
      frame.stop()
    }
  }

  private companion object {
    const val TAG = "ImmortalDream"
  }
}

/** Main-thread bridge for Immortal's Dream and preview; never a system/Meta screensaver. */
internal object ImmortalDreamNavigation {
  private var active = WeakReference<PhotoDreamService>(null)
  private var preview = WeakReference<PhotoFramePreviewActivity>(null)

  fun attached(service: PhotoDreamService) { active = WeakReference(service) }

  fun detached(service: PhotoDreamService) {
    if (active.get() === service) active.clear()
  }

  fun attached(activity: PhotoFramePreviewActivity) { preview = WeakReference(activity) }

  fun detached(activity: PhotoFramePreviewActivity) {
    if (preview.get() === activity) preview.clear()
  }

  /** Called on main by navigation admission; only Immortal registers here. */
  fun hasOwnedSurface(): Boolean = active.get() != null || preview.get() != null || PinnedAppScreensaver.hasFrame()

  /** A tap ends the screensaver session, including a continuation retained under this Dream. */
  fun dismissContinuationForTap(service: PhotoDreamService): Boolean {
    if (active.get() !== service) return false
    val continuation = preview.get()?.takeIf { it.isScreensaverContinuation } ?: return false
    preview.clear()
    // Clear ownership and suppress recovery before finish can deliver lifecycle callbacks.
    DreamPolicy.userExitAt = System.currentTimeMillis()
    continuation.finish()
    return true
  }

  fun dismissForNavigation(): Boolean {
    val service = active.get()
    val activity = preview.get()
    val hadOverlay = PinnedAppScreensaver.hasFrame()
    PinnedAppScreensaver.release()
    if (service == null && activity == null && !hadOverlay) return false
    active.clear()
    preview.clear()
    // Clear ownership and mark intent before either surface's asynchronous exit callbacks.
    // A cold Home may be in another task; bringing it forward cannot clear that preview's stack.
    DreamPolicy.userExitAt = System.currentTimeMillis()
    service?.finish()
    activity?.finish()
    return true
  }
}
