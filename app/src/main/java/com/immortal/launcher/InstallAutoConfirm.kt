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
 * once its fleet session is committed, sized to the session-result wait, and releases that same
 * window when the install finishes. Overlapping attempts cannot release each other's windows.
 * Interactive installs never acquire one.
 */
object InstallAutoConfirm {
  private val window = InstallAutoConfirmWindow(SystemClock::elapsedRealtime)

  /**
   * Arm the auto-confirm window for a fleet/headless install that has just committed. [ttlMs]
   * must cover the whole result wait: the old fixed 30 s window was armed before staging, so a
   * slow copy or a dialog shown late (dream, re-presentation) found the service already dormant.
   */
  internal fun arm(ttlMs: Long = InstallAutoConfirmWindow.DEFAULT_TTL_MS): InstallAutoConfirmWindow.Lease =
      window.arm(ttlMs)

  /** Release only this install's window once it reaches a terminal state. */
  internal fun disarm(lease: InstallAutoConfirmWindow.Lease) = window.disarm(lease)

  /** True only while a headless install is in flight — the service is a no-op otherwise. */
  fun isArmed(): Boolean = window.isArmed()

  /** The service tapped an enabled, focused affirmative button for the armed install(s). */
  internal fun recordTap() = window.recordTap()

  internal fun tapped(lease: InstallAutoConfirmWindow.Lease): Boolean = window.tapped(lease)
}

/** Independent expiry retains the existing safety cap without extending an older attempt. */
internal class InstallAutoConfirmWindow(
    private val clock: () -> Long,
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {
  class Lease internal constructor()
  private class Entry(val deadline: Long, var tapped: Boolean = false)
  private val entries = mutableMapOf<Lease, Entry>()

  @Synchronized fun arm(ttl: Long = ttlMs): Lease {
    val now = clock()
    removeExpired(now)
    return Lease().also { entries[it] = Entry(now + ttl.coerceAtLeast(1L)) }
  }

  @Synchronized fun disarm(lease: Lease) {
    entries.remove(lease)
  }

  @Synchronized fun isArmed(): Boolean {
    // Hot path for dream recovery: no lease means no clock read.
    if (entries.isEmpty()) return false
    removeExpired(clock())
    return entries.isNotEmpty()
  }

  /** Marks every live lease: a tap can't be attributed to one session from the dialog alone. */
  @Synchronized fun recordTap() {
    removeExpired(clock())
    entries.values.forEach { it.tapped = true }
  }

  @Synchronized fun tapped(lease: Lease): Boolean = entries[lease]?.tapped == true

  private fun removeExpired(now: Long) {
    entries.entries.removeAll { it.value.deadline <= now }
  }

  companion object {
    const val DEFAULT_TTL_MS = 30_000L
  }
}
