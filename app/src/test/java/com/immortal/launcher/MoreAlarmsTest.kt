/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The other alarms' saved list: round trip, tolerant decode, free slots. */
class MoreAlarmsTest {
  private fun alarm(id: Int, hour: Int, days: Set<Int> = emptySet(), on: Boolean = true) =
      MoreAlarms.Alarm(id, AlarmConfig.Config(on, hour, 15, "Gym", days))

  @Test fun roundTripsEveryField() {
    val list = listOf(alarm(1, 6, setOf(Calendar.MONDAY, Calendar.FRIDAY)), alarm(3, 21, on = false))
    assertEquals(list, MoreAlarms.decode(MoreAlarms.encode(list)))
  }

  @Test fun badEntriesAreDroppedNotTheWholeList() {
    val raw = """[{"id":1,"hour":6,"minute":0,"enabled":true,"days":[2,9]},{"id":0,"hour":6,"minute":0},""" +
        """{"id":2,"hour":24,"minute":0},{"id":3,"hour":5,"minute":61},"junk",{"id":1,"hour":8,"minute":0},""" +
        """{"id":4,"hour":7,"minute":30,"label":"  "}]"""
    val got = MoreAlarms.decode(raw)
    assertEquals(listOf(1, 4), got.map { it.id })
    assertEquals(setOf(2), got[0].config.days)
    assertEquals(6, got[0].config.hour)
    assertEquals("Alarm", got[1].config.label)
    assertEquals(emptyList<MoreAlarms.Alarm>(), MoreAlarms.decode("{"))
    assertEquals(emptyList<MoreAlarms.Alarm>(), MoreAlarms.decode(null))
  }

  @Test fun freeIdIsTheLowestUnusedSlotUntilFull() {
    assertEquals(1, MoreAlarms.freeId(emptyList()))
    assertEquals(2, MoreAlarms.freeId(listOf(alarm(1, 6), alarm(3, 6))))
    assertNull(MoreAlarms.freeId((1..MoreAlarms.MAX).map { alarm(it, 6) }))
  }

  @Test fun putAddsOrReplacesInIdOrder() {
    val list = MoreAlarms.put(listOf(alarm(2, 6)), alarm(1, 7))
    assertEquals(listOf(1, 2), list.map { it.id })
    assertEquals(8, MoreAlarms.put(list, alarm(2, 8)).first { it.id == 2 }.config.hour)
  }

  @Test fun everySlotHasItsOwnRequestCode() {
    val codes = (0..MoreAlarms.MAX).map(AlarmScheduler::requestCode)
    assertEquals(codes.size, codes.toSet().size)
    assertEquals(false, AlarmScheduler.SNOOZE_RC in codes)
    // Two alarms snoozed at once each keep their own snooze.
    val snoozes = (0..MoreAlarms.MAX).map(AlarmScheduler::snoozeRequestCode)
    assertEquals(snoozes.size, snoozes.toSet().size)
    assertEquals(AlarmScheduler.SNOOZE_RC, AlarmScheduler.snoozeRequestCode(MoreAlarms.MAIN_ID))
    assertEquals(emptySet<Int>(), codes.toSet() intersect snoozes.toSet())
  }
}

/** Tools › Alarm's other-alarm rows say the repeat, then a non-default label. */
class OtherAlarmSubtitleTest {
  @Test fun repeatThenLabel() {
    assertEquals("Once", otherAlarmSubtitle(AlarmConfig.Config(true, 9, 0, "Alarm", emptySet())))
    assertEquals("On weekdays · Gym", otherAlarmSubtitle(AlarmConfig.Config(true, 6, 30, "Gym",
        (Calendar.MONDAY..Calendar.FRIDAY).toSet())))
    assertEquals("Every day", otherAlarmSubtitle(AlarmConfig.Config(true, 6, 30, "", (1..7).toSet())))
  }
}
