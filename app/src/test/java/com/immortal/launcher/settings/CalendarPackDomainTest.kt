/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.settings

import android.content.Context
import com.immortal.launcher.CalendarPacks
import com.immortal.launcher.FeastDays
import com.immortal.launcher.IrishHolidays
import com.immortal.launcher.NameDays
import com.immortal.launcher.PrayerTimes
import com.immortal.launcher.ScreensaverTestPrefs
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/** Public registry contract for the existing opt-in almanac packs and their stored choices. */
class CalendarPackDomainTest {
  private val keys = setOf("romanian", "irish", "prayer")

  // Separate named stores prevent the screensaver fixture from accidentally masking a prefs move.
  private class Fixture(initial: Map<String, Any?> = emptyMap()) {
    val packs = ScreensaverTestPrefs(initial)
    val weather = ScreensaverTestPrefs(mapOf(
        "lat" to "51.5", "lon" to "0.0", "resolved_at" to System.currentTimeMillis()))
    fun newContext(): Context = mock(Context::class.java).also {
      `when`(it.getSharedPreferences("immortal_packs", Context.MODE_PRIVATE)).thenReturn(packs)
      `when`(it.getSharedPreferences("immortal_weather", Context.MODE_PRIVATE)).thenReturn(weather)
    }
    val context = newContext()
  }

  @Suppress("UNCHECKED_CAST")
  private fun domain(): SettingsDomain<Any> {
    val registered = SettingsRegistry.domain("calendarpacks")
    assertNotNull("The public registry must expose the existing almanac switches", registered)
    return registered as SettingsDomain<Any>
  }

  private fun controls(domain: SettingsDomain<Any>, context: Context): Map<String, JSONObject> {
    val controls = domain.schemaJson(context).getJSONArray("controls")
    return (0 until controls.length()).associate {
      val control = controls.getJSONObject(it)
      control.getString("key") to control
    }
  }

  private fun holidayDate(): Calendar = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
    clear()
    set(2026, Calendar.DECEMBER, 25, 0, 0, 0)
  }

  @Test
  fun registry_exposesAlmanacWithExactlyThreeOptInSwitches() {
    val domain = domain()
    val fixture = Fixture()
    assertEquals("calendarpacks", domain.id)
    assertEquals("Almanac", domain.title)
    assertEquals(keys, domain.specs.map { it.key }.toSet())
    assertEquals(3, domain.specs.size)
    val values = domain.flatJson(fixture.context)
    keys.forEach {
      assertEquals(false, values.get(it))
      assertFalse(CalendarPacks.isEnabled(fixture.context, it))
    }
    assertTrue(CalendarPacks.headerLines(fixture.context, holidayDate()).isEmpty())
    assertEquals(0, fixture.packs.writeCount)
  }

  @Test
  fun partialApply_preservesUnrelatedEnabledPacks() {
    val domain = domain()
    val fixture = Fixture(mapOf("romanian" to true, "prayer" to true))
    assertEquals(setOf("irish"),
        SettingsRegistry.apply(fixture.context, domain.id, JSONObject().put("irish", true)))
    keys.forEach { assertTrue(CalendarPacks.isEnabled(fixture.context, it)) }
    assertEquals(setOf("romanian"),
        SettingsRegistry.apply(fixture.context, domain.id, JSONObject().put("romanian", false)))
    val values = domain.flatJson(fixture.context)
    assertFalse(values.getBoolean("romanian"))
    assertTrue(values.getBoolean("irish"))
    assertTrue(values.getBoolean("prayer"))
  }

  @Test
  fun romanianSwitch_bindsExistingSetterReaderAndHeaderSelection() {
    val domain = domain()
    val fixture = Fixture()
    val date = holidayDate()
    val expected = listOf(
        FeastDays.forToday(date).takeIf { it.isNotEmpty() }?.let { "🕯️ $it" },
        NameDays.todayLabel(date).takeIf { it.isNotEmpty() }?.let { "🎉 $it" }).filterNotNull()
    assertTrue("The chosen date must exercise the Romanian header", expected.isNotEmpty())
    assertEquals(setOf("romanian"), domain.apply(fixture.context, JSONObject().put("romanian", true)))
    assertTrue(CalendarPacks.isEnabled(fixture.context, CalendarPacks.ROMANIAN))
    assertEquals(expected, CalendarPacks.headerLines(fixture.context, date))
    CalendarPacks.setEnabled(fixture.context, CalendarPacks.ROMANIAN, false)
    assertFalse(domain.flatJson(fixture.context).getBoolean("romanian"))
    assertTrue(CalendarPacks.headerLines(fixture.context, date).isEmpty())
  }

  @Test
  fun irishSwitch_bindsExistingSetterReaderAndHeaderSelection() {
    val domain = domain()
    val fixture = Fixture()
    val date = holidayDate()
    assertEquals(setOf("irish"), domain.apply(fixture.context, JSONObject().put("irish", true)))
    assertTrue(CalendarPacks.isEnabled(fixture.context, CalendarPacks.IRISH))
    assertEquals(listOf("🍀 ${IrishHolidays.forToday(date)}"),
        CalendarPacks.headerLines(fixture.context, date))
    CalendarPacks.setEnabled(fixture.context, CalendarPacks.IRISH, false)
    assertFalse(domain.flatJson(fixture.context).getBoolean("irish"))
    assertTrue(CalendarPacks.headerLines(fixture.context, date).isEmpty())
  }

  @Test
  fun prayerSwitch_bindsExistingSetterReaderAndHeaderSelection() {
    val domain = domain()
    val fixture = Fixture()
    val date = holidayDate()
    // Reuse the existing display API; this is a selection contract, not an astronomy test.
    val expected = PrayerTimes.nextLine(fixture.context, date)
    assertTrue("Cached location must yield a prayer header", expected.isNotEmpty())
    assertEquals(setOf("prayer"), domain.apply(fixture.context, JSONObject().put("prayer", true)))
    assertTrue(CalendarPacks.isEnabled(fixture.context, CalendarPacks.PRAYER))
    assertEquals(listOf(expected), CalendarPacks.headerLines(fixture.context, date))
    CalendarPacks.setEnabled(fixture.context, CalendarPacks.PRAYER, false)
    assertFalse(domain.flatJson(fixture.context).getBoolean("prayer"))
    assertTrue(CalendarPacks.headerLines(fixture.context, date).isEmpty())
  }

  @Test
  fun invalidScalarTypes_areRejectedWithoutMutatingSavedChoices() {
    val domain = domain()
    val fixture = Fixture(mapOf("romanian" to true, "irish" to false, "prayer" to true))
    val before = fixture.packs.getAll()
    val invalid = listOf(0, 1, "maybe", "", JSONObject.NULL, JSONObject(), JSONArray())
    keys.forEach { key ->
      invalid.forEach { value ->
        assertEquals("Reject $key = $value", emptySet<String>(),
            SettingsRegistry.apply(fixture.context, domain.id, JSONObject().put(key, value)))
        assertEquals(before, fixture.packs.getAll())
      }
    }
    assertEquals(0, fixture.packs.writeCount)
    assertTrue(domain.flatJson(fixture.context).getBoolean("romanian"))
    assertFalse(domain.flatJson(fixture.context).getBoolean("irish"))
    assertTrue(domain.flatJson(fixture.context).getBoolean("prayer"))
  }

  @Test
  fun schema_exposesBooleanDefaultsAdvancedTierAndUsefulShortHelp() {
    val domain = domain()
    val fixture = Fixture(mapOf("irish" to true))
    val schema = domain.schemaJson(fixture.context)
    assertEquals("calendarpacks", schema.getString("id"))
    assertEquals("Almanac", schema.getString("title"))
    assertEquals(3, schema.getJSONArray("controls").length())
    val controls = controls(domain, fixture.context)
    assertEquals(keys, controls.keys)
    CalendarPacks.AVAILABLE.forEach { pack ->
      val control = controls.getValue(pack.id)
      assertEquals("bool", control.getString("type"))
      assertEquals(pack.title, control.getString("title"))
      assertEquals(false, control.get("default"))
      assertEquals(pack.id == "irish", control.get("value"))
      assertEquals("advanced", control.getString("tier"))
      val help = control.getString("shortHelp").trim()
      assertTrue("${pack.id} needs a useful short explanation", help.length in 12..160)
      assertTrue("${pack.id} help must explain the choice", help.split(Regex("\\s+")).size >= 3)
    }
  }

  @Test
  fun registryFields_matchAvailablePacksAndReuseExistingPersistedPreferences() {
    val domain = domain()
    assertEquals(CalendarPacks.AVAILABLE.map { it.id }.toSet(), domain.specs.map { it.key }.toSet())
    val fixture = Fixture(mapOf("romanian" to true, "irish" to false, "prayer" to true))
    val original = domain.flatJson(fixture.context)
    assertTrue(original.getBoolean("romanian"))
    assertFalse(original.getBoolean("irish"))
    assertTrue(original.getBoolean("prayer"))
    CalendarPacks.setEnabled(fixture.context, CalendarPacks.IRISH, true)
    assertTrue(domain.flatJson(fixture.newContext()).getBoolean("irish"))
    assertEquals(setOf("romanian", "prayer"),
        SettingsRegistry.apply(fixture.context, domain.id,
            JSONObject().put("romanian", false).put("prayer", false)))
    val reopened = fixture.newContext()
    assertEquals(mapOf("romanian" to false, "irish" to true, "prayer" to false), fixture.packs.getAll())
    keys.forEach { key ->
      assertEquals(fixture.packs.getBoolean(key, false), CalendarPacks.isEnabled(reopened, key))
      assertEquals(CalendarPacks.isEnabled(reopened, key), domain.flatJson(reopened).getBoolean(key))
    }
    assertEquals(setOf("lat", "lon", "resolved_at"), fixture.weather.getAll().keys)
    assertEquals(0, fixture.weather.writeCount)
  }
}
