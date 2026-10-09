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

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    ImmortalDreamNavigation.attached(this)
    Log.i(TAG, "attached: dream starting")
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
    frame.onExit = {
      Log.i(TAG, "onExit (tap) -> finish()")
      // Mark this as a USER exit so DreamPolicy doesn't relaunch the frame.
      DreamPolicy.userExitAt = System.currentTimeMillis()
      // If the user chose an app to open on dismiss (e.g. their Home Assistant
      // dashboard), launch it; otherwise this is a no-op and we fall back to the
      // launcher — same as before, and also the path when that app is uninstalled.
      ScreensaverDismiss.launchChosenApp(this)
      finish()
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
    // A screensaver session is running: start (or keep) the idle screen-off countdown.
    SleepScheduler.onScreensaverStarted(this)
  }

  override fun onDreamingStopped() {
    Log.i(TAG, "onDreamingStopped")
    super.onDreamingStopped()
    DreamPolicy.onImmortalDreamingStopped(this)
  }

  override fun onWakeUp() {
    Log.i(TAG, "onWakeUp (system or finish())")
    super.onWakeUp()
  }

  override fun onDetachedFromWindow() {
    ImmortalDreamNavigation.detached(this)
    Log.i(TAG, "detached: dream ending")
    window?.let { FramePerformanceCapture.untrack(it) }
    if (this::frame.isInitialized) frame.stop()
    super.onDetachedFromWindow()
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
  fun hasOwnedSurface(): Boolean = active.get() != null || preview.get() != null

  fun dismissForNavigation(): Boolean {
    val service = active.get()
    val activity = preview.get()
    if (service == null && activity == null) return false
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
