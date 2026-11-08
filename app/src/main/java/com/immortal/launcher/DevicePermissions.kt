/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * Live status of the special permissions and controls connection Immortal relies on.
 * Permissions are granted by the
 * provisioning kit (`provision.sh` / `provision.ps1`) over adb, none of them toggleable
 * from the Portal's own UI on Android 9/10. Surfaced as a "Device health" view so a
 * struggling user (or we, debugging) can see at a glance which capability is missing and
 * what it costs them.
 *
 * Each [Check] always reports what it enables; the [degraded] cost and [fix] are only
 * meant to be shown when it's NOT [granted].
 */
object DevicePermissions {

  enum class Repair { RECONNECT_CONTROLS }

  data class Check(
      val title: String,
      val enables: String,
      val granted: Boolean,
      val degraded: String,
      val fix: String,
      val technicalFix: String = "",
      val repair: Repair? = null,
  )

  data class Snapshot(val checks: List<Check>, val adminActive: Boolean)

  fun all(context: Context): List<Check> = snapshot(context).checks

  /** Permission permits recovery; only a connected service makes controls healthy. */
  internal fun controlCheck(connected: Boolean, canRepair: Boolean): Check = Check(
      title = "Calls & remote controls",
      enables = "Opening Meta home for Calls, and using remote navigation and the touchpad.",
      granted = connected,
      degraded = "Calls can't reliably open Meta home; remote navigation and the touchpad are unavailable.",
      fix = if (canRepair) "Use Reconnect controls, then choose Check again if necessary."
          else "Run Calder setup on a computer to restore the controls connection.",
      repair = if (!connected && canRepair) Repair.RECONNECT_CONTROLS else null,
  )

  /** One administrator query supplies both the health row and the uninstall control. */
  fun snapshot(context: Context): Snapshot {
    val adminActive = ScreenControl.isAdminActive(context)
    val canWriteSecureSettings = SettingsGuard.canWriteSecureSettings(context)
    val checks = listOf(
          Check(
              title = "Screen off",
              enables = "Turning the screen off — idle timeout, overnight sleep, and the Home Assistant control.",
              granted = adminActive,
              degraded = "The screen can't be turned off automatically or from Home Assistant; it stays on.",
              fix = "Run Calder setup to restore its screen-lock permission.",
              technicalFix = "dpm set-active-admin ${context.packageName}/com.immortal.launcher.AdminReceiver",
          ),
          Check(
              title = "Now playing",
              enables = "Showing the current track and album art on the screensaver and home header.",
              granted = SettingsGuard.isMediaListenerEnabled(context),
              degraded = "What's playing won't appear on the screensaver or the home header.",
              fix = "Run Calder setup to allow notification access.",
              technicalFix = "cmd notification allow_listener ${context.packageName}/com.immortal.launcher.MediaNotificationListenerService",
          ),
          Check(
              title = "Install apps",
              enables = "Installing and updating apps from the Calder App Store.",
              granted = canInstallApps(context),
              degraded = "The App Store can't install or update apps.",
              fix = "Run Calder setup to allow app installation.",
              technicalFix = "appops set ${context.packageName} REQUEST_INSTALL_PACKAGES allow",
          ),
          Check(
              title = "Frame relaunch",
              enables = "Bringing the photo frame straight back when the system force-wakes the screensaver.",
              granted = Settings.canDrawOverlays(context),
              degraded = "The photo frame may not reappear promptly after the system force-wakes it.",
              fix = "Run Calder setup to allow the photo frame to appear over other apps.",
              technicalFix = "appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow",
          ),
          Check(
              title = "System settings",
              enables = "Keeping the screensaver, status bar, and WiFi-debug settings applied (and self-healing them).",
              granted = canWriteSecureSettings,
              degraded = "Calder can't keep the screensaver/status-bar settings applied or repair them after changes.",
              fix = "Run Calder setup to restore system-settings permission.",
              technicalFix = "pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS",
          ),
          controlCheck(connected = RemoteInput.available(), canRepair = canWriteSecureSettings),
      )
    return Snapshot(checks, adminActive)
  }

  /** Count of capabilities that aren't granted — 0 means a healthy device. */
  fun issueCount(context: Context): Int = all(context).count { !it.granted }

  private fun canInstallApps(context: Context): Boolean =
      runCatching {
            if (Build.VERSION.SDK_INT >= 26) context.packageManager.canRequestPackageInstalls()
            else true
          }
          .getOrDefault(false)
}
