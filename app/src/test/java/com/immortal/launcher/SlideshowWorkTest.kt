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

class SlideshowWorkTest {
  @Test fun rapidRequestsKeepOnlyActiveAndLatestPendingWork() {
    val work = SlideshowWork()
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    val finished = CountDownLatch(1)
    val executed = java.util.concurrent.CopyOnWriteArrayList<Int>()
    try {
      work.submit { current ->
        started.countDown()
        while (release.count > 0) {
          try { release.await() } catch (_: InterruptedException) { /* stalled decoder fixture */ }
        }
        assertFalse(current())
      }
      assertTrue(started.await(2, TimeUnit.SECONDS))
      for (i in 1..50) work.submit { executed.add(i); if (i == 50) finished.countDown() }
      release.countDown()
      assertTrue(finished.await(2, TimeUnit.SECONDS))
      assertEquals(listOf(50), executed.toList())
    } finally { release.countDown(); work.close() }
  }

  @Test fun closeInvalidatesActiveResultAndRejectsFutureRequests() {
    val work = SlideshowWork()
    val started = CountDownLatch(1)
    val ended = CountDownLatch(1)
    var validAfterClose = true
    work.submit { current ->
      started.countDown()
      try { Thread.sleep(10_000) } catch (_: InterruptedException) { }
      validAfterClose = current()
      ended.countDown()
    }
    assertTrue(started.await(2, TimeUnit.SECONDS))
    work.close()
    assertTrue(ended.await(2, TimeUnit.SECONDS))
    assertFalse(validAfterClose)
    assertEquals(-1L, work.submit { fail("Closed worker accepted work") })
  }
}
