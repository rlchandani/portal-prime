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

class RoutineScheduleTest {
  private val wed = MON + 2

  @Test fun skipReasons() {
    val r = morning().copy(skips = listOf(wed))
    assertNull(RoutineSchedule.skipReason(r, MON))
    assertEquals(RoutineSchedule.Skip.DAY, RoutineSchedule.skipReason(r, MON + 5))
    assertEquals(RoutineSchedule.Skip.DATE, RoutineSchedule.skipReason(r, wed))
    assertEquals(RoutineSchedule.Skip.OFF, RoutineSchedule.skipReason(r.copy(enabled = false), MON))
  }

  @Test fun dueAtStart_notBefore_notAfterEnd_notTwice() {
    val d = doc(morning(), bedtime())
    assertNull(RoutineSchedule.dueToStart(d, emptyMap(), at(MON, 7, 14, 59)))
    assertEquals("r1", RoutineSchedule.dueToStart(d, emptyMap(), at(MON, 7, 15))?.id)
    assertEquals("r1", RoutineSchedule.dueToStart(d, emptyMap(), at(MON, 7, 54))?.id) // late boot still starts
    assertNull(RoutineSchedule.dueToStart(d, emptyMap(), at(MON, 7, 55)))
    val run = RoutineSchedule.startRun(morning(), at(MON, 7, 15), manual = false)
    assertNull(RoutineSchedule.dueToStart(d, mapOf("r1" to run), at(MON, 7, 20)))
    assertNull(RoutineSchedule.dueToStart(d, emptyMap(), at(MON + 5, 7, 20))) // Saturday
    assertNull(RoutineSchedule.dueToStart(doc(morning().copy(skips = listOf(wed))), emptyMap(), at(wed, 7, 20)))
  }

  @Test fun startRunDeadlines() {
    val scheduled = RoutineSchedule.startRun(morning(), at(MON, 7, 15), manual = false)
    assertEquals(at(MON, 7, 55), scheduled.deadlineMs)
    assertEquals(MON, scheduled.day)
    val manual = RoutineSchedule.startRun(morning(), at(MON, 18, 0), manual = true)
    assertEquals(at(MON, 18, 40), manual.deadlineMs) // a full routine length from now
  }

  @Test fun nextWake_isNextStartOrGraceEnd() {
    val d = doc(morning(), bedtime())
    assertEquals(at(MON, 7, 15), RoutineSchedule.nextWake(d, emptyMap(), at(MON, 7, 0)))
    val run = RoutineSchedule.startRun(morning(), at(MON, 7, 15), manual = false)
    assertEquals(at(MON, 8, 25), RoutineSchedule.nextWake(d, mapOf("r1" to run), at(MON, 7, 16)))
    val done = RoutineRunLogic.close(run, "done", at(MON, 7, 41))
    assertEquals(at(MON, 19, 30), RoutineSchedule.nextWake(d, mapOf("r1" to done), at(MON, 7, 42)))
    assertEquals(at(MON + 1, 7, 15), RoutineSchedule.nextWake(d, mapOf("r1" to done), at(MON, 20, 0)))
    assertNull(RoutineSchedule.nextWake(doc(), emptyMap(), at(MON, 7, 0)))
  }

  @Test fun expiredRunsCloseThirtyMinutesAfterReadyBy_orWhenRemoved() {
    val d = doc(morning())
    val run = RoutineSchedule.startRun(morning(), at(MON, 7, 15), manual = false)
    assertTrue(RoutineSchedule.expired(d, mapOf("r1" to run), at(MON, 8, 24)).isEmpty())
    assertEquals("auto", RoutineSchedule.expired(d, mapOf("r1" to run), at(MON, 8, 25)).single().second)
    assertEquals("removed", RoutineSchedule.expired(doc(), mapOf("r1" to run), at(MON, 7, 20)).single().second)
  }

  @Test fun widgetAndSheetStatusCopy() {
    val r = morning()
    assertEquals("Today, 7:15", RoutineSchedule.widgetStatus(r, emptyMap(), at(MON, 7, 0)).text)
    assertEquals("Tomorrow, 7:15", RoutineSchedule.widgetStatus(r, emptyMap(), at(MON, 9, 0)).text)
    assertEquals("Monday, 7:15", RoutineSchedule.widgetStatus(r, emptyMap(), at(MON + 4, 9, 0)).text)
    val run = RoutineSchedule.startRun(r, at(MON, 7, 15), manual = false)
    assertEquals(RoutineSchedule.Tone.LIVE, RoutineSchedule.widgetStatus(r, mapOf("r1" to run), at(MON, 7, 20)).tone)
    assertEquals("On now", RoutineSchedule.widgetStatus(r, mapOf("r1" to run), at(MON, 7, 20)).text)
    val done = RoutineRunLogic.close(run, "done", at(MON, 7, 41))
    assertEquals("Done at 7:41", RoutineSchedule.widgetStatus(r, mapOf("r1" to done), at(MON, 7, 50)).text)
    val closed = RoutineRunLogic.close(run, "auto", at(MON, 8, 25))
    assertEquals("Closed at 8:25", RoutineSchedule.widgetStatus(r, mapOf("r1" to closed), at(MON, 8, 30)).text)
    assertEquals("Skipped today", RoutineSchedule.widgetStatus(r.copy(skips = listOf(wed)), emptyMap(), at(wed, 7, 0)).text)
    assertEquals("Off", RoutineSchedule.widgetStatus(r.copy(enabled = false), emptyMap(), at(MON, 7, 0)).text)
    assertEquals("Next: Today, 7:15", RoutineSchedule.statusLine(r, emptyMap(), at(MON, 7, 0)))
    assertEquals("Done today at 7:41", RoutineSchedule.statusLine(r, mapOf("r1" to done), at(MON, 8, 0)))
    assertEquals("Switched off in Portal Toolkit", RoutineSchedule.statusLine(r.copy(enabled = false), emptyMap(), at(MON, 8, 0)))
    assertEquals("Monday to Friday", RoutineSchedule.daysText(r.days))
    assertEquals("Every day", RoutineSchedule.daysText(bedtime().days))
  }
}
