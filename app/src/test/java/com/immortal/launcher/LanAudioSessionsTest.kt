/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.net.ServerSocket
import java.net.InetSocketAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanAudioSessionsTest {
  @Test fun cancelledUnboundServerCannotBindAfterItsReplacementStarts() {
    val sessions = LanAudioSessions()
    val old = sessions.start()
    ServerSocket().use { server ->
      val owned = old.own { server.close() }!!
      val next = sessions.start()
      var attemptedBind = false
      assertFalse(owned.start {
        attemptedBind = true
        server.bind(InetSocketAddress("127.0.0.1", 0))
      })
      assertFalse(attemptedBind)
      assertTrue(server.isClosed)
      assertFalse(server.isBound)
      assertTrue(next.isActive)
      sessions.stop()
    }
  }

  private class Transport {
    val connecting = CountDownLatch(1)
    val closed = CountDownLatch(1)
    val closes = AtomicInteger()

    fun connect() {
      connecting.countDown()
      // A blocking transport only wakes when closed; no network or Android calls.
      check(closed.await(2, TimeUnit.SECONDS))
    }

    fun close() {
      closes.incrementAndGet()
      closed.countDown()
    }
  }

  @Test fun stopClosesPendingConnectAndOldCompletionCannotStopTheNextSession() {
    val sessions = LanAudioSessions()
    val old = sessions.start()
    val socket = Transport()
    val published = AtomicBoolean()
    val allowCompletion = CountDownLatch(1)
    val completed = CountDownLatch(1)
    val worker = Thread {
      val owned = old.own { socket.close() }!!
      try {
        socket.connect()
        check(allowCompletion.await(2, TimeUnit.SECONDS))
        if (old.isActive) published.set(true)
      } finally {
        owned.close()
        sessions.finish(old)
        completed.countDown()
      }
    }.apply { isDaemon = true; start() }
    try {
      assertTrue(socket.connecting.await(2, TimeUnit.SECONDS))
      sessions.stop()
      assertTrue(socket.closed.await(1, TimeUnit.SECONDS))
      val next = sessions.start()
      allowCompletion.countDown()
      assertTrue(completed.await(2, TimeUnit.SECONDS))
      assertFalse(published.get())
      assertFalse(old.isActive)
      assertTrue(next.isActive)
      assertTrue(sessions.isActive)
      assertEquals(1, socket.closes.get())
    } finally {
      socket.closed.countDown()
      allowCompletion.countDown()
      sessions.stop()
      worker.join(2_000)
    }
  }

  @Test fun serverListenerAndAudioCreatedAfterStopAreClosedWithoutPublication() {
    val sessions = LanAudioSessions()
    val old = sessions.start()
    sessions.stop()
    val next = sessions.start()
    // Model constructors/accept completing after cancellation of their original start.
    repeat(4) {
      val closes = AtomicInteger()
      assertNull(old.own { closes.incrementAndGet() })
      assertEquals(1, closes.get())
    }
    assertFalse(old.isActive)
    assertTrue(next.isActive)
    sessions.stop()
  }

  @Test fun replacingASessionClosesEveryPeerSocketAndAudioHandleEvenIfCleanupThrows() {
    val sessions = LanAudioSessions()
    val old = sessions.start()
    val closes = List(5) { AtomicInteger() }
    // Listening server, two listener sockets, and their two microphone handles.
    closes.forEachIndexed { index, count ->
      assertNotNull(old.own {
        count.incrementAndGet()
        if (index == 1) error("socket teardown failed")
      })
    }
    val next = sessions.start()
    closes.forEach { assertEquals(1, it.get()) }
    assertFalse(old.isActive)
    sessions.finish(old)
    assertTrue(next.isActive)
    sessions.stop()
    closes.forEach { assertEquals(1, it.get()) }
  }

  @Test fun stoppedOrReleasedAudioCannotStartAndCleanupIsIdempotent() {
    val sessions = LanAudioSessions()
    val session = sessions.start()
    val starts = AtomicInteger()
    val releases = AtomicInteger()
    val audio = session.own { releases.incrementAndGet() }!!
    assertTrue(audio.start { starts.incrementAndGet() })
    audio.close()
    assertFalse(audio.start { starts.incrementAndGet() })
    sessions.stop()
    audio.close()
    assertEquals(1, starts.get())
    assertEquals(1, releases.get())
    assertFalse(sessions.isActive)
  }

  @Test fun cancellationPreventsAudioStartBeforeItsCleanupHasRun() {
    val sessions = LanAudioSessions()
    val session = sessions.start()
    val cleaning = CountDownLatch(1)
    val allowCleanup = CountDownLatch(1)
    val stopped = CountDownLatch(1)
    val starts = AtomicInteger()
    session.own {
      cleaning.countDown()
      check(allowCleanup.await(2, TimeUnit.SECONDS))
    }
    val audio = session.own {}!!
    val stopper = Thread {
      sessions.stop()
      stopped.countDown()
    }.apply { isDaemon = true; start() }
    try {
      assertTrue(cleaning.await(2, TimeUnit.SECONDS))
      assertFalse(session.isActive)
      assertFalse(audio.start { starts.incrementAndGet() })
      assertEquals(0, starts.get())
    } finally {
      allowCleanup.countDown()
      assertTrue(stopped.await(2, TimeUnit.SECONDS))
      stopper.join(2_000)
    }
  }

  @Test fun concurrentRegistrationAndStopCloseEachResourceExactlyOnce() {
    val sessions = LanAudioSessions()
    val session = sessions.start()
    val go = CountDownLatch(1)
    val registered = CountDownLatch(1)
    val stopped = CountDownLatch(1)
    val closes = List(100) { AtomicInteger() }
    val registrar = Thread {
      go.await()
      closes.forEach { count -> session.own { count.incrementAndGet() } }
      registered.countDown()
    }.apply { isDaemon = true; start() }
    val stopper = Thread {
      go.await()
      sessions.stop()
      stopped.countDown()
    }.apply { isDaemon = true; start() }
    go.countDown()
    assertTrue(registered.await(2, TimeUnit.SECONDS))
    assertTrue(stopped.await(2, TimeUnit.SECONDS))
    closes.forEach { assertEquals(1, it.get()) }
    assertFalse(session.isActive)
    registrar.join(2_000)
    stopper.join(2_000)
  }

  @Test fun normalCompletionReleasesPlaybackAndDoesNotLeaveAnActiveSession() {
    val sessions = LanAudioSessions()
    val session = sessions.start()
    val socket = Transport()
    val playbackReleases = AtomicInteger()
    session.own { socket.close() }
    session.own { playbackReleases.incrementAndGet() }
    sessions.finish(session)
    sessions.finish(session)
    sessions.stop()
    assertFalse(session.isActive)
    assertFalse(sessions.isActive)
    assertEquals(1, socket.closes.get())
    assertEquals(1, playbackReleases.get())
  }

  @Test fun workerCleanupRacingStopReleasesAudioExactlyOnce() {
    val sessions = LanAudioSessions()
    val session = sessions.start()
    val cancelling = CountDownLatch(1)
    val cleaning = CountDownLatch(1)
    val allowCleanup = CountDownLatch(1)
    val finished = CountDownLatch(1)
    val stopped = CountDownLatch(1)
    val releases = AtomicInteger()
    session.own { cancelling.countDown() }
    val audio = session.own {
      releases.incrementAndGet()
      cleaning.countDown()
      check(allowCleanup.await(2, TimeUnit.SECONDS))
    }!!
    val worker = Thread {
      audio.close()
      finished.countDown()
    }.apply { isDaemon = true; start() }
    var stopper: Thread? = null
    try {
      assertTrue(cleaning.await(2, TimeUnit.SECONDS))
      stopper = Thread {
        sessions.stop()
        stopped.countDown()
      }.apply { isDaemon = true; start() }
      assertTrue(cancelling.await(2, TimeUnit.SECONDS))
      assertFalse(session.isActive)
    } finally {
      allowCleanup.countDown()
      assertTrue(finished.await(2, TimeUnit.SECONDS))
      worker.join(2_000)
      if (stopper != null) {
        assertTrue(stopped.await(2, TimeUnit.SECONDS))
        stopper.join(2_000)
      }
      sessions.stop()
    }
    assertEquals(1, releases.get())
  }
}
