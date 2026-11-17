/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.SharedTimerMerge.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dedupe by id, last writer wins by sequence, deterministic ties, tombstones that stay dead. */
class SharedTimerMergeTest {
  private val now = 5_000_000L
  private val pasta = SharedTimerOps.create("aaaaaaaa", "Pasta", "Kitchen", "k", 600_000L, now, "k")!!

  @Test fun newTimerIsAddedAndExactRepeatIsADuplicate() {
    val first = SharedTimerMerge.merge(emptyList(), pasta)
    assertEquals(Outcome.APPLIED, first.outcome)
    // A second relay re-anchored a little later must not move the countdown.
    val repeat = SharedTimerMerge.merge(first.timers, pasta.copy(endAtMs = pasta.endAtMs + 300, changedAtMs = now + 300))
    assertEquals(Outcome.DUPLICATE, repeat.outcome)
    assertEquals(pasta.endAtMs, repeat.timers.single().endAtMs)
  }

  @Test fun higherSequenceWinsAndOlderIsStale() {
    val paused = SharedTimerOps.pause(pasta, now + 1_000, "den")!!
    val applied = SharedTimerMerge.merge(listOf(pasta), paused)
    assertEquals(Outcome.APPLIED, applied.outcome)
    assertEquals(SharedTimerState.PAUSED, applied.timers.single().state)
    assertEquals(pasta, applied.before)
    val stale = SharedTimerMerge.merge(applied.timers, pasta)
    assertEquals(Outcome.STALE, stale.outcome)
    assertEquals(paused, stale.timers.single())
  }

  @Test fun sameSequenceTiesSettleTheSameWayOnEveryDevice() {
    // Den pauses while the kitchen adds a minute: both at seq 2.
    val pause = SharedTimerOps.pause(pasta, now, "den")!!
    val add = SharedTimerOps.addMinute(pasta, now, "kitchen")!!
    val onDen = SharedTimerMerge.merge(listOf(pause), add).timers.single()
    val onKitchen = SharedTimerMerge.merge(listOf(add), pause).timers.single()
    assertEquals(onDen.by, onKitchen.by)
    assertEquals(onDen.state, onKitchen.state)
  }

  @Test fun anEndingBeatsARingAtTheSameSequence() {
    // A Portal that missed the cancel rings at seq 2; the household cancelled at seq 2.
    val cancelled = SharedTimerOps.cancel(pasta, now, "z-phone-relay")!!
    val rang = SharedTimerOps.ring(pasta, now, "a-stale")!!
    assertEquals(Outcome.STALE, SharedTimerMerge.merge(listOf(cancelled), rang).outcome)
    val healed = SharedTimerMerge.merge(listOf(rang), cancelled)
    assertEquals(Outcome.APPLIED, healed.outcome)
    assertEquals(SharedTimerState.CANCELLED, healed.timers.single().state)
  }

  @Test fun tombstoneStopsALateOlderMessageFromRevivingTheTimer() {
    val stopped = SharedTimerOps.stop(SharedTimerOps.ring(pasta, now, "k")!!, now, "den")!!
    assertEquals(Outcome.STALE, SharedTimerMerge.merge(listOf(stopped), pasta).outcome)
  }

  @Test fun liveLimitRefusesNewTimersButNotUpdates() {
    val full = (1..SharedTimer.MAX_LIVE).map { pasta.copy(id = "t%07d".format(it)) }
    assertEquals(Outcome.REJECTED, SharedTimerMerge.merge(full, pasta.copy(id = "overflow")).outcome)
    val update = SharedTimerOps.pause(full.first(), now, "k")!!
    assertEquals(Outcome.APPLIED, SharedTimerMerge.merge(full, update).outcome)
  }

  @Test fun pruneKeepsLiveAndRecentTombstonesOnly() {
    val old = SharedTimerOps.cancel(pasta, now, "k")!!.copy(id = "oldoldold")
    val recent = SharedTimerOps.cancel(pasta, now + SharedTimer.TOMBSTONE_MS, "k")!!.copy(id = "recentrecent")
    val kept = SharedTimerMerge.prune(listOf(pasta, old, recent), now + SharedTimer.TOMBSTONE_MS + 1)
    assertEquals(listOf(pasta.id, recent.id), kept.map { it.id })
  }

  @Test fun unansweredRingsAreFoundAfterTheTimeout() {
    val ringing = SharedTimerOps.ring(pasta, now, "k")!!
    assertTrue(SharedTimerMerge.unanswered(listOf(ringing), now + SharedTimer.RING_TIMEOUT_MS - 1).isEmpty())
    assertEquals(listOf(ringing), SharedTimerMerge.unanswered(listOf(ringing), now + SharedTimer.RING_TIMEOUT_MS))
  }

  @Test fun displayListsRingingThenSoonestThenPausedAndHidesEnded() {
    val tea = SharedTimerOps.create("bbbbbbbb", "Tea", "Den", "d", 60_000L, now, "d")!!
    val oven = SharedTimerOps.pause(SharedTimerOps.create("cccccccc", "Oven", "K", "k", 30_000L, now, "k")!!, now, "k")!!
    val bell = SharedTimerOps.ring(SharedTimerOps.create("dddddddd", "Bell", "K", "k", 1_000L, now, "k")!!, now, "k")!!
    val gone = SharedTimerOps.cancel(pasta.copy(id = "eeeeeeee"), now, "k")!!
    val shown = SharedTimerMerge.display(listOf(pasta, tea, oven, bell, gone), now)
    assertEquals(listOf("Bell", "Tea", "Pasta", "Oven"), shown.map { it.name })
  }
}
