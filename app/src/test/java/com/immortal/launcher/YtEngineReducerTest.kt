/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtEngineReducerTest {
  private fun reduce(s: YtEngineState, a: YtAction) = YtEngineReducer.reduce(s, a)

  /** Ready, foreground, with a 3-track queue loaded and playing track 1. */
  private fun playing(count: Int = 3): YtEngineState {
    val ready = YtEngineState(status = YtPlayerStatus.UNSTARTED, foreground = true)
    val loaded = reduce(ready, YtAction.PlayNow((1..count).map(::ytTrack), 0, "Mix")).state
    return reduce(loaded, YtPlayerEvent.StateChanged(YtPlayerStatus.PLAYING)).state
  }

  private fun id(n: Int) = ytTrack(n).videoId

  @Test fun playNow_beforeReady_waitsThenLoadsOnReady() {
    val step = reduce(YtEngineState(foreground = true), YtAction.PlayNow(listOf(ytTrack(1)), 0, "L"))
    assertTrue(step.state.wantsPlay)
    assertEquals(listOf(YtEffect.Persist), step.effects)
    val ready = reduce(step.state, YtPlayerEvent.Ready)
    assertEquals(listOf(YtEffect.Load(id(1), 0)), ready.effects)
  }

  @Test fun playNow_whenReady_loads() {
    val s = YtEngineState(status = YtPlayerStatus.UNSTARTED, foreground = true)
    val step = reduce(s, YtAction.PlayNow(listOf(ytTrack(1), ytTrack(2)), 1, "L"))
    assertEquals(listOf(YtEffect.Load(id(2), 0), YtEffect.Persist), step.effects)
  }

  @Test fun playNow_inBackground_onlyCues() {
    val s = YtEngineState(status = YtPlayerStatus.UNSTARTED, foreground = false)
    val step = reduce(s, YtAction.PlayNow(listOf(ytTrack(1)), 0, "L"))
    assertFalse(step.state.wantsPlay)
    assertEquals(YtEffect.Cue(id(1), 0), step.effects.first())
  }

  @Test fun restore_cuesPausedAtSavedPosition_andIsIgnoredWhenQueueExists() {
    val restored = reduce(YtEngineState(foreground = true), YtAction.Restore(ytQueueOf(2, start = 1), 42_000)).state
    assertFalse(restored.wantsPlay)
    assertEquals(42_000, restored.positionMs)
    assertEquals(listOf(YtEffect.Cue(id(2), 42_000)), reduce(restored, YtPlayerEvent.Ready).effects)
    val busy = playing()
    assertEquals(busy, reduce(busy, YtAction.Restore(ytQueueOf(5), 1)).state)
  }

  @Test fun pause_dropsIntentAndPersists() {
    val step = reduce(playing(), YtAction.Pause)
    assertFalse(step.state.wantsPlay)
    assertEquals(listOf(YtEffect.Pause, YtEffect.Persist), step.effects)
  }

  @Test fun play_isRefusedInBackgroundOrWhenUndersized() {
    val paused = reduce(reduce(playing(), YtAction.Pause).state, YtPlayerEvent.StateChanged(YtPlayerStatus.PAUSED)).state
    assertEquals(listOf(YtEffect.Play), reduce(paused, YtAction.Play).effects)
    assertTrue(reduce(paused.copy(foreground = false), YtAction.Play).effects.isEmpty())
    assertTrue(reduce(paused.copy(sizeOk = false), YtAction.Play).effects.isEmpty())
  }

  @Test fun play_afterEndOfQueue_replaysCurrentFromStart() {
    val ended = reduce(playing(1), YtPlayerEvent.StateChanged(YtPlayerStatus.ENDED)).state
    assertEquals(YtHalt.EndOfQueue, ended.halt)
    val step = reduce(ended, YtAction.Play)
    assertNull(step.state.halt)
    assertEquals(listOf(YtEffect.Load(id(1), 0)), step.effects)
  }

  @Test fun leavingForeground_pauses_andLatePlayingIsPausedAgain() {
    val step = reduce(playing(), YtAction.Foreground(false))
    assertFalse(step.state.wantsPlay)
    assertEquals(listOf(YtEffect.Pause, YtEffect.Persist), step.effects)
    val late = reduce(step.state, YtPlayerEvent.StateChanged(YtPlayerStatus.PLAYING))
    assertEquals(listOf(YtEffect.Pause), late.effects)
    assertTrue(reduce(step.state, YtAction.Foreground(true)).effects.isEmpty())
  }

  @Test fun next_loadsFollowing_andAtEndDoesNothing() {
    assertEquals(YtEffect.Load(id(2), 0), reduce(playing(), YtAction.Next).effects.first())
    val last = reduce(reduce(playing(2), YtAction.Next).state, YtPlayerEvent.StateChanged(YtPlayerStatus.PLAYING)).state
    val atEnd = reduce(last, YtAction.Next)
    assertEquals(last, atEnd.state)
    assertTrue(atEnd.effects.isEmpty())
  }

  @Test fun previous_restartsPastThreeSeconds_elseLoadsPrevious() {
    val second = reduce(playing(), YtAction.Next).state
    val late = second.copy(positionMs = 10_000)
    assertEquals(listOf(YtEffect.Seek(0)), reduce(late, YtAction.Previous).effects)
    assertEquals(YtEffect.Load(id(1), 0), reduce(second.copy(positionMs = 1_000), YtAction.Previous).effects.first())
  }

  @Test fun ended_autoAdvances_repeatOneRestarts() {
    val step = reduce(playing(), YtPlayerEvent.StateChanged(YtPlayerStatus.ENDED))
    assertEquals(YtEffect.Load(id(2), 0), step.effects.first())
    val one = reduce(playing(), YtAction.SetRepeat(YtRepeat.ONE)).state
    assertEquals(listOf(YtEffect.Seek(0), YtEffect.Play),
        reduce(one, YtPlayerEvent.StateChanged(YtPlayerStatus.ENDED)).effects)
  }

  @Test fun embedError_marksUnplayable_notifies_andAdvances() {
    val step = reduce(playing(), YtPlayerEvent.Error(150))
    val first = step.state.queue.entries[0]
    assertFalse(first.playable)
    assertEquals(1, step.state.consecutiveFailures)
    assertEquals("Song 1", step.state.lastSkip?.title)
    assertEquals(1, step.state.lastSkip?.seq)
    assertEquals(YtEffect.Load(id(2), 0), step.effects.first())
  }

  @Test fun skippableCodes_areTheDocumentedOnes() {
    for (code in listOf(2, 5, 100, 101, 150)) {
      assertEquals("code $code", 1, reduce(playing(), YtPlayerEvent.Error(code)).state.consecutiveFailures)
    }
  }

  @Test fun threeFailuresInARow_stopWithClearState() {
    var s = playing(6)
    repeat(2) { s = reduce(s, YtPlayerEvent.Error(101)).state }
    val third = reduce(s, YtPlayerEvent.Error(101))
    assertEquals(YtHalt.TooManyFailures(3), third.state.halt)
    assertFalse(third.state.wantsPlay)
    assertEquals(YtEffect.Stop, third.effects.first())
  }

  @Test fun successfulPlay_resetsFailureCount() {
    val failed = reduce(playing(), YtPlayerEvent.Error(150)).state
    val ok = reduce(failed, YtPlayerEvent.StateChanged(YtPlayerStatus.PLAYING)).state
    assertEquals(0, ok.consecutiveFailures)
  }

  @Test fun errorOnLastPlayable_haltsAtEndOfQueue() {
    val step = reduce(playing(1), YtPlayerEvent.Error(100))
    assertEquals(YtHalt.EndOfQueue, step.state.halt)
    assertEquals(YtEffect.Stop, step.effects.first())
  }

  @Test fun otherErrors_haltWithoutSkipping() {
    val step = reduce(playing(), YtPlayerEvent.Error(153))
    assertEquals(YtHalt.PlayerError(153), step.state.halt)
    assertTrue(step.state.queue.entries.all { it.playable })
    assertEquals(YtEffect.Stop, step.effects.first())
  }

  @Test fun progress_updatesPositionDurationAndVideoId() {
    val s = reduce(playing(), YtPlayerEvent.Progress(12_000, 200_000, id(1), elapsedMs = 5_000)).state
    assertEquals(12_000, s.positionMs)
    assertEquals(200_000, s.durationMs)
    assertEquals(5_000, s.positionAtElapsedMs)
    assertFalse(s.isUnknownItem)
    val ad = reduce(s, YtPlayerEvent.Progress(1_000, 15_000, "adAdAdAdAd1", elapsedMs = 6_000)).state
    assertTrue(ad.isUnknownItem)
  }

  @Test fun playerLost_keepsPosition_thenReadyCuesPaused() {
    val s = playing().copy(positionMs = 30_000)
    val lost = reduce(s, YtAction.PlayerLost)
    assertEquals(YtPlayerStatus.NOT_READY, lost.state.status)
    assertFalse(lost.state.wantsPlay)
    assertEquals(listOf(YtEffect.Persist), lost.effects)
    assertEquals(listOf(YtEffect.Cue(id(1), 30_000)), reduce(lost.state, YtPlayerEvent.Ready).effects)
  }

  @Test fun removeCurrent_loadsReplacement_removeLastStops() {
    val s = playing()
    val step = reduce(s, YtAction.Remove(s.queue.entries[0].id))
    assertEquals(YtEffect.Load(id(2), 0), step.effects.first())
    val other = reduce(s, YtAction.Remove(s.queue.entries[2].id))
    assertEquals(listOf(YtEffect.Persist), other.effects)
    val single = playing(1)
    assertEquals(YtEffect.Stop, reduce(single, YtAction.Remove(single.queue.entries[0].id)).effects.first())
  }

  @Test fun undersized_pausesPlayback() {
    val step = reduce(playing(), YtAction.SizeChanged(ok = false))
    assertFalse(step.state.sizeOk)
    assertEquals(YtEffect.Pause, step.effects.first())
  }

  @Test fun seek_clampsToDuration() {
    val s = playing().copy(durationMs = 100_000)
    assertEquals(listOf(YtEffect.Seek(100_000)), reduce(s, YtAction.SeekTo(500_000)).effects)
    assertEquals(listOf(YtEffect.Seek(0)), reduce(s, YtAction.SeekTo(-5)).effects)
  }

  @Test fun shuffle_keepsCurrentPlaying_withoutReload() {
    val step = reduce(playing(5), YtAction.SetShuffle(on = true, seed = 9))
    assertTrue(step.state.queue.isShuffled)
    assertEquals(listOf(YtEffect.Persist), step.effects)
  }

  @Test fun clear_stopsAndPersists() {
    val step = reduce(playing(), YtAction.Clear)
    assertTrue(step.state.queue.isEmpty)
    assertEquals(listOf(YtEffect.Stop, YtEffect.Persist), step.effects)
  }

  @Test fun select_loadsEntry() {
    val s = playing()
    assertEquals(YtEffect.Load(id(3), 0), reduce(s, YtAction.Select(s.queue.entries[2].id)).effects.first())
  }

  @Test fun autoplayBlocked_flagsUntilPlaying() {
    val blocked = reduce(playing(), YtPlayerEvent.AutoplayBlocked).state
    assertTrue(blocked.autoplayBlocked)
    assertFalse(reduce(blocked, YtPlayerEvent.StateChanged(YtPlayerStatus.PLAYING)).state.autoplayBlocked)
  }

  @Test fun apiUnavailable_halts() {
    assertEquals(YtHalt.PlayerUnavailable, reduce(playing(), YtPlayerEvent.ApiUnavailable).state.halt)
  }

  @Test fun pausedByPlayerItself_dropsIntent() {
    val s = reduce(playing(), YtPlayerEvent.StateChanged(YtPlayerStatus.PAUSED)).state
    assertFalse(s.wantsPlay)
  }

  @Test fun playTrackNow_onEmptyQueue_isJustThatSong() {
    val ready = YtEngineState(status = YtPlayerStatus.UNSTARTED, foreground = true)
    val step = reduce(ready, YtAction.PlayTrackNow(ytTrack(7), "From your phone"))
    assertEquals(listOf(id(7)), step.state.queue.entries.map { it.track.videoId })
    assertEquals("From your phone", step.state.queue.sourceLabel)
    assertEquals(YtEffect.Load(id(7), 0), step.effects.first())
  }

  @Test fun playTrackNow_keepsUpNextAfterIt() {
    val step = reduce(playing(), YtAction.PlayTrackNow(ytTrack(7), "Search · x"))
    assertEquals(listOf(id(1), id(7), id(2), id(3)), step.state.queue.entries.map { it.track.videoId })
    assertEquals(id(7), step.state.queue.current?.track?.videoId)
    assertEquals(YtEffect.Load(id(7), 0), step.effects.first())
    assertEquals("Mix", step.state.queue.sourceLabel)
  }

  @Test fun playTrackNow_ignoresAnInvalidVideo() {
    val s = playing()
    assertEquals(s, reduce(s, YtAction.PlayTrackNow(YtTrack("bad", "x", "y"), "L")).state)
  }
}
