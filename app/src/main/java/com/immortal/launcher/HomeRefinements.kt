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
   * What a screen reader calls a widget header's action. Every card shows the same "Settings" or
   * "Open", so the spoken name says whose it is ("Weather settings", "Open Routines"); the visible
   * word stays the start or end of it, so voice control still matches what's on screen.
   */
  fun headerActionSpoken(action: HeaderAction, title: String): String = when (action) {
    HeaderAction.OPEN -> "Open $title"
    HeaderAction.SETTINGS -> "$title settings"
  }

  /** Customize's per-card Move/Size/Settings/Remove, named for their card ("Move Weather", "Weather settings"). */
  fun widgetEditSpoken(label: String, widgetName: String): String =
      if (label == HeaderAction.SETTINGS.label) "$widgetName settings" else "$label $widgetName"

  /**
   * The header action for a widget, or null when it would only repeat a button the card already
   * shows in its body (e.g. Home Assistant's "Open dashboard").
   */
  fun headerAction(opensContent: Boolean, duplicatesBodyAction: Boolean): HeaderAction? = when {
    duplicatesBodyAction -> null
    opensContent -> HeaderAction.OPEN
    else -> HeaderAction.SETTINGS
  }

  /** Where the Home Assistant widget's primary tap and open action go. */
  enum class HaWidgetTarget { CONTROL_CENTER, WEB_DASHBOARD, SETTINGS }

  /**
   * The Home Assistant widget opens the Control Center once HA is connected — except on banners-only
   * Portals, which have no Control Center app. Otherwise the web dashboard (if set), else Settings.
   * The web dashboard stays reachable from Settings › Home Assistant and its launcher tile.
   */
  fun haWidgetTarget(haConfigured: Boolean, bannersOnly: Boolean, dashboardConfigured: Boolean): HaWidgetTarget = when {
    haConfigured && !bannersOnly -> HaWidgetTarget.CONTROL_CENTER
    dashboardConfigured -> HaWidgetTarget.WEB_DASHBOARD
    else -> HaWidgetTarget.SETTINGS
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
