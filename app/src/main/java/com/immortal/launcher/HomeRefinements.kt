/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Pure presentation rules for the approved Home v2 refinements (2026-11-06). Kept free of Compose
 * and Android so the decisions are unit-tested; the composables only render what these return.
 */
internal object HomeRefinements {
  /** What a widget header's trailing action does, labelled truthfully. */
  enum class HeaderAction(val label: String) {
    /** Opens the widget's own content (dashboard, timers, world clock …). */
    OPEN("Open"),
    /** Opens the widget's settings page; rendered quieter than a content action. */
    SETTINGS("Settings"),
  }

  /**
   * The header action for a widget, or null when it would only repeat a button the card already
   * shows in its body (e.g. Home Assistant's "Open dashboard").
   */
  fun headerAction(opensContent: Boolean, duplicatesBodyAction: Boolean): HeaderAction? = when {
    duplicatesBodyAction -> null
    opensContent -> HeaderAction.OPEN
    else -> HeaderAction.SETTINGS
  }

  /** One labelled fact in Today's 2×2 grid. */
  data class TodayFact(val label: String, val value: String)

  /**
   * Today's facts in reading order (row-major for a two-column grid): weather, moon, then sunrise/
   * sunset — or high/low when sun times are unavailable. "Feels like" is only claimed when the
   * apparent temperature is actually known; otherwise the air temperature is labelled "Now".
   */
  fun todayFacts(
      temp: Int?,
      feelsLike: Int?,
      sun: Pair<String, String>?,
      high: Int?,
      low: Int?,
      moon: String,
  ): List<TodayFact> = buildList {
    add(when {
      feelsLike != null -> TodayFact("Feels like", "$feelsLike°")
      temp != null -> TodayFact("Now", "$temp°")
      else -> TodayFact("Weather", "Unavailable")
    })
    add(TodayFact("Moon", moon))
    if (sun != null) {
      add(TodayFact("Sunrise", sun.first))
      add(TodayFact("Sunset", sun.second))
    } else {
      high?.let { add(TodayFact("High", "$it°")) }
      low?.let { add(TodayFact("Low", "$it°")) }
    }
  }

  /**
   * Today shows calendar content only once a calendar is configured. The setup prompt lives in the
   * Calendar widget (and Today's Settings action), so Today no longer repeats "Add your calendar".
   */
  fun todayShowsCalendar(calendarUrl: String?): Boolean = !calendarUrl.isNullOrBlank()

  /** Widget page dots + "n of m" appear only when there is more than one page to move between. */
  fun showsPageIndicator(pageCount: Int): Boolean = pageCount > 1
}
