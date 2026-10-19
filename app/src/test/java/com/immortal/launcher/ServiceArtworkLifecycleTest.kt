/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceArtworkLifecycleTest {
  @Test fun olderQueuedCompletionCannotReplaceTheLatestTrack() {
    val fixture = Fixture()
    val published = mutableListOf<String>()
    fixture.lifecycle.submit({ "old" }, published::add)
    fixture.executor.runNext()
    fixture.lifecycle.submit({ "new" }, published::add)
    fixture.executor.runNext()
    fixture.drain()

    assertEquals(listOf("new"), published)
  }

  @Test fun supersededWorkDoesNotFetchArtwork() {
    val fixture = Fixture()
    var fetches = 0
    val published = mutableListOf<String>()
    fixture.lifecycle.submit({ fetches++; "old" }, published::add)
    fixture.lifecycle.submit({ fetches++; "new" }, published::add)
    fixture.executor.runNext()
    fixture.executor.runNext()
    fixture.drain()

    assertEquals(1, fetches)
    assertEquals(listOf("new"), published)
  }

  @Test fun requestSupersededDuringFetchCannotQueueItsResult() {
    val fixture = Fixture()
    val published = mutableListOf<String>()
    fixture.lifecycle.submit({
      fixture.lifecycle.submit({ "new" }, published::add)
      "old"
    }, published::add)
    fixture.executor.runNext()
    assertTrue(fixture.pending.isEmpty())
    fixture.executor.runNext()
    fixture.drain()

    assertEquals(listOf("new"), published)
  }

  @Test fun newConnectionRejectsOldCallbacksAndArtwork() {
    val fixture = Fixture()
    val oldGeneration = fixture.lifecycle.nextGeneration()!!
    val published = mutableListOf<String>()
    fixture.lifecycle.post(oldGeneration) { published += "old status" }
    fixture.lifecycle.submit({ "old art" }, published::add)
    fixture.executor.runNext()
    val newGeneration = fixture.lifecycle.nextGeneration()!!
    fixture.lifecycle.post(oldGeneration) { published += "late old state" }
    fixture.lifecycle.post(newGeneration) { published += "new status" }
    fixture.drain()

    assertEquals(listOf("new status"), published)
  }

  @Test fun closeClearsQueuedCallbacksAndRejectsNewWork() {
    val fixture = Fixture()
    val generation = fixture.lifecycle.nextGeneration()!!
    var updates = 0
    fixture.lifecycle.post(generation) { updates++ }
    fixture.lifecycle.submit({ Any() }) { updates++ }
    fixture.executor.runNext()

    fixture.lifecycle.close()
    fixture.lifecycle.close()
    fixture.lifecycle.post(generation) { updates++ }

    assertNull(fixture.lifecycle.nextGeneration())
    assertFalse(fixture.lifecycle.submit({ Any() }) { updates++ })
    assertTrue(fixture.pending.isEmpty())
    assertEquals(1, fixture.executor.shutdowns)
    assertEquals(0, updates)
  }

  @Test fun completionAlreadyTakenFromQueueCannotPublishAfterClose() {
    val fixture = Fixture()
    var updates = 0
    fixture.lifecycle.submit({ Any() }) { updates++ }
    fixture.executor.runNext()
    val completion = fixture.pending.removeAt(0)

    fixture.lifecycle.close()
    completion.run()

    assertEquals(0, updates)
  }

  @Test fun rejectedExecutorSubmissionIsSafe() {
    val fixture = Fixture()
    fixture.executor.shutdownNow()
    var updates = 0

    assertFalse(fixture.lifecycle.submit({ Any() }) { updates++ })
    fixture.drain()
    assertEquals(0, updates)
  }

  @Test fun fetchThatFinishesAfterTeardownCannotQueueACompletion() {
    val executor = Executors.newSingleThreadExecutor()
    val fetching = CountDownLatch(1)
    val finishFetch = CountDownLatch(1)
    val pending = mutableListOf<Runnable>()
    val lifecycle = ServiceArtworkLifecycle(executor, { pending += it }, { pending.clear() })
    var updates = 0
    try {
      lifecycle.submit({
        fetching.countDown()
        // Like a blocking network read, this fetch may outlive shutdownNow's interrupt.
        var done = false
        while (!done) {
          try { finishFetch.await(); done = true } catch (_: InterruptedException) {}
        }
        Any()
      }) { updates++ }
      assertTrue(fetching.await(2, TimeUnit.SECONDS))
      lifecycle.close()
      finishFetch.countDown()
      assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))

      assertTrue(pending.isEmpty())
      assertEquals(0, updates)
    } finally {
      finishFetch.countDown()
      lifecycle.close()
      executor.shutdownNow()
    }
  }

  @Test fun acceptedCompletionPublishesTheBorrowedArtworkUnchanged() {
    val fixture = Fixture()
    val sharedCover = Any()
    var published: Any? = null
    fixture.lifecycle.submit({ sharedCover }) { published = it }
    fixture.executor.runNext()
    fixture.drain()

    assertSame(sharedCover, published)
    fixture.lifecycle.close()
    assertSame(sharedCover, published)
  }

  @Test fun closeWaitsForAnAdmittedPostThenClearsIt() {
    val executor = QueuedExecutor()
    val posting = CountDownLatch(1)
    val finishPost = CountDownLatch(1)
    val closing = CountDownLatch(1)
    val cleared = CountDownLatch(1)
    val closed = CountDownLatch(1)
    val pending = mutableListOf<Runnable>()
    val lifecycle = ServiceArtworkLifecycle(executor, {
      posting.countDown()
      check(finishPost.await(2, TimeUnit.SECONDS))
      pending += it
    }, {
      pending.clear()
      cleared.countDown()
    })
    val generation = lifecycle.nextGeneration()!!
    val producer = Thread { lifecycle.post(generation) {} }
    val teardown = Thread {
      closing.countDown()
      lifecycle.close()
      closed.countDown()
    }
    try {
      producer.start()
      assertTrue(posting.await(2, TimeUnit.SECONDS))
      teardown.start()
      assertTrue(closing.await(2, TimeUnit.SECONDS))
      assertFalse(cleared.await(100, TimeUnit.MILLISECONDS))
      finishPost.countDown()
      assertTrue(closed.await(2, TimeUnit.SECONDS))
      producer.join(2000)
      teardown.join(2000)

      assertTrue(pending.isEmpty())
    } finally {
      finishPost.countDown()
      producer.join(2000)
      if (teardown.state != Thread.State.NEW) teardown.join(2000)
      lifecycle.close()
    }
  }

  private class Fixture {
    val executor = QueuedExecutor()
    val pending = mutableListOf<Runnable>()
    val lifecycle = ServiceArtworkLifecycle(executor, { pending += it }, { pending.clear() })
    fun drain() {
      while (pending.isNotEmpty()) pending.removeAt(0).run()
    }
  }

  private class QueuedExecutor : AbstractExecutorService() {
    private val work = mutableListOf<Runnable>()
    private var stopped = false
    var shutdowns = 0
      private set
    fun runNext() = work.removeAt(0).run()
    override fun execute(command: Runnable) {
      if (stopped) throw RejectedExecutionException()
      work += command
    }
    override fun shutdown() { stopped = true; shutdowns++ }
    override fun shutdownNow(): MutableList<Runnable> {
      shutdown()
      return work.toMutableList().also { work.clear() }
    }
    override fun isShutdown() = stopped
    override fun isTerminated() = stopped && work.isEmpty()
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
  }
}
