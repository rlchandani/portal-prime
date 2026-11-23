/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `hearthpane.routines`: multi-step routines with no Home Assistant scene, every step through the house's own gate. */
class HearthpaneRoutinesTest {
  private var stored = listOf<HomeRoutine>()
  private val batches = mutableListOf<List<String>>()
  private var connected = true
  private var nextAlarm: String? = "tomorrow at 7 am"
  /** What each request does in the fake house. */
  private val house = mutableMapOf<String, HomeStepResult.Status>()

  private val tool = HearthpaneRoutines(
      saved = { stored }, save = { stored = it },
      home = { requests ->
        batches += requests
        if (!connected) HomeBatch(emptyList(), "Home Assistant isn't connected")
        else HomeBatch(requests.map { r ->
          val status = house[r] ?: HomeStepResult.Status.DONE
          HomeStepResult(r, status, when (status) {
            HomeStepResult.Status.DONE -> "Did $r."
            HomeStepResult.Status.CONFIRM -> "Arming the alarm needs a tap to confirm. It's on the Portal's screen."
            HomeStepResult.Status.SKIPPED -> "There's nothing like that."
            HomeStepResult.Status.FAILED -> "Home Assistant didn't do it."
            HomeStepResult.Status.REFUSED -> "That one needs a grown-up."
          })
        }, null)
      },
      nextAlarm = { nextAlarm })

  private fun call(vararg kv: Pair<String, Any>) = tool.invoke(JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }.toString())

  @Test fun goodNightRunsLightsLocksAndTheAlarmTogetherThenQuietAndTheWakeUpTime() {
    house["arm the alarm for the night"] = HomeStepResult.Status.CONFIRM
    val r = call("action" to "run", "name" to "Goodnight")
    assertTrue(r.toString(), r.getBoolean("ok"))
    assertEquals(listOf(listOf("turn off all the lights", "lock all the doors", "arm the alarm for the night")), batches)
    val message = r.getString("message")
    assertTrue(message, message.startsWith("Good night: Did turn off all the lights. Did lock all the doors. Arming the alarm"))
    assertTrue(message, message.contains("Your alarm rings tomorrow at 7 am."))
    assertTrue(r.getBoolean("needs_confirmation"))
    val also = r.getJSONObject("also_call")
    assertEquals("portal.set_do_not_disturb", also.getString("tool"))
    assertTrue(also.getJSONObject("args").getBoolean("enabled"))
  }

  @Test fun leavingNeedsNoSceneAndKeepsTheGarageOnTheConfirmSheet() {
    house["close the garage"] = HomeStepResult.Status.CONFIRM
    val r = call("action" to "run", "name" to "I'm leaving")
    assertEquals(listOf("turn off all the lights", "lock all the doors", "close the garage", "arm the alarm away"), batches.single())
    assertTrue(r.getBoolean("needs_confirmation"))
    assertFalse(r.has("also_call"))
  }

  @Test fun stepsTheHouseHasNoDeviceForAreLeftOutQuietly() {
    house["lock all the doors"] = HomeStepResult.Status.SKIPPED
    house["arm the alarm for the night"] = HomeStepResult.Status.SKIPPED
    nextAlarm = null
    val r = call("action" to "run", "name" to "bedtime")
    assertEquals("Good night: Did turn off all the lights. Do Not Disturb comes on next.", r.getString("message"))
  }

  @Test fun withHomeAssistantOffTheOtherStepsStillRun() {
    connected = false
    val r = call("action" to "run", "name" to "good night")
    assertTrue(r.getString("message"), r.getString("message").contains("Home Assistant isn't connected"))
    assertTrue(r.has("also_call"))
  }

  @Test fun aRoutineNobodyMadeAsksForItsName() {
    val r = call("action" to "run", "name" to "party mode")
    assertFalse(r.getBoolean("ok"))
    assertTrue(r.getString("message"), r.getString("message").contains("Good night and Leaving"))
    assertTrue(batches.isEmpty())
  }

  @Test fun createListAndDeleteAHouseholdRoutine() {
    val steps = JSONArray(listOf("dim the living room to 20 percent", "close the living room blinds", "quiet mode"))
    val made = call("action" to "create", "name" to "Movie night", "steps" to steps)
    assertTrue(made.toString(), made.getBoolean("ok"))
    assertEquals(listOf(RoutineStep.Home("dim the living room to 20 percent"), RoutineStep.Home("close the living room blinds"),
        RoutineStep.Quiet), stored.single().steps)
    assertTrue(call("action" to "list").getString("message").contains("Movie night"))
    call("action" to "run", "name" to "movie night")
    assertEquals(listOf("dim the living room to 20 percent", "close the living room blinds"), batches.single())
    assertTrue(call("action" to "delete", "name" to "movie night").getBoolean("ok"))
    assertTrue(stored.isEmpty())
  }

  @Test fun aHouseholdRoutineCanReplaceABuiltInOne() {
    call("action" to "create", "name" to "good night", "steps" to JSONArray(listOf("turn off the bedroom lights")))
    call("action" to "run", "name" to "goodnight")
    assertEquals(listOf("turn off the bedroom lights"), batches.single())
    assertTrue(call("action" to "delete", "name" to "good night").getString("message").contains("back to"))
    assertFalse(call("action" to "delete", "name" to "good night").getBoolean("ok"))
  }

  @Test fun badRoutinesAreRefused() {
    assertTrue(call("action" to "create", "name" to "x").has("error"))
    assertTrue(call("action" to "create", "name" to "", "steps" to JSONArray(listOf("a"))).has("error"))
    assertTrue(call("action" to "create", "name" to "big", "steps" to JSONArray((1..13).map { "turn on light $it" })).has("error"))
    assertTrue(call("action" to "create", "name" to "blank", "steps" to JSONArray(listOf(" ", ""))).has("error"))
    assertTrue(call("action" to "run").has("error"))
    assertTrue(call("action" to "fly").getString("error").contains("action must"))
    assertTrue(tool.invoke("nope").has("error"))
    assertTrue(stored.isEmpty())
  }

  @Test fun theStoreRoundTripsAndDropsJunk() {
    val list = listOf(HomeRoutine("Movie night", listOf(RoutineStep.Home("close the blinds"), RoutineStep.Quiet, RoutineStep.NextAlarm)))
    assertEquals(list, HomeRoutines.decode(HomeRoutines.encode(list)))
    assertEquals(emptyList<HomeRoutine>(), HomeRoutines.decode("""[{"name":"","steps":["a"]},{"name":"x","steps":[]},"junk"]"""))
    assertNull(HomeRoutines.find(emptyList(), "nothing"))
    assertEquals("Good night", HomeRoutines.find(emptyList(), "good night routine")?.name)
  }
}
