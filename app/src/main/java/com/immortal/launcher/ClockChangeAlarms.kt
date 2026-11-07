/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/** One clock-event invalidation and independent rearming of saved wall-clock alarms. */
internal object ClockChangeAlarms {
  var revision by mutableIntStateOf(0)
    private set

  fun rearm(action: String?, alarm: (Boolean) -> Unit, wakeLight: () -> Unit,
      chimes: () -> Unit = {}) {
    if (action != Intent.ACTION_TIME_CHANGED && action != Intent.ACTION_TIMEZONE_CHANGED) return
    revision++
    // Recompute regular occurrences without cancelling a user's existing snooze.
    // Each scheduler may fail independently; still attempt the others and refresh labels.
    runCatching { alarm(false) }
    runCatching { wakeLight() }
    runCatching { chimes() }
  }
}

/** Protected system clock events; no preferences are changed by the receiver. */
class ClockChangeReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    ClockChangeAlarms.rearm(intent.action,
        alarm = { cancelSnooze -> AlarmScheduler.reschedule(context, cancelSnooze) },
        wakeLight = { SunriseScheduler.reschedule(context) },
        chimes = { ChimeScheduler.reschedule(context) })
    runCatching { RoutinesFeature.onClockChanged(context, intent.action) }
  }
}
