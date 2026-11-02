/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.os.SystemClock

/**
 * A short-lived "arm" window that gates [InstallConfirmService].
 *
 * The auto-confirm accessibility service exists for HEADLESS fleet installs — the agent commits
 * a PackageInstaller session, the system shows its confirm dialog, and the service taps "Install"
 * so no human is needed. But the service is enabled device-wide, so it used to fire on EVERY
 * installer dialog — including the one you see when you tap Install in Aurora Store or the App
 * Store yourself. It would tap through / dismiss that dialog before you could read it, which the
 * system reports as STATUS_FAILURE_ABORTED (status 3): installs appeared to "flash and vanish".
 *
 * The service only acts while an owned window is armed. [HeadlessInstaller] acquires a window
 * for its fleet session and releases that same window when the install finishes. Overlapping
 * attempts cannot release each other's windows. Interactive installs never acquire one.
 */
object InstallAutoConfirm {
  private val window = InstallAutoConfirmWindow(SystemClock::elapsedRealtime)

  /** Arm the auto-confirm window for a fleet/headless install about to commit. */
  internal fun arm(): InstallAutoConfirmWindow.Lease = window.arm()

  /** Release only this install's window once it reaches a terminal state. */
  internal fun disarm(lease: InstallAutoConfirmWindow.Lease) = window.disarm(lease)

  /** True only while a headless install is in flight — the service is a no-op otherwise. */
  fun isArmed(): Boolean = window.isArmed()
}

/** Independent expiry retains the existing safety cap without extending an older attempt. */
internal class InstallAutoConfirmWindow(
    private val clock: () -> Long,
    private val ttlMs: Long = 30_000L,
) {
  class Lease internal constructor()
  private val deadlines = mutableMapOf<Lease, Long>()

  @Synchronized fun arm(): Lease {
    val now = clock()
    removeExpired(now)
    return Lease().also { deadlines[it] = now + ttlMs }
  }

  @Synchronized fun disarm(lease: Lease) {
    deadlines.remove(lease)
  }

  @Synchronized fun isArmed(): Boolean {
    removeExpired(clock())
    return deadlines.isNotEmpty()
  }

  private fun removeExpired(now: Long) {
    deadlines.entries.removeAll { it.value <= now }
  }
}
