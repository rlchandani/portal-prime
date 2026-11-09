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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * "Quieter music during quiet hours": while the chime's quiet hours are on, music (STREAM_MUSIC)
 * never goes above the Settings › Music level. Enforced when playback starts and on every
 * STREAM_MUSIC change (Portal slider, phone, remote, hardware keys all raise the same stream);
 * callers that set a level also pre-cap it via [allowedStep] so the room never hears a blip.
 * STREAM_ALARM (announcements, [AnnouncementVolume]) is never touched. Outside quiet hours: no effect.
 */
internal object MusicNightGuard {
  private const val TAG = "HearthpaneNightVolume"
  private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
  @Volatile private var installed = false

  /** The phone's `nightCap`; [quietNow] when the caller already knows. Null when the setting is off. */
  fun state(context: Context, quietNow: Boolean? = null): MusicNightCap? {
    val settings = MusicNightConfig.load(context)
    if (!settings.quietCap) return null
    val quiet = quietNow ?: DinnerQuiet.isQuiet(ChimeConfig.load(context), System.currentTimeMillis(), TimeZone.getDefault())
    return MusicNightCapMath.state(settings.quietCap, quiet, settings.quietMaxLevel)
  }

  /** The step to apply for a [requested] STREAM_MUSIC step on a device with [deviceMax] steps. */
  fun allowedStep(context: Context, requested: Int, deviceMax: Int): Int =
      MusicNightCapMath.allowed(requested, deviceMax, state(context))

  fun install(context: Context) {
    if (installed) return
    installed = true
    val app = context.applicationContext
    val volume = MusicVolume(app)
    // Process-lifetime observer: the handle is intentionally never closed.
    volume.observe { enforce(app, volume) }
    scope.launch {
      YouTubeMusicEngine.state.map { it.isPlaying }.distinctUntilChanged().collect { playing ->
        if (playing) enforce(app, volume)
      }
    }
  }

  /** Lowers STREAM_MUSIC to the cap when it is above it during quiet hours. */
  private fun enforce(context: Context, volume: MusicVolume) {
    runCatching {
      val now = volume.read()
      val allowed = allowedStep(context, now.step, now.maxStep)
      if (allowed < now.step) {
        Log.i(TAG, "quiet hours: music volume capped at $allowed of ${now.maxStep}")
        volume.setStep(allowed)
      }
    }.onFailure { Log.w(TAG, "night volume check failed: ${it.javaClass.simpleName}") }
  }
}
