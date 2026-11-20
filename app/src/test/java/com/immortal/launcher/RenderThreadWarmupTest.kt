/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cold-start RenderThread warm-up opens one offscreen surface (API 29+) and always releases it. */
class RenderThreadWarmupTest {
  private val opened = mutableListOf<Int>()
  private val closed = mutableListOf<Int>()
  private val scheduled = mutableListOf<Pair<Runnable, Long>>()
  private val reports = mutableListOf<String>()

  private fun warmup(sdk: Int = 29, open: () -> AutoCloseable = { opened += 1; AutoCloseable { closed += 1 } }) =
      OffscreenWarmup(sdk, open, schedule = { r, d -> scheduled += r to d }, report = { what, _ -> reports += what })

  @Test fun belowApi29ItDoesNothing() {
    assertFalse(warmup(sdk = 28).start())
    assertEquals(emptyList<Int>(), opened)
    assertEquals(emptyList<Pair<Runnable, Long>>(), scheduled)
  }

  @Test fun opensOnceAndSchedulesOneRelease() {
    val w = warmup()
    assertTrue(w.start())
    assertFalse(w.start())
    assertEquals(listOf(1), opened)
    assertEquals(listOf(OffscreenWarmup.RELEASE_DELAY_MS), scheduled.map { it.second })
    assertEquals(emptyList<Int>(), closed)
    scheduled.single().first.run()
    assertEquals(listOf(1), closed)
    assertEquals(emptyList<String>(), reports)
  }

  @Test fun anOpenFailureIsReportedAndNothingIsScheduled() {
    val w = warmup(open = { throw IllegalStateException("no EGL") })
    assertFalse(w.start())
    assertEquals(listOf("warm-up skipped"), reports)
    assertEquals(emptyList<Pair<Runnable, Long>>(), scheduled)
    assertFalse("a failed attempt is not retried", w.start())
  }

  @Test fun aReleaseFailureIsReportedNotThrown() {
    val w = warmup(open = { AutoCloseable { throw IllegalStateException("already gone") } })
    assertTrue(w.start())
    scheduled.single().first.run()
    assertEquals(listOf("warm-up release failed"), reports)
  }

  @Test fun theReleaseComesLongAfterHomesFirstFrames() {
    assertTrue(OffscreenWarmup.RELEASE_DELAY_MS >= 5_000L)
  }
}
