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
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/** Pure mapping between a slider fraction and the device's discrete volume steps. */
object MusicVolumeMath {
  fun clampStep(step: Int, maxStep: Int): Int = step.coerceIn(0, maxStep.coerceAtLeast(0))

  /** Nearest whole step for [fraction] in `[0, 1]`; NaN and out-of-range input clamp. */
  fun stepForFraction(fraction: Float, maxStep: Int): Int {
    if (maxStep <= 0 || fraction.isNaN()) return 0
    return clampStep((fraction.coerceIn(0f, 1f) * maxStep).roundToInt(), maxStep)
  }

  fun fractionForStep(step: Int, maxStep: Int): Float =
      if (maxStep <= 0) 0f else clampStep(step, maxStep).toFloat() / maxStep
}

/** A snapshot of STREAM_MUSIC volume. */
data class MusicVolumeLevel(val step: Int, val maxStep: Int) {
  val fraction: Float
    get() = MusicVolumeMath.fractionForStep(step, maxStep)
}

/**
 * Portal speaker volume over [AudioManager.STREAM_MUSIC]. The audio plays on this device,
 * so this is the true volume of whatever the music screen shows.
 */
class MusicVolume(context: Context) {
  private val appContext = context.applicationContext
  private val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

  fun read(): MusicVolumeLevel {
    val am = audio ?: return MusicVolumeLevel(0, 0)
    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    return MusicVolumeLevel(MusicVolumeMath.clampStep(
        am.getStreamVolume(AudioManager.STREAM_MUSIC), max), max)
  }

  /** Sets an exact step (clamped); returns the level actually applied. */
  fun setStep(step: Int): MusicVolumeLevel {
    val am = audio ?: return MusicVolumeLevel(0, 0)
    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    val target = MusicVolumeMath.clampStep(step, max)
    runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
        .onFailure { Log.w(TAG, "setStreamVolume($target) failed", it) }
    return read()
  }

  /** Snaps [fraction] to the nearest hardware step (Portal has ~15) and applies it. */
  fun setFraction(fraction: Float): MusicVolumeLevel =
      setStep(MusicVolumeMath.stepForFraction(fraction, read().maxStep))

  /**
   * Observes volume changes; [onChange] runs on the main thread. Close the handle to stop.
   *
   * Why the VOLUME_CHANGED broadcast and not a ContentObserver on Settings.System: on API
   * 24–29 AudioService persists stream volume to Settings.System lazily (a delayed
   * MSG_PERSIST_VOLUME) under per-device keys such as `volume_music_speaker`, so an observer
   * fires late, under a key we would have to guess, and not at all for un-persisted changes.
   * `android.media.VOLUME_CHANGED_ACTION` is sent synchronously by AudioService for every
   * stream index change (hardware keys, other apps, our own setStreamVolume) on all of
   * those releases. It is a protected system broadcast, so the not-exported flag still
   * receives it on newer platforms.
   */
  fun observe(onChange: (MusicVolumeLevel) -> Unit): AutoCloseable {
    val main = Handler(Looper.getMainLooper())
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        val stream = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC)
        if (stream != AudioManager.STREAM_MUSIC) return
        val level = read()
        main.post { onChange(level) }
      }
    }
    val registered = runCatching {
      ContextCompat.registerReceiver(appContext, receiver,
          IntentFilter(VOLUME_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
    }.onFailure { Log.w(TAG, "volume receiver registration failed", it) }.isSuccess
    return AutoCloseable {
      main.removeCallbacksAndMessages(null)
      if (registered) {
        runCatching { appContext.unregisterReceiver(receiver) }
            .onFailure { Log.w(TAG, "volume receiver unregister failed", it) }
      }
    }
  }

  private companion object {
    const val TAG = "ImmortalMusicVolume"
    // Hidden AudioManager constants (stable since API 9).
    const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
    const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
  }
}
