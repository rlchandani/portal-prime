/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis.wake

import kotlin.math.floor
import kotlin.math.ln1p

/**
 * The 40-channel mel filterbank of the TFLite Micro audio frontend (`filterbank.cc` / `filterbank_util.cc`,
 * Apache-2.0), ported bit for bit: float setup in float precision, integer accumulation in wrapping 64-bit
 * arithmetic, and the frontend's own rounding integer square root. Not thread-safe.
 */
internal class MicroFilterbank(spectrumSize: Int) {
  private val channelsPlus1 = CHANNELS + 1
  private val freqStarts = IntArray(channelsPlus1)
  private val weightStarts = IntArray(channelsPlus1)
  private val widths = IntArray(channelsPlus1)
  private val weights: IntArray
  private val unweights: IntArray
  private val startIndex: Int
  private val endIndex: Int
  private val work = LongArray(channelsPlus1)
  private val energy = LongArray(spectrumSize + BLOCK)

  init {
    val centers = FloatArray(channelsPlus1)
    val melLow = toMel(LOWER_HZ)
    val melSpacing = (toMel(UPPER_HZ) - melLow) / channelsPlus1.toFloat()
    for (i in 0 until channelsPlus1) centers[i] = melLow + melSpacing * (i + 1).toFloat()
    val hzPerBin = 0.5f * SAMPLE_RATE / (spectrumSize.toFloat() - 1)
    startIndex = (1.5f + LOWER_HZ / hzPerBin).toInt()
    val actualStarts = IntArray(channelsPlus1)
    val actualWidths = IntArray(channelsPlus1)
    var chanStart = startIndex
    var weightStart = 0
    var needsZeros = false
    for (chan in 0 until channelsPlus1) {
      var freq = chanStart
      while (toMel(freq * hzPerBin) <= centers[chan]) ++freq
      val width = freq - chanStart
      actualStarts[chan] = chanStart
      actualWidths[chan] = width
      if (width == 0) {
        // A channel no bin falls in reads a block of zero weights placed at the front of the arrays.
        freqStarts[chan] = 0
        weightStarts[chan] = 0
        widths[chan] = BLOCK
        if (!needsZeros) {
          needsZeros = true
          for (j in 0 until chan) weightStarts[j] += BLOCK
          weightStart += BLOCK
        }
      } else {
        val alignedStart = (chanStart / ALIGNMENT) * ALIGNMENT
        val alignedWidth = chanStart - alignedStart + width
        val padded = ((alignedWidth - 1) / BLOCK + 1) * BLOCK
        freqStarts[chan] = alignedStart
        weightStarts[chan] = weightStart
        widths[chan] = padded
        weightStart += padded
      }
      chanStart = freq
    }
    weights = IntArray(weightStart)
    unweights = IntArray(weightStart)
    var end = 0
    for (chan in 0 until channelsPlus1) {
      var frequency = actualStarts[chan]
      val offset = frequency - freqStarts[chan]
      val denom = if (chan == 0) melLow else centers[chan - 1]
      for (j in 0 until actualWidths[chan]) {
        val weight = (centers[chan] - toMel(frequency * hzPerBin)) / (centers[chan] - denom)
        val at = weightStarts[chan] + offset + j
        weights[at] = floor(weight * ONE + 0.5f).toInt().toShort().toInt()
        unweights[at] = floor((1.0f - weight) * ONE + 0.5f).toInt().toShort().toInt()
        ++frequency
      }
      if (frequency > end) end = frequency
    }
    endIndex = end
    check(endIndex < spectrumSize) { "filterbank end index above spectrum size" }
  }

  /**
   * Energy of each FFT bin in the band, accumulated into the triangular channels, square-rooted and scaled down by
   * [scaleDownShift]. Writes [CHANNELS] unsigned 32-bit values into [out].
   */
  fun apply(fftR: IntArray, fftI: IntArray, scaleDownShift: Int, out: LongArray) {
    for (i in startIndex until endIndex) {
      // uint32 magnitude stored into an int32 slot, then widened (sign-extended) to uint64 by the accumulator.
      energy[i] = (fftR[i] * fftR[i] + fftI[i] * fftI[i]).toLong()
    }
    var weighted = 0L
    var unweighted = 0L
    for (chan in 0 until channelsPlus1) {
      val mags = freqStarts[chan]
      val w = weightStarts[chan]
      for (j in 0 until widths[chan]) {
        val magnitude = energy[mags + j]
        weighted += weights[w + j].toLong() * magnitude
        unweighted += unweights[w + j].toLong() * magnitude
      }
      work[chan] = weighted
      weighted = unweighted
      unweighted = 0L
    }
    for (i in 0 until CHANNELS) out[i] = (sqrt64(work[i + 1]) ushr scaleDownShift) and UINT32
  }

  companion object {
    const val CHANNELS = 40
    const val BITS = 12
    private const val ONE = (1 shl BITS).toFloat()
    private const val SAMPLE_RATE = 16_000
    private const val LOWER_HZ = 125.0f
    private const val UPPER_HZ = 7500.0f
    private const val ALIGNMENT = 2 // kFilterbankIndexAlignment (4 bytes) / sizeof(int16_t)
    private const val BLOCK = 4 // kFilterbankChannelBlockSize
    private const val UINT32 = 0xFFFF_FFFFL

    private fun toMel(hz: Float): Float = 1127.0f * ln1p(hz / 700.0f)

    /** The frontend's rounding integer square root of an unsigned 32-bit [n]. */
    private fun sqrt32(n: Long): Long {
      if (n == 0L) return 0L
      var num = n
      var res = 0L
      val maxBit = (Integer.numberOfLeadingZeros(n.toInt())) or 1
      var bit = 1L shl (31 - maxBit)
      repeat((31 - maxBit) / 2 + 1) {
        if (num >= res + bit) {
          num -= res + bit
          res = (res shr 1) + bit
        } else {
          res = res shr 1
        }
        bit = bit shr 2
      }
      if (num > res && res != 0xFFFFL) ++res
      return res and 0xFFFFL
    }

    /** The frontend's rounding integer square root of an unsigned 64-bit [n], 32-bit shortcut included. */
    private fun sqrt64(n: Long): Long {
      if (n ushr 32 == 0L) return sqrt32(n)
      var num = n
      var res = 0L
      val maxBit = java.lang.Long.numberOfLeadingZeros(n) or 1
      var bit = 1L shl (63 - maxBit)
      repeat((63 - maxBit) / 2 + 1) {
        if (java.lang.Long.compareUnsigned(num, res + bit) >= 0) {
          num -= res + bit
          res = (res ushr 1) + bit
        } else {
          res = res ushr 1
        }
        bit = bit ushr 2
      }
      if (java.lang.Long.compareUnsigned(num, res) > 0 && res != UINT32) ++res
      return res and UINT32
    }
  }
}
