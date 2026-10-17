/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher.settings

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

/** A malformed remote enum must not reach normalization, persistence, or domain side effects. */
class EnumSettingValidationTest {
  private val context = mock(Context::class.java)

  private class Recorder {
    var value = "light"
    val coerced = mutableListOf<String>()
    val written = mutableListOf<String>()
    val spec = EnumSpec<Unit>(
        key = "theme", title = "Theme", get = { value },
        set = { _, next -> value = next; written.add(next) },
        options = listOf("light" to "Light", "dark" to "Dark"),
        aliases = listOf("appearance"),
        coerce = { raw -> coerced.add(raw); raw.trim().lowercase() },
    )
  }

  private fun rejects(value: Any) {
    val recorder = Recorder()
    assertFalse(recorder.spec.applyFrom(context, JSONObject().put("theme", value)))
    assertEquals("light", recorder.value)
    assertTrue(recorder.coerced.isEmpty())
    assertTrue(recorder.written.isEmpty())
  }

  @Test fun integerValue_doesNotEnterCoercionOrPersistence() = rejects(1)
  @Test fun fractionalValue_doesNotEnterCoercionOrPersistence() = rejects(1.5)
  @Test fun trueValue_doesNotEnterCoercionOrPersistence() = rejects(true)
  @Test fun falseValue_doesNotEnterCoercionOrPersistence() = rejects(false)
  @Test fun objectValue_doesNotEnterCoercionOrPersistence() =
      rejects(JSONObject().put("value", "dark"))
  @Test fun arrayValue_doesNotEnterCoercionOrPersistence() =
      rejects(JSONArray().put("dark"))
  @Test fun nullValue_doesNotEnterCoercionOrPersistence() = rejects(JSONObject.NULL)

  @Test fun stringValue_keepsTheOriginalTextForExistingNormalization() {
    val recorder = Recorder()
    assertTrue(recorder.spec.applyFrom(context, JSONObject().put("theme", " DARK ")))
    assertEquals(listOf(" DARK "), recorder.coerced)
    assertEquals(listOf("dark"), recorder.written)
    assertEquals("dark", recorder.value)
  }

  @Test fun unknownString_keepsLegacySetterFallback() {
    var range = "agenda"
    val written = mutableListOf<String>()
    val spec = EnumSpec<Unit>(
        key = "range", title = "Range", get = { range },
        set = { _, raw -> written.add(raw); range = if (raw == "agenda") raw else "week" },
        options = listOf("agenda" to "Agenda", "week" to "Week"),
    )
    assertTrue(spec.applyFrom(context, JSONObject().put("range", "legacy-unknown")))
    assertEquals(listOf("legacy-unknown"), written)
    assertEquals("week", range)
  }

  @Test fun invalidCanonicalValue_doesNotFallThroughToAlias_butAliasAloneStillApplies() {
    val recorder = Recorder()
    assertFalse(recorder.spec.applyFrom(context,
        JSONObject().put("theme", true).put("appearance", "dark")))
    assertTrue(recorder.coerced.isEmpty())
    assertTrue(recorder.written.isEmpty())
    assertTrue(recorder.spec.applyFrom(context, JSONObject().put("appearance", "dark")))
    assertEquals(listOf("dark"), recorder.written)
  }

  @Test fun mixedBatch_appliesOnlyTheValidKey_andRunsOneHook() {
    val recorder = Recorder()
    val toggles = mutableListOf<Boolean>()
    val hooks = mutableListOf<Set<String>>()
    val domain = SettingsDomain<Unit>(
        id = "fixture", title = "Fixture", load = { Unit },
        specs = listOf(recorder.spec,
            BoolSpec(key = "enabled", title = "Enabled", get = { false },
                set = { _, value -> toggles.add(value) })),
        onApplied = { _, keys -> hooks.add(keys) },
    )
    assertEquals(setOf("enabled"), domain.apply(context,
        JSONObject().put("theme", true).put("enabled", true)))
    assertEquals(listOf(true), toggles)
    assertEquals(listOf(setOf("enabled")), hooks)
    assertEquals("light", recorder.value)
    assertTrue(recorder.coerced.isEmpty())
    assertTrue(recorder.written.isEmpty())
  }

  @Test fun rejectedOnlyBatch_doesNotRunTheApplyHook() {
    val recorder = Recorder()
    val hooks = mutableListOf<Set<String>>()
    val domain = SettingsDomain<Unit>(
        id = "fixture", title = "Fixture", load = { Unit }, specs = listOf(recorder.spec),
        onApplied = { _, keys -> hooks.add(keys) },
    )
    assertTrue(domain.apply(context, JSONObject().put("theme", true)).isEmpty())
    assertTrue(hooks.isEmpty())
    assertTrue(recorder.coerced.isEmpty())
    assertTrue(recorder.written.isEmpty())
    assertEquals("light", recorder.value)
  }
}
