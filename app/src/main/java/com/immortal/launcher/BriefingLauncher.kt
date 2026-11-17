/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.util.Log
import java.util.TimeZone

/**
 * Opens the morning briefing at alarm time: called when the alarm or the sunrise wake light is
 * turned off (the briefing follows the wake-up screen instead of covering it). Once a day, only
 * with "Show at alarm time".
 */
internal object BriefingLauncher {
  fun afterWake(context: Context) {
    runCatching {
      val now = System.currentTimeMillis()
      val zone = TimeZone.getDefault()
      val settings = BriefingConfig.load(context)
      if (!BriefingPolicy.opensAfterWake(settings, now, zone, BriefingDayMarks.lastAutoDay(context))) return
      BriefingDayMarks.markAutoShown(context, BriefingPolicy.localDay(now, zone))
      BriefingActivity.open(context)
    }.onFailure { Log.w("ImmortalBriefing", "briefing after wake failed", it) }
  }
}
