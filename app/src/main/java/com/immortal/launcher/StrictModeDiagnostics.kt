/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.os.StrictMode
import android.util.Log

/**
 * Diagnostics-only StrictMode: logs main-thread disk/network/custom-slow calls and leaked
 * closables, cursors and receivers/service connections (logcat tag `StrictMode`), never kills.
 *
 * Compiled in only by a scratch debug build (`./gradlew :app:assembleDebug -PportalStrictMode=true`);
 * `PORTAL_STRICT_MODE_DIAGNOSTICS` is false in every other variant, and Gradle refuses the property
 * on release, performance and benchmark tasks, so it can never reach a shipped or measured build.
 */
internal object StrictModeDiagnostics {
  private const val TAG = "PortalStrictMode"

  /** Pure gate (JVM-tested): only the compile-time flag turns it on. */
  fun shouldInstall(buildFlag: Boolean = BuildConfig.PORTAL_STRICT_MODE_DIAGNOSTICS): Boolean = buildFlag

  /** Call first thing in [android.app.Application.onCreate], on the main thread. */
  fun installIfEnabled() {
    if (!shouldInstall()) return
    StrictMode.setThreadPolicy(
        StrictMode.ThreadPolicy.Builder()
            .detectDiskReads()
            .detectDiskWrites()
            .detectNetwork()
            .detectCustomSlowCalls()
            .penaltyLog()
            .build())
    StrictMode.setVmPolicy(
        StrictMode.VmPolicy.Builder()
            .detectLeakedClosableObjects()
            .detectLeakedSqlLiteObjects()
            .detectLeakedRegistrationObjects()
            .penaltyLog()
            .build())
    Log.w(TAG, "StrictMode diagnostics ON (scratch build only)")
  }
}
