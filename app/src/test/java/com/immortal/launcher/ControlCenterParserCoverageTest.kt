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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [CcEntityParser] / [CcRegistryParser]: malformed Home Assistant payloads are skipped, never trusted or thrown. */
class ControlCenterParserCoverageTest {
  private fun j(s: String) = JSONObject(s)
  private fun a(s: String) = JSONArray(s)

  // --- states -----------------------------------------------------------------------------

  @Test fun fullStatesNeedADottedIdAndAPresentUser() {
    assertNull(CcEntityParser.fromFull(j("""{"state":"on"}""")))
    assertNull(CcEntityParser.fromFull(j("""{"entity_id":"nodot","state":"on"}""")))
    val bare = CcEntityParser.fromFull(j("""{"entity_id":"light.a"}"""))!!
    assertEquals("", bare.state)
    assertTrue(bare.attrs.isEmpty())
    assertEquals(0L, bare.lastChanged)
    assertNull(bare.userId)
    assertNull(CcEntityParser.fromFull(j("""{"entity_id":"light.a","context":{"user_id":null}}"""))!!.userId)
    assertNull(CcEntityParser.fromFull(j("""{"entity_id":"light.a","context":{"user_id":"  "}}"""))!!.userId)
    assertNull(CcEntityParser.fromFull(j("""{"entity_id":"light.a","context":{"user_id":"null"}}"""))!!.userId)
    assertEquals("u1", CcEntityParser.fromFull(j("""{"entity_id":"light.a","context":{"user_id":"u1"}}"""))!!.userId)
  }

  @Test fun attributesBecomePlainValuesWithNullsAndNesting() {
    val e = CcEntityParser.fromFull(j("""{"entity_id":"light.a","attributes":
        {"n":null,"o":{"x":1,"y":[1,null,{"z":"q"}]},"l":[1,2],"s":"str"}}"""))!!
    assertNull(e.attrs["n"])
    assertTrue(e.attrs.containsKey("n"))
    assertEquals(mapOf("x" to 1, "y" to listOf(1, null, mapOf("z" to "q"))), e.attrs["o"])
    assertEquals(listOf(1, 2), e.attrs["l"])
    assertEquals("str", e.attrs["s"])
    assertTrue(CcEntityParser.toMap(null).isEmpty())
  }

  @Test fun compressedStatesUseLastUpdatedWhenLastChangedIsMissing() {
    assertEquals(2_500L, CcEntityParser.fromCompressed("light.a", j("""{"s":"on","lu":2.5}""")).lastChanged)
    assertEquals(1_000L, CcEntityParser.fromCompressed("light.a", j("""{"s":"on","lc":1,"lu":2.5}""")).lastChanged)
    assertEquals(0L, CcEntityParser.fromCompressed("light.a", j("""{"s":"on"}""")).lastChanged)
    assertEquals(0L, CcEntityParser.fromCompressed("light.a", j("""{"s":"on","lc":"soon"}""")).lastChanged)
    // HA sends the context as a bare id string when there is no user.
    assertNull(CcEntityParser.fromCompressed("light.a", j("""{"s":"on","c":"01ABC"}""")).userId)
    assertEquals("u2", CcEntityParser.fromCompressed("light.a", j("""{"s":"on","c":{"user_id":"u2"}}""")).userId)
  }

  @Test fun diffsOnlyChangeWhatTheyName() {
    val old = CcEntity("light.a", "on", mapOf("brightness" to 10, "keep" to 1), lastChanged = 5L, userId = "u1")
    assertEquals(old, CcEntityParser.applyDiff(old, j("{}")))
    assertEquals(old, CcEntityParser.applyDiff(old, j("""{"-":{}}""")))
    val noState = CcEntityParser.applyDiff(old, j("""{"+":{"a":{"brightness":20}}}"""))
    assertEquals("on", noState.state)
    assertEquals(20, noState.attrs["brightness"])
    assertEquals(5L, noState.lastChanged)
    assertEquals("u1", noState.userId)
    val all = CcEntityParser.applyDiff(old, j("""{"+":{"s":"off","lc":3,"c":"ctx"},"-":{"a":["brightness"]}}"""))
    assertEquals("off", all.state)
    assertEquals(mapOf("keep" to 1), all.attrs)
    assertEquals(3_000L, all.lastChanged)
    assertNull("a new context without a user clears the user", all.userId)
  }

  @Test fun eventsSkipMalformedPiecesAndUnknownIds() {
    val current = mapOf("light.a" to CcEntity("light.a", "on"))
    val (next, touched) = CcEntityParser.applyEvent(current, j("""{
        "a":{"light.b":{"s":"off"},"light.bad":"oops"},
        "c":{"light.missing":{"+":{"s":"on"}},"light.a":"oops"},
        "r":["light.none"]}"""))
    assertEquals(setOf("light.b"), touched)
    assertEquals(setOf("light.a", "light.b"), next.keys)
    assertEquals("on", next.getValue("light.a").state)
    val (empty, none) = CcEntityParser.applyEvent(current, j("{}"))
    assertEquals(current, empty)
    assertTrue(none.isEmpty())
  }

  @Test fun isoTimestampsInEveryShapeHaSends() {
    assertEquals(0L, CcEntityParser.isoMs(null))
    assertEquals(0L, CcEntityParser.isoMs(" "))
    assertEquals(0L, CcEntityParser.isoMs("yesterday"))
    assertEquals(1_000L, CcEntityParser.isoMs("1970-01-01T00:00:01Z"))
    assertEquals(1_123L, CcEntityParser.isoMs("1970-01-01T00:00:01.123456+00:00"))
    assertEquals(1_000L, CcEntityParser.isoMs("1970-01-01T01:00:01+01:00"))
  }

  // --- registries -------------------------------------------------------------------------

  @Test fun floorsSkipIdlessOnesNameByIdAndSortUnlevelledLast() {
    val floors = CcRegistryParser.floors(a("""[{"name":"Ghost"},{"floor_id":"attic","level":null},
        {"floor_id":"up","name":"Up","level":1},{"floor_id":"g","name":"Ground","level":0},"junk"]"""))
    assertEquals(listOf("g", "up", "attic"), floors.map { it.id })
    assertEquals("attic", floors.last().name)
    assertNull(floors.last().level)
    assertTrue(CcRegistryParser.floors(null).isEmpty())
  }

  @Test fun areasAndDeviceAreasIgnoreBlankAndNullFloors() {
    val areas = CcRegistryParser.areas(a("""[{"name":"No id"},{"area_id":"k","name":"","floor_id":null},
        {"area_id":"l","name":"Living","floor_id":"g"},{"area_id":"m","name":"M","floor_id":""}]"""))
    assertEquals(listOf(CcHaArea("k", "k", null), CcHaArea("l", "Living", "g"), CcHaArea("m", "M", null)), areas)
    val devices = CcRegistryParser.deviceAreas(a("""[{"id":"d1","area_id":"k"},{"id":"d2","area_id":null},
        {"id":"","area_id":"k"},{"id":"d3"},{"id":"d4","area_id":""}]"""))
    assertEquals(mapOf("d1" to "k"), devices)
  }

  @Test fun registryEntriesInheritAreasAndKeepOnlyUsableOnes() {
    val r = CcRegistryParser.registry(emptyList(), emptyList(), mapOf("dev1" to "kitchen"), a("""[
        {"entity_id":"nodot"},
        {"entity_id":"light.own","area_id":"hall","device_id":"dev1"},
        {"entity_id":"light.inherit","area_id":null,"device_id":"dev1","original_device_class":"x"},
        {"entity_id":"light.orphan","device_id":"dev9"},
        {"entity_id":"binary_sensor.door","device_class":"door","original_device_class":"opening"},
        {"entity_id":"binary_sensor.off","disabled_by":"user"},
        {"entity_id":"binary_sensor.hid","hidden_by":"integration"},
        {"entity_id":"sensor.cfg","entity_category":"config"},
        {"entity_id":"binary_sensor.tamper","entity_category":"diagnostic"},
        {"entity_id":"sensor.rssi","entity_category":"diagnostic"},
        {"entity_id":"binary_sensor.null_flags","disabled_by":null,"hidden_by":null,"entity_category":null}
        ]"""))
    assertEquals(mapOf("light.own" to "hall", "light.inherit" to "kitchen"), r.entityArea)
    assertEquals(mapOf("light.inherit" to "x", "binary_sensor.door" to "door"), r.entityClass)
    assertEquals(setOf("light.own", "light.inherit", "light.orphan", "binary_sensor.door", "binary_sensor.null_flags"), r.entities)
    assertEquals(setOf("binary_sensor.tamper"), r.diagnostics)
  }

  @Test fun camerasGetTheirPlatformAndTheSnapshotButtonsOnTheirDevice() {
    val r = CcRegistryParser.registry(emptyList(), emptyList(), emptyMap(), a("""[
        {"entity_id":"camera.ring","platform":"mqtt","device_id":"d1"},
        {"entity_id":"button.ring_take_snapshot","device_id":"d1"},
        {"entity_id":"button.ring_b","device_id":"d1","translation_key":"take_snapshot"},
        {"entity_id":"button.ring_reboot","device_id":"d1"},
        {"entity_id":"camera.wyze","platform":"","device_id":"d2"},
        {"entity_id":"camera.dead","platform":"x","device_id":"d3","disabled_by":"user"},
        {"entity_id":"button.dead_take_snapshot","device_id":"d3"},
        {"entity_id":"button.loose_take_snapshot"},
        {"entity_id":"light.take_snapshot","device_id":"d1"}
        ]"""))
    assertEquals(mapOf("camera.ring" to "mqtt", "camera.dead" to "x"), r.cameraPlatform)
    assertEquals(mapOf("camera.ring" to listOf("button.ring_b", "button.ring_take_snapshot")), r.snapshotButtons)
    assertFalse(r.snapshotButtons.containsKey("camera.wyze"))
  }

  @Test fun entryClassesFallBackToTheOriginalAndSkipEmpties() {
    assertTrue(CcRegistryParser.classes(null).isEmpty())
    val c = CcRegistryParser.classes(j("""{"binary_sensor.a":{"device_class":"door"},"binary_sensor.b":{"original_device_class":"window"},
        "binary_sensor.c":null,"binary_sensor.d":{},"binary_sensor.e":{"device_class":"","original_device_class":""}}"""))
    assertEquals(mapOf("binary_sensor.a" to "door", "binary_sensor.b" to "window"), c)
  }
}
