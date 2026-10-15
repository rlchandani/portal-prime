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
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Fires when a kitchen timer elapses: rings the chime a few times, announces the
 *  timer's label, and forgets it. A deliberately-set timer rings even during the
 *  chime's quiet hours. */
class TimerReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != TimerScheduler.ACTION_FIRE) return
    val timer = TimerConfig.consumeDue(context, intent.getStringExtra(TimerScheduler.EXTRA_ID)) ?: return
    val label = timer.label.ifBlank { "Timer" }
    Log.i(TAG, "timer fired: $label (${timer.id})")
    ChimePlayer.playTimerRing(context)
    Handler(Looper.getMainLooper())
        .postDelayed({ ChimePlayer.announce(context, "$label timer done") }, 3 * 1400L)
  }

  private companion object {
    const val TAG = "ImmortalTimer"
  }
}
