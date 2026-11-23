/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import com.immortal.launcher.AlarmConfig
import com.immortal.launcher.AlarmRinging
import com.immortal.launcher.MoreAlarms
import java.util.Calendar
import java.util.TimeZone
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `hearthpane.alarms`: several alarms, set / list / off by time or name, snooze and stop by voice. */
class HearthpaneAlarmsTest {
  /** Tuesday 17 November 2026, 07:05 UTC. */
  private val now = 1_794_899_100_000L
  private val book = FakeBook()
  private val tool = HearthpaneAlarms(book, now = { now }, zone = { TimeZone.getTimeZone("UTC") }, use24 = { false })

  private class FakeBook : AlarmBook {
    var alarms = listOf(MoreAlarms.Alarm(MoreAlarms.MAIN_ID, AlarmConfig.Config(false, 7, 0, "Alarm", emptySet())))
    val saved = mutableListOf<MoreAlarms.Alarm>()
    var ring: AlarmRinging.Ring? = null
    var snoozedFor: Int? = null
    var dismissed = false

    override fun all() = alarms
    override fun save(alarm: MoreAlarms.Alarm) { saved += alarm; alarms = MoreAlarms.put(alarms, alarm) }
    override fun remove(id: Int) {
      alarms = if (id == MoreAlarms.MAIN_ID) alarms.map { if (it.id == id) it.copy(config = it.config.copy(enabled = false)) else it }
      else alarms.filterNot { it.id == id }
    }
    override fun ringing() = ring
    override fun snooze(minutes: Int) = ring.also { snoozedFor = minutes; ring = null }
    override fun dismiss() = ring.also { dismissed = true; ring = null }

    fun on(id: Int, hour: Int, minute: Int, label: String = "Alarm", days: Set<Int> = emptySet()) {
      alarms = MoreAlarms.put(alarms, MoreAlarms.Alarm(id, AlarmConfig.Config(true, hour, minute, label, days)))
    }
    fun enabled() = alarms.filter { it.config.enabled }
  }

  private fun call(vararg kv: Pair<String, Any>) = tool.invoke(JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }.toString())

  @Test fun aOneOffAlarmRingsAtTheNextTimeTheClockShowsIt() {
    val r = call("action" to "set", "time" to "07:00")
    assertTrue(r.toString(), r.getBoolean("ok"))
    assertEquals(MoreAlarms.Alarm(0, AlarmConfig.Config(true, 7, 0, "Alarm", emptySet())), book.saved.single())
    assertEquals("Alarm set for 7 am. Next one: tomorrow at 7 am.", r.getString("message"))
  }

  @Test fun repeatingAlarmsSayTheirDaysAndNextRing() {
    assertEquals("Alarm set for 6 pm on weekdays. Next one: today at 6 pm.",
        call("action" to "set", "time" to "18:00", "repeat" to "weekdays", "label" to "Pickup").getString("message"))
    assertEquals("Pickup", book.saved.last().config.label)
    assertEquals(AlarmDays.WEEKDAYS, book.saved.last().config.days)
  }

  @Test fun aSecondAlarmIsAddedBesideTheFirstNotInItsPlace() {
    book.on(0, 6, 30, "Gym", AlarmDays.WEEKDAYS)
    val r = call("action" to "set", "time" to "09:00", "repeat" to "saturday")
    assertTrue(r.toString(), r.getBoolean("ok"))
    assertEquals("Alarm set for 9 am on Saturdays. Next one: Saturday at 9 am. You have 2 alarms on.", r.getString("message"))
    assertEquals(listOf(0, 1), book.enabled().map { it.id })
    assertEquals(6, book.alarms.first { it.id == 0 }.config.hour)
  }

  @Test fun theFirstFreeSlotIsTheMainAlarmWhenItIsOff() {
    book.on(1, 9, 0)
    call("action" to "set", "time" to "06:00")
    assertEquals(0, book.saved.single().id)
  }

  @Test fun aFullBookSaysSoAndKeepsEveryAlarm() {
    (0..MoreAlarms.MAX).forEach { book.on(it, 5, it) }
    val r = call("action" to "set", "time" to "11:00")
    assertFalse(r.getBoolean("ok"))
    assertTrue(r.getString("message"), r.getString("message").contains("6 alarms"))
    assertTrue(book.saved.isEmpty())
  }

  @Test fun theSameAlarmAgainJustSavesItsLabel() {
    book.on(0, 7, 0, days = AlarmDays.WEEKDAYS)
    assertTrue(call("action" to "set", "time" to "7am", "repeat" to "weekdays", "label" to "Work").getBoolean("ok"))
    assertEquals(MoreAlarms.Alarm(0, AlarmConfig.Config(true, 7, 0, "Work", AlarmDays.WEEKDAYS)), book.saved.single())
  }

  @Test fun replaceMovesTheNamedAlarm() {
    book.on(0, 6, 30, "Gym", AlarmDays.WEEKDAYS)
    book.on(1, 9, 0)
    val r = call("action" to "set", "time" to "07:00", "replace" to true, "from" to "06:30")
    assertTrue(r.toString(), r.getBoolean("ok"))
    assertEquals(MoreAlarms.Alarm(0, AlarmConfig.Config(true, 7, 0, "Gym", emptySet())), book.saved.single())
  }

  @Test fun replaceWithSeveralAlarmsAndNoFromAsksWhich() {
    book.on(0, 6, 30)
    book.on(1, 9, 0)
    val r = call("action" to "set", "time" to "07:00", "replace" to true)
    assertTrue(r.getBoolean("needs_which"))
    assertTrue(book.saved.isEmpty())
  }

  @Test fun replaceWithOneAlarmMovesIt() {
    book.on(0, 6, 30)
    call("action" to "set", "time" to "07:00", "replace" to true)
    assertEquals(listOf(7), book.enabled().map { it.config.hour })
  }

  @Test fun listSaysEveryAlarmInRingingOrder() {
    assertEquals("No alarm is on.", call("action" to "list").getString("message"))
    book.on(0, 6, 0, days = setOf(Calendar.TUESDAY))
    assertEquals("The alarm is on for 6 am on Tuesdays. Next one: Tuesday at 6 am.", call("action" to "list").getString("message"))
    book.on(2, 8, 0, "School")
    val r = call("action" to "list")
    assertEquals("You have 2 alarms on: 8 am (School) and 6 am on Tuesdays. Next one: today at 8 am.", r.getString("message"))
    assertEquals(2, r.getJSONArray("alarms").length())
  }

  @Test fun cancelByTimeOrNameTurnsOffOnlyThatOne() {
    book.on(0, 6, 30, "Gym", AlarmDays.WEEKDAYS)
    book.on(1, 9, 0, "Brunch")
    assertEquals("Turned off the alarm for 9 am.", call("action" to "cancel", "time" to "9:00").getString("message"))
    assertEquals(listOf(0), book.enabled().map { it.id })
    book.on(1, 9, 0, "Brunch")
    call("action" to "cancel", "label" to "gym")
    assertEquals(listOf(1), book.enabled().map { it.id })
  }

  @Test fun cancelWithSeveralAndNoChoiceAsksWhich() {
    book.on(0, 6, 30)
    book.on(1, 9, 0)
    val r = call("action" to "cancel")
    assertTrue(r.getBoolean("needs_which"))
    assertEquals(2, book.enabled().size)
    assertEquals("Turned off all 2 alarms.", call("action" to "cancel", "all" to true).getString("message"))
    assertTrue(book.enabled().isEmpty())
  }

  @Test fun cancelOfAnAlarmThatIsNotThereNamesTheOnesThatAre() {
    book.on(0, 6, 30)
    val r = call("action" to "cancel", "time" to "10:00")
    assertFalse(r.getBoolean("ok"))
    assertTrue(r.getString("message"), r.getString("message").contains("6:30 am"))
    assertEquals(1, book.enabled().size)
  }

  @Test fun snoozeAndStopActOnTheRingingAlarm() {
    assertEquals("No alarm is ringing right now.", call("action" to "snooze").getString("message"))
    book.ring = AlarmRinging.Ring(0, "Gym", now)
    assertEquals("Snoozed for 9 minutes.", call("action" to "snooze").getString("message"))
    assertEquals(9, book.snoozedFor)
    book.ring = AlarmRinging.Ring(0, "Gym", now)
    assertEquals("Snoozed for 15 minutes.", call("action" to "snooze", "minutes" to 15).getString("message"))
    book.ring = AlarmRinging.Ring(0, "Gym", now)
    assertEquals("Stopped the alarm.", call("action" to "stop").getString("message"))
    assertTrue(book.dismissed)
  }

  @Test fun turningTheAlarmOffWhileItRingsStopsTheRingAndKeepsTheSchedule() {
    book.on(0, 7, 0, days = AlarmDays.WEEKDAYS)
    book.ring = AlarmRinging.Ring(0, "Alarm", now)
    assertEquals("Stopped the alarm.", call("action" to "cancel").getString("message"))
    assertTrue(book.dismissed)
    assertEquals(1, book.enabled().size)
    assertNull(book.ring)
  }

  @Test fun badArgumentsAreErrors() {
    assertTrue(call("action" to "set").getString("error").contains("time is required"))
    assertTrue(call("action" to "set", "time" to "25:00").getString("error").contains("time must"))
    assertTrue(call("action" to "set", "time" to "07:00", "repeat" to "someday").getString("error").contains("repeat must"))
    assertTrue(call("action" to "dance").getString("error").contains("action must"))
    assertTrue(call("action" to "snooze", "minutes" to 90).getString("error").contains("minutes"))
    assertTrue(tool.invoke("{").has("error"))
    assertTrue(book.saved.isEmpty())
  }
}
