/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Repeat days as spoken ⇄ Hearthpane's Calendar day set. */
class AlarmDaysTest {
  @Test fun groupsAndOnce() {
    assertEquals(emptySet<Int>(), AlarmDays.parse(null))
    assertEquals(emptySet<Int>(), AlarmDays.parse("once"))
    assertEquals(AlarmDays.WEEKDAYS, AlarmDays.parse("Weekdays"))
    assertEquals(AlarmDays.WEEKDAYS, AlarmDays.parse("every weekday"))
    assertEquals(AlarmDays.WEEKENDS, AlarmDays.parse("on weekends"))
    assertEquals(AlarmDays.EVERY_DAY, AlarmDays.parse("every day"))
    assertEquals(AlarmDays.EVERY_DAY, AlarmDays.parse("daily"))
  }

  @Test fun namedDaysLongShortAndPlural() {
    assertEquals(setOf(Calendar.MONDAY, Calendar.THURSDAY), AlarmDays.parse("monday and thursday"))
    assertEquals(setOf(Calendar.MONDAY, Calendar.WEDNESDAY, Calendar.FRIDAY), AlarmDays.parse("Mon, Wed & Fri"))
    assertEquals(setOf(Calendar.TUESDAY, Calendar.SATURDAY), AlarmDays.parse("tuesdays, saturdays"))
    assertEquals(setOf(Calendar.THURSDAY), AlarmDays.parse("every thurs"))
    assertEquals(setOf(Calendar.SUNDAY), AlarmDays.parse("sun"))
  }

  @Test fun nonsenseIsNull() {
    assertNull(AlarmDays.parse("someday"))
    assertNull(AlarmDays.parse("monday and later"))
    assertNull(AlarmDays.parse("&&"))
  }

  @Test fun spokenFormsStartTheWeekOnMonday() {
    assertEquals("", AlarmDays.spoken(emptySet()))
    assertEquals("on weekdays", AlarmDays.spoken(AlarmDays.WEEKDAYS))
    assertEquals("on weekends", AlarmDays.spoken(AlarmDays.WEEKENDS))
    assertEquals("every day", AlarmDays.spoken(AlarmDays.EVERY_DAY))
    assertEquals("on Mondays and Sundays", AlarmDays.spoken(setOf(Calendar.SUNDAY, Calendar.MONDAY)))
    assertEquals("Friday", AlarmDays.name(Calendar.FRIDAY))
  }
}
