/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.immortal.launcher.ui.theme.LocalApprovedPalette

/**
 * Mock `permsScr`: right after *They match* (and from Households › Rooms that ring), what the new
 * home may do: call us, see when we're available, and which rooms ring. Drop-ins and auto-answer
 * are explained, not offered. Two room columns on a Portal, one on a Mini.
 */
@Composable
internal fun HomeLinkPermissionsScreen(link: HomeLink, selfName: String, rooms: List<String>, onEdit: (HomeLink) -> Unit,
                                       onDone: () -> Unit, onBack: (() -> Unit)? = null) {
  val p = LocalApprovedPalette.current
  val d = homeLinkDims()
  Column(Modifier.fillMaxSize().background(p.background)) {
    HomeLinkHeader(HomeLinkHouseholdsCopy.permsTitle(link.name), HomeLinkHouseholdsCopy.PERMS_SUB, onBack = onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = d.pad, end = d.pad, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(d.gap)) {
      HomeLinkCard {
        HomeLinkSwitchRow(HomeLinkHouseholdsCopy.call(selfName), HomeLinkHouseholdsCopy.permsCallSub(link.name), link.permissions.canCall,
            first = true, onChange = { onEdit(HomeLinkPermissionEdit.call(link, it)) }, lead = { CallRoomAvatar(link.name, d.avatar) })
        HomeLinkSwitchRow(HomeLinkHouseholdsCopy.AVAILABILITY, HomeLinkHouseholdsCopy.PERMS_AVAILABILITY_SUB, link.permissions.seesAvailability,
            first = false, onChange = { onEdit(HomeLinkPermissionEdit.availability(link, it)) }, lead = { LeadIcon("eye") })
      }
      if (link.permissions.canCall) RoomsThatRing(link, rooms, onEdit)
      HomeLinkLockNote(HomeLinkHouseholdsCopy.permsNote(link.name))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        CallPill(HomeLinkHouseholdsCopy.DONE, onDone, icon = "check", style = CallButtonStyle.PRIMARY, size = CallButtonSize.LG,
            surface = callsSurface())
      }
    }
  }
}

@Composable
private fun RoomsThatRing(link: HomeLink, rooms: List<String>, onEdit: (HomeLink) -> Unit) {
  val d = homeLinkDims()
  Column {
    HomeLinkSection(HomeLinkHouseholdsCopy.roomsThatRing(link.name))
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
      HomeLinkPermissionEdit.roomsToShow(link, rooms).chunked(d.roomColumns).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
          row.forEach { room ->
            Box(Modifier.weight(1f)) {
              HomeLinkCard {
                HomeLinkSwitchRow(room, null, link.permissions.rings(room), first = true,
                    onChange = { onEdit(HomeLinkPermissionEdit.room(link, room, it)) }, lead = { CallRoomAvatar(room, d.avatar) })
              }
            }
          }
          repeat(d.roomColumns - row.size) { Spacer(Modifier.weight(1f)) }
        }
      }
    }
  }
}

@Composable
private fun LeadIcon(icon: String) {
  val p = LocalApprovedPalette.current
  Box(Modifier.size(homeLinkDims().avatar).background(p.surface2, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
    CallIcon(icon, p.text)
  }
}

/** `.card` with a lock: what can never be switched on from here. */
@Composable
private fun HomeLinkLockNote(text: String) {
  val p = LocalApprovedPalette.current
  val d = homeLinkDims()
  HomeLinkCard {
    Row(Modifier.padding(horizontal = 32.dp, vertical = 26.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp)) {
      CallIcon("lock", p.text)
      Text(text, color = p.text, fontSize = d.sub)
    }
  }
}
