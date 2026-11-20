/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.FleetBackupArchive.PrefValue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The backup archive is parsed strictly, and an import only applies what its scope allows. */
class FleetBackupArchiveTest {
  private val here = "0123456789abcdef0123456789abcdef"
  private val other = "fedcba9876543210fedcba9876543210"
  private val household: (String, Any?) -> Any? = { key, value -> if (key.startsWith("screensaver.")) value else null }

  private fun archive(device: String = here) = FleetBackupArchive.Archive(
      device = device, createdAt = 1_763_600_000_000L, versionCode = 269, versionName = "1.275",
      prefs = mapOf(
          "immortal_screensaver" to mapOf(
              "dark_room_lux" to PrefValue.IntV(7), "face" to PrefValue.Str("flip"),
              "scale" to PrefValue.FloatV(0.1f), "last" to PrefValue.LongV(Long.MAX_VALUE),
              "shuffle" to PrefValue.Bool(true), "tags" to PrefValue.StrSet(setOf("b", "a"))),
          "immortal_household" to mapOf("k" to PrefValue.Str("00".repeat(32))),
          "remote_pairing" to mapOf("sessions" to PrefValue.StrSet(setOf("session-token")))),
      files = mapOf("files/household_list.json" to "{\"items\":[1]}".toByteArray()),
      household = mapOf("screensaver.shuffle" to true, "screensaver.interval" to 30))

  private fun code(block: () -> Unit): String {
    try {
      block()
    } catch (e: FleetBackupArchive.ArchiveException) {
      return e.code
    }
    fail("expected an ArchiveException")
    return ""
  }

  private fun json() = JSONObject(String(FleetBackupArchive.encode(archive())))
  private fun decode(json: JSONObject) = FleetBackupArchive.decode(json.toString().toByteArray(), household)

  @Test fun everyPreferenceTypeRoundTripsExactly() {
    val back = FleetBackupArchive.decode(FleetBackupArchive.encode(archive()), household)
    assertEquals(archive().prefs, back.prefs)
    assertEquals(here, back.device)
    assertEquals(269L, back.versionCode)
    assertArrayEquals("{\"items\":[1]}".toByteArray(), back.files.getValue("files/household_list.json"))
    assertEquals(mapOf("screensaver.shuffle" to true, "screensaver.interval" to 30), back.household)
  }

  @Test fun aFloatSurvivesBitForBit() {
    val back = FleetBackupArchive.decode(FleetBackupArchive.encode(archive()), household)
    val scale = back.prefs.getValue("immortal_screensaver").getValue("scale") as PrefValue.FloatV
    assertEquals(0.1f.toRawBits(), scale.v.toRawBits())
  }

  @Test fun householdKeysTheCatalogDoesNotKnowAreDropped() {
    val back = decode(json().put("household", JSONObject().put("screensaver.shuffle", true).put("fleet.name", "Kitchen")))
    assertEquals(setOf("screensaver.shuffle"), back.household.keys)
  }

  @Test fun anUnknownOrMissingTopLevelFieldIsRefused() {
    assertEquals("bad_archive", code { decode(json().put("extra", 1)) })
    assertEquals("bad_archive", code { decode(json().apply { remove("files") }) })
    assertEquals("bad_archive", code { decode(json().put("version", 2)) })
    assertEquals("bad_archive", code { decode(json().put("format", "other")) })
    assertEquals("bad_archive", code { FleetBackupArchive.decode("not json".toByteArray(), household) })
  }

  @Test fun theDeviceIdMustBeA128BitHexDigest() {
    assertEquals("bad_archive", code { decode(json().put("device", "ABC")) })
    assertEquals("bad_archive", code { decode(json().put("device", here.uppercase())) })
  }

  @Test fun theFleetTokenAndTheBackupKeyCanNeverBeImported() {
    for (name in listOf("fleet_agent", "fleet_backup_key", "../shared_prefs/x", "a/b", "")) {
      val bad = json().apply { getJSONObject("prefs").put(name, JSONObject()) }
      assertEquals(name, "bad_pref_file", code { decode(bad) })
    }
    assertFalse(FleetBackupArchive.isExportablePref("fleet_agent"))
    assertTrue(FleetBackupArchive.isExportablePref("immortal_screensaver"))
  }

  @Test fun onlyAllowlistedDataFilesAreAccepted() {
    for (path in listOf("files/../shared_prefs/fleet_agent.xml", "no_backup/whos_there/household.bin", "files/other.json", "/etc/hosts")) {
      val bad = json().apply { getJSONObject("files").put(path, "AA==") }
      assertEquals(path, "bad_data_file", code { decode(bad) })
    }
    assertEquals("bad_data_file", code { decode(json().apply { getJSONObject("files").put("files/household_list.json", "%%%") }) })
  }

  @Test fun whosThereTemplatesAreNeverPartOfAnArchive() {
    assertTrue(FleetBackupArchive.DATA_FILES.none { it.contains(WhosThereStore.DIR) })
  }

  @Test fun wrongValueShapesAreRefused() {
    fun withValue(value: Any) = json().apply { getJSONObject("prefs").getJSONObject("immortal_screensaver").put("x", value) }
    val bad = listOf<Any>(
        JSONArray().put("s").put(1), JSONArray().put("i").put("1"), JSONArray().put("i").put(Long.MAX_VALUE),
        JSONArray().put("l").put(1.5), JSONArray().put("f").put(0.5), JSONArray().put("f").put("half"),
        JSONArray().put("b").put("true"), JSONArray().put("ss").put(JSONArray().put("a").put("a")),
        JSONArray().put("ss").put(JSONArray().put(1)), JSONArray().put("x").put(1), JSONArray().put("s"),
        "plain")
    for (value in bad) assertEquals(value.toString(), "bad_pref_value", code { decode(withValue(value)) })
  }

  @Test fun sizeCapsAreEnforced() {
    assertEquals("backup_too_large", code { FleetBackupArchive.decode(ByteArray(FleetBackupArchive.MAX_PLAIN_BYTES + 1), household) })
    val manyFiles = json().apply {
      val prefs = getJSONObject("prefs")
      repeat(FleetBackupArchive.MAX_PREF_FILES + 1) { prefs.put("p$it", JSONObject()) }
    }
    assertEquals("backup_too_large", code { decode(manyFiles) })
    val longKey = json().apply { getJSONObject("prefs").getJSONObject("immortal_screensaver").put("k".repeat(513), JSONArray().put("b").put(true)) }
    assertEquals("bad_pref_value", code { decode(longKey) })
  }

  @Test fun sameDeviceImportAppliesEverythingInThePreferenceFiles() {
    val plan = FleetBackupArchive.plan(archive(), here, crossDevice = false)
    assertEquals(archive().prefs.keys, plan.prefs.keys)
    assertEquals(setOf("files/household_list.json"), plan.files.keys)
    assertTrue(plan.household.isEmpty())
  }

  @Test fun anotherDevicesArchiveNeedsCrossDevice() {
    assertEquals("different_device", code { FleetBackupArchive.plan(archive(device = other), here, crossDevice = false) })
  }

  @Test fun theOperatorCanVouchForTheSamePortalAfterAnIdentityChange() {
    val plan = FleetBackupArchive.plan(archive(device = other), here, crossDevice = false, operatorConfirmed = true)
    assertEquals(archive().prefs.keys, plan.prefs.keys)
  }

  @Test fun crossDeviceSkipsDeviceLocalAndSecretItems() {
    val plan = FleetBackupArchive.plan(archive(device = other), here, crossDevice = true)
    assertTrue(plan.prefs.isEmpty()) // household key, sessions, room name and tokens stay behind
    assertTrue(plan.files.isEmpty())
    assertEquals(archive().household, plan.household)
    val summary = plan.summary()
    assertEquals(0, summary.getInt("prefFiles"))
    assertEquals(2, summary.getInt("householdSettings"))
    assertFalse(summary.toString().contains("session-token"))
  }

  @Test fun crossDeviceOnTheSameDeviceIsStillHouseholdOnly() {
    val plan = FleetBackupArchive.plan(archive(), here, crossDevice = true)
    assertTrue(plan.prefs.isEmpty())
    assertEquals(2, plan.household.size)
  }

  @Test fun preferenceTypesOutsideTheArchiveAreLeftOut() {
    assertEquals(PrefValue.StrSet(setOf("a")), FleetBackupArchive.prefValue(setOf("a")))
    assertEquals(null, FleetBackupArchive.prefValue(setOf(1)))
    assertEquals(null, FleetBackupArchive.prefValue(null))
    assertEquals(null, FleetBackupArchive.prefValue(1.0))
  }
}
