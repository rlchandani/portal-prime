package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Unit tests for [AlarmConfig.nextTrigger] — the pure alarm-scheduling math (time + day-of-week +
 * enabled → next epoch-millis fire, or null). Deterministic via a fixed `from` calendar.
 */
class AlarmConfigTest {

  /** A fixed reference instant: Wednesday 2026-07-15, 10:00:00 local. */
  private fun wed1000(): Calendar =
      Calendar.getInstance().apply {
        set(2026, Calendar.JULY, 15, 10, 0, 0) // Jul 15 2026 is a Wednesday
        set(Calendar.MILLISECOND, 0)
      }

  private fun cfg(
      enabled: Boolean = true,
      hour: Int = 7,
      minute: Int = 0,
      days: Set<Int> = emptySet(),
  ) = AlarmConfig.Config(enabled, hour, minute, "Alarm", days)

  private fun Long.cal(): Calendar = Calendar.getInstance().apply { timeInMillis = this@cal }

  @Test
  fun disabled_returnsNull() {
    assertNull(AlarmConfig.nextTrigger(cfg(enabled = false), wed1000()))
  }

  @Test
  fun oneShot_laterToday_firesToday() {
    // 14:30 today, no repeat days → same day.
    val t = AlarmConfig.nextTrigger(cfg(hour = 14, minute = 30), wed1000())!!.cal()
    assertEquals(Calendar.WEDNESDAY, t.get(Calendar.DAY_OF_WEEK))
    assertEquals(14, t.get(Calendar.HOUR_OF_DAY))
    assertEquals(30, t.get(Calendar.MINUTE))
    assertEquals(15, t.get(Calendar.DAY_OF_MONTH))
  }

  @Test
  fun oneShot_timeAlreadyPassed_rollsToTomorrow() {
    // 07:00, but it's already 10:00 → next occurrence is tomorrow (Thu).
    val t = AlarmConfig.nextTrigger(cfg(hour = 7, minute = 0), wed1000())!!.cal()
    assertEquals(Calendar.THURSDAY, t.get(Calendar.DAY_OF_WEEK))
    assertEquals(16, t.get(Calendar.DAY_OF_MONTH))
    assertEquals(7, t.get(Calendar.HOUR_OF_DAY))
  }

  @Test
  fun repeat_picksNextMatchingWeekday() {
    // Repeats Mon/Fri at 07:00; from Wed → next match is Friday.
    val t = AlarmConfig.nextTrigger(
        cfg(hour = 7, minute = 0, days = setOf(Calendar.MONDAY, Calendar.FRIDAY)), wed1000())!!.cal()
    assertEquals(Calendar.FRIDAY, t.get(Calendar.DAY_OF_WEEK))
    assertEquals(17, t.get(Calendar.DAY_OF_MONTH))
  }

  @Test
  fun repeat_todayMatchesButTimePassed_skipsToNextMatchingDay() {
    // Repeats Wednesday only, at 07:00; it's already 10:00 Wed → next Wed (a week out).
    val t = AlarmConfig.nextTrigger(cfg(hour = 7, minute = 0, days = setOf(Calendar.WEDNESDAY)), wed1000())!!.cal()
    assertEquals(Calendar.WEDNESDAY, t.get(Calendar.DAY_OF_WEEK))
    assertEquals(22, t.get(Calendar.DAY_OF_MONTH)) // Jul 22, the following Wednesday
  }

  @Test
  fun repeat_todayMatchesAndTimeAhead_firesToday() {
    // Repeats Wednesday at 14:00; it's 10:00 Wed → today.
    val t = AlarmConfig.nextTrigger(cfg(hour = 14, minute = 0, days = setOf(Calendar.WEDNESDAY)), wed1000())!!.cal()
    assertEquals(15, t.get(Calendar.DAY_OF_MONTH))
    assertEquals(14, t.get(Calendar.HOUR_OF_DAY))
  }

  @Test
  fun triggerIsAlwaysInTheFuture() {
    val from = wed1000()
    val t = AlarmConfig.nextTrigger(cfg(hour = 10, minute = 0), from) // exactly now
    // 10:00 == from, so it must roll forward, never return <= now.
    assertTrue(t!! > from.timeInMillis)
  }
}
