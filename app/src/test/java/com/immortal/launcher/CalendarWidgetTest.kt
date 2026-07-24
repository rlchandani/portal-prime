/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class CalendarWidgetTest {
  private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long {
    val c = Calendar.getInstance(); c.set(y, mo, d, h, mi, 0); c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
  }
  @Test fun timedEventLine_12h() {
    val now = at(2026, 6, 24, 8, 0)
    val e = CalendarFeed.Event(at(2026, 6, 24, 9, 30), at(2026, 6, 24, 10, 0), "Standup", false)
    val line = calendarEventLine(e, now, is24h = false)
    assertTrue(line.contains("Standup")); assertTrue(line.contains("9:30"))
  }
  @Test fun allDayEventLine() {
    val now = at(2026, 6, 24, 8, 0)
    val e = CalendarFeed.Event(at(2026, 6, 24, 0, 0), at(2026, 6, 25, 0, 0), "Holiday", true)
    assertEquals("All day  Holiday", calendarEventLine(e, now, is24h = false))
  }
  @Test fun groupByDay_labelsAndCaps() {
    val now = at(2026, 6, 24, 8, 0)
    val a = CalendarFeed.Event(at(2026, 6, 24, 9, 0), at(2026, 6, 24, 10, 0), "A", false)
    val b = CalendarFeed.Event(at(2026, 6, 25, 9, 0), at(2026, 6, 25, 10, 0), "B", false)
    val c = CalendarFeed.Event(at(2026, 6, 30, 9, 0), at(2026, 6, 30, 10, 0), "C", false)
    val g = groupEventsByDay(listOf(a, b, c), now, maxDays = 2)
    assertEquals(2, g.size); assertEquals("Today", g[0].label); assertEquals("Tomorrow", g[1].label)
  }
}
