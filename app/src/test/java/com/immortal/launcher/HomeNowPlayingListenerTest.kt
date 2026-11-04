/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito

/** Generated tracks and controlled UI queues; no media session or Android UI execution. */
class HomeNowPlayingListenerTest {
  private lateinit var logs: MockedStatic<Log>
  private var previous: NowPlayingState? = null
  private val listeners = mutableListOf<NowPlayingHub.Listener>()
  private val ui = ConcurrentLinkedQueue<Runnable>()
  private val rendered = mutableListOf<NowPlayingState?>()
  private val old = track("Original")
  private val replacement = track("Replacement")

  @Before fun prepare() {
    logs = Mockito.mockStatic(Log::class.java)
    previous = NowPlayingHub.current
    NowPlayingHub.publish(old)
  }

  @After fun restore() {
    listeners.forEach(NowPlayingHub::removeListener)
    NowPlayingHub.publish(previous)
    logs.close()
  }

  @Test fun lateInitialReplayCannotRestoreClearedTrack() {
    delayedCallback(false, null)
    assertEquals(listOf(null, null), rendered)
    assertNull(NowPlayingHub.current)
  }

  @Test fun lateInitialReplayCannotOverwriteReplacementTrack() {
    delayedCallback(false, replacement)
    assertEquals(listOf(replacement, replacement), rendered)
    assertEquals(replacement, NowPlayingHub.current)
  }

  @Test fun lateWorkerPublicationCannotOverwriteReplacementTrack() {
    delayedCallback(true, replacement)
    assertEquals(listOf(replacement, replacement), rendered)
  }

  @Test fun queuedReplayReadsLatestStateWhenItsUiWorkRuns() {
    subscribe()
    NowPlayingHub.publish(replacement)
    ui.remove().run()
    assertEquals(listOf(replacement), rendered)
    ui.remove().run()
    assertEquals(listOf(replacement, replacement), rendered)
  }

  @Test fun pendingPlaybackUpdateUsesCurrentPausedSnapshot() {
    subscribe()
    drain()
    rendered.clear()
    NowPlayingHub.publish(replacement)
    val paused = replacement.copy(state = PlaybackState.PAUSED, positionMs = 1200)
    NowPlayingHub.publish(paused)
    ui.remove().run()
    assertEquals(listOf(paused), rendered)
    drain()
    assertEquals(listOf(paused, paused), rendered)
  }

  @Test fun immediateReplayWaitsForUiDispatch() {
    subscribe()
    assertTrue(rendered.isEmpty())
    assertEquals(1, ui.size)
  }

  @Test fun ordinaryReplayStillShowsCurrentPlayingTrack() {
    subscribe()
    drain()
    assertEquals(listOf(old), rendered)
  }

  @Test fun pausedTracksStillAppear() {
    val paused = old.copy(state = PlaybackState.PAUSED)
    NowPlayingHub.publish(paused)
    subscribe()
    drain()
    assertEquals(listOf(paused), rendered)
  }

  @Test fun inactiveAndNullPublicationsStillClearTheTrack() {
    subscribe()
    drain()
    NowPlayingHub.publish(old.copy(state = PlaybackState.STOPPED))
    drain()
    NowPlayingHub.publish(null)
    drain()
    assertEquals(listOf(old, null, null), rendered)
  }

  @Test fun removedListenerReceivesNoNewPublications() {
    val listener = subscribe()
    drain()
    NowPlayingHub.removeListener(listener)
    NowPlayingHub.publish(replacement)
    assertTrue(ui.isEmpty())
    assertEquals(listOf(old), rendered)
  }

  private fun subscribe(post: (Runnable) -> Unit = { ui.add(it) }): NowPlayingHub.Listener {
    val listener = homeNowPlayingListener(post) { rendered.add(it) }
    listeners.add(listener)
    NowPlayingHub.addListener(listener)
    return listener
  }

  private fun delayedCallback(publication: Boolean, next: NowPlayingState?) {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val worker = Executors.newSingleThreadExecutor()
    val owner = Thread.currentThread()
    val post: (Runnable) -> Unit = {
      if (Thread.currentThread() !== owner) {
        entered.countDown()
        check(release.await(5, TimeUnit.SECONDS)) { "controlled callback was not released" }
      }
      ui.add(it)
    }
    if (publication) {
      subscribe(post)
      drain()
      rendered.clear()
    }
    val future = worker.submit {
      if (publication) {
        // Mockito's static Log mock is thread-local; all playback code remains actual.
        Mockito.mockStatic(Log::class.java).use { NowPlayingHub.publish(old) }
      } else {
        subscribe(post)
      }
    }
    try {
      assertTrue("callback entered dispatch", entered.await(5, TimeUnit.SECONDS))
      NowPlayingHub.publish(next)
      release.countDown()
      future.get(5, TimeUnit.SECONDS)
      drain()
    } finally {
      release.countDown()
      worker.shutdownNow()
      assertTrue("worker retired", worker.awaitTermination(5, TimeUnit.SECONDS))
    }
  }

  private fun drain() { while (true) (ui.poll() ?: return).run() }
  private fun track(title: String) = NowPlayingState(PlaybackState.PLAYING, title = title,
      source = "com.immortal.generated")
}
