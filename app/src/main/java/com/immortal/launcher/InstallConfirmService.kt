/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.accessibilityservice.AccessibilityService
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
 */
class InstallConfirmService : AccessibilityService() {

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    val pkg = event?.packageName?.toString() ?: return
    if (pkg !in INSTALLER_PKGS) return
    val root = rootInActiveWindow ?: return
    runCatching { clickConfirm(root) }
  }

  override fun onInterrupt() {}

  /** Click the first affirmative button we can find. Returns true if we clicked. */
  private fun clickConfirm(root: AccessibilityNodeInfo): Boolean {
    for (label in CONFIRM_LABELS) {
      val matches = root.findAccessibilityNodeInfosByText(label) ?: continue
      for (node in matches) {
        val target = nearestClickable(node)
        if (target != null && target.isEnabled &&
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
          Log.i(TAG, "auto-confirmed install via \"$label\"")
          return true
        }
      }
    }
    return false
  }

  /** A text node is often a label inside a clickable button — walk up to find it. */
  private fun nearestClickable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
    var n = node
    var depth = 0
    while (n != null && depth < 6) {
      if (n.isClickable) return n
      n = n.parent
      depth++
    }
    return null
  }

  private companion object {
    private const val TAG = "ImmortalFleet"
    val INSTALLER_PKGS =
        setOf("com.android.packageinstaller", "com.google.android.packageinstaller")
    // AFFIRMATIVE only, most-specific first. Never "Cancel". Substring + case-insensitive,
    // so "Install" also matches "Install anyway" / "Update".
    val CONFIRM_LABELS = listOf("Install", "Update", "Continue", "Got it", "Done", "OK")
  }
}
