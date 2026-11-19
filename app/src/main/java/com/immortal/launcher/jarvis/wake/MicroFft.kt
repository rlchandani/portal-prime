/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis.wake

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * The 512-point int16 fixed-point real FFT the TFLite Micro audio frontend uses (KISS FFT, `FIXED_POINT=16`),
 * ported bit for bit: every intermediate is truncated to int16 exactly where the C code stores into a
 * `kiss_fft_scalar`, so the wake model sees the features it was trained on. 512 = a 256-point complex FFT
 * (radix 4⁴) plus the real-input split. Not thread-safe: one capture thread owns an instance.
 *
 * Ported from KISS FFT (BSD-3-Clause, Copyright (c) 2003-2010 Mark Borgerding), as vendored in
 * TensorFlow Lite Micro's microfrontend (Apache-2.0). See assets/wake/NOTICES.txt.
 */
internal class MicroFft {
  private val twR = IntArray(HALF)
  private val twI = IntArray(HALF)
  private val superR = IntArray(HALF / 2)
  private val superI = IntArray(HALF / 2)
  private val bufR = IntArray(HALF)
  private val bufI = IntArray(HALF)

  init {
    for (i in 0 until HALF) {
      val phase = -2.0 * PI * i / HALF
      twR[i] = fixed(cos(phase))
      twI[i] = fixed(sin(phase))
    }
    for (i in 0 until HALF / 2) {
      val phase = -PI * ((i + 1).toDouble() / HALF + 0.5)
      superR[i] = fixed(cos(phase))
      superI[i] = fixed(sin(phase))
    }
  }

  /** [input] holds [SIZE] int16 samples; [outR]/[outI] receive bins 0..[SIZE]/2 (int16 values). */
  fun compute(input: IntArray, outR: IntArray, outI: IntArray) {
    work(input, out = 0, from = 0, stride = 1, stage = 0)
    // The DC element packs the sums of the even (real) and odd (imaginary) samples.
    val dcR = divide(bufR[0], DIV_2)
    val dcI = divide(bufI[0], DIV_2)
    outR[0] = s16(dcR + dcI)
    outR[HALF] = s16(dcR - dcI)
    outI[0] = 0
    outI[HALF] = 0
    for (k in 1..HALF / 2) {
      val pkR = divide(bufR[k], DIV_2)
      val pkI = divide(bufI[k], DIV_2)
      val pnkR = divide(bufR[HALF - k], DIV_2)
      val pnkI = divide(s16(-bufI[HALF - k]), DIV_2)
      val f1R = s16(pkR + pnkR)
      val f1I = s16(pkI + pnkI)
      val f2R = s16(pkR - pnkR)
      val f2I = s16(pkI - pnkI)
      val twr = mulR(f2R, f2I, superR[k - 1], superI[k - 1])
      val twi = mulI(f2R, f2I, superR[k - 1], superI[k - 1])
      outR[k] = s16((f1R + twr) shr 1)
      outI[k] = s16((f1I + twi) shr 1)
      outR[HALF - k] = s16((f1R - twr) shr 1)
      outI[HALF - k] = s16((twi - f1I) shr 1)
    }
  }

  /** `kf_work` for the all-radix-4 factorisation of 256: decimate into four quarter-size FFTs, then recombine. */
  private fun work(input: IntArray, out: Int, from: Int, stride: Int, stage: Int) {
    val m = HALF shr (2 * (stage + 1))
    if (m == 1) {
      for (k in 0 until RADIX) {
        val sample = from + k * stride
        bufR[out + k] = input[2 * sample]
        bufI[out + k] = input[2 * sample + 1]
      }
    } else {
      for (k in 0 until RADIX) work(input, out + k * m, from + k * stride, stride * RADIX, stage + 1)
    }
    butterfly4(out, stride, m)
  }

  private fun butterfly4(base: Int, stride: Int, m: Int) {
    for (k in 0 until m) {
      val i0 = base + k
      val i1 = i0 + m
      val i2 = i1 + m
      val i3 = i2 + m
      quarter(i0)
      quarter(i1)
      quarter(i2)
      quarter(i3)
      val t1 = k * stride
      val t2 = 2 * t1
      val t3 = 3 * t1
      val s0R = mulR(bufR[i1], bufI[i1], twR[t1], twI[t1])
      val s0I = mulI(bufR[i1], bufI[i1], twR[t1], twI[t1])
      val s1R = mulR(bufR[i2], bufI[i2], twR[t2], twI[t2])
      val s1I = mulI(bufR[i2], bufI[i2], twR[t2], twI[t2])
      val s2R = mulR(bufR[i3], bufI[i3], twR[t3], twI[t3])
      val s2I = mulI(bufR[i3], bufI[i3], twR[t3], twI[t3])
      val s5R = s16(bufR[i0] - s1R)
      val s5I = s16(bufI[i0] - s1I)
      bufR[i0] = s16(bufR[i0] + s1R)
      bufI[i0] = s16(bufI[i0] + s1I)
      val s3R = s16(s0R + s2R)
      val s3I = s16(s0I + s2I)
      val s4R = s16(s0R - s2R)
      val s4I = s16(s0I - s2I)
      bufR[i2] = s16(bufR[i0] - s3R)
      bufI[i2] = s16(bufI[i0] - s3I)
      bufR[i0] = s16(bufR[i0] + s3R)
      bufI[i0] = s16(bufI[i0] + s3I)
      bufR[i1] = s16(s5R + s4I)
      bufI[i1] = s16(s5I - s4R)
      bufR[i3] = s16(s5R - s4I)
      bufI[i3] = s16(s5I + s4R)
    }
  }

  /** `C_FIXDIV(c, 4)`: scale one element down before its radix-4 stage, so the stage can't overflow int16. */
  private fun quarter(i: Int) {
    bufR[i] = divide(bufR[i], DIV_4)
    bufI[i] = divide(bufI[i], DIV_4)
  }

  companion object {
    const val SIZE = 512
    private const val HALF = SIZE / 2
    private const val RADIX = 4
    private const val FRAC_BITS = 15
    private const val SAMP_MAX = 32767
    private const val DIV_2 = SAMP_MAX / 2
    private const val DIV_4 = SAMP_MAX / 4

    /** A C store into int16: keep the low 16 bits, sign-extended. */
    fun s16(x: Int): Int = x.toShort().toInt()

    private fun round(x: Long): Int = s16(((x + (1L shl (FRAC_BITS - 1))) shr FRAC_BITS).toInt())

    private fun divide(x: Int, k: Int): Int = round(x.toLong() * k)

    private fun mulR(ar: Int, ai: Int, br: Int, bi: Int): Int = round(ar.toLong() * br - ai.toLong() * bi)

    private fun mulI(ar: Int, ai: Int, br: Int, bi: Int): Int = round(ar.toLong() * bi + ai.toLong() * br)

    private fun fixed(v: Double): Int = s16(floor(0.5 + SAMP_MAX * v).toInt())
  }
}
