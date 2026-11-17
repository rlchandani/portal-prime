/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.ChoreFixtures.MONDAY
import com.immortal.launcher.ChoreFixtures.ok
import com.immortal.launcher.ChoreFixtures.state
import com.immortal.launcher.ChoreFixtures.tick
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The replica's JSON form (storage, peer exchange, phone GET) round-trips and rejects junk. */
class ChoreCodecTest {
  private val full: ChoreState = ok(ChoreOps.deleteChore(
      ok(ChoreOps.approve(tick(tick(state, "chbath", "kidleo", MONDAY + 1, "tick0001"), "chbed", "kidmia", MONDAY, "tick0002"),
          "tick0001", ChoreLimits.PHONE)), "chtoys", "living-room"))

  @Test fun roundTripsEveryFieldIncludingTombstonesAndSchedules() {
    val back = ChoreCodec.decodeState(JSONObject(ChoreCodec.encodeState(full).toString()))
    assertEquals(full, back)
    assertEquals(ChoreSchedule.Weekdays(setOf(1, 3, 5)), back!!.chores.getValue("chtoys").schedule)
    assertTrue(back.chores.getValue("chtoys").deleted)
    assertEquals(TickStatus.APPROVED, back.ticks.getValue("tick0001").status)
  }

  @Test fun digestIsStableAndChangesWithAnyEdit() {
    val d = ChoreCodec.digest(full)
    assertEquals(12, d.length)
    assertEquals(d, ChoreCodec.digest(ChoreCodec.decodeState(ChoreCodec.encodeState(full))!!))
    assertNotEquals(d, ChoreCodec.digest(tick(full, "chbed", "kidmia", MONDAY + 1, "tick0003")))
    assertNotEquals(d, ChoreCodec.digest(ok(ChoreOps.saveKid(full, ChoreFixtures.mia.copy(goal = 7), "kitchen"))))
  }

  @Test fun badEntriesAreSkippedAndABadEnvelopeIsNull() {
    val json = ChoreCodec.encodeState(full)
    json.getJSONArray("kids").put(JSONObject().put("i", "BAD ID").put("n", "X"))
    json.getJSONArray("kids").put(ChoreCodec.kidJson(ChoreFixtures.mia.copy(id = "kidbad")).put("a", "💀"))
    json.getJSONArray("chores").put(ChoreCodec.choreJson(ChoreFixtures.bed.copy(id = "chbad")).put("p", "unknown-pic"))
    json.getJSONArray("chores").put(ChoreCodec.choreJson(ChoreFixtures.bed.copy(id = "chbad2")).put("s", 9))
    json.getJSONArray("ticks").put(ChoreCodec.tickJson(full.ticks.getValue("tick0002")).put("i", "tickbad1").put("st", "maybe"))
    json.getJSONArray("ticks").put(ChoreCodec.tickJson(full.ticks.getValue("tick0002")).put("i", "tickbad2").put("q", 1.5))
    assertEquals(full, ChoreCodec.decodeState(json))
    assertNull(ChoreCodec.decodeState(null))
    assertNull(ChoreCodec.decodeState(JSONObject().put("v", 2).put("kids", JSONArray()).put("chores", JSONArray()).put("ticks", JSONArray())))
    assertNull(ChoreCodec.decodeState(JSONObject().put("v", 1).put("kids", JSONArray())))
  }

  @Test fun schedulesRejectEmptyOrOutOfRangeWeekdays() {
    assertNull(ChoreCodec.schedule(JSONObject().put("w", "w").put("wd", JSONArray())))
    assertNull(ChoreCodec.schedule(JSONObject().put("w", "w").put("wd", JSONArray(listOf(0, 3)))))
    assertNull(ChoreCodec.schedule(JSONObject().put("w", "o").put("od", -4)))
    assertNull(ChoreCodec.schedule(JSONObject().put("w", "x")))
    assertEquals(ChoreSchedule.Once(MONDAY), ChoreCodec.schedule(JSONObject().put("w", "o").put("od", MONDAY)))
  }

  @Test fun textIsCleanedAndClipped() {
    assertEquals("Mia Rose", ChoreText.clean("  Mia\n\tRose ", 16))
    assertEquals("Supercalifragili", ChoreText.clean("Supercalifragilistic", 16))
    assertNull(ChoreText.clean(" \u0007 ", 16))
  }

  /** The same fixture the phone's ChoreModelTest decodes: the wire contract both sides share. */
  @Test fun decodesTheSharedContractFixture() {
    val wire = """{"v":1,"kids":[{"i":"kidmia","n":"Mia","a":"🦊","c":0,"g":6,"r":"Movie night","q":2,"b":"living-room","x":false}],
      "chores":[{"i":"chtoys","t":"Tidy toys","p":"toys","k":"","s":3,"ok":true,"q":1,"b":"kitchen","x":false,"w":"w","wd":[1,3,5]}],
      "ticks":[{"i":"tick0002","c":"chtoys","k":"kidmia","d":20773,"s":3,"st":"wait","q":1,"b":"kitchen"}]}"""
    val s = ChoreCodec.decodeState(JSONObject(wire))!!
    assertEquals(ChoreKid("kidmia", "Mia", "🦊", 0, 6, "Movie night", 2, "living-room"), s.kids.getValue("kidmia"))
    assertEquals(Chore("chtoys", "Tidy toys", "toys", null, 3, ChoreSchedule.Weekdays(setOf(1, 3, 5)), true, 1, "kitchen"),
        s.chores.getValue("chtoys"))
    assertEquals(ChoreTick("tick0002", "chtoys", "kidmia", MONDAY, 3, TickStatus.PENDING, 1, "kitchen"), s.ticks.getValue("tick0002"))
  }
}
