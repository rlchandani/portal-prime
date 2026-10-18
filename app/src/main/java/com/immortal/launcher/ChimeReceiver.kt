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
import java.util.Calendar

/** Plays the ambient cues from [ChimeScheduler]'s alarms, then re-arms them.
 * All cues are suppressed inside the quiet-hours window. */
class ChimeReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val cfg = ChimeConfig.load(context)
    val now = Calendar.getInstance()
    val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
    val quiet = cfg.quietHoursOn && ChimeConfig.inQuietWindow(nowMin, cfg.quietStartMin, cfg.quietEndMin)

    when (intent.action) {
      ChimeScheduler.ACTION_HOURLY -> {
        if (!quiet) {
          if (cfg.hourlyChimeOn) ChimePlayer.playChime(context)
          // Speak a beat after the chime so they don't overlap.
          if (cfg.spokenTimeOn) {
            android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed({ ChimePlayer.speakTime(context, now) },
                    if (cfg.hourlyChimeOn) 1400 else 0)
          }
          Log.i(TAG, "hourly cue fired (chime=${cfg.hourlyChimeOn} speak=${cfg.spokenTimeOn})")
        } else {
          Log.i(TAG, "hourly cue suppressed (quiet hours)")
        }
        ChimeScheduler.rearmHourly(context)
      }
      ChimeScheduler.ACTION_GOLDEN_SUNRISE,
      ChimeScheduler.ACTION_GOLDEN_SUNSET -> {
        if (!quiet && cfg.goldenHourOn) {
          if (intent.action == ChimeScheduler.ACTION_GOLDEN_SUNRISE)
              ChimePlayer.playSunriseTone(context)
          else ChimePlayer.playSunsetTone(context)
          Log.i(TAG, "golden-hour tone fired (${intent.action})")
        }
        // Share the scheduler's one worker and keep this receiver alive until its job finishes.
        val pending = goAsync()
        ChimeScheduler.requestGoldenHour(context) { pending.finish() }
      }
    }
  }

  private companion object {
    const val TAG = "ImmortalChime"
  }
}
