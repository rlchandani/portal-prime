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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import java.time.ZoneId

/** Everything Households & devices does, as callbacks (the caller owns state and storage). */
internal class HomeLinkHouseholdsActions(
    val onState: (HomeLinkHouseholdsState) -> Unit,
    val onEdit: (HomeLink) -> Unit,
    val onRemove: (String) -> Unit,
    val onInvite: () -> Unit,
    val onJoin: () -> Unit,
    val onRooms: (HomeLink) -> Unit,
    val onCheckAgain: (HomeLink) -> Unit,
    val onBack: () -> Unit,
    /** "Pair Portals" on This home (measurement builds: [PortalPinFlowScreen]); null hides it. */
    val onPortals: (() -> Unit)? = null,
)

/**
 * Mock `hhScr`: households down the side (this home first), the chosen one's card, devices and
 * what it can do on the right. On a Mini the list and the detail take turns ([HomeLinkPane]).
 */
@Composable
internal fun HomeLinkHouseholdsScreen(state: HomeLinkHouseholdsState, selfName: String, devices: List<HomeLinkDevice>,
                                      zone: ZoneId, actions: HomeLinkHouseholdsActions) {
  val p = LocalApprovedPalette.current
  val d = homeLinkDims()
  val pane = state.pane(currentLayoutClass().compact)
  val back = if (pane == HomeLinkPane.DETAIL) ({ actions.onState(state.back()) }) else actions.onBack
  Column(Modifier.fillMaxSize().background(p.background)) {
    HomeLinkHeader(HomeLinkHouseholdsCopy.TITLE, onBack = back)
    Row(Modifier.fillMaxSize()) {
      if (pane != HomeLinkPane.DETAIL) {
        HouseholdsSide(state, selfName, actions,
            if (pane == HomeLinkPane.BOTH) Modifier.width(d.sideWidth).fillMaxHeight() else Modifier.fillMaxSize())
      }
      if (pane != HomeLinkPane.LIST) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
          val link = state.link
          if (link == null) ThisHomeDetail(selfName, devices, actions) else LinkDetail(state, link, selfName, zone, actions)
        }
      }
    }
  }
}

@Composable
private fun HouseholdsSide(state: HomeLinkHouseholdsState, selfName: String, actions: HomeLinkHouseholdsActions, modifier: Modifier) {
  val p = LocalApprovedPalette.current
  val d = homeLinkDims()
  Column(modifier.background(p.sidebar).verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)) {
    HouseholdNav(selfName, HomeLinkHouseholdsCopy.THIS_HOME, on = state.selected == null) { actions.onState(state.select(null)) }
    state.book.links.forEach { link ->
      HouseholdNav(link.name, HomeLinkHouseholdsCopy.ringSummary(link), on = state.selected == link.id) {
        actions.onState(state.select(link.id))
      }
    }
    if (state.book.links.isEmpty()) {
      Text(HomeLinkHouseholdsCopy.NO_LINKS, color = p.secondary, fontSize = d.sub, modifier = Modifier.padding(16.dp))
    }
    Spacer(Modifier.heightIn(min = 16.dp))
    if (state.book.canAdd) CallPill(HomeLinkHouseholdsCopy.ADD, actions.onInvite, icon = "plus", style = CallButtonStyle.PRIMARY,
        surface = callsSurface(), modifier = Modifier.fillMaxWidth())
    CallPill(HomeLinkHouseholdsCopy.JOIN, actions.onJoin, icon = "key", surface = callsSurface(), modifier = Modifier.fillMaxWidth())
    Text(HomeLinkHouseholdsCopy.SIDE_NOTE, color = p.secondary, fontSize = d.sub, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
  }
}

/** `.navi`: avatar, name, a quiet line on the right; the chosen one in the accent. */
@Composable
private fun HouseholdNav(name: String, aside: String, on: Boolean, onClick: () -> Unit) {
  val p = LocalApprovedPalette.current
  val d = homeLinkDims()
  val ink = if (on) callsSurface().onAccent else p.text
  Row(Modifier.fillMaxWidth().heightIn(min = d.navMin).background(if (on) p.accent else p.sidebar, RoundedCornerShape(26.dp))
      .callClick("$name, $aside", true, onClick).padding(horizontal = 24.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
    CallRoomAvatar(name, d.avatar)
    Text(name, color = ink, fontSize = d.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f))
    Text(aside, color = ink.copy(alpha = 0.8f), fontSize = d.sub, maxLines = 1)
  }
}

@Composable
private fun DetailColumn(content: @Composable () -> Unit) {
  val d = homeLinkDims()
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = d.pad, end = d.pad, bottom = 40.dp),
      verticalArrangement = Arrangement.spacedBy(d.gap)) { content() }
}

/** The big card at the top of the detail: avatar, name, line, chip and one action. */
@Composable
private fun HeroCard(name: String, sub: String, chip: String?, action: @Composable () -> Unit) {
  val p = LocalApprovedPalette.current
  val d = homeLinkDims()
  HomeLinkCard {
    Row(Modifier.padding(horizontal = 36.dp, vertical = 32.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(30.dp)) {
      CallRoomAvatar(name, d.bigAvatar)
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, color = p.text, fontSize = d.title, fontWeight = FontWeight.Bold)
        Text(sub, color = p.secondary, fontSize = d.sub)
        chip?.let { CallChip(it, "shield", tone = CallChipTone.OK) }
      }
      if (!currentLayoutClass().compact) action()
    }
    if (currentLayoutClass().compact) Box(Modifier.padding(start = 36.dp, end = 36.dp, bottom = 28.dp)) { action() }
  }
}

@Composable
private fun ThisHomeDetail(selfName: String, devices: List<HomeLinkDevice>, actions: HomeLinkHouseholdsActions) = DetailColumn {
  HeroCard(selfName, HomeLinkHouseholdsCopy.THIS_HOME, chip = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      CallPill(HomeLinkHouseholdsCopy.ADD, actions.onInvite, icon = "plus", surface = callsSurface())
      actions.onPortals?.let { CallPill(HomeLinkHouseholdsCopy.PORTALS, it, icon = "key", surface = callsSurface()) }
    }
  }
  if (devices.isNotEmpty()) DeviceList(devices)
}

@Composable
private fun DeviceList(devices: List<HomeLinkDevice>) {
  val d = homeLinkDims()
  Column {
    HomeLinkSection(HomeLinkHouseholdsCopy.DEVICES)
    HomeLinkCard {
      devices.forEachIndexed { i, dev ->
        HomeLinkRow(dev.name, dev.detail, first = i == 0, lead = {
          Box(Modifier.size(d.avatar).background(LocalApprovedPalette.current.surface2, RoundedCornerShape(18.dp)),
              contentAlignment = Alignment.Center) { CallIcon(if (dev.phone) "handoff" else "house", LocalApprovedPalette.current.text) }
        }) { CallChip(HomeLinkHouseholdsCopy.presence(dev.presence), tone = presenceTone(dev.presence)) }
      }
    }
  }
}

private fun presenceTone(p: CallPresence): CallChipTone = when (p) {
  CallPresence.AVAILABLE -> CallChipTone.OK
  CallPresence.IN_CALL -> CallChipTone.WARN
  CallPresence.QUIET -> CallChipTone.QUIET
  CallPresence.OFFLINE -> CallChipTone.PLAIN
}

@Composable
private fun LinkDetail(state: HomeLinkHouseholdsState, link: HomeLink, selfName: String, zone: ZoneId,
                       actions: HomeLinkHouseholdsActions) = DetailColumn {
  HeroCard(link.name, HomeLinkHouseholdsCopy.ringSummary(link), state.checkedLine(link, zone)) {
    CallPill(HomeLinkHouseholdsCopy.CHECK_AGAIN, { actions.onCheckAgain(link) }, icon = "key", surface = callsSurface())
  }
  Column {
    HomeLinkSection(HomeLinkHouseholdsCopy.whatCanDo(link.name))
    HomeLinkCard {
      HomeLinkSwitchRow(HomeLinkHouseholdsCopy.call(selfName), HomeLinkHouseholdsCopy.SUB_CALL, link.permissions.canCall, first = true,
          onChange = { actions.onEdit(HomeLinkPermissionEdit.call(link, it)) })
      HomeLinkSwitchRow(HomeLinkHouseholdsCopy.AVAILABILITY, HomeLinkHouseholdsCopy.SUB_AVAILABILITY, link.permissions.seesAvailability,
          first = false, onChange = { actions.onEdit(HomeLinkPermissionEdit.availability(link, it)) })
      HomeLinkSwitchRow(HomeLinkHouseholdsCopy.DROP_IN, HomeLinkHouseholdsCopy.SUB_DROP_IN, checked = false, first = false, onChange = null)
      HomeLinkRow(HomeLinkHouseholdsCopy.roomsThatRing(link.name), HomeLinkHouseholdsCopy.ringSummary(link), first = false,
          modifier = Modifier.callClick(HomeLinkHouseholdsCopy.roomsThatRing(link.name), true) { actions.onRooms(link) }) {
        Text("›", color = LocalApprovedPalette.current.secondary, fontSize = homeLinkDims().title)
      }
    }
  }
  if (state.confirmingRemove) RemoveConfirm(state, link, actions)
  else CallPill(HomeLinkHouseholdsCopy.remove(link.name), { actions.onState(state.askRemove()) }, icon = "trash",
      style = CallButtonStyle.BAD, surface = callsSurface())
}

/** "Remove Lakeview?": says what goes (its keys, from every Portal here), then Remove or Cancel. */
@Composable
private fun RemoveConfirm(state: HomeLinkHouseholdsState, link: HomeLink, actions: HomeLinkHouseholdsActions) {
  val p = LocalApprovedPalette.current
  val d = homeLinkDims()
  HomeLinkCard {
    Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
      Text(HomeLinkHouseholdsCopy.remove(link.name) + "?", color = p.text, fontSize = d.label, fontWeight = FontWeight.Bold)
      Text(HomeLinkHouseholdsCopy.removeBody(link.name), color = p.secondary, fontSize = d.sub)
      Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        CallPill(HomeLinkHouseholdsCopy.remove(link.name), { actions.onRemove(link.id) }, icon = "trash", style = CallButtonStyle.BAD,
            surface = callsSurface())
        CallPill(HomeLinkHouseholdsCopy.CANCEL, { actions.onState(state.cancelRemove()) }, surface = callsSurface())
      }
    }
  }
}
