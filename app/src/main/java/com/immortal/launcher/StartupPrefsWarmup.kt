/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.util.Log

/**
 * Cold-start SharedPreferences warm-up.
 *
 * Every `getSharedPreferences` call for a file not yet in the process cache starts that file's
 * load on its own platform thread, and the first read on the main thread blocks until the load is
 * done. On a cold boot Home's first composition opened about 20 files one after another, each
 * starting its load only when first read, so the main thread waited on them in series (perfetto:
 * ~210 ms asleep on SharedPreferences loaders in and before Home's first frame).
 *
 * One background thread opens the files that Application setup and Home's first frame read, as
 * early as the process allows, so their loads run in parallel with bindApplication. The objects
 * land in the platform's process-wide cache, so the later main-thread calls return the same
 * instances; this only moves when each load starts. Nothing is read or written here.
 */
internal object StartupPrefsWarmup {
  private const val TAG = "StartupPrefsWarmup"
  private const val THREAD_NAME = "hearthpane-prefs"

  /**
   * In first-read order: Application setup, then Home's first composition. Names only; each
   * owner keeps its own constant (StartupPrefsWarmupTest checks every name still has one).
   */
  val FILES = listOf(
      // Application.onCreate, before the first frame.
      "immortal_routines", "immortal_guest_mode", "hearthpane_rotation", "dream_bridge",
      "hearthpane_jarvis_wake", "immortal_settings", "immortal_screensaver", "immortal_chime",
      "immortal_sunrise", "immortal_alarm", "immortal_timers", "immortal_share",
      // HomeActivity.onCreate and Home's first composition (default widgets included).
      "immortal_help", "immortal_wallpaper", "immortal_welcome", "fleet_agent", "immortal_layout",
      "immortal_home_widgets", "immortal_ha", "hearthpane_control_center", "immortal_share_notes",
      // Also read inside Home's first frame; unwarmed they cost ~47 ms of main-thread waits there.
      "immortal_display", "immortal_share_fresh", "immortal_countdown", "immortal_todos", "immortal_crypto",
      "immortal_home_dashboard",
  )

  @Volatile private var started = false

  /** Once per process; returns immediately. Call as early as possible (attachBaseContext). */
  fun start(context: Context) {
    if (started) return
    started = true
    runCatching {
      Thread({
        val skipped = warm(context)
        if (skipped.isNotEmpty()) Log.d(TAG, "skipped $skipped")
      }, THREAD_NAME).apply { isDaemon = true }.start()
    }.onFailure { Log.w(TAG, "warm-up thread failed to start", it) }
  }

  /**
   * Opens each of [files] (starting its platform load) and returns the ones that couldn't be
   * opened. Best-effort: for those (e.g. storage not yet available) the main thread's own later
   * call loads the file exactly as it would without the warm-up.
   */
  fun warm(context: Context, files: List<String> = FILES): List<String> =
      files.filter { name -> runCatching { context.getSharedPreferences(name, Context.MODE_PRIVATE) }.isFailure }
}
