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

/** Routine codec edges the basic suite doesn't reach: wire types, limits, skips, docs and cross-routine rules. */
class RoutineModelCoverageTest {
  private fun bad(j: JSONObject?): String? = (RoutineCodec.parseRoutine(j) as? Parsed.Bad)?.code
  private fun ok(j: JSONObject): Routine = (RoutineCodec.parseRoutine(j) as Parsed.Ok).value
  private fun card(id: String = "a", pic: String = "shirt", label: Any? = "Dress") =
      JSONObject().put("id", id).put("pic", pic).apply { if (label != null) put("label", label) }
  private fun kid(id: String = "maya", name: Any? = "Maya", avatar: String = "fox") =
      JSONObject().put("id", id).put("avatar", avatar).apply { if (name != null) put("name", name) }

  @Test fun idMustBeShortLowercaseAlphanumeric() {
    assertEquals("invalid_id", bad(routineJson(id = "")))
    assertEquals("invalid_id", bad(routineJson(id = "Abc")))
    assertEquals("invalid_id", bad(routineJson(id = "a".repeat(25))))
    assertEquals("invalid_id", bad(routineJson().apply { remove("id") }))
    assertEquals("a".repeat(24), ok(routineJson(id = "a".repeat(24))).id)
  }

  @Test fun nameMustBeAStringWithoutControlCharacters() {
    assertEquals("invalid_name", bad(routineJson().put("name", 42)))
    assertEquals("invalid_name", bad(routineJson().apply { remove("name") }))
    assertEquals("invalid_name", bad(routineJson(name = "Sw\u0007im")))
    assertEquals("x".repeat(RoutineCodec.NAME_MAX), ok(routineJson(name = " ${"x".repeat(RoutineCodec.NAME_MAX)} ")).name)
  }

  @Test fun kidsListIsBoundedTypedAndUnique() {
    assertEquals(emptyList<String>(), ok(routineJson().apply { remove("kids") }).kids)
    assertEquals(listOf("maya", "leo"), ok(routineJson().put("kids", JSONArray().put("maya").put("leo"))).kids)
    assertEquals("invalid_kids", bad(routineJson().put("kids", JSONArray().put("maya").put("maya"))))
    assertEquals("invalid_kids", bad(routineJson().put("kids", JSONArray().put(7))))
    assertEquals("invalid_kids", bad(routineJson().put("kids", JSONArray(listOf("a", "b", "c", "d", "e")))))
    assertEquals(4, ok(routineJson().put("kids", JSONArray(listOf("a", "b", "c", "d")))).kids.size)
  }

  @Test fun cardsMustBeObjectsWithValidIdsAndLabels() {
    assertEquals("invalid_cards", bad(routineJson().apply { remove("cards") }))
    assertEquals("invalid_cards", bad(routineJson().put("cards", "shirt")))
    assertEquals("invalid_cards", bad(routineJson(cards = JSONArray().put("not an object"))))
    assertEquals("invalid_cards", bad(routineJson(cards = JSONArray().put(card(id = "A!")))))
    assertEquals("invalid_picture", bad(routineJson(cards = JSONArray().put(card().apply { remove("pic") }))))
    assertEquals("invalid_label", bad(routineJson(cards = JSONArray().put(card(label = null)))))
    assertEquals("invalid_label", bad(routineJson(cards = JSONArray().put(card(label = 3)))))
    assertEquals("invalid_label", bad(routineJson(cards = JSONArray().put(card(label = "Tab\there")))))
    val eight = JSONArray().apply { repeat(RoutineCodec.MAX_CARDS) { put(card(id = "c$it")) } }
    assertEquals(RoutineCodec.MAX_CARDS, ok(routineJson(cards = eight)).cards.size)
  }

  @Test fun daysAcceptOnlySevenBooleansOrBits() {
    assertEquals("invalid_days", bad(routineJson().apply { remove("days") }))
    assertEquals("invalid_days", bad(routineJson(days = List(8) { 1 })))
    assertEquals("invalid_days", bad(routineJson(days = listOf(0, 0, 0, 0, 0, 1, 2))))
    assertEquals("invalid_days", bad(routineJson().put("days", JSONArray(listOf(1, 0, 0, 0, 0, 0, "1")))))
    val bools = JSONArray().apply { put(true); repeat(5) { put(false) }; put(true) }
    assertEquals(listOf(true, false, false, false, false, false, true), ok(routineJson().put("days", bools)).days)
    val mixed = JSONArray(listOf(1, false, 0, true, 0, 0, 0))
    assertEquals(listOf(true, false, false, true, false, false, false), ok(routineJson().put("days", mixed)).days)
  }

  @Test fun timesMustBeWholeNumbersInsideTheDay() {
    assertEquals("invalid_time", bad(routineJson().put("start", "600")))
    assertEquals("invalid_time", bad(routineJson().put("start", 600.0)))
    assertEquals("invalid_time", bad(routineJson().apply { remove("end") }))
    assertEquals("invalid_time", bad(routineJson().put("start", Int.MAX_VALUE.toLong() + 1)))
    assertEquals("invalid_time", bad(routineJson(start = 1440, end = 1440)))
    assertEquals("invalid_time", bad(routineJson(start = 0, end = 0)))
    // A Long that fits in an Int is accepted (org.json may store numbers either way).
    assertEquals(600, ok(routineJson().put("start", 600L)).start)
  }

  @Test fun lengthBoundariesAreInclusive() {
    val min = ok(routineJson(start = 600, end = 600 + RoutineCodec.MIN_LEN))
    assertEquals(RoutineCodec.MIN_LEN, min.end - min.start)
    val max = ok(routineJson(start = 600, end = 600 + RoutineCodec.MAX_LEN))
    assertEquals(RoutineCodec.MAX_LEN, max.end - max.start)
    // The window may run right up to midnight.
    assertEquals(1440, ok(routineJson(start = 1400, end = 1440)).end)
  }

  @Test fun enabledMustBeABoolean() {
    assertEquals("invalid_routine", bad(routineJson().put("enabled", "true")))
    assertEquals("invalid_routine", bad(routineJson().put("enabled", 1)))
    assertEquals("invalid_routine", bad(routineJson().apply { remove("enabled") }))
    assertFalse(ok(routineJson().put("enabled", false)).enabled)
  }

  @Test fun skipsAreStrictDatesDedupedAndSorted() {
    val r = ok(routineJson().put("skips", JSONArray(listOf("2026-11-10", "2026-11-09", "2026-11-10"))))
    assertEquals(listOf(RoutineFixtures.MON, RoutineFixtures.MON + 1), r.skips)
    assertEquals("invalid_skip", bad(routineJson().put("skips", JSONArray().put(20766))))
    assertEquals("invalid_skip", bad(routineJson().put("skips", JSONArray().put("2026-11-9"))))
    assertEquals("invalid_skip", bad(routineJson().put("skips", JSONArray().put("2026-13-01"))))
    val many = JSONArray().apply { repeat(RoutineCodec.MAX_SKIPS + 1) { put(RoutineDates.isoOf(RoutineFixtures.MON + it)) } }
    assertEquals("invalid_skip", bad(routineJson().put("skips", many)))
    assertEquals(emptyList<Int>(), ok(routineJson().apply { remove("skips") }).skips)
  }

  @Test fun parseKidsRejectsEveryMalformedEntry() {
    assertTrue(RoutineCodec.parseKids(null) is Parsed.Bad)
    assertEquals(Parsed.Ok(emptyList<RoutineKid>()), RoutineCodec.parseKids(JSONArray()))
    assertEquals(Parsed.Bad("invalid_kids"), RoutineCodec.parseKids(JSONArray().put("maya")))
    assertEquals(Parsed.Bad("invalid_kids"), RoutineCodec.parseKids(JSONArray().put(kid(id = "Maya"))))
    assertEquals(Parsed.Bad("invalid_kids"), RoutineCodec.parseKids(JSONArray().put(kid(name = null))))
    assertEquals(Parsed.Bad("invalid_kids"), RoutineCodec.parseKids(JSONArray().put(kid(name = "x".repeat(RoutineCodec.KID_NAME_MAX + 1)))))
    assertEquals(Parsed.Bad("invalid_kids"), RoutineCodec.parseKids(JSONArray().put(kid()).put(kid(name = "Other"))))
    val four = JSONArray().apply { repeat(RoutineCodec.MAX_KIDS) { put(kid(id = "k$it", avatar = "owl")) } }
    assertEquals(RoutineCodec.MAX_KIDS, (RoutineCodec.parseKids(four) as Parsed.Ok).value.size)
  }

  @Test fun kidsJsonRoundTrips() {
    val kids = RoutineFixtures.kids
    assertEquals(Parsed.Ok(kids), RoutineCodec.parseKids(RoutineCodec.kidsJson(kids)))
  }

  @Test fun parseDocRefusesAnyUnreadablePart() {
    val good = RoutineCodec.docJson(RoutineFixtures.doc(RoutineFixtures.morning()))
    assertNotNull(RoutineCodec.parseDoc(good))
    assertNull(RoutineCodec.parseDoc(JSONObject(good.toString()).apply { remove("rev") }))
    assertNull(RoutineCodec.parseDoc(JSONObject(good.toString()).apply { remove("routines") }))
    assertNull(RoutineCodec.parseDoc(JSONObject(good.toString()).put("routines", JSONArray().put(JSONObject()))))
    assertNull(RoutineCodec.parseDoc(JSONObject(good.toString()).put("routines", JSONArray().put("x"))))
    assertNull(RoutineCodec.parseDoc(JSONObject(good.toString()).put("kids", JSONArray().put("x"))))
    val noKids = RoutineCodec.parseDoc(JSONObject(good.toString()).apply { remove("kids") })!!
    assertEquals(emptyList<RoutineKid>(), noKids.kids)
    assertEquals(3L, noKids.rev)
    assertEquals(7L, RoutineCodec.parseDoc(JSONObject(good.toString()).put("rev", 7.9))!!.rev)
  }

  @Test fun checkCapsTheNumberOfRoutinesButAllowsEditingAtTheCap() {
    val full = (0 until RoutineRules.MAX_ROUTINES).map { i ->
      RoutineFixtures.bedtime().copy(id = "r$i", start = i * 60, end = i * 60 + 30)
    }
    val doc = RoutineDoc(1, full, RoutineFixtures.kids)
    val extra = RoutineFixtures.bedtime().copy(id = "new", start = 1400, end = 1430)
    assertEquals("too_many_routines", RoutineRules.check(extra, doc))
    assertEquals("This Portal already has 12 routines.", RoutineRules.message("too_many_routines", extra, doc))
    assertNull(RoutineRules.check(full[3].copy(name = "Renamed"), doc))
    assertNull(RoutineRules.check(extra, doc.copy(routines = full.drop(1))))
  }

  @Test fun clashNeedsASharedDayAndAnOverlappingWindow() {
    val doc = RoutineFixtures.doc(RoutineFixtures.morning())
    val m = RoutineFixtures.morning()
    // Back-to-back windows do not overlap.
    assertNull(RoutineRules.clash(m.copy(id = "r7", start = m.end, end = m.end + 30), doc))
    assertNull(RoutineRules.clash(m.copy(id = "r7", start = m.start - 30, end = m.start), doc))
    assertEquals(m, RoutineRules.clash(m.copy(id = "r7", start = m.end - 1, end = m.end + 30), doc))
    // A routine wholly inside another clashes.
    assertEquals(m, RoutineRules.clash(m.copy(id = "r7", start = m.start + 5, end = m.start + 10), doc))
  }

  @Test fun messagesCoverEveryCode() {
    val doc = RoutineFixtures.doc(RoutineFixtures.morning())
    val apart = RoutineFixtures.bedtime().copy(id = "r8")
    assertEquals("Overlaps another routine.", RoutineRules.message("overlap", apart, doc))
    assertEquals("Pick kids from this Portal's list.", RoutineRules.message("invalid_kids", apart, doc))
    assertEquals("Check the routine and try again.", RoutineRules.message("too_short", apart, doc))
    val late = RoutineFixtures.bedtime().copy(id = "r9", start = 1180, end = 1210)
    val evening = RoutineFixtures.doc(RoutineFixtures.bedtime())
    assertEquals("Overlaps Bedtime (7:30 PM to 8:00 PM).", RoutineRules.message("overlap", late, evening))
  }
}
