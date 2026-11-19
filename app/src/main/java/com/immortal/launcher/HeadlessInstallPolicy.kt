/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.pm.PackageInstaller

/**
 * How a headless install ended, decided ONLY from the PackageInstaller session's own result —
 * never from whether the auto-confirm service managed to tap a button.
 */
internal sealed class HeadlessInstallOutcome {
  object Installed : HeadlessInstallOutcome() {
    override fun toString() = "installed"
  }

  /** Android reported a terminal failure for our session. */
  data class Failed(val status: Int, val message: String?) : HeadlessInstallOutcome()

  /** No session result before the timeout: the confirmation was never accepted. */
  data class ConfirmTimeout(val waitedMs: Long, val presentations: Int, val tapped: Boolean) :
      HeadlessInstallOutcome()

  /** The session couldn't be staged or committed at all. */
  data class Error(val reason: String) : HeadlessInstallOutcome()

  val ok: Boolean
    get() = this is Installed

  /**
   * The system dialog was never accepted (timed out, or aborted — e.g. dismissed without
   * acceptance). Retrying later can help, unlike Android rejecting the APK itself.
   */
  val confirmationFailed: Boolean
    get() = this is ConfirmTimeout ||
        (this is Failed && status == PackageInstaller.STATUS_FAILURE_ABORTED)
}

/** Pure timing and bookkeeping decisions for [HeadlessInstaller]. */
internal object HeadlessInstallPolicy {
  private const val MB = 1024L * 1024L

  /** Floor for the session-result wait; 1.250–1.256 (~38 MB) needed well under this. */
  const val MIN_RESULT_TIMEOUT_MS = 180_000L
  /** Per staged MB: the installer parses the APK on its main thread, then PM copies and verifies. */
  const val RESULT_TIMEOUT_PER_MB_MS = 2_000L
  const val MAX_RESULT_TIMEOUT_MS = 420_000L

  /** Without a tap on a ready dialog by then, show the confirmation again (dream/Home covered it). */
  const val REPRESENT_AFTER_MS = 30_000L
  const val MAX_PRESENTATIONS = 4

  /** Result wait sized for the APK: a 52 MB build gets ~4.7 min instead of the old flat 2 min. */
  fun resultTimeoutMs(apkBytes: Long): Long {
    val mb = (apkBytes.coerceAtLeast(0L) + MB - 1) / MB
    return (MIN_RESULT_TIMEOUT_MS + mb * RESULT_TIMEOUT_PER_MB_MS)
        .coerceIn(MIN_RESULT_TIMEOUT_MS, MAX_RESULT_TIMEOUT_MS)
  }

  /**
   * Whether to launch the system confirmation again for the same session. Only when the service
   * never tapped a ready button for it: re-showing an already-accepted session could make
   * Android re-run its commit while the install is in flight.
   */
  fun shouldRepresent(havePendingIntent: Boolean, tapped: Boolean, presentations: Int): Boolean =
      havePendingIntent && !tapped && presentations in 1 until MAX_PRESENTATIONS

  /**
   * Sessions this app owns that no live attempt in this process tracks. At process start every
   * owned session is such an orphan: its result receiver died with the previous process (e.g. the
   * launcher was replaced over adb mid-confirmation), so nobody would ever abandon it.
   */
  fun orphans(ownedSessionIds: Collection<Int>, liveSessionIds: Set<Int>): List<Int> =
      ownedSessionIds.filter { it >= 0 && it !in liveSessionIds }.distinct()

  /** A short, honest one-line description for logs and fleet status. */
  fun describe(outcome: HeadlessInstallOutcome): String =
      when (outcome) {
        HeadlessInstallOutcome.Installed -> "installed (session reported success)"
        is HeadlessInstallOutcome.Failed ->
            "failed: session status ${outcome.status}" +
                (outcome.message?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
        is HeadlessInstallOutcome.ConfirmTimeout ->
            "confirmation never accepted: no session result after ${outcome.waitedMs / 1000}s, " +
                "${outcome.presentations} presentation(s), " +
                (if (outcome.tapped) "Install was tapped" else "Install was never tapped")
        is HeadlessInstallOutcome.Error -> "not committed: ${outcome.reason}"
      }
}
