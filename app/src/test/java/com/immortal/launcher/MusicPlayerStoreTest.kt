/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class MusicPlayerStoreTest {
  private fun ready(source: String = "ma:family") = MusicPlayerSnapshot(
      connection = MusicConnection.READY, sourceId = source,
      track = MusicTrack("one", source, "One", durationMs = 120_000),
      playback = PlaybackState.PLAYING,
      capabilities = MusicCapabilities(play = true, pause = true, rooms = true, volume = true))

  @Test fun oldGenerationCannotReplaceNewPlayerEvenWhenTheSourceNameMatches() {
    val store = MusicPlayerStore()
    val old = store.selectSource("ma:family")
    val fresh = store.selectSource("ma:family")
    assertFalse(store.publish(old, ready()))
    assertTrue(store.publish(fresh, ready()))
    assertEquals("One", store.current.track?.title)
  }

  @Test fun otherSourcesCannotPublishIntoSelectedPlayer() {
    val store = MusicPlayerStore()
    val generation = store.selectSource("ma:family")
    assertFalse(store.publish(generation, ready("ma:living")))
    assertEquals(MusicConnection.CONNECTING, store.current.connection)
  }

  @Test fun queuesAreCopiedAndBoundedAtPublication() {
    val queue = MutableList(600) { MusicQueueItem("item:$it", "Track $it") }
    val store = MusicPlayerStore()
    val generation = store.selectSource("ma:family")
    assertTrue(store.publish(generation, ready().copy(queue = queue)))
    queue.clear()
    assertEquals(500, store.current.queue.size)
    assertEquals("item:499", store.current.queue.last().id)
  }

  @Test fun successfulReplyAloneDoesNotConfirmGroupMembership() {
    val store = MusicPlayerStore()
    val generation = store.selectSource("ma:family")
    store.publish(generation, ready())
    assertTrue(store.beginCommand(7, MusicCommand.CHANGE_ROOMS, 1_000,
        MusicConfirmation.Group("ma:family", setOf("ma:family", "ma:living"))))
    assertTrue(store.acknowledge(generation, 7))
    assertNotNull(store.current.pending)
    store.publish(generation, ready().copy(rooms = listOf(
        MusicRoom("ma:family", "Family", true, groupId = "ma:family"))))
    assertNotNull(store.current.pending)
    store.publish(generation, ready().copy(rooms = listOf(
        MusicRoom("ma:family", "Family", true, groupId = "ma:family"),
        MusicRoom("ma:living", "Living", true, groupId = "ma:family"))))
    assertNull(store.current.pending)
  }

  @Test fun pendingChangeRejectsDuplicateCommandsAndStaleFailures() {
    val store = MusicPlayerStore()
    val generation = store.selectSource("ma:family")
    store.publish(generation, ready())
    assertTrue(store.beginCommand(7, MusicCommand.PAUSE, 1_000,
        MusicConfirmation.Playback(PlaybackState.PAUSED)))
    assertFalse(store.beginCommand(8, MusicCommand.PAUSE, 1_001,
        MusicConfirmation.Playback(PlaybackState.PAUSED)))
    assertFalse(store.reject(generation, 8, "Wrong reply"))
    assertNotNull(store.current.pending)
    assertTrue(store.reject(generation, 7, "Player rejected command"))
    assertNull(store.current.pending)
    assertEquals("Player rejected command", store.current.problem)
  }

  @Test fun offlineOrBusyStateCannotStartACommand() {
    val store = MusicPlayerStore()
    val generation = store.selectSource("ma:family")
    for (s in listOf(ready().copy(connection = MusicConnection.OFFLINE),
        ready().copy(audioOwnedByOther = true))) {
      store.publish(generation, s)
      assertFalse(store.beginCommand(7, MusicCommand.PAUSE, 1_000,
          MusicConfirmation.Playback(PlaybackState.PAUSED)))
    }
  }

  @Test fun volumeConfirmationValidatesRangeAndCorrectRoom() {
    assertThrows(IllegalArgumentException::class.java) { MusicConfirmation.Volume("ma:family", 101) }
    val target = MusicConfirmation.Volume("ma:family", 25)
    assertFalse(target.matches(ready().copy(rooms = listOf(
        MusicRoom("ma:living", "Living", true, volume = 25)))))
    assertFalse(target.matches(ready().copy(rooms = listOf(
        MusicRoom("ma:family", "Family", true, volume = 20)))))
    assertTrue(target.matches(ready().copy(rooms = listOf(
        MusicRoom("ma:family", "Family", true, volume = 25)))))
  }

  @Test fun unconfirmedCommandTimesOutWithoutPretendingItSucceeded() {
    val store = MusicPlayerStore()
    val generation = store.selectSource("ma:family")
    store.publish(generation, ready())
    store.beginCommand(7, MusicCommand.PAUSE, 1_000,
        MusicConfirmation.Playback(PlaybackState.PAUSED))
    assertFalse(store.expirePending(5_999))
    assertTrue(store.expirePending(6_000))
    assertNull(store.current.pending)
    assertEquals(PlaybackState.PLAYING, store.current.playback)
    assertNotNull(store.current.problem)
  }
}
