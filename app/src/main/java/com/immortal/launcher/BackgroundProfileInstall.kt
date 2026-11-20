/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.profileinstaller.ProfileInstaller

/**
 * Installs the bundled baseline profile for builds that arrive without a `.dm` (OTA, sideload).
 *
 * This replaces androidx's `ProfileInstallerInitializer` (removed in the manifest). That initializer
 * posts a Choreographer frame callback from `bindApplication`; the vsync it requests becomes Home's
 * first frame's IntendedVsync, so the first frame was timed from inside Application.onCreate. Here
 * the same install runs on its own low-priority thread, long after startup, with no frame request.
 */
internal object BackgroundProfileInstall {
  private const val TAG = "ProfileInstall"
  /** androidx waits ~5 s after the first frame; this is measured from the deferred startup steps. */
  const val DELAY_MS = 10_000L

  @Volatile private var scheduled = false

  fun schedule(context: Context) {
    if (scheduled) return
    scheduled = true
    val app = context.applicationContext
    Handler(Looper.getMainLooper()).postDelayed({
      Thread({
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        try {
          ProfileInstaller.writeProfile(app)
        } catch (e: RuntimeException) {
          Log.w(TAG, "baseline profile install failed", e)
        }
      }, "hearthpane-profile").apply { isDaemon = true }.start()
    }, DELAY_MS)
  }
}
