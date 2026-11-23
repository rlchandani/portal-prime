/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A ring is under way until AlarmSound's auto-silence has passed. */
class AlarmRingingTest {
  private val ring = AlarmRinging.Ring(0, "Alarm", 1_000_000L)

  @Test fun aRingIsActiveUntilItAutoSilences() {
    assertEquals(ring, AlarmRinging.active(ring, 1_000_000L))
    assertEquals(ring, AlarmRinging.active(ring, 1_000_000L + AlarmRinging.RING_MS))
    assertNull(AlarmRinging.active(ring, 1_000_000L + AlarmRinging.RING_MS + 1))
    assertNull(AlarmRinging.active(ring, 999_999L))
    assertNull(AlarmRinging.active(null, 1_000_000L))
  }

  @Test fun aVoiceSnoozeIsKeptWithinItsRange() {
    val deadlines = mutableListOf<Long>()
    AlarmSnooze.schedule({ _, at -> deadlines += at }, { _, _ -> }, 0L, minutes = 15)
    AlarmSnooze.schedule({ _, at -> deadlines += at }, { _, _ -> }, 0L, minutes = 0)
    AlarmSnooze.schedule({ _, at -> deadlines += at }, { _, _ -> }, 0L, minutes = 99)
    assertEquals(listOf(900_000L, 60_000L, 1_800_000L), deadlines)
  }
}
