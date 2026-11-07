/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.settings

import com.immortal.launcher.HouseholdListConfig

/**
 * PROPOSED settings domain for the household list display options. Not yet in
 * [SettingsDomains.all] — the integrator adds it there (which also exposes it on the phone remote).
 */
object HouseholdListSettings {
  val domain: SettingsDomain<HouseholdListConfig.Settings> =
      SettingsDomain(
          id = "householdlist",
          title = "Household list",
          load = HouseholdListConfig::load,
          specs = listOf(
              BoolSpec("showWho", "Show who added each item",
                  get = { it.showWho }, set = HouseholdListConfig::setShowWho,
                  help = "Shows the device name and time under each item in the full list."),
              BoolSpec("widgetShowsDone", "Show ticked items on the widget",
                  get = { it.widgetShowsDone }, set = HouseholdListConfig::setWidgetShowsDone,
                  help = "When there’s room, the widget shows the last few ticked items."),
          ),
          defaults = { HouseholdListConfig.Settings() },
      )
}
