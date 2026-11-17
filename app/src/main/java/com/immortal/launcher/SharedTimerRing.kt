/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.TimeZone

/**
 * How a finished timer rings in THIS room. Quiet hours (the chime's [ChimeConfig] window, shared
 * with Call everyone) make the other rooms visual only; the room the timer was started in still
 * rings, because a timer is set on purpose (the kitchen timer's long-standing behavior).
 */
internal object SharedTimerRing {
  /** Second ring for a timer still ringing after this long (sound rooms only). */
  const val REMINDER_MS = 60_000L

  data class Decision(val sound: Boolean, val quiet: Boolean)

  fun decide(quietHours: Boolean, startedHere: Boolean): Decision =
      Decision(sound = !quietHours || startedHere, quiet = quietHours && !startedHere)

  fun isQuiet(settings: ChimeConfig.Settings, nowMs: Long, zone: TimeZone): Boolean =
      DinnerQuiet.isQuiet(settings, nowMs, zone)

  /** Card copy under the name: "Timer done · Kitchen". */
  fun doneLine(t: SharedTimer): String = "Timer done · ${t.room}"

  fun statusLine(decision: Decision): String =
      if (decision.quiet) "Quiet hours, shown without sound" else "Stop here stops it in every room"
}

/** The existing kitchen-timer ring: the chime three times, then the spoken "<name> timer done". */
internal object TimerRingSound {
  private const val RINGS = 3
  private const val RING_GAP_MS = 1_400L

  /** A fresh main-thread [Handler] per ring, as before, so nothing is held between rings. */
  fun play(context: Context, label: String) {
    ChimePlayer.playTimerRing(context, RINGS)
    Handler(Looper.getMainLooper())
        .postDelayed({ ChimePlayer.announce(context, "${label.ifBlank { SharedTimer.DEFAULT_NAME }} timer done") },
            RINGS * RING_GAP_MS)
  }
}
