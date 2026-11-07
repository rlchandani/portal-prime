/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.RoutineFixtures.routineJson
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RoutineCodecTest {
  private fun bad(json: JSONObject?) = (RoutineCodec.parseRoutine(json) as? Parsed.Bad)?.code

  @Test fun pictureSetHasStableIds() {
    assertEquals(23, RoutinePictures.ids.size)
    assertEquals("shirt", RoutinePictures.ids.first())
    assertEquals("car", RoutinePictures.ids.last())
    assertEquals("Brush teeth", RoutinePictures.defaultLabel("toothbrush"))
    assertEquals("Evening", RoutinePictures.group("moon"))
    assertFalse(RoutinePictures.isValid("rocket"))
  }

  @Test fun parsesAValidRoutine_andRoundTrips() {
    val parsed = RoutineCodec.parseRoutine(routineJson()) as Parsed.Ok
    assertEquals("Swim", parsed.value.name)
    assertEquals(listOf(false, false, false, false, false, true, true), parsed.value.days)
    val again = RoutineCodec.parseRoutine(RoutineCodec.routineJson(parsed.value)) as Parsed.Ok
    assertEquals(parsed.value, again.value)
  }

  @Test fun trimsNameAndLabels() {
    val json = routineJson(name = "  Swim  ")
    json.getJSONArray("cards").getJSONObject(0).put("label", " Bottle ")
    val r = (RoutineCodec.parseRoutine(json) as Parsed.Ok).value
    assertEquals("Swim", r.name)
    assertEquals("Bottle", r.cards[0].label)
  }

  @Test fun rejectsUntrustedShapes() {
    assertEquals("invalid_routine", bad(null))
    assertEquals("invalid_id", bad(routineJson(id = "../x")))
    assertEquals("invalid_name", bad(routineJson(name = " ")))
    assertEquals("invalid_name", bad(routineJson(name = "x".repeat(25))))
    assertEquals("invalid_days", bad(routineJson(days = listOf(1, 1, 1))))
    assertEquals("invalid_days", bad(routineJson(days = List(7) { 0 })))
    assertEquals("invalid_time", bad(routineJson(start = 600, end = 600)))
    assertEquals("invalid_time", bad(routineJson(start = -1, end = 30)))
    assertEquals("invalid_time", bad(routineJson(start = 1430, end = 1441)))
    assertEquals("too_short", bad(routineJson(start = 600, end = 604)))
    assertEquals("too_long", bad(routineJson(start = 600, end = 781)))
    assertEquals("invalid_cards", bad(routineJson(cards = JSONArray())))
    val nine = JSONArray().apply { repeat(9) { put(JSONObject().put("id", "c$it").put("pic", "shirt").put("label", "Dress")) } }
    assertEquals("invalid_cards", bad(routineJson(cards = nine)))
    assertEquals("invalid_picture", bad(routineJson(cards = JSONArray().put(JSONObject().put("id", "a").put("pic", "rocket").put("label", "Go")))))
    assertEquals("invalid_label", bad(routineJson(cards = JSONArray().put(JSONObject().put("id", "a").put("pic", "shirt").put("label", "")))))
    assertEquals("invalid_label", bad(routineJson(cards = JSONArray().put(JSONObject().put("id", "a").put("pic", "shirt").put("label", "x".repeat(15))))))
    val dup = JSONArray().put(JSONObject().put("id", "a").put("pic", "shirt").put("label", "A")).put(JSONObject().put("id", "a").put("pic", "bowl").put("label", "B"))
    assertEquals("invalid_cards", bad(routineJson(cards = dup)))
    assertEquals("invalid_skip", bad(routineJson().put("skips", JSONArray().put("2026-02-30"))))
    assertEquals("invalid_kids", bad(routineJson().put("kids", JSONArray().put("Bad Id"))))
  }

  @Test fun kidsAreValidated() {
    val ok = RoutineCodec.parseKids(JSONArray().put(JSONObject().put("id", "maya").put("name", "Maya").put("avatar", "fox")))
    assertEquals(1, (ok as Parsed.Ok).value.size)
    assertTrue(RoutineCodec.parseKids(JSONArray().put(JSONObject().put("id", "maya").put("name", "").put("avatar", "fox"))) is Parsed.Bad)
    assertTrue(RoutineCodec.parseKids(JSONArray().put(JSONObject().put("id", "maya").put("name", "M").put("avatar", "dragon"))) is Parsed.Bad)
    val five = JSONArray().apply { repeat(5) { put(JSONObject().put("id", "k$it").put("name", "K").put("avatar", "owl")) } }
    assertTrue(RoutineCodec.parseKids(five) is Parsed.Bad)
  }

  @Test fun docRoundTrips_andCorruptStorageIsNull() {
    val doc = RoutineFixtures.doc(RoutineFixtures.morning(), RoutineFixtures.bedtime())
    assertEquals(doc, RoutineCodec.parseDoc(RoutineCodec.docJson(doc)))
    assertNull(RoutineCodec.parseDoc(JSONObject().put("rev", "x")))
  }

  @Test fun rulesCatchOverlapAndUnknownKids() {
    val doc = RoutineFixtures.doc(RoutineFixtures.morning())
    val clash = RoutineFixtures.morning().copy(id = "r5", name = "Early", start = 450, end = 480)
    assertEquals("overlap", RoutineRules.check(clash, doc))
    assertEquals("Overlaps School morning (7:15 AM to 7:55 AM).", RoutineRules.message("overlap", clash, doc))
    val weekend = clash.copy(days = listOf(false, false, false, false, false, true, true))
    assertNull(RoutineRules.check(weekend, doc))
    assertEquals("invalid_kids", RoutineRules.check(weekend.copy(kids = listOf("zoe")), doc))
    assertNull(RoutineRules.check(RoutineFixtures.morning().copy(start = 440), doc)) // editing itself
  }
}
