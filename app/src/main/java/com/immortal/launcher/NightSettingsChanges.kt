/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.Intent

/** One package-scoped notification per applied batch; retained frames read validated storage. */
internal object NightSettingsChanges {
  const val ACTION = "com.immortal.launcher.NIGHT_SETTINGS_CHANGED"
  private val keys = setOf("darkRoomClock", "darkRoomLux", "nightClockBrightness", "nightClockTone")

  fun notifyApplied(context: Context, appliedKeys: Collection<String>): Boolean {
    if (appliedKeys.none { it in keys }) return false
    return runCatching {
      context.sendBroadcast(Intent(ACTION).setPackage(context.packageName))
      true
    }.getOrDefault(false)
  }
}
