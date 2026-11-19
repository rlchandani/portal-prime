/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Auto-confirms the system package-installer dialog so the fleet agent can install
 * apps **without a human tap** on a non-root Portal. The agent (or the App Store)
 * commits a PackageInstaller session; when the system shows its confirm dialog,
 * this service finds the affirmative button (Install / Update / …) and clicks it.
 *
 * Scoped to the installer package only (see res/xml/install_confirm_accessibility),
 * and it only ever clicks AFFIRMATIVE buttons — never Cancel — so it can't do
 * anything beyond completing an install the agent already initiated. Enabled via
 * `WRITE_SECURE_SETTINGS` by [SettingsGuard.enableInstallConfirm] on provisioned
 * devices; a no-op anywhere it isn't turned on.
 *
 * A tap is only an attempt. It is logged as such, and success or failure comes solely from
 * the session result in [HeadlessInstaller]. [InstallConfirmGate] holds the tap until the
 * button is enabled in a focused window, and the service re-checks on a short timer while armed
 * (window focus changes carry no installer-package event), so a dialog that appears late,
 * resumes after the dream, or is still showing after a tap gets retried until it closes.
 */
class InstallConfirmService : AccessibilityService() {
  private val main = Handler(Looper.getMainLooper())
  private val gate = InstallConfirmGate(SystemClock::elapsedRealtime)
  private val recheck = Runnable { evaluate() }
  private var lastLogged: ConfirmDecision? = null
  private var taps = 0

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    // Only act during a headless/fleet install (armed by HeadlessInstaller). Otherwise this
    // service must stay dormant, or it dismisses the interactive install dialog the user is
    // looking at (Aurora Store / App Store) before they can tap it — the "flash and vanish"
    // STATUS 3 abort. Interactive installs never arm the window.
    if (!InstallAutoConfirm.isArmed()) return
    val pkg = event?.packageName?.toString() ?: return
    if (pkg !in INSTALLER_PKGS) return
    evaluate()
  }

  override fun onInterrupt() {}

  override fun onDestroy() {
    main.removeCallbacks(recheck)
    super.onDestroy()
  }

  private fun evaluate() {
    main.removeCallbacks(recheck)
    if (!InstallAutoConfirm.isArmed()) {
      lastLogged = null
      taps = 0
      return
    }
    val decision = runCatching { tryConfirm() }
        .onFailure { Log.w(TAG, "installer dialog check failed", it) }
        .getOrDefault(ConfirmDecision.NO_DIALOG)
    if (decision != ConfirmDecision.TAP && decision != lastLogged) {
      Log.i(TAG, "installer dialog not tapped yet: $decision")
    }
    lastLogged = decision
    main.postDelayed(recheck, if (decision == ConfirmDecision.NO_DIALOG) IDLE_RECHECK_MS else RECHECK_MS)
  }

  private fun tryConfirm(): ConfirmDecision {
    val root = installerRoot() ?: return gate.decide(false, MISSING)
    val button = affirmativeButton(root)
    // A cached node can say "enabled" for a dialog that has since paused; re-read it now.
    val fresh = button?.refresh() == true
    val snapshot = ConfirmButtonSnapshot(
        found = button != null && fresh,
        enabled = button?.isEnabled == true,
        visibleToUser = button?.isVisibleToUser == true,
        windowFocused = root.window?.isFocused ?: true,
    )
    val decision = gate.decide(true, snapshot)
    if (decision == ConfirmDecision.TAP && button != null) {
      taps++
      if (button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
        InstallAutoConfirm.recordTap()
        Log.i(TAG, "tapped \"${button.text}\" on the installer dialog (tap $taps); " +
            "waiting for the session result")
      } else {
        Log.w(TAG, "installer rejected the tap on \"${button.text}\" (tap $taps)")
      }
    }
    return decision
  }

  /** The installer's window, whether or not it is currently the active one. */
  private fun installerRoot(): AccessibilityNodeInfo? {
    rootInActiveWindow?.takeIf { it.packageName?.toString() in INSTALLER_PKGS }?.let { return it }
    return runCatching { windows }.getOrNull().orEmpty()
        .mapNotNull { runCatching { it.root }.getOrNull() }
        .firstOrNull { it.packageName?.toString() in INSTALLER_PKGS }
  }

  /** AlertController's positive button first, then any affirmative-labelled button. */
  private fun affirmativeButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    root.findAccessibilityNodeInfosByViewId(POSITIVE_BUTTON_ID).orEmpty()
        .firstOrNull { it.isClickable && InstallConfirmLabels.isAffirmative(it.text) }
        ?.let { return it }
    for (label in InstallConfirmLabels.AFFIRMATIVE) {
      root.findAccessibilityNodeInfosByText(label).orEmpty()
          .firstOrNull { it.isClickable && InstallConfirmLabels.isAffirmative(it.text) }
          ?.let { return it }
    }
    return null
  }

  private companion object {
    private const val TAG = "ImmortalFleet"
    val INSTALLER_PKGS =
        setOf("com.android.packageinstaller", "com.google.android.packageinstaller")
    const val POSITIVE_BUTTON_ID = "android:id/button1"
    const val RECHECK_MS = 400L
    const val IDLE_RECHECK_MS = 1_000L
    val MISSING = ConfirmButtonSnapshot(
        found = false, enabled = false, visibleToUser = false, windowFocused = false)
  }
}
