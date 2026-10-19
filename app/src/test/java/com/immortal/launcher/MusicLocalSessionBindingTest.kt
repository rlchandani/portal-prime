/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.media.session.MediaSession
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

/** Runs the real controller publication boundary; only Android framework scheduling is mocked. */
class MusicLocalSessionBindingTest {
  private fun withController(test: (MusicPlayerController, () -> Unit) -> Unit) {
    val context = mock(Context::class.java)
    `when`(context.applicationContext).thenReturn(context)
    val looper = mock(Looper::class.java)
    mockStatic(Looper::class.java).use { loopers ->
      loopers.`when`<Looper> { Looper.getMainLooper() }.thenReturn(looper)
      mockStatic(SystemClock::class.java).use { clock ->
        clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenReturn(5_000L)
        mockStatic(Log::class.java).use {
          mockConstruction(Handler::class.java).use {
            val previous = NowPlayingHub.current
            try {
              val controller = MusicPlayerController(context)
              val publish = MusicPlayerController::class.java.getDeclaredMethod("publishLocal").apply {
                isAccessible = true
              }
              test(controller) { publish.invoke(controller); Unit }
            } finally {
              NowPlayingHub.publish(previous)
            }
          }
        }
      }
    }
  }

  private fun session(token: MediaSession.Token?) = NowPlayingState(
      PlaybackState.PLAYING, title = "Track", source = "same.player", mediaId = "same-track",
      durationMs = 120_000L, positionMs = 10_000L, sessionToken = token,
      mediaActions = android.media.session.PlaybackState.ACTION_PAUSE)

  private fun pause(snapshot: MusicPlayerSnapshot) = JSONObject()
      .put("command", "pause").put("sourceId", snapshot.sourceId)
      .put("sourceRevision", snapshot.presentationRevision).put("trackId", snapshot.track!!.id)

  @Test fun replacementSessionInvalidatesRemoteCommandsAndPendingStateEvenWithTheSameAppAndTrack() {
    withController { controller, publish ->
      val first = mock(MediaSession.Token::class.java)
      val second = mock(MediaSession.Token::class.java)
      NowPlayingHub.publish(session(first)); publish()
      val old = controller.store.current
      val body = pause(old)
      assertTrue(MusicRemoteProtocol.decode(body, old, controller.sourceRevision)
          is MusicRemoteProtocol.Decoded.Accepted)
      assertTrue(controller.store.beginCommand(8L, MusicCommand.PAUSE, 5_000L,
          MusicConfirmation.Playback(PlaybackState.PAUSED)))
      NowPlayingHub.publish(session(second)); publish()
      val current = controller.store.current
      assertEquals(old.sourceId, current.sourceId)
      assertEquals(old.track!!.id, current.track!!.id)
      assertEquals(second, current.localSessionToken)
      assertNotEquals(old.presentationRevision, controller.sourceRevision)
      assertNull(current.pending)
      assertEquals(MusicRemoteProtocol.Decoded.Rejected(409, "stale"),
          MusicRemoteProtocol.decode(body, current, controller.sourceRevision))
    }
  }

  @Test fun progressUpdatesWithinTheSameSessionKeepTheRemoteRevisionAndValidControls() {
    withController { controller, publish ->
      val token = mock(MediaSession.Token::class.java)
      NowPlayingHub.publish(session(token)); publish()
      val old = controller.store.current
      NowPlayingHub.publish(session(token).copy(positionMs = 20_000L)); publish()
      val current = controller.store.current
      assertEquals(old.presentationRevision, controller.sourceRevision)
      assertEquals(old.presentationRevision, current.presentationRevision)
      assertEquals(20_000L, current.positionMs)
      assertTrue(MusicRemoteProtocol.decode(pause(old), current, controller.sourceRevision)
          is MusicRemoteProtocol.Decoded.Accepted)
    }
  }

  @Test fun losingASessionTokenInvalidatesPreviouslyCapturedRemoteControls() {
    withController { controller, publish ->
      val token = mock(MediaSession.Token::class.java)
      NowPlayingHub.publish(session(token)); publish()
      val old = controller.store.current
      NowPlayingHub.publish(session(null)); publish()
      assertEquals(MusicRemoteProtocol.Decoded.Rejected(409, "stale"),
          MusicRemoteProtocol.decode(pause(old), controller.store.current, controller.sourceRevision))
    }
  }

  private fun maSnapshot() = MusicPlayerSnapshot(
      sourceId = "ma:family", connection = MusicConnection.READY,
      controlPlayerId = "ma:family", queueId = "queue-1",
      track = MusicTrack("same-track", "ma:family", "Track", durationMs = 120_000L),
      playback = PlaybackState.PLAYING, positionMs = 10_000L,
      queue = listOf(MusicQueueItem("item-1", "Track")),
      capabilities = MusicCapabilities(play = true, pause = true, queue = true))

  private fun publishMa(controller: MusicPlayerController, snapshot: MusicPlayerSnapshot) {
    MusicPlayerController::class.java.getDeclaredMethod("publishSnapshot", MusicPlayerSnapshot::class.java)
        .apply { isAccessible = true }.invoke(controller, snapshot)
  }

  private fun assertMaBindingReplacement(replacement: (MusicPlayerSnapshot) -> MusicPlayerSnapshot) {
    withController { controller, _ ->
      publishMa(controller, maSnapshot())
      val old = controller.store.current
      val body = pause(old)
      assertTrue(controller.store.beginCommand(12L, MusicCommand.PAUSE, 5_000L,
          MusicConfirmation.Playback(PlaybackState.PAUSED)))
      publishMa(controller, replacement(old).copy(pending = null))
      val current = controller.store.current
      assertEquals(old.sourceId, current.sourceId)
      assertEquals(old.track!!.id, current.track!!.id)
      assertNotEquals(old.presentationRevision, current.presentationRevision)
      assertNull(current.pending)
      assertEquals(MusicRemoteProtocol.Decoded.Rejected(409, "stale"),
          MusicRemoteProtocol.decode(body, current, controller.sourceRevision))
    }
  }

  @Test fun replacementMaLeaderInvalidatesPreviouslyRenderedTransport() {
    assertMaBindingReplacement { it.copy(controlPlayerId = "ma:living") }
  }

  @Test fun replacementMaQueueInvalidatesTransportEvenWhenTheTrackIdIsReused() {
    assertMaBindingReplacement { it.copy(queueId = "queue-2") }
  }

  @Test fun unchangedMaPlaybackBindingKeepsRevisionDuringProgressAndVolumeUpdates() {
    withController { controller, _ ->
      publishMa(controller, maSnapshot())
      val old = controller.store.current
      publishMa(controller, old.copy(positionMs = 20_000L,
          rooms = listOf(MusicRoom("ma:family", "Family", true, volume = 20))))
      assertEquals(old.presentationRevision, controller.sourceRevision)
      assertEquals(old.presentationRevision, controller.store.current.presentationRevision)
      assertTrue(MusicRemoteProtocol.decode(pause(old), controller.store.current, controller.sourceRevision)
          is MusicRemoteProtocol.Decoded.Accepted)
    }
  }
}
