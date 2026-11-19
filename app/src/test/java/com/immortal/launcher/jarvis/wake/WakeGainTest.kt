/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis.wake

import org.junit.Assert.assertEquals
import org.junit.Test

/** The ×32 pre-gain: little-endian int16 in place, saturating instead of wrapping. */
class WakeGainTest {
  private fun pcmOf(vararg samples: Int) = ByteArray(samples.size * 2).also { out ->
    samples.forEachIndexed { i, s -> out[2 * i] = s.toByte(); out[2 * i + 1] = (s shr 8).toByte() }
  }

  private fun samplesOf(pcm: ByteArray) = (0 until pcm.size / 2).map { (pcm[2 * it + 1].toInt() shl 8) or (pcm[2 * it].toInt() and 0xFF) }

  @Test fun quietSpeechIsRaisedThirtyTwoTimes() {
    val pcm = pcmOf(0, 18, -18, 1000, -1023)
    WakeGain.apply(pcm, pcm.size)
    assertEquals(listOf(0, 576, -576, 32000, -32736), samplesOf(pcm))
  }

  @Test fun loudSpeechSaturatesInsteadOfWrapping() {
    val pcm = pcmOf(1024, -1025, 32767, -32768)
    WakeGain.apply(pcm, pcm.size)
    assertEquals(listOf(32767, -32768, 32767, -32768), samplesOf(pcm))
  }

  @Test fun onlyTheBytesReadAreTouched() {
    val pcm = pcmOf(2, 2)
    WakeGain.apply(pcm, 2)
    assertEquals(listOf(64, 2), samplesOf(pcm))
  }
}
