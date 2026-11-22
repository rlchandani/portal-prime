/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import com.immortal.launcher.AlarmConfig
import java.util.Calendar
import java.util.TimeZone
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `hearthpane.alarms`: the Portal's one alarm, set / read / off, never moved without asking. */
class HearthpaneAlarmsTest {
  /** Tuesday 17 November 2026, 07:05 UTC. */
  private val now = 1_794_899_100_000L
  private var config = AlarmConfig.Config(false, 7, 0, "Alarm", emptySet())
  private val saved = mutableListOf<AlarmConfig.Config>()
  private val tool = HearthpaneAlarms(load = { config }, save = { saved += it; config = it }, now = { now },
      zone = { TimeZone.getTimeZone("UTC") }, use24 = { false })

  private fun call(vararg kv: Pair<String, Any>) = tool.invoke(JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }.toString())

  @Test fun aOneOffAlarmRingsAtTheNextTimeTheClockShowsIt() {
    val r = call("action" to "set", "time" to "07:00")
    assertTrue(r.toString(), r.getBoolean("ok"))
    assertEquals(AlarmConfig.Config(true, 7, 0, "Alarm", emptySet()), saved.single())
    assertEquals("Alarm set for 7 am. Next one: tomorrow at 7 am.", r.getString("message"))
  }

  @Test fun repeatingAlarmsSayTheirDaysAndNextRing() {
    assertEquals("Alarm set for 6 pm on weekdays. Next one: today at 6 pm.",
        call("action" to "set", "time" to "18:00", "repeat" to "weekdays", "label" to "Pickup").getString("message"))
    assertEquals("Pickup", saved.last().label)
    assertEquals(AlarmDays.WEEKDAYS, saved.last().days)
    config = config.copy(enabled = false)
    assertEquals("Alarm set for 6:30 am on Saturdays. Next one: Saturday at 6:30 am.",
        call("action" to "set", "time" to "6:30 am", "repeat" to "saturday").getString("message"))
  }

  @Test fun aDifferentAlarmIsNotReplacedUntilTheUserAgrees() {
    config = AlarmConfig.Config(true, 6, 30, "Gym", AlarmDays.WEEKDAYS)
    val ask = call("action" to "set", "time" to "07:00")
    assertTrue(ask.getBoolean("needs_replace"))
    assertTrue(ask.getString("message"), ask.getString("message").contains("already an alarm for 6:30 am on weekdays"))
    assertTrue(saved.isEmpty())
    assertTrue(call("action" to "set", "time" to "07:00", "replace" to true).getBoolean("ok"))
    assertEquals(AlarmConfig.Config(true, 7, 0, "Gym", emptySet()), saved.single())
  }

  @Test fun theSameAlarmAgainJustSavesIt() {
    config = AlarmConfig.Config(true, 7, 0, "Alarm", AlarmDays.WEEKDAYS)
    assertTrue(call("action" to "set", "time" to "7am", "repeat" to "weekdays", "label" to "Work").getBoolean("ok"))
    assertEquals("Work", saved.single().label)
  }

  @Test fun listAndCancel() {
    assertEquals("No alarm is on.", call("action" to "list").getString("message"))
    assertEquals("There's no alarm on.", call("action" to "cancel").getString("message"))
    assertTrue(saved.isEmpty())
    config = AlarmConfig.Config(true, 6, 0, "Alarm", setOf(Calendar.TUESDAY))
    assertEquals("The alarm is on for 6 am on Tuesdays. Next one: Tuesday at 6 am.", call("action" to "list").getString("message"))
    assertEquals("Turned off the alarm for 6 am on Tuesdays.", call("action" to "cancel").getString("message"))
    assertFalse(saved.single().enabled)
  }

  @Test fun badArgumentsAreErrors() {
    assertTrue(call("action" to "set").getString("error").contains("time is required"))
    assertTrue(call("action" to "set", "time" to "25:00").getString("error").contains("time must"))
    assertTrue(call("action" to "set", "time" to "07:00", "repeat" to "someday").getString("error").contains("repeat must"))
    assertTrue(call("action" to "snooze").getString("error").contains("action must"))
    assertTrue(tool.invoke("{").has("error"))
    assertTrue(saved.isEmpty())
  }
}
