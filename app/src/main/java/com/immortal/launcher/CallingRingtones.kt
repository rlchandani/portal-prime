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
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The four ringtones and the drop-in chime, synthesised on the Portal (no audio files). Each
 * tone is one loop period of 16-bit mono PCM: a few decaying partials, then a pause. Pure, so
 * the shape (length, loudness ceiling, silence gap) is JVM-tested.
 */
internal object CallingRingtones {
  const val SAMPLE_RATE = 22_050
  /** Peak sample as a share of full scale: headroom so the Portal speaker never clips. */
  const val PEAK = 0.6

  private data class Note(val startS: Double, val hz: Double, val decayS: Double, val gain: Double,
                          val partials: List<Pair<Double, Double>> = listOf(1.0 to 1.0))

  private val warm = listOf(1.0 to 1.0, 2.0 to 0.35, 3.0 to 0.12)
  private val bell = listOf(1.0 to 1.0, 2.76 to 0.45, 5.4 to 0.2)
  private val wood = listOf(1.0 to 1.0, 4.0 to 0.3, 9.9 to 0.08)

  private fun notes(tone: CallRingtone): Pair<List<Note>, Double> = when (tone) {
    // Two warm notes, a falling fourth, then a long breath.
    CallRingtone.HEARTH -> listOf(Note(0.0, 659.3, 0.55, 1.0, warm), Note(0.42, 493.9, 0.9, 0.95, warm)) to 3.0
    CallRingtone.WIND -> listOf(Note(0.0, 1046.5, 1.2, 0.6, bell), Note(0.28, 1318.5, 1.1, 0.5, bell),
        Note(0.61, 1174.7, 1.4, 0.55, bell)) to 3.4
    CallRingtone.MARIMBA -> listOf(Note(0.0, 392.0, 0.28, 1.0, wood), Note(0.16, 523.3, 0.28, 0.9, wood),
        Note(0.32, 659.3, 0.32, 0.9, wood), Note(0.62, 523.3, 0.4, 0.8, wood)) to 2.6
    // Old phone: 440 + 480 Hz with a 20 Hz bell tremolo, 1.2 s on, then quiet.
    CallRingtone.CLASSIC -> listOf(Note(0.0, 440.0, 4.0, 0.5, listOf(1.0 to 1.0, 480.0 / 440.0 to 1.0))) to 3.6
  }

  /** One loop period of [tone]. */
  fun render(tone: CallRingtone): ShortArray {
    val (list, periodS) = notes(tone)
    val classic = tone == CallRingtone.CLASSIC
    return synth(list, periodS) { t -> if (classic) classicEnvelope(t) else 1.0 }
  }

  /** The drop-in chime: a short rising two-note "hello", played once. */
  fun chime(): ShortArray = synth(listOf(Note(0.0, 784.0, 0.5, 1.0, warm), Note(0.22, 1046.5, 0.8, 0.9, warm)), 1.6) { 1.0 }

  private fun classicEnvelope(t: Double): Double {
    if (t > CLASSIC_ON_S) return 0.0
    val tremolo = if (sin(2 * PI * CLASSIC_TREMOLO_HZ * t) >= 0) 1.0 else 0.35
    val edge = minOf(1.0, t / FADE_S, (CLASSIC_ON_S - t) / FADE_S)
    return tremolo * edge
  }

  private fun synth(notes: List<Note>, periodS: Double, envelope: (Double) -> Double): ShortArray {
    val n = (periodS * SAMPLE_RATE).toInt()
    val raw = DoubleArray(n)
    for (note in notes) {
      val start = (note.startS * SAMPLE_RATE).toInt()
      for (i in start until n) {
        val t = (i - start).toDouble() / SAMPLE_RATE
        val attack = minOf(1.0, t / ATTACK_S)
        val decay = exp(-t / note.decayS)
        if (decay < SILENT) break
        var v = 0.0
        for ((ratio, gain) in note.partials) v += gain * sin(2 * PI * note.hz * ratio * t)
        raw[i] += v * note.gain * attack * decay
      }
    }
    for (i in 0 until n) raw[i] *= envelope(i.toDouble() / SAMPLE_RATE)
    val peak = raw.maxOf { kotlin.math.abs(it) }.takeIf { it > 0 } ?: 1.0
    val scale = PEAK * Short.MAX_VALUE / peak
    // Fade the last few ms so the loop point never clicks.
    val tail = (FADE_S * SAMPLE_RATE).toInt()
    return ShortArray(n) { i ->
      val fade = if (i >= n - tail) (n - i).toDouble() / tail else 1.0
      (raw[i] * scale * fade).toInt().toShort()
    }
  }

  private const val ATTACK_S = 0.006
  private const val SILENT = 0.001
  private const val FADE_S = 0.02
  private const val CLASSIC_ON_S = 1.2
  private const val CLASSIC_TREMOLO_HZ = 20.0
}

/** Plays the ring (looping) and the drop-in chime at the configured ring volume. Main thread. */
internal object CallRinger {
  private const val TAG = "HearthpaneCalling"
  private var track: AudioTrack? = null

  fun ring(context: Context) {
    val config = CallingConfig.load(context)
    play(CallingRingtones.render(config.tone), config.ringVolume, loop = true)
  }

  fun chime(context: Context) = play(CallingRingtones.chime(), CallingConfig.load(context).ringVolume, loop = false)

  /** Settings › Ringtone preview: one period of [tone]. */
  fun preview(context: Context, tone: CallRingtone) =
      play(CallingRingtones.render(tone), CallingConfig.load(context).ringVolume, loop = false)

  fun stop() {
    val t = track ?: return
    track = null
    runCatching { t.stop() }
    runCatching { t.release() }
  }

  private fun play(pcm: ShortArray, volumePct: Int, loop: Boolean) {
    stop()
    if (volumePct <= 0) return
    runCatching {
      val t = AudioTrack.Builder()
          .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
              .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
          .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
              .setSampleRate(CallingRingtones.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
          .setTransferMode(AudioTrack.MODE_STATIC)
          .setBufferSizeInBytes(pcm.size * 2)
          .build()
      t.write(pcm, 0, pcm.size)
      if (loop) t.setLoopPoints(0, pcm.size, -1)
      t.setVolume(volumePct / PERCENT)
      t.play()
      track = t
    }.onFailure { Log.w(TAG, "ring failed", it) }
  }

  private const val PERCENT = 100f
}
