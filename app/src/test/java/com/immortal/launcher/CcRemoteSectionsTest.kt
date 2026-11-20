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

class CcRemoteSectionsTest {
  private val lamp = CcEntity("light.lamp", "on", mapOf("friendly_name" to "Lamp", "brightness" to 255))
  private val other = CcEntity("light.hall", "off", mapOf("friendly_name" to "Hall"))
  private val garage = CcEntity("cover.garage", "open", mapOf("device_class" to "garage", "friendly_name" to "Garage"), lastChanged = 1)
  private val now = 10 * 60_000L

  private fun state(vararg e: CcEntity, version: Long = 1) =
      CcUiState(entities = e.associateBy { it.id }, entitiesVersion = version, connection = CcConnection(CcPhase.LIVE))

  private fun versions(answer: JSONObject): Map<String, String> =
      answer.getJSONObject("sv").let { sv -> sv.keys().asSequence().associateWith { sv.getString(it) } }

  @Test fun anOldPhoneStillGetsEverySectionAndNoVersions() {
    val answer = CcRemoteState.json(state(lamp), CcRemoteJournal(), 0, -1, now, "Kitchen")
    listOf("alerts", "security", "cameras", "scenes", "rooms").forEach { assertTrue(it, answer.has(it)) }
    assertFalse(answer.has("sv"))
    assertFalse("no car, no tesla key, as before", answer.has("tesla"))
  }

  @Test fun aNewPhoneHoldingNothingGetsEverySectionAndTheirVersions() {
    val answer = CcRemoteState.json(state(lamp), CcRemoteJournal(), 0, -1, now, "Kitchen", held = emptyMap())
    listOf("alerts", "security", "cameras", "scenes", "rooms").forEach { assertTrue(it, answer.has(it)) }
    assertTrue("a missing car is sent as null so the phone can clear it", answer.isNull("tesla") && answer.has("tesla"))
    assertEquals(CcRemoteSections.NAMES.toSet(), versions(answer).keys)
  }

  @Test fun aChangeThatMovesNoDerivedSectionSendsOnlyTheEntityDelta() {
    val j = CcRemoteJournal()
    val first = CcRemoteState.json(state(lamp), j, 0, -1, now, "Kitchen", held = emptyMap())
    // A light the Portal shows in no room and in no alert: entities move, sections don't.
    val second = CcRemoteState.json(state(lamp, other, version = 2), j, first.getLong("rev"), first.getLong("minute"),
        now + 1_000, "Kitchen", held = versions(first))
    assertFalse(second.optBoolean("unchanged"))
    assertEquals(1, second.getJSONArray("entities").length())
    CcRemoteSections.NAMES.forEach { assertFalse("$it left out", second.has(it)) }
    assertEquals(versions(first), versions(second))
    assertTrue("smaller than the first answer", second.toString().length < first.toString().length)
  }

  @Test fun onlyTheSectionThatChangedIsSent() {
    val j = CcRemoteJournal()
    val first = CcRemoteState.json(state(lamp), j, 0, -1, now, "Kitchen", held = emptyMap())
    val second = CcRemoteState.json(state(lamp, garage, version = 2), j, first.getLong("rev"), first.getLong("minute"),
        now + 1_000, "Kitchen", held = versions(first))
    assertTrue("an open garage is an alert", second.has("alerts"))
    assertEquals(1, second.getJSONArray("alerts").length())
    assertFalse("scenes didn't move", second.has("scenes"))
    assertNotEquals(versions(first)["alerts"], versions(second)["alerts"])
    assertEquals(versions(first)["scenes"], versions(second)["scenes"])
  }

  @Test fun heldVersionsAreReadDefensively() {
    assertNull("no sv: an old phone", CcRemoteSections.held(JSONObject().put("since", 3)))
    val held = CcRemoteSections.held(JSONObject().put("sv", JSONObject().put("alerts", "ab12").put("rooms", 7)
        .put("bogus", "x").put("scenes", "").put("cameras", "f".repeat(64))))!!
    assertEquals(mapOf("alerts" to "ab12", "rooms" to "7"), held)
  }

  @Test fun versionsAreStableAndContentSensitive() {
    val a = JSONArray().put(JSONObject().put("id", "x").put("n", 1))
    assertEquals(CcRemoteSections.version(a), CcRemoteSections.version(JSONArray(a.toString())))
    assertNotEquals(CcRemoteSections.version(a), CcRemoteSections.version(JSONArray().put(JSONObject().put("id", "x").put("n", 2))))
    assertEquals(CcRemoteSections.version(null), CcRemoteSections.version(null))
    assertEquals("cbf29ce484222325", CcRemoteSections.version(""))
  }
}
