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

class MusicSleepTest {
  private val minute = 60_000L
  private fun reduce(s: YtEngineState, a: YtAction) = YtEngineReducer.reduce(s, a)

  private fun playing(): YtEngineState {
    val ready = YtEngineState(status = YtPlayerStatus.UNSTARTED, foreground = true)
    val loaded = reduce(ready, YtAction.PlayNow((1..3).map(::ytTrack), 0, "Mix")).state
    return reduce(loaded, YtPlayerEvent.StateChanged(YtPlayerStatus.PLAYING)).state
  }

  private fun armed(choice: MusicSleepChoice) =
      reduce(playing(), YtAction.SetSleep(MusicSleepPolicy.arm(choice, 0))).state

  @Test fun choicesMatchThePhoneWire() {
    assertEquals(listOf(0, 15, 30, 60, -1), MusicSleepChoice.entries.map { it.minutes })
    assertEquals(MusicSleepChoice.END_OF_SONG, MusicSleepChoice.fromMinutes(-1))
    assertNull(MusicSleepChoice.fromMinutes(45))
  }

  @Test fun armSetsTheStopTime() {
    assertNull(MusicSleepPolicy.arm(MusicSleepChoice.OFF, 5))
    assertEquals(MusicSleepTimer(MusicSleepChoice.MIN_30, 5 + 30 * minute), MusicSleepPolicy.arm(MusicSleepChoice.MIN_30, 5))
    val end = MusicSleepPolicy.arm(MusicSleepChoice.END_OF_SONG, 5)!!
    assertTrue(end.endOfSong)
    assertNull(end.untilMs)
    assertEquals(MusicSleepChoice.END_OF_SONG, MusicSleepPolicy.choice(end))
    assertEquals(MusicSleepChoice.OFF, MusicSleepPolicy.choice(null))
  }

  @Test fun timedPhaseWaitsFadesThenStops() {
    val timer = MusicSleepPolicy.arm(MusicSleepChoice.MIN_15, 0)!!
    val stop = 15 * minute
    assertEquals(MusicSleepPhase.Waiting, MusicSleepPolicy.phase(timer, stop - MusicSleepPolicy.FADE_MS - 1, null))
    assertEquals(MusicSleepPhase.Fading(0f), MusicSleepPolicy.phase(timer, stop - MusicSleepPolicy.FADE_MS, null))
    assertEquals(MusicSleepPhase.Fading(0.5f), MusicSleepPolicy.phase(timer, stop - 5_000, null))
    assertEquals(MusicSleepPhase.Stop, MusicSleepPolicy.phase(timer, stop, 99_000))
  }

  @Test fun endOfSongFadesOverTheLastSecondsAndStopsAfterAGrace() {
    val timer = MusicSleepPolicy.arm(MusicSleepChoice.END_OF_SONG, 0)!!
    assertEquals(MusicSleepPhase.Waiting, MusicSleepPolicy.phase(timer, 1_000, null))
    assertEquals(MusicSleepPhase.Waiting, MusicSleepPolicy.phase(timer, 1_000, 60_000))
    assertEquals(MusicSleepPhase.Fading(0.5f), MusicSleepPolicy.phase(timer, 1_000, 5_000))
    assertEquals(MusicSleepPhase.Fading(1f), MusicSleepPolicy.phase(timer, 1_000, 0))
    assertEquals(MusicSleepPhase.Stop, MusicSleepPolicy.phase(timer, 1_000, -MusicSleepPolicy.END_GRACE_MS))
  }

  @Test fun fadeStepGoesToSilence() {
    assertEquals(12, MusicSleepPolicy.fadeStep(12, 0f))
    assertEquals(6, MusicSleepPolicy.fadeStep(12, 0.5f))
    assertEquals(0, MusicSleepPolicy.fadeStep(12, 1f))
    assertEquals(0, MusicSleepPolicy.fadeStep(12, 3f))
    assertEquals(0, MusicSleepPolicy.fadeStep(-4, 0f))
  }

  @Test fun labelRoundsUp() {
    val timer = MusicSleepPolicy.arm(MusicSleepChoice.MIN_30, 0)!!
    assertEquals("Stops in 30 min", MusicSleepPolicy.label(timer, 0))
    assertEquals("Stops in 23 min", MusicSleepPolicy.label(timer, 7 * minute + 1))
    assertEquals("Stops in 1 min", MusicSleepPolicy.label(timer, 30 * minute + 5))
    assertEquals("Stops after this song", MusicSleepPolicy.label(MusicSleepPolicy.arm(MusicSleepChoice.END_OF_SONG, 0), 0))
    assertNull(MusicSleepPolicy.label(null, 0))
  }

  @Test fun pauseClearLeavingAndPlayerLossCancel() {
    val s = armed(MusicSleepChoice.MIN_15)
    assertTrue(s.sleep != null)
    assertNull(reduce(s, YtAction.Pause).state.sleep)
    assertNull(reduce(s, YtAction.Clear).state.sleep)
    assertNull(reduce(s, YtAction.Foreground(false)).state.sleep)
    assertNull(reduce(s, YtAction.PlayerLost).state.sleep)
    assertNull(reduce(s, YtAction.SetSleep(null)).state.sleep)
  }

  @Test fun queueChangesKeepTheTimer() {
    val s = armed(MusicSleepChoice.MIN_15)
    assertEquals(s.sleep, reduce(s, YtAction.Next).state.sleep)
    assertEquals(s.sleep, reduce(s, YtAction.AddToEnd(ytTrack(9))).state.sleep)
    assertEquals(s.sleep, reduce(s, YtPlayerEvent.StateChanged(YtPlayerStatus.ENDED)).state.sleep)
  }

  @Test fun endOfSongStopsInsteadOfAdvancingAndCuesTheNextSong() {
    val step = reduce(armed(MusicSleepChoice.END_OF_SONG), YtPlayerEvent.StateChanged(YtPlayerStatus.ENDED))
    assertNull(step.state.sleep)
    assertFalse(step.state.wantsPlay)
    assertEquals(ytTrack(2).videoId, step.state.queue.current?.track?.videoId)
    assertEquals(listOf(YtEffect.Cue(ytTrack(2).videoId, 0), YtEffect.Persist), step.effects)
  }

  @Test fun endOfSongOnTheLastTrackJustStops() {
    val ready = YtEngineState(status = YtPlayerStatus.UNSTARTED, foreground = true)
    val one = reduce(reduce(ready, YtAction.PlayNow(listOf(ytTrack(1)), 0, "")).state,
        YtPlayerEvent.StateChanged(YtPlayerStatus.PLAYING)).state
    val armedOne = reduce(one, YtAction.SetSleep(MusicSleepPolicy.arm(MusicSleepChoice.END_OF_SONG, 0))).state
    val step = reduce(armedOne, YtPlayerEvent.StateChanged(YtPlayerStatus.ENDED))
    assertNull(step.state.sleep)
    assertFalse(step.state.wantsPlay)
    assertEquals(listOf(YtEffect.Persist), step.effects)
  }
}
