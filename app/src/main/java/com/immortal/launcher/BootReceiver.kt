/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-asserts our screensaver settings after a reboot. */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
      SettingsGuard.reaffirmAdb(context)
      SettingsGuard.reaffirmScreensaver(context)
      // Alarms don't survive a reboot: re-arm the overnight window and apply it now.
      SleepScheduler.applyOvernightNow(context)
      // Re-open the WiFi fleet channel after the reboot (the whole point of an
      // in-app agent: it comes back without USB, unlike adb-over-WiFi here).
      FleetAgentService.ensureRunning(context)
    }
  }
}
