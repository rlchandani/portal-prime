/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/**
 * Self-healing for our screensaver settings. The stock Aloha launcher rewrites
 * `screensaver_components` / `screensaver_default_component` back to its own
 * SuperFrame whenever it runs, so Immortal re-asserts them on boot and on every
 * resume. Requires `WRITE_SECURE_SETTINGS`, which provisioning grants via
 * `pm grant` — without it this is a silent no-op (settings stay as provisioned).
 *
 * Note: the *home* role can't be reasserted this way (it isn't a secure setting
 * and needs system privilege); provisioning sets it separately via
 * `cmd package set-home-activity`. The stock launcher stays enabled and remains
 * explicitly launchable — that's what the Calls tile bridges to.
 */
object SettingsGuard {

  /**
   * Directly turn the system Dream on/off. Used to keep the screen dark after an
   * idle/overnight sleep — otherwise a docked Portal immediately re-dreams the
   * screensaver and re-lights the screen. [reaffirmScreensaver] (run on the next
   * return to Immortal's home) puts it back per the user's setting.
   */
  fun setSystemScreensaverEnabled(context: Context, on: Boolean) {
    runCatching {
      Settings.Secure.putInt(context.contentResolver, "screensaver_enabled", if (on) 1 else 0)
    }
  }

  fun reaffirmScreensaver(context: Context) {
    runCatching {
      val resolver = context.contentResolver
      // User turned Immortal's screensaver OFF (Screensaver settings): stop hijacking
      // the system Dream and turn it off, so the Portal can sleep on its screen-off
      // timer and the user can run their own setup. We mirror our normal "force on"
      // behaviour as a "force off" so a stray re-enable can't bring it back — which
      // is exactly the "I disable it and it comes back" complaint.
      if (!ScreensaverConfig.load(context).enabled || SleepScheduler.isOvernightNow(context)) {
        Settings.Secure.putInt(resolver, "screensaver_enabled", 0)
        return@runCatching
      }
      val ours = ComponentName(context, PhotoDreamService::class.java).flattenToShortString()
      if (Settings.Secure.getString(resolver, "screensaver_components") != ours) {
        Settings.Secure.putString(resolver, "screensaver_components", ours)
      }
      if (Settings.Secure.getString(resolver, "screensaver_default_component") != ours) {
        Settings.Secure.putString(resolver, "screensaver_default_component", ours)
      }
      Settings.Secure.putInt(resolver, "screensaver_enabled", 1)
    }
  }

  /**
   * Re-enables ADB after boot. The vendor init script
   * (`init.common.usb.rc`) kills adbd on every boot for omni_prod-user
   * builds when `ro.boot.force_enable_usb_adb=0`. Writing `adb_enabled=1`
   * to Settings.Global triggers UsbDeviceManager to reconfigure the USB
   * gadget and restart adbd, restoring connectivity.
   *
   * Also re-enables developer settings so the toggle stays visible in
   * Android Settings as a fallback.
   */
  fun reaffirmAdb(context: Context) {
    runCatching {
      val resolver = context.contentResolver
      Settings.Global.putInt(resolver, "adb_enabled", 1)
      Settings.Global.putInt(resolver, "development_settings_enabled", 1)
    }
  }

  /**
   * Enable [InstallConfirmService] so the fleet agent's installs auto-confirm.
   * APPENDS our component to `enabled_accessibility_services` — the Portal ships
   * Meta accessibility services (presence, key events) in that same list, so we
   * must never overwrite it. Requires `WRITE_SECURE_SETTINGS`; a silent no-op
   * without it. Idempotent.
   */
  fun enableInstallConfirm(context: Context) {
    runCatching {
      val resolver = context.contentResolver
      val comp = ComponentName(context, InstallConfirmService::class.java).flattenToString()
      val current =
          Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
      val parts = current.split(':').filter { it.isNotBlank() }
      if (comp !in parts) {
        Settings.Secure.putString(
            resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (parts + comp).joinToString(":"))
      }
      Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
    }
  }

  /** Whether [InstallConfirmService] is currently enabled (so dialog-mode installs
   *  complete unattended). Lets the fleet dashboard show that a "dialog"-mode device
   *  can still be deployed to without a tap. */
  fun isInstallConfirmEnabled(context: Context): Boolean =
      runCatching {
            val comp = ComponentName(context, InstallConfirmService::class.java).flattenToString()
            (Settings.Secure.getString(
                    context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
                .split(':')
                .any { it.equals(comp, ignoreCase = true) }
          }
          .getOrDefault(false)

  /** True if we hold WRITE_SECURE_SETTINGS (so self-healing is active). */
  fun canWriteSecureSettings(context: Context): Boolean =
      context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
          android.content.pm.PackageManager.PERMISSION_GRANTED
}
