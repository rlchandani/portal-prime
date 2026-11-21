/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.ComponentCallbacks2
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * When the Control Center has been off screen for [delayMs] (Back to Home, the screensaver), asks
 * the renderer to drop what its frames left in the process-wide GPU cache: the plan layers, the
 * sheet's offscreen texture and their scratch targets stay cached after the window is gone, and
 * the platform never sends UI_HIDDEN while Home is in front. Opening again within [delayMs] keeps
 * everything (a quick re-open pays nothing). On Family Portal+ (2026-11-21) the cache fell from
 * 15.5-17.3 MB to Home's own 10.1 MB, and an open after the trim had the same frames as any other.
 * Main thread only.
 */
internal class CcGpuTrim(
    private val delayMs: Long,
    private val schedule: (Long, Runnable) -> Unit,
    private val unschedule: (Runnable) -> Unit,
    private val trim: () -> Unit,
) {
  /** Control Center windows on screen now (a new one starts before the old one stops). */
  private var shown = 0
  private var pending = false
  private val run = Runnable {
    pending = false
    if (shown == 0) trim()
  }

  val trimPending: Boolean get() = pending

  fun started() {
    shown++
    cancel()
  }

  fun stopped() {
    shown = (shown - 1).coerceAtLeast(0)
    if (shown > 0) return
    cancel()
    pending = true
    schedule(delayMs, run)
  }

  private fun cancel() {
    if (!pending) return
    pending = false
    unschedule(run)
  }

  companion object {
    private const val TAG = "ImmortalMemory"
    /** Long enough for the Back transition and Home's settle frames; a re-open sooner keeps the cache. */
    const val DELAY_MS = 4_000L

    private val main by lazy { Handler(Looper.getMainLooper()) }

    val instance: CcGpuTrim by lazy {
      CcGpuTrim(DELAY_MS, { ms, r -> main.postDelayed(r, ms) }, { r -> main.removeCallbacks(r) }, ::trimRenderer)
    }

    /**
     * The renderer's own UI_HIDDEN trim (what the platform sends when no window of ours is shown):
     * unlocked scratch textures and glyph/image caches go, live windows keep their content.
     * Hidden API; when the platform refuses it the cache simply stays as it is today.
     */
    private fun trimRenderer() {
      runCatching {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        global.getMethod("trimMemory", Int::class.javaPrimitiveType).invoke(instance, ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
      }.onSuccess { Log.i(TAG, "control center closed: renderer caches trimmed") }
          .onFailure { Log.w(TAG, "control center closed: renderer trim unavailable", it) }
    }
  }
}
