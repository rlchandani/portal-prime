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

class FilmstripTextureWarmupTest {
  /** A manual queue: run() executes the oldest step; close() discards everything pending. */
  private class Queue {
    val delays = mutableListOf<Long>()
    private val pending = ArrayDeque<Pair<Runnable, () -> Unit>>()
    var closed = false
    var reject = false
    fun post(step: Runnable, delay: Long, discard: () -> Unit): Boolean {
      if (closed || reject) return false
      delays.add(delay); pending.addLast(step to discard); return true
    }
    fun run(): Boolean = pending.removeFirstOrNull()?.first?.run()?.let { true } ?: false
    fun drain() { while (run()) Unit }
    fun close() { closed = true; while (pending.isNotEmpty()) pending.removeFirst().second() }
    val size get() = pending.size
  }

  private class Probe {
    val prepared = mutableListOf<String>()
    var ready = 0
    var abandoned = 0
  }

  private fun warmup(q: Queue, p: Probe, steps: List<List<String>>, current: () -> Boolean = { true },
                     prepare: (String) -> Unit = { p.prepared.add(it) }) =
      FilmstripTextureWarmup(steps, prepare, current, q::post, { p.ready++ }, { p.abandoned++ })

  @Test fun uploadsOneStepPerPostThenAdmitsOnce() {
    val q = Queue(); val p = Probe()
    warmup(q, p, listOf(listOf("a", "a-bg"), listOf("b", "b-bg"), listOf("c", "c-bg"))).start()
    assertTrue(q.run())
    assertEquals(listOf("a", "a-bg"), p.prepared)
    assertEquals(0, p.ready)
    q.run()
    assertEquals(listOf("a", "a-bg", "b", "b-bg"), p.prepared)
    q.drain()
    assertEquals(6, p.prepared.size)
    assertEquals(1, p.ready)
    assertEquals(0, p.abandoned)
    // First step at once, spaced steps between uploads, admission right after the last.
    assertEquals(listOf(0L, 34L, 34L, 0L), q.delays)
  }

  @Test fun anEmptyDeckIsAdmittedWithoutUploads() {
    val q = Queue(); val p = Probe()
    warmup(q, p, emptyList()).start()
    q.drain()
    assertEquals(1, p.ready)
    assertTrue(p.prepared.isEmpty())
  }

  @Test fun aSupersededDeckIsAbandonedAtItsNextStepAndNeverAdmitted() {
    val q = Queue(); val p = Probe(); var current = true
    warmup(q, p, listOf(listOf("a"), listOf("b"), listOf("c")), { current }).start()
    q.run()
    current = false
    q.drain()
    assertEquals(listOf("a"), p.prepared)
    assertEquals(0, p.ready)
    assertEquals(1, p.abandoned)
    assertEquals(0, q.size)
  }

  @Test fun closingTheQueueAbandonsExactlyOnce() {
    val q = Queue(); val p = Probe()
    warmup(q, p, listOf(listOf("a"), listOf("b"))).start()
    q.run()
    q.close()
    assertEquals(1, p.abandoned)
    assertEquals(0, p.ready)
    q.close()
    assertEquals(1, p.abandoned)
  }

  @Test fun aRejectedFirstPostAbandons() {
    val q = Queue().apply { reject = true }; val p = Probe()
    warmup(q, p, listOf(listOf("a"))).start()
    assertEquals(1, p.abandoned)
    assertTrue(p.prepared.isEmpty())
    assertEquals(0, p.ready)
  }

  @Test fun aFailedUploadHintStillAdmitsTheDeck() {
    val q = Queue(); val p = Probe()
    warmup(q, p, listOf(listOf("a"), listOf("b")),
        prepare = { if (it == "a") throw IllegalStateException("recycled") else p.prepared.add(it) }).start()
    q.drain()
    assertEquals(listOf("b"), p.prepared)
    assertEquals(1, p.ready)
    assertFalse(p.abandoned > 0)
  }

  @Test fun pausedQueueKeepsTheDelayedOwnedStep() {
    var now = 0L
    val scheduled = mutableListOf<Pair<Runnable, Long>>()
    val ui = PausableFrameCallbacks({ now }, { r, d -> scheduled.add(r to d); true }, { r -> scheduled.removeAll { it.first === r } })
    var discarded = 0; var ran = 0
    assertTrue(ui.postOwnedDelayed(Runnable { ran++ }, 34L) { discarded++ })
    assertEquals(34L, scheduled.single().second)
    now = 10L
    ui.setActive(false)
    assertTrue(scheduled.isEmpty())
    ui.setActive(true)
    assertEquals(24L, scheduled.single().second)
    ui.close()
    assertEquals(1, discarded)
    assertEquals(0, ran)
  }
}
