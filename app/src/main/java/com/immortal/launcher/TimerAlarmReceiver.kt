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

/**
 * Fires when a [TimerStore] timer reaches zero (scheduled via an exact [android.app.AlarmManager]
 * alarm). Marks the timer as ringing and starts the audible alarm — this runs even if neither the
 * home screen nor the screensaver is in the foreground, since AlarmManager wakes the process.
 */
class TimerAlarmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val app = context.applicationContext
    TimerStore.markRinging(app)
    TimerAlarm.start(app)
  }
}
