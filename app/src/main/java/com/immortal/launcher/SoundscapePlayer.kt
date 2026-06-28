/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * Procedurally-synthesized ambient soundscapes for the screensaver. Everything is
 * generated on the fly into an [AudioTrack] in streaming mode — no audio assets are
 * bundled and nothing is streamed over the network, so it works fully offline on the
 * Portal and adds nothing to the APK size.
 *
 * The palette is built from coloured noise put through proper filters and envelopes:
 *  - white/pink/brown noise directly;
 *  - rain      = high band wash + a stream of soft band-passed droplets over a low bed;
 *  - ocean     = filtered brown swell with surf hiss that rises on each wave crest;
 *  - fireplace = warm low rumble with band-limited crackle bursts.
 *
 * Output is stereo: slow elements (swell, rumble) are shared, the bright detail
 * (hiss, droplets, crackle) is decorrelated per channel for natural width. A soft
 * tanh limiter replaces hard clipping so nothing rasps.
 *
 * One instance plays one soundscape; call [stop] to release. Not thread-safe across
 * concurrent [start]/[stop] from different threads — drive it from one owner.
 */
class SoundscapePlayer {
  private val TAG = "ImmortalSound"
  private val SAMPLE_RATE = 44100

  @Volatile private var running = false
  private var thread: Thread? = null
  private var track: AudioTrack? = null

  /** Start playing [soundscape] (a `ScreensaverConfig.SOUND_*`) at [volume] (0..100).
   * No-op for SOUND_OFF or an unknown id. Safe to call when already stopped. */
  fun start(soundscape: String, volume: Int) {
    if (soundscape == ScreensaverConfig.SOUND_OFF) return
    stop()
    val gain = (volume.coerceIn(0, 100) / 100f)
    if (gain <= 0f) return
    running = true
    thread = Thread { runCatching { render(soundscape, gain) }.onFailure { Log.w(TAG, "render stopped", it) } }
        .also { it.isDaemon = true; it.start() }
  }

  /** Stop playback and release the track. Idempotent. */
  fun stop() {
    running = false
    thread?.let { runCatching { it.join(500) } }
    thread = null
    track?.let { t -> runCatching { t.stop() }; runCatching { t.release() } }
    track = null
  }

  private fun render(soundscape: String, gain: Float) {
    val minBuf = AudioTrack.getMinBufferSize(
        SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
    val bufFrames = (SAMPLE_RATE / 10) // ~100 ms chunks
    val t = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build())
        .setBufferSizeInBytes(maxOf(minBuf, bufFrames * 4))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
    track = t
    t.play()

    val gen = Generator(soundscape, SAMPLE_RATE)
    val out = ShortArray(bufFrames * 2) // interleaved stereo
    val lr = FloatArray(2)
    // Slow fade-in (~0.4 s) so starting the screensaver doesn't pop.
    var fade = 0f
    val fadeStep = 1f / (SAMPLE_RATE * 0.4f)
    while (running) {
      for (i in 0 until bufFrames) {
        gen.next(lr)
        if (fade < 1f) fade = (fade + fadeStep).coerceAtMost(1f)
        // tanh soft-clip keeps peaks musical instead of rasping at the rails.
        out[i * 2] = toPcm(tanh(lr[0] * gain * fade))
        out[i * 2 + 1] = toPcm(tanh(lr[1] * gain * fade))
      }
      val n = t.write(out, 0, out.size)
      if (n < 0) break
    }
  }

  private fun toPcm(s: Float): Short = (s.coerceIn(-1f, 1f) * 32767f).toInt().toShort()

  /** Pure DSP core: writes the next stereo sample (each in [-1, 1]) into [out].
   *  Everything is gain-staged to sit comfortably below ±0.8 so the outer tanh only
   *  ever rounds off the rare peak — no internal hard-clipping (the old source of the
   *  gritty "disturbance"). */
  private class Generator(private val kind: String, private val rate: Int) {
    private val rnd = Random(System.nanoTime())

    // Pink-noise running state (Paul Kellet's refined method).
    private var b0 = 0f; private var b1 = 0f; private var b2 = 0f
    private var b3 = 0f; private var b4 = 0f; private var b5 = 0f; private var b6 = 0f
    // Brown-noise leaky integrator. Kept well-scaled (no ×6 blow-up) so it never clips.
    private var brown = 0f
    // Body low-pass states for ocean / fireplace.
    private var lpA = 0f
    private var lpB = 0f
    // Per-channel low-pass states (warm rain wash + ocean surf), for stereo width.
    private var rainLpL = 0f; private var rainLpR = 0f
    private var surfLpL = 0f; private var surfLpR = 0f
    // Wave LFOs for the ocean swell (two detuned so it never feels metronomic).
    private var phase1 = 0f
    private var phase2 = 0f
    // Droplet / crackle envelopes (separate L/R for width).
    private var dropL = 0f; private var dropR = 0f
    private var dropFreqL = 0f; private var dropFreqR = 0f
    private var dropPhaseL = 0f; private var dropPhaseR = 0f
    private var crackleL = 0f; private var crackleR = 0f
    private var crackleLpL = 0f; private var crackleLpR = 0f

    private val TWO_PI = (2.0 * Math.PI).toFloat()

    private fun white(): Float = rnd.nextFloat() * 2f - 1f

    private fun pink(): Float {
      val w = white()
      b0 = 0.99886f * b0 + w * 0.0555179f
      b1 = 0.99332f * b1 + w * 0.0750759f
      b2 = 0.96900f * b2 + w * 0.1538520f
      b3 = 0.86650f * b3 + w * 0.3104856f
      b4 = 0.55000f * b4 + w * 0.5329522f
      b5 = -0.7616f * b5 - w * 0.0168980f
      val p = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362f
      b6 = w * 0.115926f
      return p * 0.11f
    }

    /** Brown noise scaled to ~0.3 RMS — comfortably below clipping. */
    private fun brown(): Float {
      brown = 0.99f * brown + white() * 0.05f
      return brown * 0.9f
    }

    fun next(out: FloatArray) {
      when (kind) {
        ScreensaverConfig.SOUND_WHITE -> { out[0] = white() * 0.45f; out[1] = white() * 0.45f }
        ScreensaverConfig.SOUND_PINK -> { out[0] = pink() * 0.8f; out[1] = pink() * 0.8f }
        ScreensaverConfig.SOUND_BROWN -> { val s = brown() * 0.9f; out[0] = s; out[1] = s }
        ScreensaverConfig.SOUND_RAIN -> rain(out)
        ScreensaverConfig.SOUND_OCEAN -> ocean(out)
        ScreensaverConfig.SOUND_FIREPLACE -> fireplace(out)
        else -> { out[0] = 0f; out[1] = 0f }
      }
    }

    /** A warm broadband wash (gently low-passed white, not raw hiss) with occasional
     *  soft droplet pings — a steady, calm rain rather than a buzzing tone. */
    private fun rain(out: FloatArray) {
      out[0] = rainChannel(true)
      out[1] = rainChannel(false)
    }

    private fun rainChannel(left: Boolean): Float {
      // Warm wash: one-pole low-pass on white softens the harsh top end.
      val w = white()
      val wash = if (left) { rainLpL += 0.45f * (w - rainLpL); rainLpL } else { rainLpR += 0.45f * (w - rainLpR); rainLpR }
      var s = wash * 0.5f
      // Sparse soft droplets (~16/sec per channel), each a short decaying sine.
      if (left) {
        if (rnd.nextInt(rate) < 16) { dropL = 0.4f + rnd.nextFloat() * 0.4f; dropFreqL = 2200f + rnd.nextFloat() * 2200f; dropPhaseL = 0f }
        if (dropL > 0.001f) {
          dropPhaseL += TWO_PI * dropFreqL / rate
          s += sin(dropPhaseL.toDouble()).toFloat() * dropL * 0.22f
          dropL *= exp(-32f / rate)
        }
      } else {
        if (rnd.nextInt(rate) < 16) { dropR = 0.4f + rnd.nextFloat() * 0.4f; dropFreqR = 2200f + rnd.nextFloat() * 2200f; dropPhaseR = 0f }
        if (dropR > 0.001f) {
          dropPhaseR += TWO_PI * dropFreqR / rate
          s += sin(dropPhaseR.toDouble()).toFloat() * dropR * 0.22f
          dropR *= exp(-32f / rate)
        }
      }
      return s
    }

    /** Filtered brown swell whose surf hiss rises and falls with each wave. */
    private fun ocean(out: FloatArray) {
      phase1 += TWO_PI * 0.075f / rate // ~13 s wave
      phase2 += TWO_PI * 0.053f / rate // ~19 s wave (beats against it)
      if (phase1 > TWO_PI) phase1 -= TWO_PI
      if (phase2 > TWO_PI) phase2 -= TWO_PI
      val s1 = (sin(phase1.toDouble()).toFloat() + 1f) / 2f
      val s2 = (sin(phase2.toDouble()).toFloat() + 1f) / 2f
      val swell = 0.3f + 0.7f * (0.6f * s1 + 0.4f * s2)
      // Low body: heavily low-passed brown noise (deep rumble).
      lpA += 0.05f * (brown() - lpA)
      val body = lpA * 0.8f
      // Surf hiss, brighter at the crest, decorrelated per channel.
      val crest = swell * swell
      val sL = white().let { surfLpL += 0.5f * (it - surfLpL); surfLpL } * crest * 0.45f
      val sR = white().let { surfLpR += 0.5f * (it - surfLpR); surfLpR } * crest * 0.45f
      out[0] = (body + sL) * swell
      out[1] = (body + sR) * swell
    }

    /** Warm low rumble with random soft (low-passed) crackle pops, per channel. */
    private fun fireplace(out: FloatArray) {
      lpB += 0.04f * (brown() - lpB)
      val rumble = lpB * 0.75f
      out[0] = rumble + crackleChannel(true)
      out[1] = rumble + crackleChannel(false)
    }

    private fun crackleChannel(left: Boolean): Float {
      return if (left) {
        if (rnd.nextInt(rate) < 30) crackleL = 0.6f + rnd.nextFloat() * 0.4f
        crackleL *= exp(-45f / rate)
        val pop = if (crackleL > 0.001f) white() * crackleL else 0f
        crackleLpL += 0.6f * (pop - crackleLpL); crackleLpL * 0.6f
      } else {
        if (rnd.nextInt(rate) < 30) crackleR = 0.6f + rnd.nextFloat() * 0.4f
        crackleR *= exp(-45f / rate)
        val pop = if (crackleR > 0.001f) white() * crackleR else 0f
        crackleLpR += 0.6f * (pop - crackleLpR); crackleLpR * 0.6f
      }
    }
  }
}
