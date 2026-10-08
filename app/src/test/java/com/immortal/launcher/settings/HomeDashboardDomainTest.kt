/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher.settings

import com.immortal.launcher.HomeDashboardConfig
import com.immortal.launcher.HomeDashboardTestPrefs
import java.lang.reflect.Modifier
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDashboardDomainTest {
  @Test
  fun nullAndNonTextValuesCannotClearTheSavedAddress() {
    val saved = "https://dashboard.example/view/"
    val prefs = HomeDashboardTestPrefs(saved)
    listOf(JSONObject.NULL, 12, true, JSONObject(), JSONArray()).forEach { value ->
      assertTrue(SettingsDomains.homeDashboard.apply(
          prefs.context, JSONObject().put("url", value)).isEmpty())
      assertEquals(saved, HomeDashboardConfig.load(prefs.context).url)
    }
    assertEquals(0, prefs.writeCount)
  }

  @Test
  fun dashboardDomainIsRegisteredOnceAndCanBeFoundByItsWireId() {
    val domain = SettingsDomains.homeDashboard
    assertEquals("home_dashboard", domain.id)
    assertEquals(1, SettingsDomains.all.count { it.id == "home_dashboard" })
    assertSame(domain, SettingsRegistry.domain("home_dashboard"))
    assertEquals(listOf("url"), domain.specs.map { it.key })
    assertTrue(domain.specs.single() is StringSpec)
  }

  @Test
  fun schemaExposesTheAddressAndTheDisabledDefault() {
    val url = "https://dashboard.example/view/?kiosk=1"
    val prefs = HomeDashboardTestPrefs(url)
    val domain = SettingsDomains.homeDashboard
    val schema = domain.schemaJson(prefs.context)
    assertEquals("home_dashboard", schema.getString("id"))
    val controls = schema.getJSONArray("controls")
    assertEquals(1, controls.length())
    val address = controls.getJSONObject(0)
    assertEquals("url", address.getString("key"))
    assertEquals("Dashboard address", address.getString("title"))
    assertEquals("string", address.getString("type"))
    assertEquals(url, address.getString("value"))
    assertEquals("", address.getString("default"))
    assertEquals("inline", address.getString("entry"))
    assertFalse(address.getBoolean("secret"))
    assertEquals(url, domain.flatJson(domain.load(prefs.context)).getString("url"))
    assertEquals(0, prefs.writeCount)
  }

  @Test
  fun registryApplyPersistsTheAddressAndPreservesEarlierSnapshots() {
    val prefs = HomeDashboardTestPrefs()
    val domain = SettingsDomains.homeDashboard
    val before = domain.load(prefs.context)
    val applied = SettingsRegistry.apply(prefs.context, "home_dashboard",
        JSONObject().put("url", "  http://dashboard.example:8123/view/?kiosk=1#lights  "))

    assertEquals(setOf("url"), applied)
    assertEquals("", domain.flatJson(before).getString("url"))
    assertEquals("http://dashboard.example:8123/view/?kiosk=1#lights",
        domain.flatJson(prefs.context).getString("url"))
    assertTrue(HomeDashboardConfig.configured(prefs.context))
    assertEquals(1, prefs.writeCount)
  }

  @Test
  fun applyRejectsInvalidAddressesBeforeCallingTheSetter() {
    val saved = "https://dashboard.example/view/"
    val prefs = HomeDashboardTestPrefs(saved)
    val domain = SettingsDomains.homeDashboard
    val spec = domain.specs.single() as StringSpec<HomeDashboardConfig.Settings>
    listOf("not a URL", "/view", "file:///view", "https://user@dashboard.example/",
        "http://dashboard.example:65536/view", "https://dashboard.example/\n").forEach { raw ->
      assertFalse(spec.applyWhen(raw))
      assertTrue(domain.apply(prefs.context, JSONObject().put("url", raw)).isEmpty())
    }
    listOf(17, true, JSONObject(), JSONArray()).forEach { value ->
      assertTrue(domain.apply(prefs.context, JSONObject().put("url", value)).isEmpty())
    }

    assertEquals(saved, HomeDashboardConfig.load(prefs.context).url)
    assertEquals(0, prefs.writeCount)
  }

  @Test
  fun applyAcceptsBlankAddressesToDisableTheDashboard() {
    listOf("", "   ").forEach { blank ->
      val prefs = HomeDashboardTestPrefs("https://dashboard.example/view/")
      assertEquals(setOf("url"),
          SettingsDomains.homeDashboard.apply(prefs.context, JSONObject().put("url", blank)))
      assertEquals("", HomeDashboardConfig.load(prefs.context).url)
      assertFalse(HomeDashboardConfig.configured(prefs.context))
      assertEquals("", SettingsDomains.homeDashboard.schemaJson(prefs.context)
          .getJSONArray("controls").getJSONObject(0).getString("value"))
    }
  }

  @Test
  fun applyWithoutTheAddressLeavesTheSavedValueUnchanged() {
    val saved = "https://dashboard.example/view/"
    val prefs = HomeDashboardTestPrefs(saved)
    val domain = SettingsDomains.homeDashboard
    assertTrue(domain.apply(prefs.context, JSONObject()).isEmpty())
    assertTrue(domain.apply(prefs.context, JSONObject().put("other", "ignored")).isEmpty())
    assertEquals(saved, HomeDashboardConfig.load(prefs.context).url)
    assertEquals(0, prefs.writeCount)
  }

  @Test
  fun dashboardRegistryCoversEveryPersistedSnapshotField() {
    val fields = HomeDashboardConfig.Settings::class.java.declaredFields
        .filter { !Modifier.isStatic(it.modifiers) }
        .map { it.name }.toSet()
    assertEquals(fields, SettingsDomains.homeDashboard.specs.map { it.key }.toSet())
  }
}
