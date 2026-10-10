/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.ArrayDeque
import org.junit.Assert.*
import org.junit.Test

class SlideshowPreparedDeliveryTest {
  private class Fixture {
    val tasks = ArrayDeque<() -> Unit>()
    val dropped = mutableListOf<Int>()
    val admitted = mutableListOf<Int>()
    var accept = true
    val delivery = SlideshowPreparedDelivery<Int>(
        postMain = { task -> if (accept) { tasks.add(task); true } else false },
        discard = { dropped.add(it) }, admit = { admitted.add(it) })
    fun run() { while (tasks.isNotEmpty()) tasks.removeFirst().invoke() }
  }
  @Test fun `worker publication does not attach before main dispatch`() {
    val f = Fixture(); f.delivery.publish(1)
    assertTrue(f.admitted.isEmpty()); assertEquals(1, f.tasks.size)
    f.run(); assertEquals(listOf(1), f.admitted); assertTrue(f.dropped.isEmpty())
  }
  @Test fun `stop after preparation releases unpublished scene once`() {
    val f = Fixture(); f.delivery.publish(1); f.delivery.close(); f.delivery.close(); f.run()
    assertEquals(listOf(1), f.dropped); assertTrue(f.admitted.isEmpty())
  }
  @Test fun `non interruptible late decode is discarded after close`() {
    val f = Fixture(); f.delivery.close(); f.delivery.publish(1); f.run()
    assertEquals(listOf(1), f.dropped); assertEquals(0, f.tasks.size)
  }
  @Test fun `latest ready scene replaces and releases earlier one with one main task`() {
    val f = Fixture(); (1..50).forEach(f.delivery::publish)
    assertEquals((1..49).toList(), f.dropped); assertEquals(1, f.tasks.size)
    f.run(); assertEquals(listOf(50), f.admitted)
  }
  @Test fun `a rejected main post releases scene without stranding publication`() {
    val f = Fixture(); f.accept = false; f.delivery.publish(1)
    assertEquals(listOf(1), f.dropped); f.accept = true; f.delivery.publish(2); f.run()
    assertEquals(listOf(2), f.admitted)
  }
  @Test fun `close does not release pixels already owned by admitted views`() {
    val f = Fixture(); f.delivery.publish(1); f.run(); f.delivery.close()
    assertEquals(listOf(1), f.admitted); assertTrue(f.dropped.isEmpty())
  }
}
