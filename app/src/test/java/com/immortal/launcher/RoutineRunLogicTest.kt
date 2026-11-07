/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.RoutineFixtures.MON
import com.immortal.launcher.RoutineFixtures.at
import com.immortal.launcher.RoutineFixtures.morning
import org.junit.Assert.*
import org.junit.Test

class RoutineRunLogicTest {
  private val r = morning()
  private val run = RoutineSchedule.startRun(r, at(MON, 7, 15), manual = false)
  private val now = at(MON, 7, 30)

  @Test fun tickThenUntick() {
    val ticked = RoutineRunLogic.toggle(r, run, "c0", "all", now)
    assertEquals(RoutineRunLogic.TickEvent.TICKED, ticked.event)
    assertEquals(listOf("c0"), ticked.run.ticks["all"])
    assertTrue(run.ticks.isEmpty()) // immutable: the original run is untouched
    val unticked = RoutineRunLogic.toggle(r, ticked.run, "c0", "all", now)
    assertEquals(RoutineRunLogic.TickEvent.UNTICKED, unticked.event)
    assertEquals(0, RoutineRunLogic.doneCount(r, unticked.run, "all"))
  }

  @Test fun lastTickFinishesTheRun() {
    var cur = run
    var last = RoutineRunLogic.TickEvent.IGNORED
    r.cards.forEach { c -> RoutineRunLogic.toggle(r, cur, c.id, "all", now).also { cur = it.run; last = it.event } }
    assertEquals(RoutineRunLogic.TickEvent.ALL_DONE, last)
    assertEquals(RunStatus.DONE, cur.status)
    assertEquals(now, cur.endedAtMs)
    assertEquals("done", cur.endedBy)
    assertEquals(RoutineRunLogic.TickEvent.IGNORED, RoutineRunLogic.toggle(r, cur, "c0", "all", now).event)
  }

  @Test fun unknownCardOrKidIsIgnored() {
    assertEquals(RoutineRunLogic.TickEvent.IGNORED, RoutineRunLogic.toggle(r, run, "zz", "all", now).event)
    assertEquals(RoutineRunLogic.TickEvent.IGNORED, RoutineRunLogic.toggle(r, run, "c0", "maya", now).event)
  }

  @Test fun twoKidsTickSeparately_andKidDoneMovesToTheNext() {
    val two = r.copy(kids = listOf("maya", "leo"))
    assertEquals(listOf("maya", "leo"), RoutineRunLogic.kidKeys(two))
    assertEquals(listOf("all"), RoutineRunLogic.kidKeys(r.copy(kids = listOf("maya"))))
    var cur = RoutineSchedule.startRun(two, at(MON, 7, 15), manual = false)
    var res: RoutineRunLogic.TickResult? = null
    two.cards.forEach { c -> res = RoutineRunLogic.toggle(two, cur, c.id, "maya", now); cur = res!!.run }
    assertEquals(RoutineRunLogic.TickEvent.KID_DONE, res!!.event)
    assertEquals("leo", RoutineRunLogic.nextKid(two, cur, "maya"))
    assertEquals(RunStatus.RUNNING, cur.status)
    assertEquals(5 to 10, RoutineRunLogic.totals(two, cur))
    two.cards.forEach { c -> res = RoutineRunLogic.toggle(two, cur, c.id, "leo", now); cur = res!!.run }
    assertEquals(RoutineRunLogic.TickEvent.ALL_DONE, res!!.event)
  }

  @Test fun closeAndUndo() {
    val closed = RoutineRunLogic.close(run, "parent", now)
    assertEquals(RunStatus.CLOSED, closed.status)
    val back = RoutineRunLogic.undoClose(closed)!!
    assertEquals(RunStatus.RUNNING, back.status)
    assertNull(back.endedAtMs)
    assertNull(RoutineRunLogic.undoClose(RoutineRunLogic.close(run, "done", now)))
  }

  @Test fun graceEndIsThirtyMinutesPastReadyBy_cappedAtMidnight() {
    assertEquals(at(MON, 8, 25), RoutineRunLogic.graceEndMs(run))
    val late = RoutineSchedule.startRun(morning().copy(start = 1400, end = 1430), at(MON, 23, 20), manual = false)
    assertEquals(at(MON + 1, 0, 0), RoutineRunLogic.graceEndMs(late))
  }

  @Test fun timeBarCopy() {
    assertEquals("25 min left", RoutineRunLogic.timeLine(run, at(MON, 7, 30)).main)
    assertEquals("Ready by 7:55", RoutineRunLogic.timeLine(run, at(MON, 7, 30)).sub)
    assertEquals("Almost time", RoutineRunLogic.timeLine(run, at(MON, 7, 54, 30)).main)
    val late = RoutineRunLogic.timeLine(run, at(MON, 7, 58))
    assertTrue(late.late)
    assertEquals("Time to go", late.main)
    assertEquals("3 min past 7:55", late.goal)
    assertEquals(0f, late.fraction)
    assertEquals(0.625f, RoutineRunLogic.timeLine(run, at(MON, 7, 30)).fraction, 0.001f)
  }

  @Test fun cheerCopy() {
    assertEquals("Ready at 7:41, 14 min early", RoutineRunLogic.cheerLine(run, at(MON, 7, 41)))
    assertEquals("Ready at 7:58", RoutineRunLogic.cheerLine(run, at(MON, 7, 58)))
  }

  @Test fun runJsonRoundTrips_andRejectsGarbage() {
    val t = RoutineRunLogic.toggle(r, run, "c1", "all", now).run
    assertEquals(t, RoutineRunLogic.parseRun(RoutineRunLogic.runJson(t)))
    assertNull(RoutineRunLogic.parseRun(org.json.JSONObject().put("rid", "r1")))
  }
}
