/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** A fresh read of the installer dialog's affirmative button, taken immediately before a tap. */
internal data class ConfirmButtonSnapshot(
    val found: Boolean,
    val enabled: Boolean,
    val visibleToUser: Boolean,
    /** The dialog's window holds input focus: not covered by the dream, Home or the keyguard. */
    val windowFocused: Boolean,
)

internal enum class ConfirmDecision {
  TAP,
  NO_DIALOG,
  NO_BUTTON,
  DISABLED,
  NOT_VISIBLE,
  NOT_FOCUSED,
  THROTTLED,
}

/**
 * Decides whether [InstallConfirmService] may tap "Install" right now.
 *
 * Android 10's PackageInstallerActivity disables its Install button whenever it is paused
 * (dream or Home on top, keyguard/sleep) and ignores a tap on it then — but the AlertActivity
 * still finishes, so a premature tap closes the dialog WITHOUT accepting the session. The gate
 * therefore only taps an enabled, visible button in a focused window, and spaces repeated taps
 * so a dialog that is still showing gets retried instead of hammered.
 */
internal class InstallConfirmGate(
    private val clock: () -> Long,
    private val minTapIntervalMs: Long = MIN_TAP_INTERVAL_MS,
) {
  private var lastTapAt: Long? = null

  @Synchronized fun decide(dialogShowing: Boolean, button: ConfirmButtonSnapshot): ConfirmDecision {
    val decision = when {
      !dialogShowing -> ConfirmDecision.NO_DIALOG
      !button.found -> ConfirmDecision.NO_BUTTON
      !button.enabled -> ConfirmDecision.DISABLED
      !button.visibleToUser -> ConfirmDecision.NOT_VISIBLE
      !button.windowFocused -> ConfirmDecision.NOT_FOCUSED
      lastTapAt?.let { clock() - it < minTapIntervalMs } == true -> ConfirmDecision.THROTTLED
      else -> ConfirmDecision.TAP
    }
    if (decision == ConfirmDecision.TAP) lastTapAt = clock()
    return decision
  }

  companion object {
    const val MIN_TAP_INTERVAL_MS = 1_500L
  }
}

/** Which button texts count as the affirmative install action. */
internal object InstallConfirmLabels {
  /** Most-specific first. Never "Cancel"; never "OK"/"Done" (final screens, unrelated dialogs). */
  val AFFIRMATIVE = listOf("Install", "Update", "Continue")

  /**
   * Exact label or a longer label that starts with one ("Install anyway"). The old substring
   * match also hit the dialog's question text ("Do you want to install an update…").
   */
  fun isAffirmative(text: CharSequence?): Boolean {
    val label = text?.toString()?.trim().orEmpty()
    if (label.isEmpty()) return false
    return AFFIRMATIVE.any { label.equals(it, ignoreCase = true) || label.startsWith("$it ", ignoreCase = true) }
  }
}
