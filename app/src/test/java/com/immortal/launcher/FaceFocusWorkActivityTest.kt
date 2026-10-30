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
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real worker/request/cache lifecycle; no Android host or physical hardware timing claims. */
class FaceFocusWorkActivityTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test fun `hidden before registration defers the one registered callback until resume`() {
    val work = FaceFocusWork()
    val entered = CountDownLatch(1)
    try {
      work.setActive(false)
      assertTrue(work.start { entered.countDown() })
      assertFalse(work.start { fail("Duplicate registration ran") })
      assertFalse("Hidden registration ran analysis", entered.await(150, TimeUnit.MILLISECONDS))

      work.setActive(true)

      assertTrue(entered.await(3, TimeUnit.SECONDS))
    } finally { work.close() }
  }

  @Test fun `pause promptly cancels the captured running request`() {
    val work = FaceFocusWork()
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val captured = work.request
    try {
      work.start {
        entered.countDown()
        awaitIgnoringInterrupts(release)
      }
      assertTrue(entered.await(3, TimeUnit.SECONDS))

      work.setActive(false)

      assertTrue("Pause left the running request current", captured.cancelled)
      assertEquals(1L, release.count)
    } finally { release.countDown(); work.close() }
  }

  @Test fun `paused analysis cannot publish a late cache result`() {
    val work = FaceFocusWork()
    val cache = FaceFocusCache(temporary.root.resolve("late.json"))
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val exited = CountDownLatch(1)
    try {
      work.start { request ->
        entered.countDown()
        awaitIgnoringInterrupts(release)
        request.commit { cache.record("late", "0.2,0.3,0.8") }
        cache.persist()
        exited.countDown()
      }
      assertTrue(entered.await(3, TimeUnit.SECONDS))
      work.setActive(false)
      release.countDown()
      assertTrue(exited.await(3, TimeUnit.SECONDS))

      assertNull("Paused worker committed after cancellation", cache.entry("late"))
      assertFalse(temporary.root.resolve("late.json").exists())
    } finally { release.countDown(); work.close() }
  }

  @Test fun `resume uses a fresh captured request and analyzes only remaining cache entries`() {
    val work = FaceFocusWork()
    val cache = FaceFocusCache(temporary.root.resolve("remaining.json"))
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val resumed = CountDownLatch(1)
    val requests = CopyOnWriteArrayList<FaceFocusRequest>()
    val remaining = CopyOnWriteArrayList<String>()
    try {
      work.start { request ->
        requests.add(request)
        if (requests.size == 1) {
          request.commit { cache.record("complete", "0.2,0.3,0.8") }
          entered.countDown()
          awaitIgnoringInterrupts(release)
          request.commit { cache.record("pending", "") }
        } else {
          listOf("complete", "pending").filter { cache.needsAnalysis(it) }.forEach { url ->
            remaining.add(url)
            request.commit { cache.record(url, "") }
          }
          resumed.countDown()
        }
      }
      assertTrue(entered.await(3, TimeUnit.SECONDS))
      val old = requests.single()
      work.setActive(false)
      work.setActive(true)
      val fresh = work.request
      assertNotSame(old, fresh)
      assertFalse(fresh.cancelled)
      assertFalse(resumed.await(150, TimeUnit.MILLISECONDS))

      release.countDown()

      assertTrue(resumed.await(3, TimeUnit.SECONDS))
      assertSame(fresh, requests[1])
      assertEquals(listOf("pending"), remaining.toList())
      assertEquals("0.2,0.3,0.8", cache.entry("complete"))
      assertEquals("", cache.entry("pending"))
      assertFalse(work.start { fail("Resume reopened registration") })
    } finally { release.countDown(); work.close() }
  }

  @Test fun `resume waits for unsafe SDK task completion before using the shared worker`() {
    val work = FaceFocusWork()
    val sdk = TaskCompletionSource<Boolean>()
    val entered = CountDownLatch(1)
    val pixelsReleased = CountDownLatch(1)
    val resumed = CountDownLatch(1)
    val calls = AtomicInteger()
    try {
      work.start {
        if (calls.incrementAndGet() == 1) {
          val pixels = SlideshowFaceLease("retained pixels") { pixelsReleased.countDown() }
          entered.countDown()
          awaitFaceFocusTask(sdk.task)
          pixels.finish(taskComplete = true)
        } else {
          resumed.countDown()
        }
      }
      assertTrue(entered.await(3, TimeUnit.SECONDS))
      work.setActive(false)
      work.setActive(true)
      assertFalse("Resume reused an unsafe SDK worker", resumed.await(150, TimeUnit.MILLISECONDS))
      assertEquals(1L, pixelsReleased.count)

      sdk.setResult(true)

      assertTrue(pixelsReleased.await(3, TimeUnit.SECONDS))
      assertTrue(resumed.await(3, TimeUnit.SECONDS))
      assertEquals(2, calls.get())
    } finally { sdk.trySetResult(true); work.close() }
  }

  @Test fun `normally completed analysis never reruns on hide and wake`() {
    val work = FaceFocusWork()
    val finalized = CountDownLatch(1)
    val unexpected = CountDownLatch(1)
    val calls = AtomicInteger()
    try {
      work.start { request ->
        if (calls.incrementAndGet() == 1) {
          request.own(AutoCloseable { finalized.countDown() })
        } else {
          unexpected.countDown()
        }
      }
      assertTrue(finalized.await(3, TimeUnit.SECONDS))

      repeat(10) {
        work.setActive(false)
        work.setActive(true)
      }

      assertFalse(unexpected.await(150, TimeUnit.MILLISECONDS))
      assertEquals(1, calls.get())
      assertFalse(work.start { fail("Completed registration reopened") })
    } finally { work.close() }
  }

  @Test fun `repeated pause and wake keeps only the latest resumed request`() {
    val work = FaceFocusWork()
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val resumed = CountDownLatch(1)
    val releaseResumed = CountDownLatch(1)
    val requests = CopyOnWriteArrayList<FaceFocusRequest>()
    try {
      work.start { request ->
        requests.add(request)
        if (requests.size == 1) {
          entered.countDown()
          awaitIgnoringInterrupts(release)
        } else {
          resumed.countDown()
          awaitIgnoringInterrupts(releaseResumed)
        }
      }
      assertTrue(entered.await(3, TimeUnit.SECONDS))
      repeat(30) {
        val old = work.request
        work.setActive(false)
        work.setActive(false)
        assertTrue(old.cancelled)
        work.setActive(true)
        val fresh = work.request
        work.setActive(true)
        assertSame(fresh, work.request)
      }
      val latest = work.request
      assertEquals(1, requests.size)

      release.countDown()

      assertTrue(resumed.await(3, TimeUnit.SECONDS))
      assertEquals(2, requests.size)
      assertSame(latest, requests[1])
      assertFalse("Old finally cancelled the latest request", latest.cancelled)
    } finally { release.countDown(); releaseResumed.countDown(); work.close() }
  }

  @Test fun `old task finally preserves resumed ownership so another pause interrupts it`() {
    val work = FaceFocusWork()
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val resumed = CountDownLatch(1)
    val releaseResumed = CountDownLatch(1)
    val interrupted = CountDownLatch(1)
    val calls = AtomicInteger()
    try {
      work.start {
        if (calls.incrementAndGet() == 1) {
          entered.countDown()
          awaitIgnoringInterrupts(release)
        } else {
          resumed.countDown()
          try { releaseResumed.await() }
          catch (_: InterruptedException) {
            interrupted.countDown()
            awaitIgnoringInterrupts(releaseResumed)
          }
        }
      }
      assertTrue(entered.await(3, TimeUnit.SECONDS))
      work.setActive(false)
      work.setActive(true)
      val fresh = work.request
      release.countDown()
      assertTrue(resumed.await(3, TimeUnit.SECONDS))
      assertFalse(fresh.cancelled)

      work.setActive(false)

      assertTrue(fresh.cancelled)
      assertTrue("Stale finally cleared resumed global ownership",
          interrupted.await(3, TimeUnit.SECONDS))
    } finally { release.countDown(); releaseResumed.countDown(); work.close() }
  }

  @Test fun `paused old handle and its close cannot interrupt another active owner`() {
    val old = FaceFocusWork()
    val next = FaceFocusWork()
    val oldEntered = CountDownLatch(1)
    val releaseOld = CountDownLatch(1)
    val nextEntered = CountDownLatch(1)
    val releaseNext = CountDownLatch(1)
    val nextInterrupted = CountDownLatch(1)
    try {
      old.start {
        oldEntered.countDown()
        awaitIgnoringInterrupts(releaseOld)
      }
      assertTrue(oldEntered.await(3, TimeUnit.SECONDS))
      old.setActive(false)
      next.start {
        nextEntered.countDown()
        try { releaseNext.await() }
        catch (_: InterruptedException) {
          nextInterrupted.countDown()
          awaitIgnoringInterrupts(releaseNext)
        }
      }
      releaseOld.countDown()
      assertTrue(nextEntered.await(3, TimeUnit.SECONDS))

      old.setActive(false)
      old.close()
      old.setActive(true)

      assertFalse(next.request.cancelled)
      assertFalse(nextInterrupted.await(150, TimeUnit.MILLISECONDS))
      next.setActive(false)
      assertTrue(nextInterrupted.await(3, TimeUnit.SECONDS))
    } finally { releaseOld.countDown(); releaseNext.countDown(); old.close(); next.close() }
  }

  @Test fun `close invalidates hidden pending registration and prevents resume`() {
    val work = FaceFocusWork()
    val unexpected = CountDownLatch(1)
    try {
      work.setActive(false)
      assertTrue(work.start { unexpected.countDown() })

      work.close()
      work.setActive(true)
      work.setActive(false)
      work.setActive(true)

      assertTrue(work.request.cancelled)
      assertFalse(unexpected.await(150, TimeUnit.MILLISECONDS))
      assertFalse(work.start { unexpected.countDown() })
    } finally { work.close() }
  }

  @Test fun `pause returns during blocked transport cleanup and resume remains bounded`() {
    val work = FaceFocusWork()
    val tracked = CountDownLatch(1)
    val closing = CountDownLatch(1)
    val releaseClose = CountDownLatch(1)
    val releaseWorker = CountDownLatch(1)
    val resumed = CountDownLatch(1)
    val calls = AtomicInteger()
    val caller = Thread.currentThread()
    var cleanupThread: Thread? = null
    try {
      work.start { request ->
        if (calls.incrementAndGet() == 1) {
          request.own(AutoCloseable {
            cleanupThread = Thread.currentThread()
            closing.countDown()
            awaitIgnoringInterrupts(releaseClose)
          })
          tracked.countDown()
          awaitIgnoringInterrupts(releaseWorker)
        } else {
          resumed.countDown()
        }
      }
      assertTrue(tracked.await(3, TimeUnit.SECONDS))

      work.setActive(false)

      assertTrue(closing.await(3, TimeUnit.SECONDS))
      assertNotSame(caller, cleanupThread)
      assertEquals(1L, releaseClose.count)
      work.setActive(true)
      releaseWorker.countDown()
      assertFalse(resumed.await(150, TimeUnit.MILLISECONDS))
      releaseClose.countDown()
      assertTrue(resumed.await(3, TimeUnit.SECONDS))
    } finally { releaseClose.countDown(); releaseWorker.countDown(); work.close() }
  }

  @Test fun `current callback failure completes boundedly without retrying on wake`() {
    val work = FaceFocusWork()
    val finalized = CountDownLatch(1)
    val unexpected = CountDownLatch(1)
    val calls = AtomicInteger()
    try {
      work.start { request ->
        if (calls.incrementAndGet() == 1) {
          request.own(AutoCloseable { finalized.countDown() })
          throw IllegalStateException("Unavailable analysis")
        } else {
          unexpected.countDown()
        }
      }
      assertTrue(finalized.await(3, TimeUnit.SECONDS))

      work.setActive(false)
      work.setActive(true)

      assertFalse(unexpected.await(150, TimeUnit.MILLISECONDS))
      assertEquals(1, calls.get())
    } finally { work.close() }
  }

  @Test fun `hidden new registration leaves its visible peer current and running`() {
    val visible = FaceFocusWork()
    val hidden = FaceFocusWork()
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val interrupted = CountDownLatch(1)
    val hiddenEntered = CountDownLatch(1)
    try {
      visible.start {
        entered.countDown()
        try { release.await() }
        catch (_: InterruptedException) {
          interrupted.countDown()
          awaitIgnoringInterrupts(release)
        }
      }
      assertTrue(entered.await(3, TimeUnit.SECONDS))
      val visibleRequest = visible.request

      hidden.setActive(false)
      assertTrue(hidden.start { hiddenEntered.countDown() })

      assertFalse("Hidden registration cancelled its visible peer", visibleRequest.cancelled)
      assertFalse(interrupted.await(150, TimeUnit.MILLISECONDS))
      assertEquals(1L, hiddenEntered.count)
      hidden.close()
      assertFalse(visibleRequest.cancelled)
      assertEquals(1L, interrupted.count)
    } finally { release.countDown(); hidden.close(); visible.close() }
  }

  @Test fun `superseded retained host can hide and return with a fresh request`() {
    val retained = FaceFocusWork()
    val preview = FaceFocusWork()
    val entered = CountDownLatch(1)
    val releaseOld = CountDownLatch(1)
    val previewEntered = CountDownLatch(1)
    val releasePreview = CountDownLatch(1)
    val resumed = CountDownLatch(1)
    val releaseResumed = CountDownLatch(1)
    val requests = CopyOnWriteArrayList<FaceFocusRequest>()
    try {
      retained.start { request ->
        requests.add(request)
        if (requests.size == 1) {
          entered.countDown()
          awaitIgnoringInterrupts(releaseOld)
        } else {
          resumed.countDown()
          awaitIgnoringInterrupts(releaseResumed)
        }
      }
      assertTrue(entered.await(3, TimeUnit.SECONDS))
      val old = retained.request
      preview.start {
        previewEntered.countDown()
        awaitIgnoringInterrupts(releasePreview)
      }
      assertTrue(old.cancelled)
      retained.setActive(false) // Superseded host hides without dropping preview's queued work.
      releaseOld.countDown()
      assertTrue(previewEntered.await(3, TimeUnit.SECONDS))
      assertFalse(preview.request.cancelled)

      retained.setActive(true)
      val fresh = retained.request

      assertNotSame("Supersession permanently closed a retained host", old, fresh)
      assertFalse(fresh.cancelled)
      assertTrue(preview.request.cancelled)
      assertFalse(resumed.await(150, TimeUnit.MILLISECONDS))
      releasePreview.countDown()
      assertTrue(resumed.await(3, TimeUnit.SECONDS))
      assertEquals(2, requests.size)
      assertSame(fresh, requests[1])
      assertFalse(fresh.cancelled)
    } finally {
      releaseOld.countDown(); releasePreview.countDown(); releaseResumed.countDown()
      retained.close(); preview.close()
    }
  }

  private fun awaitIgnoringInterrupts(latch: CountDownLatch) {
    while (latch.count > 0) {
      try { latch.await() } catch (_: InterruptedException) { }
    }
  }
}
