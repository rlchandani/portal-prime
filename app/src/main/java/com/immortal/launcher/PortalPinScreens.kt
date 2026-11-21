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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.LocalApprovedPalette

/** What the Portals list does, as callbacks (the caller owns state and storage). */
internal class PortalPinListActions(
    val onShow: () -> Unit,
    val onType: () -> Unit,
    val onAskRemove: (String) -> Unit,
    val onRemove: (String) -> Unit,
    val onCancelRemove: () -> Unit,
    val onBack: () -> Unit,
)

/**
 * "Portals in this home" (mock `hhScr`'s This home, Devices + Add a device): the pair card with
 * Show a code / Type a code, then the Portals paired in person, each with Remove.
 */
@Composable
internal fun PortalPinListScreen(selfName: String, pins: List<PortalPin>, removing: String?, actions: PortalPinListActions) {
  val p = LocalApprovedPalette.current
  val d = homeLinkDims()
  Column(Modifier.fillMaxSize().background(p.background)) {
    HomeLinkHeader(PortalPinCopy.TITLE, PortalPinCopy.SUB, onBack = actions.onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = d.pad, end = d.pad, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(d.gap)) {
      PairCard(selfName, actions)
      Column {
        HomeLinkSection(PortalPinCopy.PAIRED)
        HomeLinkCard {
          if (pins.isEmpty()) HomeLinkRow(PortalPinCopy.NONE, null, first = true)
          pins.forEachIndexed { i, pin ->
            HomeLinkRow(pin.name, PortalPinCopy.pinDetail(pin), first = i == 0, lead = { PortalLead() }) {
              CallPill(PortalPinCopy.REMOVE, { actions.onAskRemove(pin.kid) }, icon = "trash", surface = callsSurface(),
                  spoken = "${PortalPinCopy.REMOVE} ${pin.name}")
            }
          }
        }
      }
      pins.firstOrNull { it.kid == removing }?.let { RemoveCard(it, actions) }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PairCard(selfName: String, actions: PortalPinListActions) {
  val p = LocalApprovedPalette.current
  val d = homeLinkDims()
  HomeLinkCard {
    Column(Modifier.padding(horizontal = 36.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(30.dp)) {
        CallRoomAvatar(selfName, d.bigAvatar)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(PortalPinCopy.PAIR, color = p.text, fontSize = d.title, fontWeight = FontWeight.Bold)
          Text(PortalPinCopy.PAIR_HOW, color = p.secondary, fontSize = d.sub)
        }
      }
      FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CallPill(PortalPinCopy.SHOW, actions.onShow, icon = "key", style = CallButtonStyle.PRIMARY, size = CallButtonSize.LG,
            surface = callsSurface())
        CallPill(PortalPinCopy.TYPE, actions.onType, icon = "plus", size = CallButtonSize.LG, surface = callsSurface())
      }
    }
  }
}

@Composable
private fun PortalLead() {
  val p = LocalApprovedPalette.current
  Box(Modifier.size(homeLinkDims().avatar).background(p.surface2, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
    CallIcon("house", p.text)
  }
}

@Composable
private fun RemoveCard(pin: PortalPin, actions: PortalPinListActions) {
  val p = LocalApprovedPalette.current
  val d = homeLinkDims()
  HomeLinkCard {
    Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
      Text("${PortalPinCopy.REMOVE} ${pin.name}?", color = p.text, fontSize = d.label, fontWeight = FontWeight.Bold)
      Text(PortalPinCopy.removeBody(pin.name), color = p.secondary, fontSize = d.sub)
      Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        CallPill(PortalPinCopy.REMOVE, { actions.onRemove(pin.kid) }, icon = "trash", style = CallButtonStyle.BAD, surface = callsSurface())
        CallPill(PortalPinCopy.CANCEL, actions.onCancelRemove, surface = callsSurface())
      }
    }
  }
}

/** The showing Portal: the code in big tiles, how long it lasts, and that the other Portal types it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PortalPinShowScreen(invite: HomeLinkInvite, now: Long, onNewCode: () -> Unit, onCancel: () -> Unit) {
  val p = LocalApprovedPalette.current
  val d = homeLinkDims()
  Column(Modifier.fillMaxSize().background(p.background)) {
    HomeLinkHeader(PortalPinCopy.SHOW_TITLE, onBack = onCancel)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = d.pad, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(32.dp)) {
      Text(PortalPinCopy.SHOW_H2, color = p.text, fontSize = d.title, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
      HomeLinkSlots(invite.code.value.toList(), cursor = -1, spoken = "Code ${invite.code.value.toList().joinToString(" ")}")
      Text(PortalPinCopy.SHOW_HOW, color = p.secondary, fontSize = d.sub, textAlign = TextAlign.Center)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CallChip(HomeLinkCopy.expires(invite, now), "clock", plainFill = p.surface2, plainInk = p.secondary)
        CallChip(PortalPinCopy.ONCE, "key", plainFill = p.surface2, plainInk = p.secondary)
        CallChip(PortalPinCopy.SAME_ROOM, "house", plainFill = p.surface2, plainInk = p.secondary)
      }
      Text(PortalPinCopy.WAITING, color = p.secondary, fontSize = d.label, fontWeight = FontWeight.SemiBold)
      Box(Modifier.widthIn(max = 1300.dp)) { HomeLinkNoteCard(PortalPinCopy.NOTE) }
      Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        CallPill(HomeLinkCopy.NEW_CODE, onNewCode, icon = "redo", surface = callsSurface())
        CallPill(PortalPinCopy.CANCEL, onCancel, surface = callsSurface())
      }
    }
  }
}

/** The same Portal already paired with another key: only an explicit "Pair again" replaces it. */
@Composable
internal fun PortalPinRepairScreen(name: String, onRepair: () -> Unit, onCancel: () -> Unit) =
    PortalPinMessage("shield", PortalPinCopy.REPAIR_TITLE, PortalPinCopy.repairBody(name)) {
      CallPill(PortalPinCopy.CANCEL, onCancel, size = CallButtonSize.LG, surface = callsSurface())
      CallPill(PortalPinCopy.REPAIR_GO, onRepair, icon = "redo", style = CallButtonStyle.PRIMARY, size = CallButtonSize.LG,
          surface = callsSurface())
    }

@Composable
internal fun PortalPinDoneScreen(name: String, onDone: () -> Unit) =
    PortalPinMessage("check", PortalPinCopy.doneTitle(name), PortalPinCopy.doneBody(name), ok = true) {
      CallPill(PortalPinCopy.DONE, onDone, icon = "check", style = CallButtonStyle.PRIMARY, size = CallButtonSize.LG, surface = callsSurface())
    }

/** One big icon, a title, a line and the buttons, centred (the mock's done / mismatch layout). */
@Composable
private fun PortalPinMessage(icon: String, title: String, body: String, ok: Boolean = false, buttons: @Composable () -> Unit) {
  val p = LocalApprovedPalette.current
  val compact = currentLayoutClass().compact
  val tint = if (ok) CallColors.Ok else p.accent
  Box(Modifier.fillMaxSize().background(p.background).padding(horizontal = homeLinkDims().pad), contentAlignment = Alignment.Center) {
    Column(Modifier.widthIn(max = 1300.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(30.dp)) {
      Box(Modifier.size(if (compact) 160.dp else 200.dp).background(tint.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
        CallIcon(icon, tint, if (compact) 76.dp else 96.dp)
      }
      Text(title, color = p.text, fontSize = if (compact) 44.sp else 60.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
          modifier = Modifier.semantics { heading() })
      Text(body, color = p.secondary, fontSize = if (compact) 24.sp else 30.sp, lineHeight = if (compact) 34.sp else 42.sp,
          textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
      Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) { buttons() }
    }
  }
}
