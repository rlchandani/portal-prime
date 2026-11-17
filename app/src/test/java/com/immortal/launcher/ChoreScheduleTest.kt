/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.ChoreFixtures.MONDAY
import com.immortal.launcher.ChoreFixtures.state
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** Which chores show today, for whom, and how local days and weeks are counted. */
class ChoreScheduleTest {
  @Test fun weekdaysAreIsoAndWeeksStartOnMonday() {
    assertEquals(1, ChoreDays.weekday(MONDAY))
    assertEquals(7, ChoreDays.weekday(MONDAY + 6))
    assertEquals(4, ChoreDays.weekday(0)) // 1 Jan 1970 was a Thursday
    assertEquals(MONDAY, ChoreDays.weekStart(MONDAY))
    assertEquals(MONDAY, ChoreDays.weekStart(MONDAY + 6))
    assertEquals(MONDAY + 7, ChoreDays.weekStart(MONDAY + 7))
    assertEquals(MONDAY - 28, ChoreDays.keepFrom(MONDAY + 3))
  }

  @Test fun localDayFollowsTheZoneNotUtc() {
    val pacific = TimeZone.getTimeZone("America/Los_Angeles")
    // Monday 00:30 in Los Angeles is still Monday there, though UTC already reads 08:30.
    val mondayHalfPastMidnight = MONDAY * ChoreDays.DAY_MS + 8 * 3_600_000L + 30 * 60_000L
    assertEquals(MONDAY, ChoreDays.day(mondayHalfPastMidnight, pacific))
    // Sunday 23:59 local is the day before: the week resets at Monday 00:00 local.
    assertEquals(MONDAY - 1, ChoreDays.day(mondayHalfPastMidnight - 31 * 60_000L, pacific))
    assertEquals(MONDAY - 7, ChoreDays.weekStart(ChoreDays.day(mondayHalfPastMidnight - 31 * 60_000L, pacific)))
  }

  @Test fun dailyWeekdayAndOneOffChoresAreDueOnTheirDays() {
    assertTrue(ChoreDays.isDue(ChoreFixtures.bed, MONDAY + 4))
    assertTrue(ChoreDays.isDue(ChoreFixtures.toys, MONDAY))
    assertFalse(ChoreDays.isDue(ChoreFixtures.toys, MONDAY + 1))
    assertTrue(ChoreDays.isDue(ChoreFixtures.toys, MONDAY + 4))
    assertTrue(ChoreDays.isDue(ChoreFixtures.bath, MONDAY + 1))
    assertFalse(ChoreDays.isDue(ChoreFixtures.bath, MONDAY + 8))
    assertFalse(ChoreDays.isDue(ChoreFixtures.bed.copy(deleted = true), MONDAY))
  }

  @Test fun eachKidSeesTheirOwnChoresAndAnyoneChores() {
    val monday = ChoreBoard.today(state, MONDAY).associate { it.kid.name to it.items.map { i -> i.chore.id } }
    assertEquals(listOf("chbed", "chtoys"), monday["Mia"])
    assertEquals(listOf("chtoys"), monday["Leo"])
    val tuesday = ChoreBoard.today(state, MONDAY + 1).associate { it.kid.name to it.items.map { i -> i.chore.id } }
    assertEquals(listOf("chbed"), tuesday["Mia"])
    assertEquals(listOf("chbath"), tuesday["Leo"])
  }

  @Test fun anAnyoneChoreDoneByOneKidLeavesTheOthersList() {
    val s = ChoreFixtures.tick(state, "chtoys", "kidleo", MONDAY, "tick0001")
    val board = ChoreBoard.today(s, MONDAY).associateBy { it.kid.name }
    assertEquals(listOf("chbed"), board.getValue("Mia").items.map { it.chore.id })
    assertTrue(board.getValue("Leo").items.single().done)
  }

  @Test fun deletedKidsAndChoresDisappear() {
    val s = ChoreFixtures.ok(ChoreOps.deleteKid(state, "kidmia", ChoreFixtures.KITCHEN))
    assertEquals(listOf("Leo"), ChoreBoard.today(s, MONDAY).map { it.kid.name })
    assertTrue("Mia's own chores go with her", s.chores.getValue("chbed").deleted)
    assertFalse("anyone chores stay", s.chores.getValue("chtoys").deleted)
  }
}
