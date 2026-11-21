/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember

/** The header's title line and subtitle. */
internal data class CcHeading(val title: String, val subtitle: String)

/**
 * What the heading needs from the HA state, at minute resolution: equal across HA updates within
 * a minute, so the shell passes the same value and the header skips; the screen and floor are read
 * by the title itself ([rememberCcHeading]).
 */
@Immutable
internal data class CcHeadingInputs(val minute: Long, val car: String, val plan: CcPlan?, val kids: Boolean) {
  companion object {
    fun of(s: CcUiState) = CcHeadingInputs(s.minute, s.registry?.tesla?.label ?: CcTeslaIds.DEFAULT_LABEL, s.plan, s.settings.kids)
  }
}

/** The heading for [screen] (and the map's [floorId]); reads the current screen in the caller's (small) scope. */
@Composable
internal fun rememberCcHeading(inputs: CcHeadingInputs, ui: CcUi): CcHeading {
  val screen = ui.screen
  val floorId = ui.floorId
  return remember(inputs, screen, floorId) { ccHeading(inputs, screen, floorId) }
}

/** "Home" + "Tuesday evening · 7:42 pm · Ground floor"; other screens drop the day part. */
internal fun ccHeading(inputs: CcHeadingInputs, screen: CcScreen, floorId: String?): CcHeading {
  val now = inputs.minute * MS_PER_MINUTE
  val subtitle = if (screen != CcScreen.MAP) ccSubtitle(now).replace(DAY_PART, "") else {
    val floor = inputs.plan?.floor(floorId)?.name
    val kids = if (inputs.kids) "Kids mode" else null
    ccSubtitle(now, listOfNotNull(floor, kids).joinToString(" · ").ifBlank { null })
  }
  return CcHeading(ccTitle(screen, inputs.car), subtitle)
}

/** The header title; the Tesla screen is called by the car's HA name ([car]). */
internal fun ccTitle(screen: CcScreen, car: String = CcTeslaIds.DEFAULT_LABEL): String = when (screen) {
  CcScreen.MAP -> "Home"
  CcScreen.CAMERAS -> "Cameras"
  CcScreen.TESLA -> car
  CcScreen.ALERTS -> "Alerts"
  CcScreen.SECURITY -> "Security"
  CcScreen.EDIT -> "Edit floor plan"
  CcScreen.SETTINGS -> "Home settings"
}

private val DAY_PART = Regex(" (morning|afternoon|evening|night)")
private const val MS_PER_MINUTE = 60_000L
