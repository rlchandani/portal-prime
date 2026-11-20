/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Peers and files are untrusted: every field of a kid / chore / tick is checked for type and range,
 * a bad entry is skipped on its own, and a bad envelope reads as null.
 */
class ChoreCodecCoverageTest {
  private val tick = ChoreTick("tk0001", "chbed", "kidmia", ChoreFixtures.MONDAY, 2, TickStatus.PENDING, 3, ChoreFixtures.LIVING)
  private val full = ChoreFixtures.state.copy(ticks = mapOf(tick.id to tick))

  private fun envelope(kids: List<Any> = emptyList(), chores: List<Any> = emptyList(), ticks: List<Any> = emptyList()) =
      JSONObject().put("v", ChoreCodec.VERSION).put("kids", JSONArray(kids)).put("chores", JSONArray(chores)).put("ticks", JSONArray(ticks))

  private fun kidJson() = ChoreCodec.kidJson(ChoreFixtures.mia)
  private fun choreJson(c: Chore = ChoreFixtures.bed) = ChoreCodec.choreJson(c)
  private fun tickJson() = ChoreCodec.tickJson(tick)

  private fun decodedKids(o: JSONObject) = ChoreCodec.decodeState(envelope(kids = listOf(o)))!!.kids
  private fun decodedChores(o: JSONObject) = ChoreCodec.decodeState(envelope(chores = listOf(o)))!!.chores
  private fun decodedTicks(o: JSONObject) = ChoreCodec.decodeState(envelope(ticks = listOf(o)))!!.ticks

  private val badStamp: List<Pair<String, Any>> = listOf(
      "q" to 0, "q" to -5, "q" to Long.MAX_VALUE, "q" to "1", "q" to 1.5,
      "b" to "", "b" to "bad device", "b" to 7, "x" to "true", "x" to 1)

  @Test fun fullStateRoundTrips() {
    assertEquals(full, ChoreCodec.decodeState(ChoreCodec.encodeState(full)))
    assertEquals(full, ChoreCodec.decodeState(JSONObject(ChoreCodec.encodeState(full).toString())))
  }

  @Test fun badEnvelopesReadAsNull() {
    val good = ChoreCodec.encodeState(full)
    assertNull(ChoreCodec.decodeState(null))
    assertNull(ChoreCodec.decodeState(JSONObject()))
    assertNull(ChoreCodec.decodeState(JSONObject(good.toString()).put("v", 2)))
    for (field in listOf("kids", "chores", "ticks")) {
      assertNull(field, ChoreCodec.decodeState(JSONObject(good.toString()).apply { remove(field) }))
      assertNull(field, ChoreCodec.decodeState(JSONObject(good.toString()).put(field, JSONObject())))
    }
  }

  @Test fun nonObjectArrayEntriesAreSkipped() {
    val s = ChoreCodec.decodeState(envelope(kids = listOf("junk", 4, kidJson()), chores = listOf(JSONArray()), ticks = listOf(JSONObject.NULL)))!!
    assertEquals(setOf("kidmia"), s.kids.keys)
    assertTrue(s.chores.isEmpty())
    assertTrue(s.ticks.isEmpty())
  }

  @Test fun kidFieldsAreValidated() {
    val bad: List<Pair<String, Any>> = listOf(
        "i" to 5, "i" to "AB", "i" to "abc", "i" to "a".repeat(25),
        "n" to 5, "n" to "   ", "a" to "x", "a" to 3,
        "c" to -1, "c" to ChoreLimits.COLORS, "c" to "1", "c" to 1.5, "c" to (1L shl 40),
        "g" to -1, "g" to ChoreLimits.MAX_GOAL + 1, "g" to "6") + badStamp
    for ((field, value) in bad) {
      assertTrue("kid $field=$value", decodedKids(kidJson().put(field, value)).isEmpty())
    }
    for (field in listOf("i", "n", "a", "c", "g", "q", "b", "x")) {
      assertTrue("kid missing $field", decodedKids(kidJson().apply { remove(field) }).isEmpty())
    }
  }

  @Test fun kidRewardIsOptionalAndJunkRewardsBecomeEmpty() {
    for (reward in listOf<Any>(5, "   ", JSONObject())) {
      assertEquals("reward $reward", "", decodedKids(kidJson().put("r", reward)).getValue("kidmia").reward)
    }
    assertEquals("", decodedKids(kidJson().apply { remove("r") }).getValue("kidmia").reward)
    assertEquals(ChoreFixtures.mia, decodedKids(kidJson()).getValue("kidmia"))
  }

  @Test fun kidBoundariesAreAccepted() {
    val k = decodedKids(kidJson().put("c", ChoreLimits.COLORS - 1).put("g", ChoreLimits.MAX_GOAL).put("q", Long.MAX_VALUE / 2))
    assertEquals(ChoreLimits.MAX_GOAL, k.getValue("kidmia").goal)
    assertEquals(Long.MAX_VALUE / 2, k.getValue("kidmia").seq)
  }

  @Test fun choreFieldsAreValidated() {
    val bad: List<Pair<String, Any>> = listOf(
        "i" to "Bad", "i" to 3, "t" to 9, "t" to "\n\t", "p" to "nopic", "p" to 1,
        "k" to 5, "k" to "BAD!", "s" to 0, "s" to ChoreLimits.MAX_STARS + 1, "s" to "2",
        "w" to "x", "w" to 1, "ok" to "true", "ok" to 0) + badStamp
    for ((field, value) in bad) {
      assertTrue("chore $field=$value", decodedChores(choreJson().put(field, value)).isEmpty())
    }
    for (field in listOf("i", "t", "p", "k", "s", "w", "ok", "q", "b", "x")) {
      assertTrue("chore missing $field", decodedChores(choreJson().apply { remove(field) }).isEmpty())
    }
  }

  @Test fun emptyKidMeansAnyone() {
    assertNull(decodedChores(choreJson().put("k", "")).getValue("chbed").kid)
  }

  @Test fun weekdayScheduleNeedsOneToSevenWholeNumbers() {
    val toys = { choreJson(ChoreFixtures.toys) }
    assertEquals(ChoreFixtures.toys, decodedChores(toys()).getValue("chtoys"))
    for (wd in listOf<Any>(JSONArray(), JSONArray(listOf(0)), JSONArray(listOf(8)), JSONArray(listOf("1")),
        JSONArray(listOf(1.5)), JSONArray(listOf(1, JSONObject.NULL)), "1,2")) {
      assertTrue("wd $wd", decodedChores(toys().put("wd", wd)).isEmpty())
    }
    assertTrue(decodedChores(toys().apply { remove("wd") }).isEmpty())
    assertEquals(ChoreSchedule.Weekdays(setOf(1, 7)),
        decodedChores(toys().put("wd", JSONArray(listOf(7, 1, 7)))).getValue("chtoys").schedule)
  }

  @Test fun onceScheduleNeedsAPlausibleDay() {
    val bath = { choreJson(ChoreFixtures.bath) }
    assertEquals(ChoreFixtures.bath, decodedChores(bath()).getValue("chbath"))
    for (od in listOf<Any>(-1, 200_001, "5", 1.5)) {
      assertTrue("od $od", decodedChores(bath().put("od", od)).isEmpty())
    }
    assertTrue(decodedChores(bath().apply { remove("od") }).isEmpty())
    assertEquals(ChoreSchedule.Once(0), decodedChores(bath().put("od", 0)).getValue("chbath").schedule)
    assertEquals(ChoreSchedule.Once(200_000), decodedChores(bath().put("od", 200_000)).getValue("chbath").schedule)
  }

  @Test fun tickFieldsAreValidated() {
    val bad: List<Pair<String, Any>> = listOf(
        "i" to "X", "c" to "X", "c" to 1, "k" to "", "k" to 2, "d" to -1, "d" to 200_001, "d" to "1",
        "s" to 0, "s" to 6, "st" to "zzz", "st" to 1, "q" to 0, "q" to "3", "q" to Long.MAX_VALUE,
        "b" to "", "b" to 1)
    for ((field, value) in bad) {
      assertTrue("tick $field=$value", decodedTicks(tickJson().put(field, value)).isEmpty())
    }
    for (field in listOf("i", "c", "k", "d", "s", "st", "q", "b")) {
      assertTrue("tick missing $field", decodedTicks(tickJson().apply { remove(field) }).isEmpty())
    }
    for (status in TickStatus.entries) {
      assertEquals(status, decodedTicks(tickJson().put("st", status.wire)).getValue("tk0001").status)
    }
  }

  @Test fun oneBadEntryDoesNotSinkItsNeighbours() {
    val s = ChoreCodec.decodeState(envelope(
        kids = listOf(kidJson().put("c", 99), ChoreCodec.kidJson(ChoreFixtures.leo)),
        chores = listOf(choreJson().put("s", 9), choreJson(ChoreFixtures.toys))))!!
    assertEquals(setOf("kidleo"), s.kids.keys)
    assertEquals(setOf("chtoys"), s.chores.keys)
  }

  @Test fun digestIsTwelveHexStableAndSensitiveToEveryKind() {
    val d = ChoreCodec.digest(full)
    assertTrue(Regex("^[0-9a-f]{12}$").matches(d))
    assertEquals(d, ChoreCodec.digest(ChoreCodec.decodeState(ChoreCodec.encodeState(full))!!))
    assertNotEquals(d, ChoreCodec.digest(full.copy(kids = full.kids + ("kidmia" to ChoreFixtures.mia.copy(goal = 7)))))
    assertNotEquals(d, ChoreCodec.digest(full.copy(chores = full.chores + ("chbed" to ChoreFixtures.bed.copy(deleted = true)))))
    assertNotEquals(d, ChoreCodec.digest(full.copy(ticks = mapOf(tick.id to tick.copy(status = TickStatus.APPROVED)))))
    val weekdaysA = full.copy(chores = mapOf("chtoys" to ChoreFixtures.toys.copy(schedule = ChoreSchedule.Weekdays(setOf(3, 1)))))
    val weekdaysB = full.copy(chores = mapOf("chtoys" to ChoreFixtures.toys.copy(schedule = ChoreSchedule.Weekdays(setOf(1, 3)))))
    assertEquals(ChoreCodec.digest(weekdaysA), ChoreCodec.digest(weekdaysB))
    assertEquals(ChoreCodec.digest(ChoreState()), ChoreCodec.digest(ChoreState()))
  }

  @Test fun wholeNumberHelpers() {
    assertEquals(3L, ChoreCodec.long(3))
    assertEquals(1L shl 40, ChoreCodec.long(1L shl 40))
    assertNull(ChoreCodec.long(3.0))
    assertNull(ChoreCodec.long("3"))
    assertNull(ChoreCodec.long(null))
    assertEquals(Int.MAX_VALUE, ChoreCodec.int(Int.MAX_VALUE.toLong()))
    assertEquals(Int.MIN_VALUE, ChoreCodec.int(Int.MIN_VALUE.toLong()))
    assertNull(ChoreCodec.int(Int.MAX_VALUE.toLong() + 1))
    assertNull(ChoreCodec.int(Int.MIN_VALUE.toLong() - 1))
    assertTrue(ChoreCodec.isDay(0))
    assertTrue(!ChoreCodec.isDay(-1))
  }
}
