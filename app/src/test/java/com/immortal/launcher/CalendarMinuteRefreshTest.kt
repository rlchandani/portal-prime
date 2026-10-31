/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class CalendarMinuteRefreshTest {
  private class Fixture(start: Long = 0L) {
    var now = start
    var refreshes = 0
    var posts = 0
    val pending = LinkedHashMap<Runnable, Long>()
    val ticker = CalendarMinuteRefresh(
        clock = { now },
        cancel = { pending.remove(it) },
        schedule = { task, delay -> posts++; pending[task] = now + delay },
        refresh = { refreshes++ })

    fun advanceUntil(end: Long) {
      while (pending.isNotEmpty() && pending.values.min() <= end) {
        val (task, due) = pending.entries.minBy { it.value }.let { it.key to it.value }
        pending.remove(task)
        now = due
        task.run()
      }
      now = end
    }
  }

  @Test fun `one minute schedules one periodic wake instead of sixty`() {
    val f = Fixture()
    f.ticker.run()
    f.advanceUntil(60_000L)
    assertEquals(2, f.refreshes)
    assertEquals(2, f.posts) // Initial scheduling and the first actual minute boundary.
    assertEquals(listOf(120_000L), f.pending.values.toList())
  }

  @Test fun `starting partway through a minute targets its real boundary`() {
    val f = Fixture(59_750L)
    f.ticker.run()
    assertEquals(1, f.refreshes)
    assertEquals(listOf(60_000L), f.pending.values.toList())
    f.advanceUntil(60_000L)
    assertEquals(2, f.refreshes)
  }

  @Test fun `wake after a retained midnight pause refreshes immediately and replaces old delay`() {
    val f = Fixture(86_399_000L)
    f.ticker.run()
    f.now = 86_400_250L
    f.ticker.run() // Host wake, rather than waiting for its frozen prior delay.
    assertEquals(2, f.refreshes)
    assertEquals(1, f.pending.size)
    assertEquals(listOf(86_460_000L), f.pending.values.toList())
  }

  @Test fun `repeated wake in the same minute neither rerenders nor duplicates scheduled work`() {
    val f = Fixture(10L)
    repeat(4) { f.ticker.run(); f.now += 10L }
    assertEquals(1, f.refreshes)
    assertEquals(1, f.pending.size)
    assertEquals(listOf(60_000L), f.pending.values.toList())
  }

  @Test fun `a backwards clock change refreshes and aligns to the corrected minute`() {
    val f = Fixture(240_500L)
    f.ticker.run()
    f.now = 60_250L
    f.ticker.run()
    assertEquals(2, f.refreshes)
    assertEquals(listOf(120_000L), f.pending.values.toList())
  }

  @Test fun `negative wall time uses floor division and a positive boundary delay`() {
    val f = Fixture(-1L)
    f.ticker.run()
    assertEquals(listOf(0L), f.pending.values.toList())
    f.advanceUntil(0L)
    assertEquals(2, f.refreshes)
    assertEquals(listOf(60_000L), f.pending.values.toList())
  }
}
