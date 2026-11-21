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

class CcGpuTrimTest {
  private class FakeMain {
    val queued = mutableListOf<Pair<Long, Runnable>>()
    var trims = 0
    val gpu = CcGpuTrim(DELAY, { ms, r -> queued += ms to r }, { r -> queued.removeAll { it.second === r } }, { trims++ })
    fun runQueued() { val due = queued.toList(); queued.clear(); due.forEach { it.second.run() } }
  }

  @Test fun leavingSchedulesOneTrimAfterTheDelay() {
    val m = FakeMain()
    m.gpu.started()
    m.gpu.stopped()
    assertEquals(listOf(DELAY), m.queued.map { it.first })
    assertTrue(m.gpu.trimPending)
    m.runQueued()
    assertEquals(1, m.trims)
    assertFalse(m.gpu.trimPending)
  }

  @Test fun reopeningBeforeTheDelayKeepsTheCache() {
    val m = FakeMain()
    m.gpu.started(); m.gpu.stopped()
    m.gpu.started()
    assertTrue(m.queued.isEmpty())
    assertFalse(m.gpu.trimPending)
    m.runQueued()
    assertEquals(0, m.trims)
  }

  @Test fun aNewWindowStartingBeforeTheOldStopsDoesNotTrim() {
    val m = FakeMain()
    m.gpu.started()
    m.gpu.started() // a second Control Center (new task) comes up before the first one stops
    m.gpu.stopped()
    assertTrue(m.queued.isEmpty())
    m.gpu.stopped()
    m.runQueued()
    assertEquals(1, m.trims)
  }

  @Test fun repeatedStopsQueueOnlyOneTrim() {
    val m = FakeMain()
    m.gpu.started(); m.gpu.stopped()
    m.gpu.stopped()
    assertEquals(1, m.queued.size)
    m.runQueued()
    assertEquals(1, m.trims)
  }

  @Test fun aTrimThatFiresWhileShownIsSkipped() {
    var started: (() -> Unit)? = null
    var trims = 0
    lateinit var gpu: CcGpuTrim
    // A scheduler that only runs the trim after another open (as if removeCallbacks lost the race).
    gpu = CcGpuTrim(DELAY, { _, r -> started = { gpu.started(); r.run() } }, { }, { trims++ })
    gpu.started(); gpu.stopped()
    started!!.invoke()
    assertEquals(0, trims)
  }

  @Test fun theScreensaverAndControlCenterShareOneCount() {
    val m = FakeMain()
    m.gpu.started() // Control Center
    m.gpu.started() // the screensaver comes over it
    m.gpu.stopped() // Control Center stops under the dream
    assertTrue(m.gpu.anyShown)
    assertTrue(m.queued.isEmpty())
    m.gpu.stopped() // the dream ends on Home
    assertFalse(m.gpu.anyShown)
    m.runQueued()
    assertEquals(1, m.trims)
  }

  private companion object { const val DELAY = 4_000L }
}
