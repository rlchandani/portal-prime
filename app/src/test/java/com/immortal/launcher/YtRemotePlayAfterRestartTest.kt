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

/**
 * After the Portal app restarts (e.g. an update) the saved queue is only on disk until the music
 * screen opens. A phone "play" must restore it and play once visible, or say nothing is queued.
 */
class YtRemotePlayAfterRestartTest {
  private fun reduce(s: YtEngineState, a: YtAction) = YtEngineReducer.reduce(s, a)

  @Test fun loadedQueuePlaysDirectly() {
    assertEquals(YtRemotePlayPlan.PLAY, YtRemoteProtocol.playPlan(engineHasCurrent = true, savedHasCurrent = false))
    assertEquals(YtRemotePlayPlan.PLAY, YtRemoteProtocol.playPlan(engineHasCurrent = true, savedHasCurrent = true))
  }

  @Test fun emptyEngineWithASavedQueueRestoresItFirst() {
    assertEquals(YtRemotePlayPlan.RESTORE_THEN_PLAY,
        YtRemoteProtocol.playPlan(engineHasCurrent = false, savedHasCurrent = true))
  }

  @Test fun nothingSavedIsDeclinedAsNothingQueued() {
    assertEquals(YtRemotePlayPlan.NOTHING_QUEUED,
        YtRemoteProtocol.playPlan(engineHasCurrent = false, savedHasCurrent = false))
    val reply = YtRemoteProtocol.nothingQueued()
    assertTrue(reply.getBoolean("ok"))
    assertEquals("declined", reply.getString("status"))
    assertEquals("nothing_queued", reply.getString("reason"))
  }

  @Test fun restoredQueuePlaysOnceTheMusicScreenIsVisible() {
    val cold = YtEngineState()
    val restored = reduce(cold, YtAction.Restore(ytQueueOf(3, start = 1), 12_000)).state
    assertEquals(2L, restored.queue.current?.id)
    val pending = reduce(restored, YtAction.PlayWhenVisible)
    assertTrue(pending.state.playWhenVisible)
    assertTrue("nothing plays hidden", pending.effects.none { it == YtEffect.Play || it is YtEffect.Load })
    val shown = reduce(pending.state, YtAction.Foreground(true)).state
    assertTrue(shown.wantsPlay)
    assertFalse(shown.playWhenVisible)
    val ready = reduce(shown, YtPlayerEvent.Ready)
    assertTrue(ready.effects.any { it is YtEffect.Load && it.videoId == ytTrack(2).videoId })
  }
}
