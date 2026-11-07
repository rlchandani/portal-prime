/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

/** Approved Home v2 refinements (2026-11-06): header labels, Today facts, calendar de-duplication. */
class HomeRefinementsTest {
  // --- Feels like (bug: Today showed the air temperature labelled "Feels like") ---

  @Test fun currentUrl_requestsApparentTemperatureAlongsideAirTemperature() {
    val url = Weather.currentUrl(1.0, 2.0, true)
    val fields = url.substringAfter("&current=").substringBefore("&").split(",")
    assertTrue(fields.containsAll(listOf("temperature_2m", "apparent_temperature", "weather_code")))
    assertTrue(url.endsWith("temperature_unit=fahrenheit"))
  }

  @Test fun parseCurrent_readsRoundedApparentTemperatureSeparatelyFromAirTemperature() {
    val value = Weather.parseCurrent(
        """{"current":{"temperature_2m":51.2,"apparent_temperature":46.6,"weather_code":3}}""")
    assertEquals(51, value.temp)
    assertEquals(47, value.feelsLike)
    assertEquals(3, value.code)
  }

  @Test fun parseCurrent_missingOrNullApparentTemperatureIsUnknownNotTheAirTemperature() {
    assertNull(Weather.parseCurrent("""{"current":{"temperature_2m":51.2,"weather_code":3}}""").feelsLike)
    assertNull(Weather.parseCurrent(
        """{"current":{"temperature_2m":51.2,"apparent_temperature":null,"weather_code":3}}""").feelsLike)
  }

  @Test fun todayFacts_feelsLikeUsesApparentTemperature() {
    val facts = HomeRefinements.todayFacts(temp = 51, feelsLike = 47, sun = "7:01 AM" to "4:43 PM",
        high = null, low = null, moon = "Waning crescent")
    assertEquals(
        listOf(
            HomeRefinements.TodayFact("Feels like", "47°"),
            HomeRefinements.TodayFact("Moon", "Waning crescent"),
            HomeRefinements.TodayFact("Sunrise", "7:01 AM"),
            HomeRefinements.TodayFact("Sunset", "4:43 PM")),
        facts)
  }

  @Test fun todayFacts_withoutApparentTemperatureLabelsTheAirTemperatureHonestly() {
    val first = HomeRefinements.todayFacts(51, null, null, null, null, "Full moon").first()
    assertEquals(HomeRefinements.TodayFact("Now", "51°"), first)
  }

  @Test fun todayFacts_withoutAnyWeatherSaysUnavailable() {
    val first = HomeRefinements.todayFacts(null, null, null, null, null, "Full moon").first()
    assertEquals(HomeRefinements.TodayFact("Weather", "Unavailable"), first)
  }

  @Test fun todayFacts_withoutSunTimesFallsBackToHighAndLow() {
    val facts = HomeRefinements.todayFacts(51, 47, null, high = 55, low = 41, moon = "New moon")
    assertEquals(listOf("Feels like", "Moon", "High", "Low"), facts.map { it.label })
    assertEquals(listOf("47°", "New moon", "55°", "41°"), facts.map { it.value })
  }

  @Test fun todayFacts_withoutSunTimesOrForecastShowsOnlyKnownFacts() {
    val facts = HomeRefinements.todayFacts(51, 47, null, null, null, "New moon")
    assertEquals(listOf("Feels like", "Moon"), facts.map { it.label })
  }

  // --- Removed duplicate: Today no longer repeats Calendar's "Add your calendar" prompt ---

  @Test fun todayShowsCalendarOnlyWhenACalendarIsConfigured() {
    assertFalse(HomeRefinements.todayShowsCalendar(null))
    assertFalse(HomeRefinements.todayShowsCalendar(""))
    assertFalse(HomeRefinements.todayShowsCalendar("   "))
    assertTrue(HomeRefinements.todayShowsCalendar("https://example.com/cal.ics"))
  }

  // --- Header action labels say what the action does ---

  @Test fun headerAction_opensSettingsIsLabelledSettings() {
    assertEquals(HomeRefinements.HeaderAction.SETTINGS,
        HomeRefinements.headerAction(opensContent = false, duplicatesBodyAction = false))
  }

  @Test fun headerAction_opensContentKeepsOpen() {
    assertEquals(HomeRefinements.HeaderAction.OPEN,
        HomeRefinements.headerAction(opensContent = true, duplicatesBodyAction = false))
  }

  @Test fun headerAction_duplicatingTheCardsOwnButtonIsRemoved() {
    assertNull(HomeRefinements.headerAction(opensContent = true, duplicatesBodyAction = true))
  }

  @Test fun headerAction_labels() {
    assertEquals("Open", HomeRefinements.HeaderAction.OPEN.label)
    assertEquals("Settings", HomeRefinements.HeaderAction.SETTINGS.label)
  }

  // --- Widget page indicator moves under the widgets and only appears when there is a choice ---

  @Test fun pageIndicatorOnlyWhenMoreThanOnePage() {
    assertFalse(HomeRefinements.showsPageIndicator(0))
    assertFalse(HomeRefinements.showsPageIndicator(1))
    assertTrue(HomeRefinements.showsPageIndicator(2))
  }
}
