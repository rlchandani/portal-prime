/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one-second level verdict: digital zero (a silenced client) versus a live room, from numbers only. */
class WakeLevelTest {
  private fun pcm(vararg samples: Int): ByteArray = ByteArray(samples.size * 2).also { b ->
    samples.forEachIndexed { i, v -> b[2 * i] = (v and 0xFF).toByte(); b[2 * i + 1] = (v shr 8).toByte() }
  }

  @Test fun pureZeroIsSilentNotMinusInfinity() {
    val level = WakeLevel().add(pcm(0, 0, 0, 0), 8)
    assertNull(level.dbfs)
    assertTrue(level.describe().startsWith("mic silent"))
  }

  @Test fun fullScaleIsZeroDbfsAndNegativeSamplesCount() {
    val level = WakeLevel().add(pcm(-32768, -32768), 4)
    assertEquals(0.0, level.dbfs!!, 1e-6)
    assertEquals(32768, level.peak)
  }

  @Test fun aQuietRoomReadsAsLowButLive() {
    val level = WakeLevel().add(pcm(4, -4, 4, -4), 8)
    assertEquals(-78.3, level.dbfs!!, 0.1)
    assertEquals(4, level.peak)
    assertTrue(level.describe().startsWith("mic live"))
  }

  @Test fun onlyTheReadLengthCountsAndAddingIsImmutable() {
    val empty = WakeLevel()
    val level = empty.add(pcm(100, 100, 100), 4)
    assertEquals(2L, level.samples)
    assertEquals(0L, empty.samples)
  }

  @Test fun completeAfterOneSecond() {
    val chunk = ByteArray(WakeLevel.SAMPLES.toInt())
    val half = WakeLevel().add(chunk, chunk.size)
    assertFalse(half.complete)
    assertTrue(half.add(chunk, chunk.size).complete)
  }
}
