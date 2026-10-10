/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PausableFrameCallbacksTest {
  /** Queues wrappers without Android, and can detach one before lifecycle cancellation. */
  private class FakeTransport {
    private data class Scheduled(val wrapper: Runnable, val due: Long, val delay: Long, val order: Int)
    private val lock = Any()
    private var now = 1_000L
    private var nextOrder = 0
    private val pending = mutableListOf<Scheduled>()
    private val history = mutableListOf<Scheduled>()
    private val cancellations = mutableListOf<Runnable>()
    var accepting = true

    fun clock(): Long = synchronized(lock) { now }
    fun schedule(wrapper: Runnable, delay: Long): Boolean = synchronized(lock) {
      if (!accepting) return@synchronized false
      val scheduled = Scheduled(wrapper, now + delay, delay, nextOrder++)
      pending += scheduled
      history += scheduled
      true
    }
    fun cancel(wrapper: Runnable) = synchronized(lock) {
      cancellations += wrapper
      pending.removeAll { it.wrapper === wrapper }
      Unit
    }
    fun elapse(millis: Long) = synchronized(lock) { now += millis }
    fun takeDue(): Runnable? = synchronized(lock) {
      val next = pending.filter { it.due <= now }
          .minWithOrNull(compareBy<Scheduled> { it.due }.thenBy { it.order })
          ?: return@synchronized null
      pending.remove(next)
      next.wrapper
    }
    fun runDue() {
      repeat(1_000) { (takeDue() ?: return).run() }
      fail("Unexpected immediate recurring loop")
    }
    val wrappers: List<Runnable> get() = synchronized(lock) { history.map { it.wrapper } }
    val delays: List<Long> get() = synchronized(lock) { history.map { it.delay } }
    val pendingCount: Int get() = synchronized(lock) { pending.size }
    val cancelCount: Int get() = synchronized(lock) { cancellations.size }
    fun queue() = PausableFrameCallbacks(clock = ::clock, schedule = ::schedule, cancel = ::cancel)
  }

  @Test fun lifecyclePropertiesCountScheduledAndPausedEntriesButExcludeRunningCallbacks() {
    val transport = FakeTransport()
    val queue = transport.queue()
    val removed = Runnable { fail("Removed callback ran") }
    assertTrue(queue.isActive)
    assertEquals(0, queue.pendingCount)
    queue.post(removed)
    queue.postDelayed(removed, 10)
    assertEquals(2, queue.pendingCount)
    queue.setActive(false)
    assertFalse(queue.isActive)
    assertEquals(2, queue.pendingCount)
    queue.post(Runnable {
      assertTrue(queue.isActive)
      assertEquals(0, queue.pendingCount)
    })
    assertEquals(3, queue.pendingCount)
    queue.removeCallbacks(removed)
    assertEquals(1, queue.pendingCount)
    queue.setActive(true)
    assertTrue(queue.isActive)
    assertEquals(1, queue.pendingCount)
    transport.runDue()
    assertEquals(0, queue.pendingCount)
    queue.postDelayed(removed, 10)
    queue.close()
    queue.setActive(true)
    assertFalse(queue.isActive)
    assertEquals(0, queue.pendingCount)
    assertFalse(queue.post(removed))
    assertEquals(0, queue.pendingCount)
  }

  @Test fun startsActiveAndQueuesCallbacksAtTheirDeadlines() {
    val transport = FakeTransport()
    val queue = transport.queue()
    val calls = mutableListOf<String>()
    assertTrue(queue.post(Runnable { calls += "immediate" }))
    assertTrue(queue.postDelayed(Runnable { calls += "delayed" }, 10))
    assertTrue(calls.isEmpty())
    transport.runDue()
    assertEquals(listOf("immediate"), calls)
    transport.elapse(9)
    transport.runDue()
    assertEquals(listOf("immediate"), calls)
    transport.elapse(1)
    transport.runDue()
    assertEquals(listOf("immediate", "delayed"), calls)
  }

  @Test fun pauseFreezesTheRemainingDeadlineRatherThanRestartingOrCatchingUp() {
    val transport = FakeTransport()
    val queue = transport.queue()
    var calls = 0
    queue.postDelayed(Runnable { calls++ }, 100)
    transport.elapse(40)
    queue.setActive(false)
    assertEquals(0, transport.pendingCount)
    assertEquals(1, transport.cancelCount)
    transport.elapse(5_000)
    transport.runDue()
    assertEquals(0, calls)
    queue.setActive(true)
    assertEquals(listOf(100L, 60L), transport.delays)
    transport.elapse(59)
    transport.runDue()
    assertEquals(0, calls)
    transport.elapse(1)
    transport.runDue()
    assertEquals(1, calls)
  }

  @Test fun duplicatePauseAndResumeDoNotChangeFrozenTimeOrScheduleTwice() {
    val transport = FakeTransport()
    val queue = transport.queue()
    var calls = 0
    queue.postDelayed(Runnable { calls++ }, 100)
    transport.elapse(25)
    queue.setActive(false)
    transport.elapse(900)
    queue.setActive(false)
    transport.elapse(100)
    queue.setActive(true)
    queue.setActive(true)
    assertEquals(listOf(100L, 75L), transport.delays)
    assertEquals(1, transport.cancelCount)
    transport.elapse(74)
    transport.runDue()
    assertEquals(0, calls)
    transport.elapse(1)
    transport.runDue()
    assertEquals(1, calls)
  }

  @Test fun delayIsFrozenAgainFromTheLatestResumeDeadline() {
    val transport = FakeTransport()
    val queue = transport.queue()
    var calls = 0
    queue.postDelayed(Runnable { calls++ }, 100)
    transport.elapse(40)
    queue.setActive(false)
    transport.elapse(1_000)
    queue.setActive(true)
    transport.elapse(15)
    queue.setActive(false)
    transport.elapse(1_000)
    queue.setActive(true)
    assertEquals(listOf(100L, 60L, 45L), transport.delays)
    transport.elapse(44)
    transport.runDue()
    assertEquals(0, calls)
    transport.elapse(1)
    transport.runDue()
    assertEquals(1, calls)
  }

  @Test fun postsWhilePausedRetainFullDelayAndResumeInEnqueueOrder() {
    val transport = FakeTransport()
    val queue = transport.queue()
    val calls = mutableListOf<String>()
    queue.setActive(false)
    assertTrue(queue.postDelayed(Runnable { calls += "first" }, 40))
    assertTrue(queue.postDelayed(Runnable { calls += "second" }, 10))
    assertTrue(queue.post(Runnable { calls += "third" }))
    transport.elapse(10_000)
    transport.runDue()
    assertTrue(calls.isEmpty())
    assertEquals(0, transport.pendingCount)
    queue.setActive(true)
    assertEquals(listOf(40L, 10L, 0L), transport.delays)
    transport.runDue()
    assertEquals(listOf("third"), calls)
    transport.elapse(10)
    transport.runDue()
    assertEquals(listOf("third", "second"), calls)
    transport.elapse(30)
    transport.runDue()
    assertEquals(listOf("third", "second", "first"), calls)
  }

  @Test fun lateWorkerPostIsRetainedWhenTheFrameIsAlreadyHidden() {
    val transport = FakeTransport()
    val queue = transport.queue()
    val worker = Executors.newSingleThreadExecutor()
    val release = CountDownLatch(1)
    var calls = 0
    try {
      val posted = worker.submit<Boolean> {
        assertTrue(release.await(2, TimeUnit.SECONDS))
        queue.post(Runnable { calls++ })
      }
      queue.setActive(false)
      release.countDown()
      assertTrue(posted.get(2, TimeUnit.SECONDS))
      transport.elapse(10_000)
      transport.runDue()
      assertEquals(0, calls)
      assertEquals(0, transport.pendingCount)
      queue.setActive(true)
      transport.runDue()
      assertEquals(1, calls)
    } finally {
      release.countDown()
      worker.shutdownNow()
    }
  }

  @Test fun alreadyDequeuedWrapperChecksActiveImmediatelyBeforeCallingUserCode() {
    val transport = FakeTransport()
    val queue = transport.queue()
    var calls = 0
    queue.post(Runnable { calls++ })
    val detached = transport.takeDue()!!
    queue.setActive(false)
    detached.run()
    assertEquals(0, calls)
    queue.setActive(true)
    transport.runDue()
    assertEquals(1, calls)
  }

  @Test fun staleWrapperAfterPauseAndResumeCannotConsumeTheRescheduledEntry() {
    val transport = FakeTransport()
    val queue = transport.queue()
    var calls = 0
    queue.postDelayed(Runnable { calls++ }, 20)
    transport.elapse(30)
    val stale = transport.takeDue()!!
    queue.setActive(false)
    queue.setActive(true)
    assertEquals(listOf(20L, 0L), transport.delays)
    assertNotSame(stale, transport.wrappers.last())
    stale.run()
    assertEquals(0, calls)
    transport.runDue()
    stale.run()
    assertEquals(1, calls)
  }

  @Test fun cancelledWrapperCannotRunANewerPostOfTheSameRunnable() {
    val transport = FakeTransport()
    val queue = transport.queue()
    var calls = 0
    val callback = Runnable { calls++ }
    queue.post(callback)
    val stale = transport.takeDue()!!
    queue.removeCallbacks(callback)
    queue.post(callback)
    stale.run()
    assertEquals(0, calls)
    transport.runDue()
    stale.run()
    assertEquals(1, calls)
  }

  @Test fun repeatedRunnablePostsRemainSeparateAndKeepEqualDeadlineOrder() {
    val transport = FakeTransport()
    val queue = transport.queue()
    val calls = mutableListOf<String>()
    val repeated = Runnable { calls += "repeated" }
    queue.post(repeated)
    queue.post(Runnable { calls += "between" })
    queue.setActive(false)
    queue.post(repeated)
    queue.setActive(true)
    transport.runDue()
    assertEquals(listOf("repeated", "between", "repeated"), calls)
  }

  private class EqualRunnable(private val action: () -> Unit) : Runnable {
    override fun run() = action()
    override fun equals(other: Any?): Boolean = other is EqualRunnable
    override fun hashCode(): Int = 0
  }

  @Test fun removalCancelsEveryMatchingReferenceWithoutUsingEquals() {
    val transport = FakeTransport()
    val queue = transport.queue()
    val calls = mutableListOf<String>()
    val removed = EqualRunnable { calls += "removed" }
    val kept = EqualRunnable { calls += "kept" }
    queue.post(removed)
    queue.postDelayed(removed, 10)
    queue.post(kept)
    queue.removeCallbacks(removed)
    assertEquals(2, transport.cancelCount)
    transport.runDue()
    transport.elapse(10)
    transport.runDue()
    transport.wrappers.forEach { it.run() }
    assertEquals(listOf("kept"), calls)
  }

  @Test fun removalAlsoDropsAllMatchingEntriesWhilePaused() {
    val transport = FakeTransport()
    val queue = transport.queue()
    val calls = mutableListOf<String>()
    val removed = EqualRunnable { calls += "removed" }
    queue.setActive(false)
    queue.post(removed)
    queue.postDelayed(removed, 10)
    queue.post(EqualRunnable { calls += "kept" })
    queue.removeCallbacks(removed)
    queue.removeCallbacks(removed)
    queue.setActive(true)
    transport.elapse(10)
    transport.runDue()
    assertEquals(listOf("kept"), calls)
    assertEquals(0, transport.cancelCount)
  }

  @Test fun selfRepostingCallbackHasNoHiddenReplayOrCatchupLoop() {
    val transport = FakeTransport()
    val queue = transport.queue()
    var calls = 0
    val callback = object : Runnable {
      override fun run() {
        calls++
        queue.postDelayed(this, 10)
      }
    }
    queue.post(callback)
    val firstWrapper = transport.takeDue()!!
    firstWrapper.run()
    firstWrapper.run()
    assertEquals(1, calls)
    transport.elapse(3)
    queue.setActive(false)
    transport.elapse(10_000)
    transport.runDue()
    assertEquals(1, calls)
    queue.setActive(true)
    transport.elapse(6)
    transport.runDue()
    assertEquals(1, calls)
    transport.elapse(1)
    transport.runDue()
    assertEquals(2, calls)
    queue.removeCallbacks(callback)
    transport.elapse(100)
    transport.runDue()
    assertEquals(2, calls)
  }

  @Test fun callbackCanPauseAndEnqueueBeforeTheNextCallbackRuns() {
    val transport = FakeTransport()
    val queue = transport.queue()
    val calls = mutableListOf<String>()
    queue.post(Runnable {
      calls += "pausing"
      queue.setActive(false)
      assertTrue(queue.post(Runnable { calls += "nested" }))
    })
    queue.post(Runnable { calls += "waiting" })
    transport.runDue()
    assertEquals(listOf("pausing"), calls)
    queue.setActive(true)
    transport.runDue()
    assertEquals(listOf("pausing", "waiting", "nested"), calls)
  }

  @Test fun callbackCanRemoveItsOtherPostsAndEnqueueAReplacement() {
    val transport = FakeTransport()
    val queue = transport.queue()
    var calls = 0
    val callback = object : Runnable {
      override fun run() {
        calls++
        queue.removeCallbacks(this)
        if (calls == 1) queue.postDelayed(this, 10)
      }
    }
    queue.post(callback)
    queue.post(callback)
    transport.runDue()
    assertEquals(1, calls)
    transport.elapse(10)
    transport.runDue()
    assertEquals(2, calls)
    queue.setActive(false)
    queue.setActive(true)
    transport.runDue()
    assertEquals(2, calls)
  }

  @Test fun callbackCanCloseAndRejectItsOwnFollowup() {
    val transport = FakeTransport()
    val queue = transport.queue()
    var calls = 0
    queue.post(Runnable {
      calls++
      queue.close()
      assertFalse(queue.post(Runnable { calls++ }))
    })
    queue.post(Runnable { calls++ })
    transport.runDue()
    assertEquals(1, calls)
    assertEquals(0, transport.pendingCount)
  }

  @Test(timeout = 5_000) fun callbackCanWaitForWorkerEnqueueWithoutHoldingTheQueueLock() {
    val transport = FakeTransport()
    val queue = transport.queue()
    val worker = Executors.newSingleThreadExecutor()
    val calls = mutableListOf<String>()
    try {
      queue.post(Runnable {
        calls += "outer"
        assertTrue(worker.submit<Boolean> {
          queue.post(Runnable { calls += "worker" })
        }.get(2, TimeUnit.SECONDS))
      })
      transport.runDue()
      assertEquals(listOf("outer", "worker"), calls)
    } finally {
      queue.close()
      worker.shutdownNow()
    }
  }

  @Test fun throwingCallbackIsConsumedAndDoesNotReplayOnResume() {
    val transport = FakeTransport()
    val queue = transport.queue()
    val expected = IllegalStateException("callback failed")
    var calls = 0
    queue.post(Runnable { throw expected })
    queue.post(Runnable { calls++ })
    try {
      transport.takeDue()!!.run()
      fail("Callback failure must escape")
    } catch (actual: IllegalStateException) {
      assertTrue(actual === expected)
    }
    queue.setActive(false)
    queue.setActive(true)
    transport.runDue()
    assertEquals(1, calls)
  }

  @Test fun closeCancelsScheduledEntriesAndIsTerminalForStaleAndFuturePosts() {
    val transport = FakeTransport()
    val queue = transport.queue()
    var calls = 0
    val callback = Runnable { calls++ }
    queue.post(callback)
    queue.postDelayed(callback, 100)
    queue.close()
    queue.close()
    queue.setActive(false)
    queue.setActive(true)
    assertEquals(2, transport.cancelCount)
    assertEquals(0, transport.pendingCount)
    transport.wrappers.forEach { it.run() }
    assertFalse(queue.post(callback))
    assertFalse(queue.postDelayed(callback, 10))
    transport.elapse(1_000)
    transport.runDue()
    assertEquals(0, calls)
  }

  @Test fun closeWhilePausedDropsRetainedEntriesPermanently() {
    val transport = FakeTransport()
    val queue = transport.queue()
    var calls = 0
    queue.post(Runnable { calls++ })
    queue.setActive(false)
    queue.postDelayed(Runnable { calls++ }, 100)
    queue.close()
    queue.setActive(true)
    assertFalse(queue.post(Runnable { calls++ }))
    transport.wrappers.forEach { it.run() }
    transport.elapse(1_000)
    transport.runDue()
    assertEquals(0, calls)
    assertEquals(listOf(0L), transport.delays)
  }

  @Test fun rejectedTransportPostReturnsFalseAndIsNotReplayed() {
    val transport = FakeTransport()
    val queue = transport.queue()
    var calls = 0
    transport.accepting = false
    assertFalse(queue.post(Runnable { calls++ }))
    assertFalse(queue.postDelayed(Runnable { calls++ }, 10))
    transport.accepting = true
    queue.setActive(false)
    queue.setActive(true)
    transport.elapse(10)
    transport.runDue()
    assertEquals(0, calls)
    assertTrue(queue.post(Runnable { calls++ }))
    transport.runDue()
    assertEquals(1, calls)
  }

  @Test fun rejectionDuringResumeDropsThatEntryWithoutBlockingTheRemainingEntries() {
    val transport = FakeTransport()
    var attempts = 0
    val queue = PausableFrameCallbacks(
        clock = transport::clock,
        schedule = { wrapper, delay -> ++attempts != 1 && transport.schedule(wrapper, delay) },
        cancel = transport::cancel,
    )
    val calls = mutableListOf<String>()
    queue.setActive(false)
    assertTrue(queue.post(Runnable { calls += "rejected" }))
    assertTrue(queue.post(Runnable { calls += "accepted" }))
    queue.setActive(true)
    transport.runDue()
    queue.setActive(false)
    queue.setActive(true)
    transport.runDue()
    assertEquals(listOf("accepted"), calls)
    assertEquals(2, attempts)
  }

  @Test fun negativeDelayBehavesLikeAnImmediatePostEvenWhenPaused() {
    val transport = FakeTransport()
    val queue = transport.queue()
    var calls = 0
    queue.postDelayed(Runnable { calls++ }, -10)
    transport.runDue()
    queue.setActive(false)
    queue.postDelayed(Runnable { calls++ }, Long.MIN_VALUE)
    queue.setActive(true)
    transport.runDue()
    assertEquals(2, calls)
    assertEquals(listOf(0L, 0L), transport.delays)
  }

  @Test fun concurrentWorkerPostsKeepEveryEntryOfTheSameRunnable() {
    val transport = FakeTransport()
    val queue = transport.queue()
    val workers = Executors.newFixedThreadPool(4)
    val calls = AtomicInteger()
    val callback = Runnable { calls.incrementAndGet() }
    queue.setActive(false)
    try {
      val posted = (1..4).map {
        workers.submit {
          repeat(50) { assertTrue(queue.post(callback)) }
        }
      }
      posted.forEach { it.get(2, TimeUnit.SECONDS) }
      assertEquals(0, transport.pendingCount)
      queue.setActive(true)
      transport.runDue()
      assertEquals(200, calls.get())
    } finally {
      queue.close()
      workers.shutdownNow()
    }
  }

  @Test(timeout = 5_000) fun workerSchedulingRacingWithPauseCannotLeaveALiveHiddenCallback() {
    val transport = FakeTransport()
    val scheduling = CountDownLatch(1)
    val release = CountDownLatch(1)
    val workers = Executors.newFixedThreadPool(2)
    var calls = 0
    val queue = PausableFrameCallbacks(
        clock = transport::clock,
        schedule = { wrapper, delay ->
          scheduling.countDown()
          assertTrue(release.await(2, TimeUnit.SECONDS))
          transport.schedule(wrapper, delay)
        },
        cancel = transport::cancel,
    )
    try {
      val posted = workers.submit<Boolean> { queue.post(Runnable { calls++ }) }
      assertTrue(scheduling.await(2, TimeUnit.SECONDS))
      val pausing = CountDownLatch(1)
      val paused = workers.submit {
        pausing.countDown()
        queue.setActive(false)
      }
      assertTrue(pausing.await(2, TimeUnit.SECONDS))
      release.countDown()
      assertTrue(posted.get(2, TimeUnit.SECONDS))
      paused.get(2, TimeUnit.SECONDS)
      assertEquals(0, transport.pendingCount)
      transport.wrappers.first().run()
      assertEquals(0, calls)
      queue.setActive(true)
      transport.runDue()
      assertEquals(1, calls)
    } finally {
      release.countDown()
      queue.close()
      workers.shutdownNow()
    }
  }

  @Test(timeout = 5_000) fun closeRacingWithWorkerSchedulingCancelsTheEntryAndRejectsLatePosts() {
    val transport = FakeTransport()
    val scheduling = CountDownLatch(1)
    val release = CountDownLatch(1)
    val workers = Executors.newFixedThreadPool(2)
    var calls = 0
    val callback = Runnable { calls++ }
    val queue = PausableFrameCallbacks(
        clock = transport::clock,
        schedule = { wrapper, delay ->
          scheduling.countDown()
          assertTrue(release.await(2, TimeUnit.SECONDS))
          transport.schedule(wrapper, delay)
        },
        cancel = transport::cancel,
    )
    try {
      val posted = workers.submit<Boolean> { queue.post(callback) }
      assertTrue(scheduling.await(2, TimeUnit.SECONDS))
      val closing = CountDownLatch(1)
      val closed = workers.submit {
        closing.countDown()
        queue.close()
      }
      assertTrue(closing.await(2, TimeUnit.SECONDS))
      release.countDown()
      assertTrue(posted.get(2, TimeUnit.SECONDS))
      closed.get(2, TimeUnit.SECONDS)
      assertEquals(0, transport.pendingCount)
      transport.wrappers.forEach { it.run() }
      assertFalse(workers.submit<Boolean> { queue.post(callback) }.get(2, TimeUnit.SECONDS))
      assertFalse(queue.postDelayed(callback, 10))
      assertEquals(0, calls)
    } finally {
      release.countDown()
      queue.close()
      workers.shutdownNow()
    }
  }
}
