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
import android.os.SystemClock
import android.util.Log
import androidx.annotation.MainThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Carries out the engine's sleep timer ([YtEngineState.sleep], rules in [MusicSleepPolicy]): ticks
 * while armed, fades STREAM_MUSIC to silence over the last [MusicSleepPolicy.FADE_MS], pauses, then
 * restores the volume the room had. Pause, Clear or Move clear the timer in the reducer; a fade in
 * progress then restores the volume at once. All state is on the main thread.
 */
internal object MusicSleepDriver {
  private const val TAG = "HearthpaneSleepTimer"
  private const val TICK_MS = 500L
  /** The official player needs a moment to actually stop before the volume comes back. */
  private const val RESTORE_DELAY_MS = 1_000L

  private val main = Handler(Looper.getMainLooper())
  private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
  private var volume: MusicVolume? = null
  /** The step the fade started from, while fading or waiting to restore it. */
  private var fadeFrom: Int? = null
  private val tick = Runnable { onTick() }

  fun install(context: Context) {
    if (volume != null) return
    volume = MusicVolume(context.applicationContext)
    scope.launch {
      YouTubeMusicEngine.state.map { it.sleep }.distinctUntilChanged().collect { timer -> onTimer(timer) }
    }
  }

  /** Moon button / phone: arms [choice] from now ([MusicSleepChoice.OFF] cancels). */
  fun set(choice: MusicSleepChoice) =
      YouTubeMusicEngine.dispatch(YtAction.SetSleep(MusicSleepPolicy.arm(choice, System.currentTimeMillis())))

  @MainThread
  private fun onTimer(timer: MusicSleepTimer?) {
    main.removeCallbacks(tick)
    if (timer != null) {
      main.post(tick)
      return
    }
    val from = fadeFrom ?: return
    main.postDelayed({ restore(from) }, RESTORE_DELAY_MS)
  }

  @MainThread
  private fun onTick() {
    val s = YouTubeMusicEngine.state.value
    val timer = s.sleep ?: return
    val length = MusicV3NowPlaying.durationMs(s)
    val remaining = if (length > 0) length - MusicV3NowPlaying.livePositionMs(s, SystemClock.elapsedRealtime()) else null
    when (val phase = MusicSleepPolicy.phase(timer, System.currentTimeMillis(), remaining)) {
      MusicSleepPhase.Waiting -> Unit
      is MusicSleepPhase.Fading -> if (s.isPlaying) fade(phase.fraction)
      MusicSleepPhase.Stop -> {
        Log.i(TAG, "sleep timer finished; pausing")
        // Pause clears the timer; [onTimer] then restores the volume.
        YouTubeMusicEngine.pause()
        return
      }
    }
    main.postDelayed(tick, TICK_MS)
  }

  private fun fade(fraction: Float) {
    val v = volume ?: return
    val from = fadeFrom ?: v.read().step.also { fadeFrom = it }
    val target = MusicSleepPolicy.fadeStep(from, fraction)
    if (v.read().step > target) v.setStep(target)
  }

  private fun restore(step: Int) {
    if (fadeFrom != step) return // already restored
    fadeFrom = null
    volume?.setStep(step)
  }
}
