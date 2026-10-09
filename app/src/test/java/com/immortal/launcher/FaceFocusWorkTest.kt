/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.google.android.gms.tasks.TaskCompletionSource
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FaceFocusWorkTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test fun `close returns before blocked analysis and rejects its late cache result`() {
    val work = FaceFocusWork()
    val cache = FaceFocusCache(temporary.root.resolve("face_focus.json"))
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    val ended = CountDownLatch(1)
    try {
      work.start { request ->
        started.countDown()
        awaitIgnoringInterrupts(release)
        request.commit { cache.record("obsolete", "0.2,0.3,0.8") }
        cache.persist()
        ended.countDown()
      }
      assertTrue(started.await(3, TimeUnit.SECONDS))
      work.close()
      assertEquals(1L, release.count)
      assertEquals(1L, ended.count)
      release.countDown()
      assertTrue(ended.await(3, TimeUnit.SECONDS))
      assertNull(cache.entry("obsolete"))
      assertFalse(temporary.root.resolve("face_focus.json").exists())
      assertFalse(work.start { fail("Closed frame accepted more analysis") })
    } finally { release.countDown(); work.close() }
  }

  @Test fun `rapid new frames keep one running job and only the latest pending job`() {
    val frames = ArrayList<FaceFocusWork>()
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    val finished = CountDownLatch(1)
    val ran = CopyOnWriteArrayList<Int>()
    val commits = CopyOnWriteArrayList<Int>()
    try {
      val old = FaceFocusWork().also { frames.add(it) }
      old.start { request ->
        started.countDown()
        awaitIgnoringInterrupts(release)
        request.commit { commits.add(0) }
      }
      assertTrue(started.await(3, TimeUnit.SECONDS))
      for (i in 1..50) {
        FaceFocusWork().also { frames.add(it) }.start { request ->
          ran.add(i)
          request.commit { commits.add(i) }
          if (i == 50) finished.countDown()
        }
      }
      // Closing the old screen after replacement must not cancel the new screen's job.
      old.close()
      release.countDown()
      assertTrue(finished.await(3, TimeUnit.SECONDS))
      assertEquals(listOf(50), ran.toList())
      assertEquals(listOf(50), commits.toList())
    } finally { release.countDown(); frames.forEach { it.close() } }
  }

  @Test fun `cancel schedules a blocking transport close away from the caller`() {
    val work = FaceFocusWork()
    val tracked = CountDownLatch(1)
    val closing = CountDownLatch(1)
    val releaseClose = CountDownLatch(1)
    val releaseWorker = CountDownLatch(1)
    val ended = CountDownLatch(1)
    val caller = Thread.currentThread()
    var closeThread: Thread? = null
    try {
      work.start { request ->
        val resource = AutoCloseable {
          closeThread = Thread.currentThread()
          closing.countDown()
          awaitIgnoringInterrupts(releaseClose)
        }
        request.own(resource)
        tracked.countDown()
        awaitIgnoringInterrupts(releaseWorker)
        request.release(resource)
        ended.countDown()
      }
      assertTrue(tracked.await(3, TimeUnit.SECONDS))
      work.close()
      assertTrue(closing.await(3, TimeUnit.SECONDS))
      assertNotSame(caller, closeThread)
      assertEquals(1L, releaseClose.count)
      releaseWorker.countDown()
      assertTrue(ended.await(3, TimeUnit.SECONDS))
    } finally { releaseClose.countDown(); releaseWorker.countDown(); work.close() }
  }

  @Test fun `blocked transport cleanup cannot accumulate across replacement jobs`() {
    val old = FaceFocusWork()
    val next = FaceFocusWork()
    val tracked = CountDownLatch(1)
    val closing = CountDownLatch(1)
    val releaseClose = CountDownLatch(1)
    val releaseWorker = CountDownLatch(1)
    val nextStarted = CountDownLatch(1)
    try {
      old.start { request ->
        request.own(AutoCloseable {
          closing.countDown()
          awaitIgnoringInterrupts(releaseClose)
        })
        tracked.countDown()
        awaitIgnoringInterrupts(releaseWorker)
      }
      assertTrue(tracked.await(3, TimeUnit.SECONDS))
      old.close()
      assertTrue(closing.await(3, TimeUnit.SECONDS))
      next.start { nextStarted.countDown() }
      releaseWorker.countDown()
      assertFalse("Another job ran while the prior transport close was stuck",
          nextStarted.await(250, TimeUnit.MILLISECONDS))
      releaseClose.countDown()
      assertTrue(nextStarted.await(3, TimeUnit.SECONDS))
    } finally { releaseClose.countDown(); releaseWorker.countDown(); old.close(); next.close() }
  }

  @Test fun `unfinished SDK task retains pixels and blocks replacement analysis after cancellation`() {
    val task = TaskCompletionSource<Boolean>()
    val old = FaceFocusWork()
    val next = FaceFocusWork()
    val started = CountDownLatch(1)
    val released = CountDownLatch(1)
    val nextStarted = CountDownLatch(1)
    try {
      old.start {
        val pixels = SlideshowFaceLease("owned derivative") { released.countDown() }
        started.countDown()
        awaitFaceFocusTask(task.task)
        pixels.finish(taskComplete = true)
      }
      assertTrue(started.await(3, TimeUnit.SECONDS))
      old.close()
      next.start { nextStarted.countDown() }
      assertEquals(1L, released.count)
      assertEquals(1L, nextStarted.count)
      task.setResult(true)
      assertTrue(released.await(3, TimeUnit.SECONDS))
      assertTrue(nextStarted.await(3, TimeUnit.SECONDS))
    } finally { task.trySetResult(true); old.close(); next.close() }
  }

  private fun awaitIgnoringInterrupts(latch: CountDownLatch) {
    while (latch.count > 0) {
      try { latch.await() } catch (_: InterruptedException) { }
    }
  }
}
