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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** The live "To phone" control for this call: tap moves to the one ready phone, opens the picker, or explains. */
@Composable
internal fun rememberMoveControl(call: ActiveCall, toast: CallToastState, openPicker: () -> Unit): CallMoveAction {
  val context = LocalContext.current
  val phones by CallPhoneMailbox.phones.collectAsState()
  val now = rememberCallClock(MOVE_CLOCK_MS)
  val control = CallMoves.control(call, phones, Calling.selfId(context), now)
  return CallMoveAction(control.label, if (control.enabled) ControlLook.PLAIN else ControlLook.DIMMED, phones.isNotEmpty()) {
    when {
      control.target != null -> if (!Calling.moveToPhone(context, control.target.id)) toast.show("That phone can’t take the call right now", "info")
      control.pick -> openPicker()
      control.why != null -> toast.show(control.why, "info")
    }
  }
}

/** What the control row shows for "To phone". [known]: this Portal has heard from a paired phone. */
internal data class CallMoveAction(val label: String, val look: ControlLook, val known: Boolean, val onClick: () -> Unit)

/** While the phone rings or connects: one calm line at the top of the call. */
@Composable
internal fun BoxScope.CallMovingBanner(call: ActiveCall, compact: Boolean) {
  val line = CallMoves.movingLine(call) ?: return
  Row(Modifier.align(Alignment.TopCenter).padding(top = if (compact) 120.dp else 150.dp)
      .background(CallColors.Popover, RoundedCornerShape(50)).padding(horizontal = 28.dp, vertical = 16.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    CallIcon("handoff", Color.White, 32.dp)
    Text(line, color = Color.White, fontSize = if (compact) 22.sp else 25.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        overflow = TextOverflow.Ellipsis)
  }
}

/** Several phones are ready: pick which one takes the call. */
@Composable
internal fun BoxScope.CallMovePicker(call: ActiveCall, layout: LayoutClass, onClose: () -> Unit) {
  val context = LocalContext.current
  val phones by CallPhoneMailbox.phones.collectAsState()
  val now = rememberCallClock(MOVE_CLOCK_MS)
  val ready = phones.filter { CallMoves.option(call, it, Calling.selfId(context), now) == CallMoves.Option.READY }
  LaunchedEffect(ready.isEmpty()) { if (ready.isEmpty()) onClose() }
  Box(Modifier.fillMaxSize().background(CallColors.Scrim).callClick("Close", true, onClose))
  val sheet = if (layout.compactPortrait) Modifier.fillMaxWidth() else Modifier.width(if (layout.compact) 640.dp else 760.dp)
  Column(Modifier.align(Alignment.Center).padding(if (layout.compact) 24.dp else 36.dp).then(sheet)
      .callPanel(CallColors.Popover, RoundedCornerShape(40.dp), CallColors.GlassEdge).padding(horizontal = 36.dp, vertical = 32.dp),
      verticalArrangement = Arrangement.spacedBy(20.dp)) {
    Text("Move this call to…", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold)
    Text("It rings there. Pick up, and this Portal’s camera and mic turn off.", color = Color.White.copy(alpha = 0.7f), fontSize = 23.sp)
    ready.forEach { phone ->
      Row(Modifier.fillMaxWidth().background(CallColors.ControlFill, RoundedCornerShape(28.dp)).padding(horizontal = 24.dp, vertical = 18.dp)
          .callClick("Move to ${phone.name}", true) { if (Calling.moveToPhone(context, phone.id)) onClose() },
          verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        CallRoomAvatar(phone.name, 80.dp, CallPresence.AVAILABLE, ring = CallColors.Popover)
        Text(phone.name, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        CallIcon("handoff", Color.White, 36.dp)
      }
    }
  }
}

/**
 * Mock scene `handoff`, the Portal frame: "Call moved to Sam’s phone", camera and mic off, with
 * "Move it back here" (asks the phone to hand it back; nothing opens until it does) and Done.
 */
@Composable
internal fun CallMovedScreen(ended: EndedCall, onDone: () -> Unit) {
  val context = LocalContext.current
  val layout = currentLayoutClass()
  val copy = CallingCopy.ended(ended, "", null)
  val canPull = ended.movedTo?.phone == true
  var asked by remember(ended.id) { mutableStateOf(false) }
  LaunchedEffect(ended.id) { delay(MOVED_AUTO_CLOSE_MS); onDone() }
  Box(Modifier.fillMaxSize()) {
    CallRingBackground(ended.movedTo?.name ?: ended.peer.name, motion = false)
    Column(Modifier.align(Alignment.Center).padding(horizontal = 40.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (layout.compact) 22.dp else 30.dp)) {
      Box(Modifier.size(if (layout.compact) 150.dp else 200.dp).background(CallColors.Glass, CircleShape), contentAlignment = Alignment.Center) {
        CallIcon("handoff", Color.White, if (layout.compact) 72.dp else 96.dp)
      }
      Text(copy.title, color = Color.White, fontSize = if (layout.compact) 48.sp else 60.sp, fontWeight = FontWeight.Bold,
          textAlign = TextAlign.Center)
      Text(copy.body, color = Color.White.copy(alpha = 0.75f), fontSize = if (layout.compact) 24.sp else 28.sp, textAlign = TextAlign.Center)
      Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        if (canPull) CallPill(if (asked) "Asking ${ended.movedTo?.name}…" else CallingCopy.MOVE_BACK,
            { if (Calling.pullBack(context)) asked = true else onDone() }, icon = "arrowL", style = CallButtonStyle.OK,
            size = if (layout.compact) CallButtonSize.MD else CallButtonSize.LG, enabled = !asked)
        CallPill("Done", onDone, style = CallButtonStyle.GLASS, size = if (layout.compact) CallButtonSize.MD else CallButtonSize.LG)
      }
    }
  }
}

private const val MOVE_CLOCK_MS = 2_000L
/** The moved screen goes back to Photos by itself after a while (the call carries on on the phone). */
private const val MOVED_AUTO_CLOSE_MS = 60_000L
