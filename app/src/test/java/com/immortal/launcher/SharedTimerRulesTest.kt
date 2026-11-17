/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.Calendar
import java.util.TimeZone
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Quiet hours, phone requests and the startup restore. */
class SharedTimerRulesTest {
  private val utc = TimeZone.getTimeZone("UTC")

  private fun at(hour: Int, minute: Int = 0): Long = Calendar.getInstance(utc).apply {
    clear(); set(2026, Calendar.NOVEMBER, 17, hour, minute)
  }.timeInMillis

  // --- quiet hours ------------------------------------------------------------------------------

  @Test fun quietHoursMakeOtherRoomsVisualOnly() {
    val night = SharedTimerRing.isQuiet(ChimeConfig.Settings(), at(23), utc)
    assertTrue(night)
    assertEquals(SharedTimerRing.Decision(sound = false, quiet = true), SharedTimerRing.decide(night, startedHere = false))
  }

  @Test fun theRoomThatStartedItStillRingsAtNight() {
    assertEquals(SharedTimerRing.Decision(sound = true, quiet = false), SharedTimerRing.decide(quietHours = true, startedHere = true))
  }

  @Test fun daytimeAndQuietHoursOffRingEverywhere() {
    assertFalse(SharedTimerRing.isQuiet(ChimeConfig.Settings(), at(12), utc))
    assertFalse(SharedTimerRing.isQuiet(ChimeConfig.Settings(quietHoursOn = false), at(23), utc))
    assertTrue("08:00 is the end of the default window", !SharedTimerRing.isQuiet(ChimeConfig.Settings(), at(8), utc))
    assertTrue(SharedTimerRing.isQuiet(ChimeConfig.Settings(), at(7, 59), utc))
    assertEquals(SharedTimerRing.Decision(sound = true, quiet = false), SharedTimerRing.decide(quietHours = false, startedHere = false))
  }

  // --- phone requests ---------------------------------------------------------------------------

  @Test fun startRequestsAreValidated() {
    val ok = SharedTimerRequests.parse(JSONObject().put("action", "start").put("durationMs", 600_000).put("name", "Pasta").put("from", "Rohit’s Pixel"))
    assertEquals(SharedTimerRequest.Start("Pasta", 600_000L, "Rohit’s Pixel"), ok)
    assertEquals(SharedTimerRequest.Start("Timer", 60_000L, "Phone"), SharedTimerRequests.parse(JSONObject().put("action", "start").put("durationMs", 60_000)))
    assertEquals(SharedTimerRequest.Invalid("invalid_duration"), SharedTimerRequests.parse(JSONObject().put("action", "start").put("durationMs", 0)))
    assertEquals(SharedTimerRequest.Invalid("invalid_duration"), SharedTimerRequests.parse(JSONObject().put("action", "start").put("durationMs", 1.5)))
    assertEquals(SharedTimerRequest.Invalid("invalid_duration"), SharedTimerRequests.parse(JSONObject().put("action", "start").put("durationMs", "60000")))
    assertEquals(SharedTimerRequest.Invalid("invalid_name"), SharedTimerRequests.parse(JSONObject().put("action", "start").put("durationMs", 60_000).put("name", 4)))
  }

  @Test fun actionRequestsNeedAKnownActionAndAValidId() {
    assertEquals(SharedTimerRequest.Act(SharedTimerAction.ADD, "a1b2c3d4e5f6"),
        SharedTimerRequests.parse(JSONObject().put("action", "add").put("id", "a1b2c3d4e5f6")))
    assertEquals(SharedTimerRequest.Invalid("invalid_id"), SharedTimerRequests.parse(JSONObject().put("action", "stop").put("id", "../x")))
    assertEquals(SharedTimerRequest.Invalid("invalid_action"), SharedTimerRequests.parse(JSONObject().put("action", "explode").put("id", "a1b2c3d4")))
    assertEquals(SharedTimerRequest.Invalid("invalid_action"), SharedTimerRequests.parse(JSONObject()))
  }

  // --- startup restore --------------------------------------------------------------------------

  private val now = 10_000_000L
  private val self = "kitchen-id"

  @Test fun runningTimerWithItsAlarmIsKeptAsIs() {
    val t = SharedTimerOps.create("aaaaaaaa", "Pasta", "Kitchen", self, 600_000L, now - 60_000L, self)!!
    val plan = SharedTimerStartup.plan(listOf(t), listOf(TimerConfig.Timer(t.id, t.name, t.durationMs, t.endAtMs)), now, self, "Kitchen")
    assertEquals(listOf(t), plan.timers)
    assertTrue(plan.arm.isEmpty())
  }

  @Test fun runningTimerWithoutAnAlarmIsRearmedOrQuietlyEnded() {
    val future = SharedTimerOps.create("aaaaaaaa", "Pasta", "Kitchen", self, 600_000L, now - 60_000L, self)!!
    val missed = SharedTimerOps.create("bbbbbbbb", "Tea", "Kitchen", self, 60_000L, now - 120_000L, self)!!
    val plan = SharedTimerStartup.plan(listOf(future, missed), emptyList(), now, self, "Kitchen")
    assertEquals(listOf(future), plan.arm)
    assertEquals(SharedTimerState.DONE, plan.timers.first { it.id == missed.id }.state)
    assertTrue(plan.reshow.isEmpty())
  }

  @Test fun ringingTimerIsShownAgainUntilItTimesOut() {
    val base = SharedTimerOps.create("aaaaaaaa", "Pasta", "Kitchen", self, 60_000L, now - 120_000L, self)!!
    val fresh = SharedTimerOps.ring(base, now - 10_000L, self)!!
    val stale = SharedTimerOps.ring(base.copy(id = "bbbbbbbb"), now - SharedTimer.RING_TIMEOUT_MS, self)!!
    val plan = SharedTimerStartup.plan(listOf(fresh, stale), emptyList(), now, self, "Kitchen")
    assertEquals(listOf(fresh.id), plan.reshow.map { it.id })
    assertEquals(SharedTimerState.DONE, plan.timers.first { it.id == stale.id }.state)
  }

  @Test fun kitchenTimersFromBeforeSharedTimersJoinTheHousehold() {
    val legacy = TimerConfig.Timer("mw069oc0-102", "5 min", 300_000L, now + 200_000L)
    val expired = TimerConfig.Timer("mw069oc1-103", "Old", 300_000L, now - 1L)
    val plan = SharedTimerStartup.plan(emptyList(), listOf(legacy, expired), now, self, "Kitchen")
    val adopted = plan.adopted.single()
    assertEquals(legacy.id, adopted.id)
    assertEquals("Kitchen", adopted.room)
    assertEquals(200_000L, adopted.remaining(now))
    assertTrue("its alarm is already armed", plan.arm.isEmpty())
  }
}
