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
 * Fix: the service only acts while this window is armed. [HeadlessInstaller] arms it right before
 * committing a fleet session and disarms it when the install finishes. Interactive installs never
 * arm it, so their dialogs behave normally and wait for a real tap.
 */
object InstallAutoConfirm {

  // How long an arm lasts if not explicitly disarmed (a safety cap so a crashed headless install
  // can't leave the auto-clicker live forever). The system dialog appears within a second or two.
  private const val ARM_TTL_MS = 30_000L

  @Volatile private var armedUntil: Long = 0L

  /** Arm the auto-confirm window for a fleet/headless install about to commit. */
  fun arm() {
    armedUntil = SystemClock.elapsedRealtime() + ARM_TTL_MS
  }

  /** Disarm once the headless install reaches a terminal state. */
  fun disarm() {
    armedUntil = 0L
  }

  /** True only while a headless install is in flight — the service is a no-op otherwise. */
  fun isArmed(): Boolean = SystemClock.elapsedRealtime() < armedUntil
}
