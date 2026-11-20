/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The plan's rooms (id, name) and the one this Portal's name matches, for the room picker. */
@Immutable
internal data class CcSetRooms(val rooms: List<Pair<String, String>> = emptyList(), val matched: String? = null) {
  companion object {
    fun of(plan: CcPlan?, portalName: String): CcSetRooms {
      val rooms = plan?.floors.orEmpty().flatMap { it.rooms }
      return CcSetRooms(rooms.map { it.id to it.name }, CcPortalRooms.byName(rooms, portalName)?.name)
    }
  }
}

/** Portals › this Portal's room on the plan: matched from its name unless the household picks one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CcSetPortalRoom(planRoom: String, rooms: CcSetRooms, write: (String, Any) -> Unit) {
  if (rooms.rooms.isEmpty()) return
  val chosen = rooms.rooms.firstOrNull { it.first == planRoom }?.second
  val line = when {
    chosen != null -> "Set here · $chosen"
    rooms.matched != null -> "Matched from this Portal's name · ${rooms.matched}"
    else -> "Not matched · pick the room so presence shows on the map"
  }
  CcSetGroup {
    CcSetRow("This Portal's room on the plan", line, divider = false)
    FlowRow(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
      CcButton("Auto", { write("planRoom", "") }, style = if (chosen == null) CcButtonStyle.PRIMARY else CcButtonStyle.TONAL, height = 58)
      rooms.rooms.forEach { (id, name) ->
        CcButton(name, { write("planRoom", id) }, style = if (id == planRoom) CcButtonStyle.PRIMARY else CcButtonStyle.TONAL, height = 58)
      }
    }
  }
}

/** Plan style › "Show who's where on the map" (household). */
@Composable
internal fun CcSetMapPeopleRow(mode: String, write: (String, Any) -> Unit) {
  CcSetRow("Show who's where on the map", when (mode) {
    ControlCenterConfig.MAP_OFF -> "Off · motion sensors still pulse"
    ControlCenterConfig.MAP_NAMES -> "Names for people who allow it on their Who's there? page · every Portal and phone"
    else -> "A soft pulse where someone is · no names · every Portal and phone"
  }) {
    CcSeg(listOf(Triple(ControlCenterConfig.MAP_OFF, "Off", null), Triple(ControlCenterConfig.MAP_SOMEONE, "Someone", null),
        Triple(ControlCenterConfig.MAP_NAMES, "Names", null)), mode, { write("mapPeople", it) })
  }
}
