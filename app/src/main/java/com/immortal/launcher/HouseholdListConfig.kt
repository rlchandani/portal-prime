/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context

/**
 * Per-Portal display options for the household list (the list itself is shared). Read/written
 * only through [HouseholdListSettings.domain]'s specs once the integrator registers it.
 */
object HouseholdListConfig {
  private const val PREFS = "immortal_household_list_display"

  data class Settings(
      /** Device name and time under each item in the full list. */
      val showWho: Boolean = true,
      /** When there's room, the widget shows the last few ticked items. */
      val widgetShowsDone: Boolean = false,
  )

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun load(c: Context): Settings = prefs(c).let {
    Settings(showWho = it.getBoolean("showWho", true), widgetShowsDone = it.getBoolean("widgetShowsDone", false))
  }

  fun setShowWho(c: Context, v: Boolean) { prefs(c).edit().putBoolean("showWho", v).apply() }
  fun setWidgetShowsDone(c: Context, v: Boolean) { prefs(c).edit().putBoolean("widgetShowsDone", v).apply() }
}
