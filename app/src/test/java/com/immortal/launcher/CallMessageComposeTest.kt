/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

/** Leave a message: camera only while ready/recording, 2:00 cap, the take never outlives the screen. */
class CallMessageComposeTest {
  private val t0 = 1_000_000L
  private fun reduce(s: ComposeState, e: ComposeEvent) = CallMessageCompose.reduce(s, e)

  private fun recorded(ms: Long = 30_000L): ComposeState {
    var s = reduce(ComposeState(), ComposeEvent.Record(t0)).state
    s = reduce(s, ComposeEvent.Stop(t0 + ms)).state
    return reduce(s, ComposeEvent.Recorded(true, ms)).state
  }

  @Test fun recordStopReview() {
    val start = reduce(ComposeState(), ComposeEvent.Record(t0))
    assertEquals(ComposePhase.RECORDING, start.state.phase)
    assertEquals(listOf(ComposeEffect.StartRecording(true)), start.effects)
    val stop = reduce(start.state, ComposeEvent.Stop(t0 + 42_000))
    assertEquals(listOf(ComposeEffect.StopRecording), stop.effects)
    assertEquals(42_000, stop.state.durationMs)
    val review = reduce(stop.state, ComposeEvent.Recorded(true, 41_900))
    assertEquals(ComposePhase.REVIEW, review.state.phase)
    assertEquals(41_900, review.state.durationMs)
    // Review shows the take, not the room.
    assertEquals(listOf(ComposeEffect.CloseCamera), review.effects)
  }

  @Test fun stopsByItselfAtTwoMinutes() {
    val rec = reduce(ComposeState(), ComposeEvent.Record(t0)).state
    assertTrue(reduce(rec, ComposeEvent.Tick(t0 + 119_000)).effects.isEmpty())
    val capped = reduce(rec, ComposeEvent.Tick(t0 + 120_000))
    assertEquals(listOf(ComposeEffect.StopRecording), capped.effects)
    assertEquals(CallMessageLimits.MAX_DURATION_MS, capped.state.durationMs)
    assertEquals(CallMessageLimits.MAX_DURATION_MS, rec.elapsed(t0 + 500_000))
  }

  @Test fun aMisTapGoesBackToReadyAndDeletesTheTake() {
    var s = reduce(ComposeState(), ComposeEvent.Record(t0)).state
    s = reduce(s, ComposeEvent.Stop(t0 + 400)).state
    val step = reduce(s, ComposeEvent.Recorded(true, 400))
    assertEquals(ComposePhase.READY, step.state.phase)
    assertFalse(step.state.hasTake)
    assertEquals(listOf(ComposeEffect.DeleteTake, ComposeEffect.OpenCamera(true)), step.effects)
  }

  @Test fun aFailedRecordingSaysSo() {
    val s = reduce(ComposeState(), ComposeEvent.Record(t0)).state
    val step = reduce(s, ComposeEvent.Recorded(false, 0))
    assertEquals(ComposePhase.READY, step.state.phase)
    assertEquals("recording_failed", step.state.failure)
  }

  @Test fun theRecordersOwnCapEndsRecordingWithoutStop() {
    val rec = reduce(ComposeState(), ComposeEvent.Record(t0)).state
    val step = reduce(rec, ComposeEvent.Recorded(true, 120_000))
    assertEquals(ComposePhase.REVIEW, step.state.phase)
    assertNull(step.state.startedMs)
    assertEquals(120_000, step.state.durationMs)
  }

  @Test fun recordAgainDeletesTheTakeAndReopensTheCamera() {
    val step = reduce(recorded(), ComposeEvent.RecordAgain)
    assertEquals(ComposePhase.READY, step.state.phase)
    assertEquals(listOf(ComposeEffect.DeleteTake, ComposeEffect.OpenCamera(true)), step.effects)
  }

  @Test fun sentDeletesTheTakeAtOnce() {
    val sending = reduce(recorded(), ComposeEvent.Send)
    assertEquals(listOf(ComposeEffect.Upload), sending.effects)
    val sent = reduce(sending.state, ComposeEvent.SendOk)
    assertEquals(ComposePhase.SENT, sent.state.phase)
    assertFalse(sent.state.hasTake)
    assertEquals(listOf(ComposeEffect.DeleteTake), sent.effects)
  }

  @Test fun aFailedSendKeepsTheTakeToRetry() {
    val failed = reduce(reduce(recorded(), ComposeEvent.Send).state, ComposeEvent.SendFailed("unreachable"))
    assertEquals(ComposePhase.FAILED, failed.state.phase)
    assertTrue(failed.state.hasTake)
    assertEquals(listOf(ComposeEffect.Upload), reduce(failed.state, ComposeEvent.Send).effects)
  }

  @Test fun discardWhileRecordingStopsClosesAndDeletes() {
    val rec = reduce(ComposeState(), ComposeEvent.Record(t0)).state
    assertEquals(listOf(ComposeEffect.StopRecording, ComposeEffect.CloseCamera, ComposeEffect.DeleteTake, ComposeEffect.Finish),
        reduce(rec, ComposeEvent.Discard).effects)
    assertEquals(listOf(ComposeEffect.CloseCamera, ComposeEffect.DeleteTake, ComposeEffect.Finish), reduce(recorded(), ComposeEvent.Discard).effects)
    assertEquals(listOf(ComposeEffect.CloseCamera, ComposeEffect.Finish), reduce(ComposeState(), ComposeEvent.Discard).effects)
  }

  @Test fun cameraToggleOnlyBeforeRecording() {
    val off = reduce(ComposeState(), ComposeEvent.ToggleCamera)
    assertFalse(off.state.cameraOn)
    assertEquals(listOf(ComposeEffect.OpenCamera(false)), off.effects)
    assertEquals(listOf(ComposeEffect.StartRecording(false)), reduce(off.state, ComposeEvent.Record(t0)).effects)
    val rec = reduce(ComposeState(), ComposeEvent.Record(t0)).state
    assertTrue(reduce(rec, ComposeEvent.ToggleCamera).effects.isEmpty())
  }

  @Test fun eventsOutOfPlaceDoNothing() {
    val ready = ComposeState()
    for (e in listOf(ComposeEvent.Stop(t0), ComposeEvent.Send, ComposeEvent.SendOk, ComposeEvent.SendFailed("x"), ComposeEvent.RecordAgain,
        ComposeEvent.Recorded(true, 5_000), ComposeEvent.Tick(t0))) {
      assertEquals(ready, reduce(ready, e).state)
      assertTrue(reduce(ready, e).effects.isEmpty())
    }
    val rec = reduce(ready, ComposeEvent.Record(t0)).state
    assertEquals(rec, reduce(rec, ComposeEvent.Record(t0 + 5)).state)
  }
}
