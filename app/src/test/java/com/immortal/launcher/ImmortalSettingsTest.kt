/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.*

class ImmortalSettingsTest {

  @Test
  fun `fahrenheit territories default to F`() {
    assertTrue(ImmortalSettings.localeUsesFahrenheit(Locale.US))
    assertTrue(ImmortalSettings.localeUsesFahrenheit(Locale("en", "LR")))
    assertTrue(ImmortalSettings.localeUsesFahrenheit(Locale("my", "MM")))
    assertTrue(ImmortalSettings.localeUsesFahrenheit(Locale("en", "BS")))
  }

  @Test
  fun `everywhere else defaults to C`() {
    assertFalse(ImmortalSettings.localeUsesFahrenheit(Locale.UK))
    assertFalse(ImmortalSettings.localeUsesFahrenheit(Locale.CANADA))
    assertFalse(ImmortalSettings.localeUsesFahrenheit(Locale.GERMANY))
    assertFalse(ImmortalSettings.localeUsesFahrenheit(Locale.JAPAN))
    assertFalse(ImmortalSettings.localeUsesFahrenheit(Locale("es", "MX")))
    assertFalse(ImmortalSettings.localeUsesFahrenheit(Locale("en", "AU")))
  }

  @Test
  fun `no-country locale defaults to C`() {
    assertFalse(ImmortalSettings.localeUsesFahrenheit(Locale("en")))
    assertFalse(ImmortalSettings.localeUsesFahrenheit(Locale.ROOT))
  }

  @Test
  fun `clock auto follows the system 24-hour setting`() {
    assertTrue(ImmortalSettings.resolve24Hour(ImmortalSettings.CLOCK_AUTO, systemIs24Hour = true))
    assertFalse(ImmortalSettings.resolve24Hour(ImmortalSettings.CLOCK_AUTO, systemIs24Hour = false))
  }

  @Test
  fun `clock 12 and 24 override the system setting`() {
    assertFalse(ImmortalSettings.resolve24Hour(ImmortalSettings.CLOCK_12, systemIs24Hour = true))
    assertTrue(ImmortalSettings.resolve24Hour(ImmortalSettings.CLOCK_24, systemIs24Hour = false))
  }
  private fun worldClockContext(initial: String?): Context {
    var saved = initial
    val context = mock(Context::class.java)
    val prefs = mock(SharedPreferences::class.java)
    val editor = mock(SharedPreferences.Editor::class.java)
    `when`(context.getSharedPreferences("immortal_settings", Context.MODE_PRIVATE)).thenReturn(prefs)
    `when`(prefs.getString("world_clock_zones", null)).thenAnswer { saved }
    `when`(prefs.edit()).thenReturn(editor)
    `when`(editor.putString(eq("world_clock_zones"), anyString())).thenAnswer {
      saved = it.getArgument<String>(1)
      editor
    }
    return context
  }

  @Test
  fun `unconfigured world clock keeps the default cities`() {
    assertEquals(ImmortalSettings.DEFAULT_WORLD_CLOCK_ZONES,
        ImmortalSettings.worldClockZones(worldClockContext(null)))
  }

  @Test
  fun `clearing selected cities stays empty on reload`() {
    val context = worldClockContext("Europe/London,Asia/Tokyo")
    ImmortalSettings.setWorldClockZones(context, emptyList())
    assertTrue(ImmortalSettings.worldClockZones(context).isEmpty())
  }

  @Test
  fun `saved blank separators do not recreate default cities`() {
    assertTrue(ImmortalSettings.worldClockZones(worldClockContext(" , \t , ")).isEmpty())
  }

  @Test
  fun `saved cities retain their order with surrounding whitespace ignored`() {
    assertEquals(listOf("Asia/Tokyo", "Europe/London", "America/New_York"),
        ImmortalSettings.worldClockZones(worldClockContext(
            " Asia/Tokyo , , Europe/London, America/New_York ")))
  }

  @Test
  fun `more than four saved cities remain available in their original order`() {
    val zones = listOf("Europe/London", "Asia/Tokyo", "Europe/Paris", "Asia/Dubai", "Australia/Sydney")
    val context = worldClockContext(null)
    ImmortalSettings.setWorldClockZones(context, zones)
    assertEquals(zones, ImmortalSettings.worldClockZones(context))
  }

  @Test
  fun `existing zone identities outside the curated city list are preserved`() {
    assertEquals(listOf("Pacific/Auckland", "Europe/Bucharest"),
        ImmortalSettings.worldClockZones(worldClockContext("Pacific/Auckland,Europe/Bucharest")))
  }
}
