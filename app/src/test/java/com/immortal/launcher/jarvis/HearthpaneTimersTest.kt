/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import com.immortal.launcher.SharedTimer
import com.immortal.launcher.SharedTimerAction
import com.immortal.launcher.SharedTimerOps
import com.immortal.launcher.SharedTimerState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `hearthpane.timers`: Jarvis's timers are the household's, shown and rung in every room. */
class HearthpaneTimersTest {
  private val now = 1_000_000_000L
  private var timers = listOf<SharedTimer>()
  private val acted = mutableListOf<Pair<SharedTimerAction, String>>()
  private var refuseStart = false
  private val tool = HearthpaneTimers(
      timers = { timers },
      start = { name, ms ->
        if (refuseStart) null
        else SharedTimerOps.create("t${timers.size + 1}", name, "Kitchen", "self", ms, now, "self")!!.also { timers = timers + it }
      },
      act = { action, id -> acted += action to id; true },
      here = { "Kitchen" }, now = { now })

  private fun call(vararg kv: Pair<String, Any>) = tool.invoke(JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }.toString())

  private fun timer(id: String, name: String, ms: Long, room: String = "Kitchen", state: SharedTimerState = SharedTimerState.RUNNING) =
      SharedTimerOps.create(id, name, room, "x", ms, now, "x")!!.let {
        when (state) {
          SharedTimerState.PAUSED -> SharedTimerOps.pause(it, now, "x")!!
          SharedTimerState.RINGING -> SharedTimerOps.ring(it, now, "x")!!
          else -> it
        }
      }

  @Test fun startMakesAHouseholdTimer() {
    val r = call("action" to "start", "duration_seconds" to 600, "label" to "pasta")
    assertTrue(r.toString(), r.getBoolean("ok"))
    assertEquals("pasta timer set for 10 minutes. It shows in every room.", r.getString("message"))
    assertEquals(600_000L, timers.single().durationMs)
    assertEquals("pasta", timers.single().name)
    assertEquals(600, r.getInt("duration_seconds"))
  }

  @Test fun anUnnamedTimerSaysItsLength() {
    assertEquals("Timer set for 1 hour 30 minutes. It shows in every room.",
        call("action" to "start", "duration_seconds" to 5400).getString("message"))
    assertEquals(SharedTimer.DEFAULT_NAME, timers.single().name)
    assertEquals("Timer set for 45 seconds. It shows in every room.",
        call("action" to "start", "duration_seconds" to 45.4).getString("message"))
  }

  @Test fun badDurationsAndAFullHouseholdAreRefused() {
    assertTrue(call("action" to "start").getString("error").contains("duration_seconds"))
    assertTrue(call("action" to "start", "duration_seconds" to 0).getString("error").contains("duration_seconds"))
    assertTrue(call("action" to "start", "duration_seconds" to 90_000).getString("error").contains("duration_seconds"))
    refuseStart = true
    assertTrue(call("action" to "start", "duration_seconds" to 60).has("error"))
    assertTrue(call("action" to "jump").getString("error").contains("action must"))
    assertTrue(tool.invoke("[").has("error"))
  }

  @Test fun listSaysWhatIsLeftEverywhere() {
    assertEquals("No timers are running.", call("action" to "list").getString("message"))
    timers = listOf(timer("a", "Pasta", 250_000), timer("b", "Eggs", 180_000, room = "Living Area", state = SharedTimerState.PAUSED),
        timer("c", "Old", 60_000).copy(state = SharedTimerState.CANCELLED))
    val r = call("action" to "list")
    assertEquals("2 timers: Pasta, 4 minutes 10 seconds left; Eggs in the Living Area, paused with 3 minutes left.",
        r.getString("message"))
    val first = r.getJSONArray("timers").getJSONObject(0)
    assertEquals("Pasta", first.getString("label"))
    assertEquals(250, first.getInt("remaining_seconds"))
    assertEquals("running", first.getString("state"))
  }

  @Test fun cancelPicksTheOnlyTimerOrTheNamedOne() {
    timers = listOf(timer("a", "Pasta", 250_000))
    assertEquals("Cancelled the Pasta timer.", call("action" to "cancel").getString("message"))
    assertEquals(SharedTimerAction.CANCEL to "a", acted.single())
    timers = listOf(timer("a", "Pasta", 250_000), timer("b", "Eggs", 180_000))
    assertTrue(call("action" to "cancel").getBoolean("needs_which"))
    assertEquals(1, acted.size)
    call("action" to "cancel", "label" to "the eggs timer")
    assertEquals(SharedTimerAction.CANCEL to "b", acted.last())
    assertFalse(call("action" to "cancel", "label" to "rice").getBoolean("ok"))
    assertEquals("Cancelled all 2 timers.", call("action" to "cancel", "all" to true).getString("message"))
  }

  @Test fun pauseResumeAndAMinuteMore() {
    timers = listOf(timer("a", "Pasta", 250_000), timer("b", "Eggs", 180_000, state = SharedTimerState.PAUSED))
    assertEquals("Paused the Pasta timer.", call("action" to "pause").getString("message"))
    assertEquals(SharedTimerAction.PAUSE to "a", acted.last())
    assertEquals("Resumed the Eggs timer.", call("action" to "resume").getString("message"))
    assertEquals(SharedTimerAction.RESUME to "b", acted.last())
    call("action" to "add_minute", "label" to "pasta")
    assertEquals(SharedTimerAction.ADD to "a", acted.last())
  }

  @Test fun stopSilencesTheRingingTimers() {
    assertEquals("No timer is ringing.", call("action" to "stop").getString("message"))
    timers = listOf(timer("a", "Pasta", 250_000), timer("r", "Tea", 60_000, state = SharedTimerState.RINGING))
    assertEquals("Stopped the Tea timer.", call("action" to "stop").getString("message"))
    assertEquals(SharedTimerAction.STOP to "r", acted.single())
  }
}
