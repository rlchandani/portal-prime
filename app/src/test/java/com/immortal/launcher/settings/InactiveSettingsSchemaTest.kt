/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher.settings

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock

class InactiveSettingsSchemaTest {
  private val context = mock(Context::class.java)
  private data class Snapshot(val enabled: Boolean = false, val number: Int = 17,
      val password: String = "public-fixture-secret")

  private fun domain(id: String = "fixture", source: SettingsSnapshotSource<Snapshot>? = null,
      load: (Context) -> Snapshot = { source?.load(it) ?: Snapshot() },
      write: (String) -> Unit = {}, hook: () -> Unit = {}): SettingsDomain<Snapshot> =
      SettingsDomain(id, id, load = load, specs = listOf(
          BoolSpec("enabled", "Enabled", { it.enabled }, { _, _ -> write("enabled") }),
          IntSpec("number", "Number", { it.number }, { _, _ -> write("number") },
              min = 0, max = 60, step = 5, wrap = true, asText = true,
              timeOfDay = true, visible = { _, s -> s.enabled }),
          StringSpec("password", "Password", { it.password }, { _, _ -> write("password") },
              secret = true, visible = { _, s -> s.enabled }),
          DerivedSpec("summary", { "public summary" }, title = "Summary", render = true),
          DerivedSpec("flatOnly", { "public flat value" }),
      ), defaults = { Snapshot(number = 10, password = "") },
          sections = mapOf("number" to "Details"), schemaSnapshotSource = source,
          onApplied = { _, _ -> hook() })

  private fun controls(schema: JSONObject): Map<String, JSONObject> {
    val array = schema.getJSONArray("controls")
    return (0 until array.length()).associate { index ->
      val control = array.getJSONObject(index)
      control.getString("key") to control
    }
  }

  @Test fun normalSchema_keepsHiddenChildrenExcluded() {
    val d = domain()
    assertEquals(setOf("enabled", "summary"), controls(d.schemaJson(context)).keys)
    assertEquals(setOf("enabled", "summary"),
        controls(d.schemaJson(context, includeInactive = false)).keys)
  }

  @Test fun validationSchema_includesHiddenChildren_withoutChangingLaterNormalSchema() {
    val d = domain()
    val all = controls(d.schemaJson(context, includeInactive = true))
    assertEquals(setOf("enabled", "number", "password", "summary"), all.keys)
    assertFalse(all.getValue("enabled").getBoolean("value"))
    assertEquals(17, all.getValue("number").getInt("value"))
    assertEquals(setOf("enabled", "summary"), controls(d.schemaJson(context)).keys)
  }

  @Test fun hiddenNumber_keepsConstraintsDefaultsAndPresentationMetadata() {
    val number = controls(domain().schemaJson(context, includeInactive = true)).getValue("number")
    assertEquals("int", number.getString("type"))
    assertEquals(0, number.getInt("min"))
    assertEquals(60, number.getInt("max"))
    assertEquals(5, number.getInt("step"))
    assertTrue(number.getBoolean("wrap"))
    assertTrue(number.getBoolean("asText"))
    assertTrue(number.getBoolean("timeOfDay"))
    assertEquals(10, number.getInt("default"))
    assertEquals("Details", number.getString("section"))
    assertEquals("everyday", number.getString("tier"))
  }

  @Test fun hiddenSecret_remainsRedactedAndReportsPresence() {
    val schema = domain().schemaJson(context, includeInactive = true)
    val password = controls(schema).getValue("password")
    assertEquals("", password.getString("value"))
    assertEquals("", password.getString("default"))
    assertTrue(password.getBoolean("secret"))
    assertTrue(password.getBoolean("hasValue"))
    assertFalse(schema.toString().contains("public-fixture-secret"))
  }

  @Test fun validationSchema_keepsReadOnlyMetadataAndExcludesFlatOnlyDerivedFields() {
    val d = domain()
    val all = controls(d.schemaJson(context, includeInactive = true))
    val summary = all.getValue("summary")
    assertEquals("info", summary.getString("type"))
    assertTrue(summary.getBoolean("readOnly"))
    assertEquals("public summary", summary.getString("value"))
    assertFalse(all.containsKey("flatOnly"))
    assertEquals("public flat value", d.flatJson(context).getString("flatOnly"))
  }

  @Test fun scopedRegistrySchema_loadsOnlyTheSelectedDomain() {
    var selectedLoads = 0
    var unrelatedLoads = 0
    val selected = domain("selected", load = { selectedLoads++; Snapshot() })
    val unrelated = domain("unrelated", load = { unrelatedLoads++; Snapshot() })
    val available = listOf(selected, unrelated)
    val response = SettingsRegistry.schemaJson(context,
        available.filter { it.id == "selected" }, includeInactive = true).getJSONArray("domains")
    assertEquals(1, response.length())
    assertEquals("selected", response.getJSONObject(0).getString("id"))
    assertEquals(17, controls(response.getJSONObject(0)).getValue("number").getInt("value"))
    assertEquals(1, selectedLoads)
    assertEquals(0, unrelatedLoads)
  }

  @Test fun inactiveRegistrySchema_sharesSnapshotOncePerRequest_andReadsFreshNextRequest() {
    var loads = 0
    var current = Snapshot(number = 17)
    val source = SettingsSnapshotSource { _: Context -> loads++; current }
    val pair = listOf(domain("first", source), domain("second", source))
    val first = SettingsRegistry.schemaJson(context, pair, includeInactive = true).getJSONArray("domains")
    for (index in 0 until first.length())
      assertEquals(17, controls(first.getJSONObject(index)).getValue("number").getInt("value"))
    assertEquals(1, loads)
    current = Snapshot(number = 23)
    val next = SettingsRegistry.schemaJson(context, pair, includeInactive = true).getJSONArray("domains")
    for (index in 0 until next.length())
      assertEquals(23, controls(next.getJSONObject(index)).getValue("number").getInt("value"))
    assertEquals(2, loads)
  }

  @Test fun schemaReads_doNotWriteOrRunApplyHooks() {
    val writes = mutableListOf<String>()
    var hooks = 0
    val d = domain(write = { writes.add(it) }, hook = { hooks++ })
    d.schemaJson(context)
    d.schemaJson(context, includeInactive = true)
    SettingsRegistry.schemaJson(context, listOf(d), includeInactive = true)
    assertTrue(writes.isEmpty())
    assertEquals(0, hooks)
  }
}
