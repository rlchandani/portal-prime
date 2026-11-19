/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis.wake

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow

/**
 * The TensorFlow Lite Micro audio frontend (`tensorflow/lite/experimental/microfrontend/lib`, Apache-2.0) with the
 * exact configuration microWakeWord models are trained on: 30 ms Hann window every 10 ms, 512-point FFT, 40 mel
 * channels 125–7500 Hz, noise reduction, PCAN gain control and log scale. A bit-exact port (checked against the C
 * library in `MicroFrontendTest`), so the model's accuracy carries over.
 *
 * Feed 10 ms of 16 kHz mono PCM at a time; each call after the first 30 ms yields one 40-value feature frame
 * (uint16 values). Samples are read and forgotten: nothing here keeps audio beyond the 30 ms window. Stateful
 * DSP by nature, so not thread-safe — the capture thread owns one instance.
 */
internal class MicroFrontend {
  private val windowCoefficients = IntArray(WINDOW) { i ->
    val arg = Math.PI.toFloat() * 2.0f / WINDOW.toFloat()
    val value = 0.5f - 0.5f * cos(arg * (i.toFloat() + 0.5f))
    floor(value * (1 shl WINDOW_BITS) + 0.5f).toInt().toShort().toInt()
  }
  private val windowInput = IntArray(WINDOW)
  private var windowUsed = 0
  private val windowOutput = IntArray(WINDOW)
  private val fftInput = IntArray(MicroFft.SIZE)
  private val fftR = IntArray(SPECTRUM)
  private val fftI = IntArray(SPECTRUM)
  private val fft = MicroFft()
  private val filterbank = MicroFilterbank(SPECTRUM)
  private val signal = LongArray(MicroFilterbank.CHANNELS)
  private val noiseEstimate = LongArray(MicroFilterbank.CHANNELS)
  private val gainLut = buildGainLut()

  /**
   * Process the [STEP] samples of [pcm] starting at [offset] (little-endian int16 bytes). Returns true and fills
   * [features] (40 values) once a full window is available.
   */
  fun process(pcm: ByteArray, offset: Int, features: IntArray): Boolean {
    for (i in 0 until STEP) {
      val lo = pcm[offset + 2 * i].toInt() and BYTE
      val hi = pcm[offset + 2 * i + 1].toInt()
      windowInput[windowUsed + i] = (hi shl 8) or lo
    }
    windowUsed += STEP
    if (windowUsed < WINDOW) return false
    val maxAbs = applyWindow()
    System.arraycopy(windowInput, STEP, windowInput, 0, WINDOW - STEP)
    windowUsed -= STEP
    val inputShift = 15 - msb(maxAbs.toLong())
    for (i in 0 until WINDOW) fftInput[i] = MicroFft.s16((windowOutput[i] and UINT16) shl inputShift)
    fftInput.fill(0, WINDOW, MicroFft.SIZE)
    fft.compute(fftInput, fftR, fftI)
    filterbank.apply(fftR, fftI, inputShift, signal)
    reduceNoise()
    controlGain()
    logScale(features)
    return true
  }

  /** Back to silence, as after `FrontendReset`: a resumed capture starts from a clean window and noise floor. */
  fun reset() {
    windowInput.fill(0)
    windowOutput.fill(0)
    windowUsed = 0
    noiseEstimate.fill(0)
  }

  private fun applyWindow(): Int {
    var maxAbs = 0
    for (i in 0 until WINDOW) {
      val value = MicroFft.s16((windowInput[i] * windowCoefficients[i]) shr WINDOW_BITS)
      windowOutput[i] = value
      val abs = MicroFft.s16(if (value < 0) -value else value)
      if (abs > maxAbs) maxAbs = abs
    }
    return maxAbs
  }

  private fun reduceNoise() {
    for (i in 0 until MicroFilterbank.CHANNELS) {
      val smoothing = if ((i and 1) == 0) EVEN_SMOOTHING else ODD_SMOOTHING
      val scaledUp = (signal[i] shl SMOOTHING_BITS) and UINT32
      var estimate = ((scaledUp * smoothing + noiseEstimate[i] * (NR_ONE - smoothing)) ushr NR_BITS) and UINT32
      noiseEstimate[i] = estimate
      if (estimate > scaledUp) estimate = scaledUp
      val floor = ((signal[i] * MIN_SIGNAL_REMAINING) ushr NR_BITS) and UINT32
      val subtracted = (scaledUp - estimate) ushr SMOOTHING_BITS
      signal[i] = if (subtracted > floor) subtracted else floor
    }
  }

  private fun controlGain() {
    for (i in 0 until MicroFilterbank.CHANNELS) {
      val gain = wideDynamic(noiseEstimate[i]).toLong() and UINT32
      val snr = ((signal[i].toULong() * gain.toULong()) shr SNR_SHIFT).toLong() and UINT32
      signal[i] = shrink(snr)
    }
  }

  private fun wideDynamic(x: Long): Int {
    if (x <= 2) return gainLut[x.toInt()]
    val interval = msb(x)
    val at = 4 * interval - 6
    val shifted = if (interval < 11) x shl (11 - interval) else x ushr (interval - 11)
    val frac = (shifted and 0x3FF).toInt()
    var result = (gainLut[at + 2] * frac) shr 5
    result += gainLut[at + 1] shl 5
    result *= frac
    result = (result + (1 shl 14)) shr 15
    result += gainLut[at]
    return MicroFft.s16(result)
  }

  private fun logScale(features: IntArray) {
    for (i in 0 until MicroFilterbank.CHANNELS) {
      val value = (signal[i] shl CORRECTION_BITS) and UINT32
      val logged = if (value > 1) log(value) else 0L
      features[i] = if (logged < UINT16) logged.toInt() else UINT16
    }
  }

  companion object {
    const val SAMPLE_RATE = 16_000
    /** Samples per call: 10 ms. */
    const val STEP = 160
    private const val WINDOW = 480
    private const val SPECTRUM = MicroFft.SIZE / 2 + 1
    private const val WINDOW_BITS = 12
    private const val BYTE = 0xFF
    private const val UINT16 = 0xFFFF
    private const val UINT32 = 0xFFFF_FFFFL

    // Noise reduction (kNoiseReductionBits 14): smoothing 0.025 / 0.06, floor 0.05, all as Q14 and truncated.
    private const val NR_BITS = 14
    private const val NR_ONE = 1L shl NR_BITS
    private const val SMOOTHING_BITS = 10
    private const val EVEN_SMOOTHING = 409L
    private const val ODD_SMOOTHING = 983L
    private const val MIN_SIGNAL_REMAINING = 819L

    // PCAN: strength 0.95, offset 80, gain bits 21. Correction = msb(512) - 1 - filterbank bits / 2 = 3.
    private const val PCAN_STRENGTH = 0.95f
    private const val PCAN_OFFSET = 80.0f
    private const val GAIN_BITS = 21
    private const val SNR_BITS = 12
    private const val OUTPUT_BITS = 6
    private const val CORRECTION_BITS = 3
    private const val SNR_SHIFT = GAIN_BITS - CORRECTION_BITS - SNR_BITS
    private const val INPUT_BITS = SMOOTHING_BITS - CORRECTION_BITS
    private const val WIDE_BITS = 32
    private const val LUT_SIZE = 4 * WIDE_BITS - 3

    // Log scale: scale shift 6, kLogScaleLog2 16, kLogSegmentsLog2 7, kLogCoeff 45426.
    private const val SCALE_SHIFT = 6
    private const val LOG_SCALE_LOG2 = 16
    private const val LOG_SEGMENTS_LOG2 = 7
    private const val LOG_COEFF = 45426L
    private const val LOG_ROUND = 1L shl (LOG_SCALE_LOG2 - 1)

    /** Bit position of the highest set bit (1-based), 0 for 0 — `MostSignificantBit32`. */
    private fun msb(x: Long): Int = 32 - Integer.numberOfLeadingZeros(x.toInt())

    private fun shrink(x: Long): Long =
        if (x < (2L shl SNR_BITS)) (x * x) ushr (2 + 2 * SNR_BITS - OUTPUT_BITS)
        else (x ushr (SNR_BITS - OUTPUT_BITS)) - (1L shl OUTPUT_BITS)

    private fun gainFor(x: Long): Int {
      val xf = x.toFloat() / (1L shl INPUT_BITS).toFloat()
      val gain = (1L shl GAIN_BITS).toFloat() * (xf + PCAN_OFFSET).pow(-PCAN_STRENGTH)
      return if (gain > Short.MAX_VALUE) Short.MAX_VALUE.toInt() else MicroFft.s16((gain + 0.5f).toInt())
    }

    private fun buildGainLut(): IntArray {
      val lut = IntArray(LUT_SIZE)
      lut[0] = gainFor(0)
      lut[1] = gainFor(1)
      for (interval in 2..WIDE_BITS) {
        val x0 = 1L shl (interval - 1)
        val x1 = x0 + (x0 shr 1)
        val x2 = if (interval == WIDE_BITS) x0 + (x0 - 1) else 2 * x0
        val y0 = gainFor(x0)
        val y1 = gainFor(x1)
        val y2 = gainFor(x2)
        val a1 = 4 * (y1 - y0) - (y2 - y0)
        val a2 = (y2 - y0) - a1
        val at = 4 * interval - 6
        lut[at] = y0
        lut[at + 1] = MicroFft.s16(a1)
        lut[at + 2] = MicroFft.s16(a2)
      }
      return lut
    }

    private fun log2Fraction(x: Long, log2x: Int): Long {
      var frac = (x - (1L shl log2x)).toInt()
      frac = if (log2x < LOG_SCALE_LOG2) frac shl (LOG_SCALE_LOG2 - log2x) else frac shr (log2x - LOG_SCALE_LOG2)
      val baseSeg = frac ushr (LOG_SCALE_LOG2 - LOG_SEGMENTS_LOG2)
      val segUnit = (1 shl LOG_SCALE_LOG2) shr LOG_SEGMENTS_LOG2
      val c0 = LOG_LUT[baseSeg]
      val c1 = LOG_LUT[baseSeg + 1]
      val relPos = ((c1 - c0) * (frac - segUnit * baseSeg)) shr LOG_SCALE_LOG2
      return (frac + c0 + relPos).toLong() and UINT32
    }

    private fun log(x: Long): Long {
      val integer = msb(x) - 1
      val log2 = ((integer.toLong() shl LOG_SCALE_LOG2) + log2Fraction(x, integer)) and UINT32
      val loge = ((LOG_COEFF * log2 + LOG_ROUND) ushr LOG_SCALE_LOG2) and UINT32
      return (((loge shl SCALE_SHIFT) and UINT32) + LOG_ROUND and UINT32) ushr LOG_SCALE_LOG2
    }

    private val LOG_LUT = intArrayOf(
        0, 224, 442, 654, 861, 1063, 1259, 1450, 1636, 1817, 1992, 2163,
        2329, 2490, 2646, 2797, 2944, 3087, 3224, 3358, 3487, 3611, 3732, 3848,
        3960, 4068, 4172, 4272, 4368, 4460, 4549, 4633, 4714, 4791, 4864, 4934,
        5001, 5063, 5123, 5178, 5231, 5280, 5326, 5368, 5408, 5444, 5477, 5507,
        5533, 5557, 5578, 5595, 5610, 5622, 5631, 5637, 5640, 5641, 5638, 5633,
        5626, 5615, 5602, 5586, 5568, 5547, 5524, 5498, 5470, 5439, 5406, 5370,
        5332, 5291, 5249, 5203, 5156, 5106, 5054, 5000, 4944, 4885, 4825, 4762,
        4697, 4630, 4561, 4490, 4416, 4341, 4264, 4184, 4103, 4020, 3935, 3848,
        3759, 3668, 3575, 3481, 3384, 3286, 3186, 3084, 2981, 2875, 2768, 2659,
        2549, 2437, 2323, 2207, 2090, 1971, 1851, 1729, 1605, 1480, 1353, 1224,
        1094, 963, 830, 695, 559, 421, 282, 142, 0, 0,
    )
  }
}
