/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.RoutineFixtures.MON
import com.immortal.launcher.RoutineFixtures.at
import com.immortal.launcher.RoutineFixtures.bedtime
import com.immortal.launcher.RoutineFixtures.doc
import com.immortal.launcher.RoutineFixtures.morning
import org.junit.Assert.*
import org.junit.Test

/** Routine scheduling edges: one run at a time, lookahead labels, stale runs and the status copy fallbacks. */
class RoutineScheduleCoverageTest {
  private val mondaysOnly = List(7) { it == 0 }

  @Test fun anotherRunningRoutineBlocksAnyNewStart() {
    val evening = bedtime().copy(start = 435, end = 475, days = List(7) { true })
    val other = RoutineSchedule.startRun(evening, at(MON, 7, 15), manual = true)
    assertNull(RoutineSchedule.dueToStart(doc(morning()), mapOf("r2" to other), at(MON, 7, 20)))
    assertEquals(other, RoutineSchedule.active(mapOf("r2" to other)))
    val finished = RoutineRunLogic.close(other, "done", at(MON, 7, 30))
    assertNull(RoutineSchedule.active(mapOf("r2" to finished)))
    assertEquals("r1", RoutineSchedule.dueToStart(doc(morning()), mapOf("r2" to finished), at(MON, 7, 31))?.id)
  }

  @Test fun theEarliestStartingRoutineWinsAndYesterdaysRunDoesNotBlockToday() {
    val early = morning().copy(id = "r0", name = "Early", start = 430, end = 470)
    assertEquals("r0", RoutineSchedule.dueToStart(doc(morning(), early), emptyMap(), at(MON, 7, 16))?.id)
    val yesterday = RoutineRunLogic.close(RoutineSchedule.startRun(morning(), at(MON - 7, 7, 15), manual = false), "done", at(MON - 7, 7, 40))
    assertEquals("r1", RoutineSchedule.dueToStart(doc(morning()), mapOf("r1" to yesterday), at(MON, 7, 16))?.id)
  }

  @Test fun startingManuallyBeforeTheWindowKeepsTheScheduledReadyBy() {
    val run = RoutineSchedule.startRun(morning(), at(MON, 6, 0), manual = true)
    assertEquals(at(MON, 7, 55), run.deadlineMs)
    assertEquals(at(MON, 6, 0), run.startMs)
    assertEquals("r1", run.rid)
  }

  @Test fun expiredIgnoresRunsThatAreNoLongerRunning() {
    val run = RoutineSchedule.startRun(morning(), at(MON, 7, 15), manual = false)
    val done = RoutineRunLogic.close(run, "done", at(MON, 7, 20))
    assertTrue(RoutineSchedule.expired(doc(), mapOf("r1" to done), at(MON, 9, 0)).isEmpty())
  }

  @Test fun graceEndIsCappedAtMidnight() {
    val late = bedtime().copy(start = 1400, end = 1435)
    val run = RoutineSchedule.startRun(late, at(MON, 23, 20), manual = false)
    assertTrue(RoutineSchedule.expired(doc(late), mapOf("r2" to run), at(MON, 23, 59)).isEmpty())
    assertEquals("auto", RoutineSchedule.expired(doc(late), mapOf("r2" to run), at(MON + 1, 0, 0)).single().second)
  }

  @Test fun nextWakeIgnoresAGraceEndThatHasAlreadyPassed() {
    val run = RoutineSchedule.startRun(morning(), at(MON, 7, 15), manual = false)
    // The run should already be closed (busy retry), so the next wake is the bedtime start, not the past grace end.
    assertEquals(at(MON, 19, 30), RoutineSchedule.nextWake(doc(morning(), bedtime()), mapOf("r1" to run), at(MON, 9, 0)))
  }

  @Test fun occurrencesOneOrMoreWeeksOutUseAShortDate() {
    val r = morning().copy(days = mondaysOnly)
    val next = RoutineSchedule.nextOccurrence(r, emptyMap(), at(MON, 9, 0))!!
    assertEquals(MON + 7, next.day)
    assertEquals("Mon 16 Nov", next.label)
    assertEquals(RoutineDates.localMs(MON + 7, r.start), next.startMs)
    // Thirteen days out is the furthest the lookahead reaches.
    val sunday = morning().copy(days = List(7) { it == 6 })
    assertEquals("Sun 22 Nov", RoutineSchedule.nextOccurrence(sunday.copy(skips = listOf(MON + 6)), emptyMap(), at(MON, 9, 0))!!.label)
  }

  @Test fun shortDateNamesTheMonthAcrossAYearEnd() {
    // Monday 28 December 2026 → the next Monday is 4 January 2027.
    val dec28 = RoutineDates.epochDayOf("2026-12-28")!!
    val r = morning().copy(days = mondaysOnly)
    assertEquals("Mon 4 Jan", RoutineSchedule.nextOccurrence(r, emptyMap(), at(dec28, 9, 0))!!.label)
  }

  @Test fun nothingWithinTheLookaheadIsNotScheduled() {
    val r = morning().copy(days = List(7) { false })
    assertNull(RoutineSchedule.nextOccurrence(r, emptyMap(), at(MON, 9, 0)))
    assertEquals(RoutineSchedule.Status("Not scheduled", RoutineSchedule.Tone.NORMAL), RoutineSchedule.widgetStatus(r, emptyMap(), at(MON, 9, 0)))
    assertEquals("Not scheduled", RoutineSchedule.statusLine(r, emptyMap(), at(MON, 9, 0)))
    assertNull(RoutineSchedule.nextOccurrence(r.copy(enabled = false), emptyMap(), at(MON, 7, 0)))
  }

  @Test fun todaysRunMovesTheNextOccurrenceToTomorrow() {
    val run = RoutineRunLogic.close(RoutineSchedule.startRun(morning(), at(MON, 7, 15), manual = false), "done", at(MON, 7, 20))
    val next = RoutineSchedule.nextOccurrence(morning(), mapOf("r1" to run), at(MON, 7, 21))!!
    assertEquals("Tomorrow", next.label)
    assertEquals(MON + 1, next.day)
  }

  @Test fun widgetFallsBackToNowWhenAnEndTimeIsMissingAndIgnoresOldRuns() {
    val r = morning()
    val base = RoutineSchedule.startRun(r, at(MON, 7, 15), manual = false)
    val doneNoTime = base.copy(status = RunStatus.DONE, endedAtMs = null)
    val closedNoTime = base.copy(status = RunStatus.CLOSED, endedAtMs = null)
    assertEquals(RoutineSchedule.Status("Done at 7:50", RoutineSchedule.Tone.DONE), RoutineSchedule.widgetStatus(r, mapOf("r1" to doneNoTime), at(MON, 7, 50)))
    assertEquals(RoutineSchedule.Status("Closed at 7:50", RoutineSchedule.Tone.NORMAL), RoutineSchedule.widgetStatus(r, mapOf("r1" to closedNoTime), at(MON, 7, 50)))
    assertEquals("Done today at 7:50", RoutineSchedule.statusLine(r, mapOf("r1" to doneNoTime), at(MON, 7, 50)))
    assertEquals("Closed today at 7:50", RoutineSchedule.statusLine(r, mapOf("r1" to closedNoTime), at(MON, 7, 50)))
    // Last week's run says nothing about today.
    val old = base.copy(day = MON - 7)
    assertEquals("Today, 7:15", RoutineSchedule.widgetStatus(r, mapOf("r1" to old), at(MON, 7, 0)).text)
    assertEquals("Next: Today, 7:15", RoutineSchedule.statusLine(r, mapOf("r1" to old), at(MON, 7, 0)))
  }

  @Test fun statusLineSaysOnNowForARunningRun() {
    val run = RoutineSchedule.startRun(morning(), at(MON, 7, 15), manual = false)
    assertEquals("On now", RoutineSchedule.statusLine(morning(), mapOf("r1" to run), at(MON, 7, 20)))
    assertEquals("Next: Tomorrow, 7:15", RoutineSchedule.statusLine(morning(), emptyMap(), at(MON, 7, 55)))
  }

  @Test fun aSkippedDateStopsSayingSkippedOnceItsWindowHasPassed() {
    val r = morning().copy(skips = listOf(MON))
    assertEquals("Skipped today", RoutineSchedule.widgetStatus(r, emptyMap(), at(MON, 7, 54)).text)
    assertEquals("Tomorrow, 7:15", RoutineSchedule.widgetStatus(r, emptyMap(), at(MON, 7, 55)).text)
    // A weekday the routine doesn't run on just shows the next one.
    assertEquals("Monday, 7:15", RoutineSchedule.widgetStatus(r.copy(skips = emptyList()), emptyMap(), at(MON + 5, 7, 0)).text)
  }

  @Test fun daysTextListsShortNamesForIrregularWeeks() {
    assertEquals("Sat, Sun", RoutineSchedule.daysText(List(7) { it >= 5 }))
    assertEquals("Mon, Tue, Wed, Thu, Fri, Sat", RoutineSchedule.daysText(List(7) { it < 6 }))
    assertEquals("Mon, Tue, Wed, Thu, Sun", RoutineSchedule.daysText(List(7) { it < 4 || it == 6 }))
    assertEquals("Wed", RoutineSchedule.daysText(List(7) { it == 2 }))
  }
}
