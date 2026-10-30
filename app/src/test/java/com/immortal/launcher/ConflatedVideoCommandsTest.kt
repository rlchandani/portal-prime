/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ConflatedVideoCommandsTest {
  @Test fun rapidSourceAndPauseRequestsKeepOnlyTheLatestPendingSnapshot() {
    val queue = ArrayList<Runnable>()
    val consumed = ArrayList<String>()
    val commands = ConflatedVideoCommands<String>({ queue.add(it); true }, consumed::add)
    repeat(100) { assertTrue(commands.offer("source:$it")) }
    assertEquals(1, queue.size)
    queue.removeAt(0).run()
    assertEquals(listOf("source:99"), consumed)
    assertTrue(commands.offer("paused"))
    assertTrue(commands.offer("closed"))
    assertEquals(1, queue.size)
    queue.removeAt(0).run()
    assertEquals(listOf("source:99", "closed"), consumed)
  }

  @Test fun blockedReleaseDoesNotLockOutTheCallerOrCreateACommandBacklog() {
    val queue = java.util.concurrent.LinkedBlockingQueue<Runnable>()
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    val consumed = java.util.concurrent.CopyOnWriteArrayList<Int>()
    val commands = ConflatedVideoCommands<Int>({ queue.add(it); true }) {
      consumed.add(it)
      if (it == 0) { started.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)) }
    }
    assertTrue(commands.offer(0))
    val owner = Thread { queue.take().run() }
    owner.start()
    try {
      assertTrue(started.await(2, TimeUnit.SECONDS))
      repeat(100) { assertTrue(commands.offer(it + 1)) }
      assertEquals(1, queue.size)
      release.countDown()
      owner.join(2_000)
      assertFalse(owner.isAlive)
      queue.take().run()
      assertEquals(listOf(0, 100), consumed)
    } finally { release.countDown(); owner.join(2_000) }
  }

  @Test fun rejectedPostDoesNotPretendToHaveQueuedAnUndeliverableCommand() {
    val queue = ArrayList<Runnable>()
    val consumed = ArrayList<Int>()
    var accepting = false
    val commands = ConflatedVideoCommands<Int>({ if (accepting) { queue.add(it); true } else false },
        consumed::add)
    assertFalse(commands.offer(1))
    accepting = true
    assertTrue(commands.offer(2))
    assertEquals(1, queue.size)
    queue.single().run()
    assertEquals(listOf(2), consumed)
  }
}
