/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Remote ("from the phone") intents: play only ever starts on the visible player. */
class YtEngineRemoteTest {
  private fun reduce(s: YtEngineState, a: YtAction) = YtEngineReducer.reduce(s, a)
  private val ready = YtEngineState(status = YtPlayerStatus.PAUSED, queue = ytQueueOf(3))

  @Test fun playWhenVisible_onScreen_playsAtOnce() {
    val step = reduce(ready.copy(foreground = true), YtAction.PlayWhenVisible)
    assertTrue(step.state.wantsPlay)
    assertEquals(listOf(YtEffect.Play), step.effects)
  }

  @Test fun playWhenVisible_offScreen_waitsForTheMusicScreen() {
    val step = reduce(ready, YtAction.PlayWhenVisible)
    assertFalse(step.state.wantsPlay)
    assertTrue(step.state.playWhenVisible)
    assertTrue("nothing plays hidden", step.effects.none { it == YtEffect.Play || it is YtEffect.Load })
    val shown = reduce(step.state, YtAction.Foreground(true))
    assertTrue(shown.state.wantsPlay)
    assertFalse(shown.state.playWhenVisible)
    assertEquals(listOf(YtEffect.Play), shown.effects)
  }

  @Test fun playWhenVisible_withEmptyQueue_isIgnored() {
    val empty = YtEngineState(status = YtPlayerStatus.UNSTARTED)
    assertSame(empty, reduce(empty, YtAction.PlayWhenVisible).state)
  }

  @Test fun pauseCancelsAPendingRemotePlay() {
    val pending = reduce(ready, YtAction.PlayWhenVisible).state
    assertFalse(reduce(pending, YtAction.Pause).state.playWhenVisible)
  }

  @Test fun playEntryNext_movesALaterEntryRightAfterCurrent() {
    val step = reduce(ready.copy(queue = ytQueueOf(4)), YtAction.PlayEntryNext(4))
    assertEquals(listOf(1L, 4L, 2L, 3L), step.state.queue.entries.map { it.id })
    assertEquals(0, step.state.queue.currentIndex)
    assertEquals(listOf(YtEffect.Persist), step.effects)
  }

  @Test fun playEntryNext_movesAnEarlierEntryAndKeepsCurrent() {
    val step = reduce(ready.copy(queue = ytQueueOf(4, start = 2)), YtAction.PlayEntryNext(1))
    assertEquals(listOf(2L, 3L, 1L, 4L), step.state.queue.entries.map { it.id })
    assertEquals(3L, step.state.queue.current?.id)
  }

  @Test fun playEntryNext_currentOrUnknownChangesNothing() {
    val s = ready.copy(queue = ytQueueOf(3))
    assertSame(s, reduce(s, YtAction.PlayEntryNext(1)).state)
    assertSame(s, reduce(s, YtAction.PlayEntryNext(42)).state)
  }
}
