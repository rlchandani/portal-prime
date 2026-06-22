/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Plays the timer's "time's up" sound. Rather than the harsh system alarm klaxon, it synthesizes a
 * soft ascending three-note bell chime (A major) that loops gently with a pause between repeats —
 * pleasant but still attention-getting. Holds a brief wake lock so it keeps sounding with the screen
 * off, and auto-silences after [AUTO_SILENCE_MS] (1 min). The UI calls [stop] on a swipe.
 */
object TimerAlarm {

  private const val AUTO_SILENCE_MS = 60_000L
  private const val SAMPLE_RATE = 44_100

  private val handler = Handler(Looper.getMainLooper())
  private var track: AudioTrack? = null
  private var fallback: Ringtone? = null
  private var wakeLock: PowerManager.WakeLock? = null

  val isRinging: Boolean
    get() = track != null || fallback?.isPlaying == true

  fun start(context: Context) {
    val app = context.applicationContext
    if (isRinging) return

    runCatching {
      val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
      wakeLock =
          pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "immortal:timer-alarm").apply {
            setReferenceCounted(false)
            acquire(AUTO_SILENCE_MS + 5_000L)
          }
    }

    val started = runCatching { startChime() }.getOrDefault(false)
    if (!started) runCatching { startFallback(app) }

    handler.postDelayed({ stop(app) }, AUTO_SILENCE_MS)
  }

  /** Soft synthesized bell chime, hardware-looped with a built-in pause between phrases. */
  private fun startChime(): Boolean {
    val samples = buildChime()
    if (samples.isEmpty()) return false
    val bytes = samples.size * 2

    val attrs =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    val format =
        AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

    @Suppress("DEPRECATION")
    val t =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bytes)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        else
            AudioTrack(
                AudioManager.STREAM_ALARM,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bytes,
                AudioTrack.MODE_STATIC,
            )

    t.write(samples, 0, samples.size)
    // Loop the whole buffer (chime + trailing silence) indefinitely.
    t.setLoopPoints(0, samples.size, -1)
    t.play()
    track = t
    return true
  }

  /** A gentle alarm ringtone, used only if PCM synthesis is unavailable on the device. */
  private fun startFallback(app: Context) {
    val uri =
        RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_ALARM)
            ?: return
    fallback =
        RingtoneManager.getRingtone(app, uri)?.apply {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            audioAttributes =
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
          }
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
          play()
        }
  }

  /** Builds one phrase: three soft bell notes (A4 → C#5 → E5) then a short rest, as 16-bit PCM. */
  private fun buildChime(): ShortArray {
    val notes = doubleArrayOf(440.0, 554.37, 659.25)
    val noteSeconds = 0.34
    val restSeconds = 1.3
    val noteLen = (SAMPLE_RATE * noteSeconds).toInt()
    val restLen = (SAMPLE_RATE * restSeconds).toInt()
    val buf = ShortArray(noteLen * notes.size + restLen)
    var i = 0
    for (freq in notes) {
      for (n in 0 until noteLen) {
        val t = n.toDouble() / SAMPLE_RATE
        // Gentle pluck: fast soft attack, slow decay; a quiet 2nd harmonic adds bell shimmer.
        val env = (1.0 - exp(-30.0 * t)) * exp(-3.2 * t)
        val wave = sin(2 * PI * freq * t) * 0.62 + sin(2 * PI * 2 * freq * t) * 0.18
        val v = (wave * env * 0.5 * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767)
        buf[i++] = v.toShort()
      }
    }
    return buf
  }

  fun stop(context: Context) {
    handler.removeCallbacksAndMessages(null)
    runCatching {
      track?.let {
        it.pause()
        it.flush()
        it.stop()
        it.release()
      }
    }
    track = null
    runCatching { fallback?.stop() }
    fallback = null
    runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
    wakeLock = null
    TimerStore.stopRinging(context.applicationContext)
  }
}
