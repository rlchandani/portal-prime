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
import android.util.Log

/** Fires when a timer's alarm elapses here. A household timer ([SharedTimers]) rings in every
 *  room and shows the "Timer done" card; an older local-only timer rings the chime a few times and
 *  announces its label, as before. Either way the alarm is claimed once and forgotten. */
class TimerReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != TimerScheduler.ACTION_FIRE) return
    val timer = TimerConfig.consumeDue(context, intent.getStringExtra(TimerScheduler.EXTRA_ID)) ?: return
    Log.i(TAG, "timer fired: ${timer.id}")
    if (SharedTimers.onAlarm(context, timer.id)) return
    TimerRingSound.play(context, timer.label)
  }

  private companion object {
    const val TAG = "ImmortalTimer"
  }
}
