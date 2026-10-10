/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.ArrayDeque
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Exercise deferred admission and actual startup callbacks without Android Views or sleeps. */
class WebViewStartupPreparationTest {
  @Test fun `disabled candidate does not schedule read settings or allocate an executor`() {
    val f = Fixture(enabled = false)
    f.enter()
    assertTrue(f.opportunities.isEmpty())
    assertEquals(0, f.relevanceReads)
    assertEquals(0, f.executorCreations)
    assertTrue(f.starts.isEmpty())
  }

  @Test fun `request waits for Home draw and idle before reading relevance or starting`() {
    val f = Fixture()
    f.enter()
    assertEquals(1, f.opportunities.size)
    assertEquals(0, f.relevanceReads)
    assertEquals(0, f.executorCreations)
    assertTrue(f.starts.isEmpty())
    f.opportunities.single().draw()
    assertTrue("Drawing Home requested startup on the draw stack", f.starts.isEmpty())
    f.opportunities.single().idle()
    assertEquals(1, f.relevanceReads)
    assertEquals(1, f.executorCreations)
    assertEquals(1, f.starts.size)
    assertFalse("UI-thread WebView startup was enabled", f.starts.single().runUiThreadTasks)
  }

  @Test fun `navigation before first draw cancels without spending the process attempt`() {
    val f = Fixture()
    val leave = f.enter()
    val old = f.opportunities.single()
    leave()
    assertEquals(1, old.cancellations)
    old.forceStaleDelivery()
    assertEquals(0, f.relevanceReads)
    assertEquals(0, f.executorCreations)
    f.enter()
    f.settleLatest()
    assertEquals(1, f.starts.size)
  }

  @Test fun `destruction after draw but before idle cannot request startup`() {
    val f = Fixture()
    val destroy = f.enter()
    val old = f.opportunities.single()
    old.draw()
    destroy()
    old.idle()
    old.forceStaleDelivery()
    assertEquals(0, f.relevanceReads)
    assertEquals(0, f.executorCreations)
    assertTrue(f.starts.isEmpty())
  }

  @Test fun `a replaced Home entry rejects an already queued stale opportunity`() {
    val f = Fixture()
    f.enter()
    val old = f.opportunities.single()
    old.draw()
    f.enter()
    assertEquals(1, old.cancellations)
    old.forceStaleDelivery()
    assertTrue(f.starts.isEmpty())
    f.settleLatest()
    assertEquals(1, f.starts.size)
  }

  @Test fun `old Home destruction cannot cancel a newer Home entry`() {
    val f = Fixture()
    val oldDestroy = f.enter()
    f.enter()
    oldDestroy()
    val latest = f.opportunities.last()
    assertEquals(0, latest.cancellations)
    f.settleLatest()
    assertEquals(1, f.starts.size)
  }

  @Test fun `repeated resume while startup is in flight does not reschedule or allocate`() {
    val f = Fixture()
    f.enter()
    f.settleLatest()
    repeat(20) { f.enter() }
    assertEquals(1, f.opportunities.size)
    assertEquals(1, f.executorCreations)
    assertEquals(1, f.starts.size)
    assertEquals(0, f.ownedExecutor.shutdownCalls)
  }

  @Test fun `successful callback shuts down once and future Home entries remain inert`() {
    val f = Fixture()
    f.enter()
    f.settleLatest()
    val callback = f.starts.single().complete
    callback(null)
    callback(null)
    repeat(20) { f.enter() }
    assertEquals(1, f.executorCreations)
    assertEquals(1, f.starts.size)
    assertEquals(1, f.ownedExecutor.shutdownCalls)
    assertEquals(0, f.ownedExecutor.shutdownNowCalls)
    assertEquals(1, f.opportunities.size)
  }

  @Test fun `leaving Home after request preserves process work until the callback`() {
    val f = Fixture()
    val leave = f.enter()
    f.settleLatest()
    leave()
    assertEquals("Home destroyed the executor still owned by WebKit", 0,
        f.ownedExecutor.shutdownCalls)
    f.starts.single().complete(null)
    assertEquals(1, f.ownedExecutor.shutdownCalls)
    f.enter()
    assertEquals(1, f.starts.size)
  }

  @Test fun `irrelevant native mode does not allocate and later relevant Home can prepare`() {
    val f = Fixture()
    f.relevant = false
    f.enter()
    f.settleLatest()
    assertEquals(1, f.relevanceReads)
    assertEquals(0, f.executorCreations)
    assertTrue(f.starts.isEmpty())
    f.relevant = true
    f.enter()
    f.settleLatest()
    assertEquals(1, f.starts.size)
  }

  @Test fun `relevance is checked at the settled opportunity rather than frozen on resume`() {
    val f = Fixture()
    f.enter()
    f.relevant = false
    f.settleLatest()
    assertEquals(1, f.relevanceReads)
    assertEquals(0, f.executorCreations)
    assertTrue(f.starts.isEmpty())
  }

  @Test fun `schedule rejection is isolated and does not retry on repeated Home entries`() {
    val f = Fixture()
    f.rejectSchedule = true
    assertNull(failure { f.enter() })
    f.rejectSchedule = false
    repeat(10) { f.enter() }
    assertEquals(1, f.scheduleCalls)
    assertEquals(0, f.relevanceReads)
    assertEquals(0, f.executorCreations)
    assertTrue(f.starts.isEmpty())
  }

  @Test fun `throwing scheduler is isolated and cannot leave an admitted stale callback`() {
    val f = Fixture()
    f.scheduleFailure = RejectedExecutionException("synthetic scheduling failure")
    assertNull(failure { f.enter() })
    f.opportunities.single().forceStaleDelivery()
    f.scheduleFailure = null
    f.enter()
    assertEquals(1, f.scheduleCalls)
    assertEquals(0, f.executorCreations)
    assertTrue(f.starts.isEmpty())
  }

  @Test fun `rejection after drawing terminates the pending request without startup`() {
    val f = Fixture()
    f.enter()
    val opportunity = f.opportunities.single()
    opportunity.draw()
    opportunity.reject()
    opportunity.forceStaleDelivery()
    assertEquals(WebViewStartupState(failed = true), f.coordinator.snapshot())
    assertEquals(0, f.executorCreations)
    assertTrue(f.starts.isEmpty())
    f.enter()
    assertEquals(1, f.scheduleCalls)
  }

  @Test fun `old registration rejection cannot poison a newer Home entry`() {
    val f = Fixture()
    f.enter()
    val old = f.opportunities.single()
    f.enter()
    old.reject()
    assertEquals(WebViewStartupState(pending = true), f.coordinator.snapshot())
    f.settleLatest()
    assertEquals(1, f.starts.size)
  }

  @Test fun `late scheduling rejection cannot fail admitted process work`() {
    val f = Fixture()
    f.enter()
    val opportunity = f.opportunities.single()
    f.settleLatest()
    opportunity.reject()
    assertEquals(WebViewStartupState(attempted = true), f.coordinator.snapshot())
    f.starts.single().complete(null)
    assertEquals(WebViewStartupState(attempted = true, completed = true),
        f.coordinator.snapshot())
  }

  @Test fun `settings failure is isolated and does not create a repeated retry`() {
    val f = Fixture()
    f.relevanceFailure = IllegalStateException("synthetic snapshot failure")
    f.enter()
    assertNull(failure { f.settleLatest() })
    f.relevanceFailure = null
    f.enter()
    assertEquals(1, f.relevanceReads)
    assertEquals(0, f.executorCreations)
    assertTrue(f.starts.isEmpty())
  }

  @Test fun `executor creation failure is isolated with no later retry`() {
    val f = Fixture()
    f.executorFailure = RejectedExecutionException("synthetic allocation failure")
    f.enter()
    assertNull(failure { f.settleLatest() })
    f.executorFailure = null
    f.enter()
    assertEquals(1, f.executorCreations)
    assertTrue(f.starts.isEmpty())
  }

  @Test fun `synchronous startup failure retires its executor without retry`() {
    val f = Fixture()
    f.startFailure = IllegalStateException("synthetic provider failure")
    f.enter()
    assertNull(failure { f.settleLatest() })
    assertEquals(1, f.ownedExecutor.shutdownCalls)
    f.startFailure = null
    f.enter()
    assertEquals(1, f.executorCreations)
    assertEquals(1, f.starts.size)
  }

  @Test fun `provider failure callback retires its executor without retry`() {
    val f = Fixture()
    f.enter()
    f.settleLatest()
    f.starts.single().complete(IllegalStateException("synthetic async failure"))
    f.starts.single().complete(null)
    f.enter()
    assertEquals(1, f.ownedExecutor.shutdownCalls)
    assertEquals(1, f.executorCreations)
    assertEquals(1, f.starts.size)
  }

  @Test fun `executor submission rejection is isolated before reaching provider work`() {
    val f = Fixture()
    f.ownedExecutor.reject = true
    f.startBody = { executor, _ -> executor.execute { fail("Rejected work ran") } }
    f.enter()
    assertNull(failure { f.settleLatest() })
    assertEquals(1, f.ownedExecutor.shutdownCalls)
    f.enter()
    assertEquals(1, f.executorCreations)
  }

  @Test fun `exception inside a WebKit executor task is isolated without callback or retry`() {
    val f = Fixture()
    f.startBody = { executor, _ ->
      executor.execute { throw IllegalStateException("synthetic legacy-provider task failure") }
    }
    f.enter()
    f.settleLatest()
    assertEquals(0, f.ownedExecutor.shutdownCalls)
    assertNull(failure { f.ownedExecutor.drain() })
    assertEquals(1, f.ownedExecutor.shutdownCalls)
    f.starts.single().complete(null)
    f.enter()
    assertEquals(1, f.ownedExecutor.shutdownCalls)
    assertEquals(1, f.executorCreations)
  }

  @Test fun `provider linkage failure inside executor work is isolated`() {
    val f = Fixture()
    f.startBody = { executor, _ ->
      executor.execute { throw NoClassDefFoundError("synthetic unavailable provider") }
    }
    f.enter()
    f.settleLatest()
    assertNull(failure { f.ownedExecutor.drain() })
    assertEquals(1, f.ownedExecutor.shutdownCalls)
    f.enter()
    assertEquals(1, f.executorCreations)
  }

  @Test fun `synchronous provider callback cannot leak or double-close its executor`() {
    val f = Fixture()
    f.startBody = { _, complete -> complete(null) }
    f.enter()
    assertNull(failure { f.settleLatest() })
    assertEquals(1, f.ownedExecutor.shutdownCalls)
    f.starts.single().complete(IllegalStateException("synthetic duplicate"))
    assertEquals(1, f.ownedExecutor.shutdownCalls)
    f.enter()
    assertEquals(1, f.executorCreations)
  }

  @Test fun `reading an untouched or disabled snapshot never schedules or initializes startup`() {
    for (enabled in listOf(false, true)) {
      val f = Fixture(enabled)
      repeat(20) { assertEquals(WebViewStartupState(), f.coordinator.snapshot()) }
      assertEquals(0, f.scheduleCalls)
      assertEquals(0, f.relevanceReads)
      assertEquals(0, f.executorCreations)
      assertTrue(f.starts.isEmpty())
    }
  }

  @Test fun `snapshot distinguishes pending Home opportunity from a startup attempt`() {
    val f = Fixture()
    val leave = f.enter()
    val pending = f.coordinator.snapshot()
    assertEquals(WebViewStartupState(pending = true), pending)
    leave()
    assertEquals(WebViewStartupState(), f.coordinator.snapshot())
    assertEquals("Published snapshots changed after cancellation",
        WebViewStartupState(pending = true), pending)
    assertEquals(0, f.executorCreations)
  }

  @Test fun `snapshot exposes in-flight background request without changing its execution`() {
    val f = Fixture()
    f.enter()
    f.settleLatest()
    repeat(20) {
      assertEquals(WebViewStartupState(attempted = true), f.coordinator.snapshot())
    }
    assertEquals(1, f.starts.size)
    assertEquals(1, f.executorCreations)
    assertEquals(0, f.ownedExecutor.shutdownCalls)
  }

  @Test fun `native-mode opportunity leaves startup state untouched`() {
    val f = Fixture()
    f.relevant = false
    f.enter()
    f.settleLatest()
    assertEquals(WebViewStartupState(), f.coordinator.snapshot())
    assertEquals(0, f.executorCreations)
    assertTrue(f.starts.isEmpty())
  }

  @Test fun `success publishes completed state and keeps old snapshot immutable`() {
    val f = Fixture()
    f.enter()
    f.settleLatest()
    val inFlight = f.coordinator.snapshot()
    f.starts.single().complete(null)
    assertEquals(WebViewStartupState(attempted = true, completed = true),
        f.coordinator.snapshot())
    assertEquals(WebViewStartupState(attempted = true), inFlight)
    f.enter()
    assertEquals(WebViewStartupState(attempted = true, completed = true),
        f.coordinator.snapshot())
  }

  @Test fun `provider error publishes failed state without claiming completed preparation`() {
    val f = Fixture()
    f.enter()
    f.settleLatest()
    f.starts.single().complete(IllegalStateException("synthetic failure"))
    assertEquals(WebViewStartupState(attempted = true, failed = true),
        f.coordinator.snapshot())
    f.starts.single().complete(null)
    assertEquals(WebViewStartupState(attempted = true, failed = true),
        f.coordinator.snapshot())
  }

  @Test fun `background task failure publishes the same content-free failed state`() {
    val f = Fixture()
    f.startBody = { executor, _ ->
      executor.execute { throw IllegalStateException("synthetic background failure") }
    }
    f.enter()
    f.settleLatest()
    f.ownedExecutor.drain()
    assertEquals(WebViewStartupState(attempted = true, failed = true),
        f.coordinator.snapshot())
  }

  @Test fun `concurrent callbacks and Main reads preserve once semantics and single shutdown`() {
    val f = Fixture()
    f.enter()
    f.settleLatest()
    val callback = f.starts.single().complete
    val start = CountDownLatch(1)
    val done = CountDownLatch(8)
    val failures = ConcurrentLinkedQueue<Throwable>()
    repeat(8) {
      Thread({
        try {
          assertTrue("Test callback rendezvous timed out", start.await(5, TimeUnit.SECONDS))
          callback(null)
        } catch (error: Throwable) {
          failures.add(error)
        } finally {
          done.countDown()
        }
      }, "synthetic-webview-callback-$it").start()
    }
    start.countDown()
    repeat(100) {
      f.enter()
      val state = f.coordinator.snapshot()
      assertTrue("Callback lost the process attempt", state.attempted)
      assertFalse("In-flight callback published a pending Home registration", state.pending)
      assertFalse("Successful callbacks published failure", state.failed)
    }
    assertTrue("Concurrent callback completion timed out", done.await(5, TimeUnit.SECONDS))
    assertTrue("Callback failed: ${failures.firstOrNull()}", failures.isEmpty())
    assertEquals(WebViewStartupState(attempted = true, completed = true),
        f.coordinator.snapshot())
    assertEquals(1, f.executorCreations)
    assertEquals(1, f.starts.size)
    assertEquals(1, f.ownedExecutor.shutdownCalls)
    assertEquals(1, f.opportunities.size)
  }

  @Test fun `fatal VM errors escape background work and are never ordinary completion`() {
    for (fatal in listOf(InternalError("synthetic VM failure"),
        OutOfMemoryError("synthetic OOME without memory pressure"))) {
      val f = Fixture()
      f.startBody = { executor, _ -> executor.execute { throw fatal } }
      f.enter()
      f.settleLatest()
      assertSame("Fatal VM error was swallowed", fatal, failure { f.ownedExecutor.drain() })
      val state = f.coordinator.snapshot()
      assertTrue(state.attempted)
      assertFalse("Fatal VM error became successful preparation", state.completed)
      assertFalse("Fatal VM error was treated as an expected startup failure", state.failed)
    }
  }

  private fun failure(action: () -> Unit): Throwable? = runCatching(action).exceptionOrNull()

  private class Opportunity(
      private val action: () -> Unit,
      private val rejected: () -> Unit,
  ) {
    private var drawn = false
    private var cancelled = false
    var cancellations = 0
      private set
    fun draw() { drawn = true }
    fun idle() { if (drawn && !cancelled) action() }
    fun reject() = rejected()
    fun cancel() {
      if (!cancelled) { cancelled = true; cancellations++ }
    }
    // Simulate a callback already enqueued when cancellation removed the registration.
    fun forceStaleDelivery() = action()
  }

  private data class Start(
      val executor: Executor,
      val runUiThreadTasks: Boolean,
      val complete: (Throwable?) -> Unit,
  )

  private class Fixture(enabled: Boolean = true) {
    val opportunities = ArrayList<Opportunity>()
    val ownedExecutor = QueuedExecutor()
    val starts = ArrayList<Start>()
    var scheduleCalls = 0
    var relevanceReads = 0
    var executorCreations = 0
    var relevant = true
    var rejectSchedule = false
    var scheduleFailure: Exception? = null
    var relevanceFailure: Exception? = null
    var executorFailure: Exception? = null
    var startFailure: Exception? = null
    var startBody: (Executor, (Throwable?) -> Unit) -> Unit = { _, _ -> }
    val coordinator = WebViewStartupCoordinator(
        enabled = enabled,
        newExecutor = {
          executorCreations++
          executorFailure?.let { throw it }
          ownedExecutor
        },
        startUp = { executor, runUiThreadTasks, complete ->
          starts.add(Start(executor, runUiThreadTasks, complete))
          startFailure?.let { throw it }
          startBody(executor, complete)
        })

    fun enter(): () -> Unit = coordinator.request(
        schedule = { action, rejected ->
          scheduleCalls++
          if (rejectSchedule) null
          else {
            val opportunity = Opportunity(action, rejected).also { opportunities.add(it) }
            scheduleFailure?.let { throw it }
            opportunity::cancel
          }
        },
        isRelevant = {
          relevanceReads++
          relevanceFailure?.let { throw it }
          relevant
        })

    fun settleLatest() {
      opportunities.last().draw()
      opportunities.last().idle()
    }
  }

  private class QueuedExecutor : AbstractExecutorService() {
    private val tasks = ArrayDeque<Runnable>()
    var reject = false
    var shutdownCalls = 0
      private set
    var shutdownNowCalls = 0
      private set
    override fun execute(command: Runnable) {
      if (reject || isShutdown) throw RejectedExecutionException("synthetic executor closed")
      tasks.addLast(command)
    }
    override fun shutdown() { shutdownCalls++ }
    override fun shutdownNow(): MutableList<Runnable> {
      shutdownNowCalls++
      return tasks.toMutableList().also { tasks.clear() }
    }
    override fun isShutdown(): Boolean = shutdownCalls > 0 || shutdownNowCalls > 0
    override fun isTerminated(): Boolean = isShutdown && tasks.isEmpty()
    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean =
        throw AssertionError("Startup preparation must never block waiting for termination")
    fun drain() {
      while (tasks.isNotEmpty()) tasks.removeFirst().run()
    }
  }
}
