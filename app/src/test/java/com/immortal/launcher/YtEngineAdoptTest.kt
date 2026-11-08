/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A queue handed over from another Portal ("Move to…") replaces this one and resumes at its position. */
class YtEngineAdoptTest {
  private fun reduce(s: YtEngineState, a: YtAction) = YtEngineReducer.reduce(s, a)
  private fun id(n: Int) = ytTrack(n).videoId

  @Test fun adoptOnVisibleReadyPlayer_loadsAtThePosition() {
    val s = YtEngineState(status = YtPlayerStatus.UNSTARTED, foreground = true)
    val step = reduce(s, YtAction.Adopt(ytQueueOf(3, start = 1), 61_000))
    assertEquals(listOf(YtEffect.Load(id(2), 61_000), YtEffect.Persist), step.effects)
    assertTrue(step.state.wantsPlay)
    assertEquals(61_000, step.state.positionMs)
  }

  @Test fun adoptReplacesAnExistingQueue() {
    val playing = reduce(YtEngineState(status = YtPlayerStatus.UNSTARTED, foreground = true),
        YtAction.PlayNow(listOf(ytTrack(9)), 0, "Old")).state
    val step = reduce(playing, YtAction.Adopt(ytQueueOf(2, label = "From Family"), 5_000))
    assertEquals(listOf(id(1), id(2)), step.state.queue.entries.map { it.track.videoId })
    assertEquals("From Family", step.state.queue.sourceLabel)
  }

  @Test fun adoptWhileHidden_cuesThenPlaysOnceTheScreenIsVisible() {
    val hidden = YtEngineState(status = YtPlayerStatus.UNSTARTED, foreground = false)
    val adopted = reduce(hidden, YtAction.Adopt(ytQueueOf(2), 30_000))
    assertEquals(listOf(YtEffect.Cue(id(1), 30_000), YtEffect.Persist), adopted.effects)
    assertFalse(adopted.state.wantsPlay)
    assertTrue(adopted.state.playWhenVisible)
    val shown = reduce(adopted.state, YtAction.Foreground(true))
    assertEquals(listOf(YtEffect.Play), shown.effects)
    assertTrue(shown.state.wantsPlay)
    assertFalse(shown.state.playWhenVisible)
  }

  @Test fun adoptBeforeThePlayerExists_playsFromThePositionOnReady() {
    val adopted = reduce(YtEngineState(), YtAction.Adopt(ytQueueOf(2), 12_000)).state
    val shown = reduce(adopted, YtAction.Foreground(true)).state
    val ready = reduce(shown, YtPlayerEvent.Ready)
    assertEquals(listOf(YtEffect.Load(id(1), 12_000)), ready.effects)
  }

  @Test fun pauseOrClearCancelsThePendingStart() {
    val adopted = reduce(YtEngineState(status = YtPlayerStatus.UNSTARTED), YtAction.Adopt(ytQueueOf(2), 0)).state
    assertFalse(reduce(adopted, YtAction.Pause).state.playWhenVisible)
    assertFalse(reduce(adopted, YtAction.Clear).state.playWhenVisible)
  }

  @Test fun emptyQueueIsIgnoredAndNegativePositionClamps() {
    val s = YtEngineState(status = YtPlayerStatus.UNSTARTED, foreground = true)
    assertEquals(YtStep(s), reduce(s, YtAction.Adopt(YtQueue.EMPTY, 1_000)))
    assertEquals(0L, reduce(s, YtAction.Adopt(ytQueueOf(1), -5)).state.positionMs)
  }

  @Test fun adoptClearsAnOldHaltAndFailureCount() {
    val halted = YtEngineState(status = YtPlayerStatus.UNSTARTED, foreground = true, halt = YtHalt.EndOfQueue,
        consecutiveFailures = 3, autoplayBlocked = true)
    val step = reduce(halted, YtAction.Adopt(ytQueueOf(1), 0)).state
    assertEquals(null, step.halt)
    assertEquals(0, step.consecutiveFailures)
    assertFalse(step.autoplayBlocked)
  }
}
