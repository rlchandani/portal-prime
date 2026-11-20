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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reminder replicas from peers: per-field validation, the privacy invariant, the entry cap and the keyed digest. */
class ReminderCodecCoverageTest {
  private val t0 = 1_800_000_000_000L
  private val key = ByteArray(32) { it.toByte() }
  private val household = Reminder("rem00001", "Bins out", null, "", t0 + 3_600_000, false, false, "Kitchen", t0, 2, "kitchen")
  private val personal = Reminder("rem00002", "Dentist", "person-1", "Mia", null, true, true, "Phone", t0, 5, "kitchen",
      deleted = false, doneMs = t0 + 10)

  private fun decodeOne(o: JSONObject) = ReminderCodec.decodeState(JSONObject().put("v", 1).put("r", JSONArray().put(o)))!!.reminders

  private fun json(r: Reminder = household) = ReminderCodec.reminderJson(r)

  @Test fun bothKindsRoundTrip() {
    val s = ReminderState(listOf(household, personal).associateBy { it.id })
    assertEquals(s, ReminderCodec.decodeState(JSONObject(ReminderCodec.encodeState(s).toString())))
  }

  @Test fun envelopeRejections() {
    assertNull(ReminderCodec.decodeState(null))
    assertNull(ReminderCodec.decodeState(JSONObject().put("v", 2).put("r", JSONArray())))
    assertNull(ReminderCodec.decodeState(JSONObject().put("v", 1)))
    assertNull(ReminderCodec.decodeState(JSONObject().put("v", 1).put("r", JSONObject())))
    assertTrue(ReminderCodec.decodeState(JSONObject().put("v", 1).put("r", JSONArray().put("x").put(3)))!!.reminders.isEmpty())
  }

  @Test fun entriesBeyondTheCapAreIgnored() {
    val arr = JSONArray()
    for (i in 0..ReminderCodec.MAX_ENTRIES) arr.put(json(household.copy(id = "rem%05d".format(i))))
    assertEquals(ReminderCodec.MAX_ENTRIES, ReminderCodec.decodeState(JSONObject().put("v", 1).put("r", arr))!!.reminders.size)
  }

  @Test fun eachFieldIsValidated() {
    val bad: List<Pair<String, Any>> = listOf(
        "i" to "R1", "i" to 1, "x" to "false", "q" to 0, "q" to Long.MAX_VALUE, "q" to "2", "b" to "", "b" to "a b",
        "t" to "  ", "t" to 5, "p" to 5, "p" to "bad id!", "d" to "0", "d" to 1.5, "d" to 42L, "d" to 40_000_000_000_000L,
        "dn" to "no", "dm" to 5L, "dm" to "0", "pv" to 1, "cm" to 0, "cm" to "t0")
    for ((field, value) in bad) assertTrue("$field=$value", decodeOne(json().put(field, value)).isEmpty())
    for (field in listOf("i", "x", "q", "b", "t", "p", "d", "dn", "dm", "pv", "cm")) {
      assertTrue("missing $field", decodeOne(json().apply { remove(field) }).isEmpty())
    }
  }

  @Test fun aHouseholdReminderCanNeverBePrivate() {
    assertTrue(decodeOne(json().put("pv", true)).isEmpty())
    assertEquals(personal, decodeOne(json(personal)).getValue(personal.id))
  }

  @Test fun optionalTextsDefaultToEmpty() {
    val r = decodeOne(json().put("pn", 7).put("cb", JSONObject())).getValue(household.id)
    assertEquals("", r.personName)
    assertEquals("", r.createdBy)
    val r2 = decodeOne(json().apply { remove("pn"); remove("cb") }).getValue(household.id)
    assertEquals("", r2.personName)
  }

  @Test fun zeroDueMeansWhenever() {
    assertNull(decodeOne(json().put("d", 0)).getValue(household.id).dueMs)
    assertNull(decodeOne(json(household.copy(dueMs = null))).getValue(household.id).dueMs)
  }

  @Test fun timeWindowBoundaries() {
    assertTrue(ReminderCodec.isTime(1_000_000_000_000L))
    assertFalse(ReminderCodec.isTime(999_999_999_999L))
    assertTrue(ReminderCodec.isTime(32_503_680_000_000L))
    assertFalse(ReminderCodec.isTime(32_503_680_000_001L))
  }

  @Test fun personIdsAreDeviceTokens() {
    assertTrue(ReminderCodec.isPersonId("person-1"))
    assertFalse(ReminderCodec.isPersonId(null))
    assertFalse(ReminderCodec.isPersonId("a/b"))
  }

  @Test fun digestIsKeyedOrderFreeAndSensitive() {
    val s = ReminderState(listOf(household, personal).associateBy { it.id })
    val d = ReminderCodec.digest(s, key)
    assertEquals(12, d.length)
    assertEquals(d, ReminderCodec.digest(ReminderState(listOf(personal, household).associateBy { it.id }), key))
    assertNotEquals(d, ReminderCodec.digest(s, ByteArray(32)))
    assertNotEquals(d, ReminderCodec.digest(ReminderState(s.reminders + (household.id to household.copy(done = true))), key))
  }
}
