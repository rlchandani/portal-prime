/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import kotlin.math.roundToInt

/** The moon button's choices; [minutes] is the phone wire value (-1 = end of song, 0 = off). */
enum class MusicSleepChoice(val minutes: Int, val label: String) {
  OFF(0, "Off"),
  MIN_15(15, "15 min"),
  MIN_30(30, "30 min"),
  MIN_60(60, "60 min"),
  END_OF_SONG(-1, "End of song");

  companion object {
    fun fromMinutes(minutes: Int): MusicSleepChoice? = entries.firstOrNull { it.minutes == minutes }
  }
}

/** An armed sleep timer: stop at wall-clock [untilMs], or (null) when the current song ends. Immutable. */
data class MusicSleepTimer(val choice: MusicSleepChoice, val untilMs: Long?) {
  val endOfSong: Boolean get() = choice == MusicSleepChoice.END_OF_SONG
}

/** Where an armed timer is, given the clock and the current song. */
internal sealed interface MusicSleepPhase {
  data object Waiting : MusicSleepPhase
  /** Volume is fading; [fraction] 0 → 1 over [MusicSleepPolicy.FADE_MS]. */
  data class Fading(val fraction: Float) : MusicSleepPhase
  /** Pause now and restore the volume. */
  data object Stop : MusicSleepPhase
}

/**
 * Pure sleep-timer rules. The last [FADE_MS] before the stop fade the volume to silence; then the
 * music pauses and the volume is restored. "End of song" fades over the song's last seconds and the
 * engine stops instead of advancing ([YtPlayerEventReducer]); [END_GRACE_MS] after the song should
 * have ended the timer pauses anyway (e.g. the end event never came).
 */
internal object MusicSleepPolicy {
  const val FADE_MS = 10_000L
  const val END_GRACE_MS = 3_000L
  private const val MINUTE_MS = 60_000L

  fun arm(choice: MusicSleepChoice, nowMs: Long): MusicSleepTimer? = when (choice) {
    MusicSleepChoice.OFF -> null
    MusicSleepChoice.END_OF_SONG -> MusicSleepTimer(choice, untilMs = null)
    else -> MusicSleepTimer(choice, untilMs = nowMs + choice.minutes * MINUTE_MS)
  }

  /** The choice shown as selected. */
  fun choice(timer: MusicSleepTimer?): MusicSleepChoice = timer?.choice ?: MusicSleepChoice.OFF

  /**
   * [remainingSongMs] = song length minus position, or null when the length isn't known (then an
   * end-of-song timer waits for the engine's end event).
   */
  fun phase(timer: MusicSleepTimer, nowMs: Long, remainingSongMs: Long?): MusicSleepPhase {
    val endAt = if (timer.endOfSong) remainingSongMs?.let { nowMs + it } else timer.untilMs
    endAt ?: return MusicSleepPhase.Waiting
    val stopAt = if (timer.endOfSong) endAt + END_GRACE_MS else endAt
    val fadeFrom = endAt - FADE_MS
    return when {
      nowMs >= stopAt -> MusicSleepPhase.Stop
      nowMs >= fadeFrom -> MusicSleepPhase.Fading(((nowMs - fadeFrom).toFloat() / FADE_MS).coerceIn(0f, 1f))
      else -> MusicSleepPhase.Waiting
    }
  }

  /** The volume step at [fraction] of the fade from [startStep] down to silence. */
  fun fadeStep(startStep: Int, fraction: Float): Int =
      (startStep.coerceAtLeast(0) * (1f - fraction.coerceIn(0f, 1f))).roundToInt()

  /** "Stops in 23 min" (rounded up), "Stops in 1 min", "Stops after this song"; null when off. */
  fun label(timer: MusicSleepTimer?, nowMs: Long): String? {
    timer ?: return null
    if (timer.endOfSong) return "Stops after this song"
    val left = ((timer.untilMs ?: nowMs) - nowMs).coerceAtLeast(0L)
    val minutes = ((left + MINUTE_MS - 1) / MINUTE_MS).coerceAtLeast(1L)
    return "Stops in $minutes min"
  }
}
