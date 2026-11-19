/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Outgoing: own preview under a scrim (or the ring background), ripple avatar, Cancel. */
@Composable
internal fun CallOutgoingScreen(call: ActiveCall, media: CallMedia?, motion: Boolean) {
  val ask = call.kind == CallKind.DROP_IN && call.phase != CallPhase.COUNTDOWN
  Box(Modifier.fillMaxSize()) {
    if (media != null && call.cameraOn) {
      // The preview *is* the self view while it rings: visible, receiving frames (camera rule).
      CallSelfVideo(media, Modifier.fillMaxSize())
      Box(Modifier.fillMaxSize().background(CallColors.Scrim))
    } else CallRingBackground(call.peer.name, motion)
    OutgoingLiveBar(call, motion, Modifier.align(Alignment.TopStart).padding(40.dp))
    Column(Modifier.align(Alignment.Center).padding(bottom = 140.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(34.dp)) {
      CallRipple(260.dp, motion) { CallRoomAvatar(call.peer.name, 260.dp) }
      CallHeadline(CallingCopy.outgoingTitle(call.kind, ask, call.peer.name), CallingCopy.outgoingSub(call))
      Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (call.kind == CallKind.DROP_IN) {
          CallGlassChip("Chime first, always", "bell")
          CallGlassChip("This home only · Wi-Fi", "wifi")
          CallGlassChip("Ends after 15 min", "clock")
        } else {
          CallGlassChip(CallingCopy.ENCRYPTED, "lock")
          CallGlassChip(CallingCopy.DIRECT, "house")
        }
      }
    }
    Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp), verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(80.dp)) {
      CallSideToggle(if (call.cameraOn) "video" else "videoOff", if (call.cameraOn) "Camera off" else "Camera on",
          off = !call.cameraOn, enabled = call.video && !ask) { Calling.toggleCamera() }
      CallBigButton("end", "Cancel", CallColors.Bad, onClick = Calling::hangUp)
      CallSideToggle(if (call.micOn) "mic" else "micOff", if (call.micOn) "Mute" else "Unmute", off = !call.micOn) { Calling.toggleMic() }
    }
  }
}

@Composable
private fun OutgoingLiveBar(call: ActiveCall, motion: Boolean, modifier: Modifier) {
  val now = if (call.phase == CallPhase.COUNTDOWN) rememberCallClock() else call.phaseSinceMs
  val left = ((CallTimings.DROP_IN_COUNTDOWN_MS - (now - call.phaseSinceMs)) / MS_PER_S + 1).coerceIn(0, COUNTDOWN_S)
  CallLiveBar(CallingCopy.outgoingLiveBar(call, left.toInt()), live = call.cameraOn, motion = motion, modifier = modifier,
      e2e = call.phase != CallPhase.DIALING)
}

/** 110 dp side toggles beside Cancel (`.ctl`-style labels under the circle). */
@Composable
internal fun CallSideToggle(icon: String, label: String, off: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
  Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp),
      modifier = Modifier.padding(top = 33.dp)) {
    Box(Modifier.size(110.dp).background(if (off) Color.White else CallColors.ControlFill, CircleShape)
        .callClick(label, enabled, onClick), contentAlignment = Alignment.Center) {
      CallIcon(icon, if (off) CallColors.OffIcon else Color.White.copy(alpha = if (enabled) 1f else 0.38f), 46.dp)
    }
    Text(label, color = Color.White.copy(alpha = if (enabled) 1f else 0.38f), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
  }
}

/** Incoming: full screen over anything. Nothing opens until Answer. */
@Composable
internal fun CallIncomingScreen(call: ActiveCall, motion: Boolean) {
  val context = LocalContext.current
  Box(Modifier.fillMaxSize()) {
    CallRingBackground(call.peer.name, motion)
    Row(Modifier.align(Alignment.TopCenter).padding(top = 40.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      CallGlassChip("Ringing here · ${Calling.selfName(context)}", "bell")
      if (call.silent) CallGlassChip("Quiet hours · ringing silently", "moon")
    }
    Column(Modifier.align(Alignment.Center).padding(bottom = 200.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(30.dp)) {
      CallRipple(280.dp, motion) { CallRoomAvatar(call.peer.name, 280.dp) }
      CallHeadline(call.peer.name, CallingCopy.incomingSub(call.kind, call.video, call.peer.name), titleSize = 84.sp)
      CallGlassChip(CallingCopy.ANSWER_PRIVACY, "lock")
    }
    Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(30.dp)) {
      Row(horizontalArrangement = Arrangement.spacedBy(120.dp)) {
        CallBigButton("end", "Decline", CallColors.Bad, onClick = Calling::decline)
        CallBigButton(if (call.video) "video" else "phone", "Answer", CallColors.Ok, onClick = { Calling.accept(audioOnly = false) },
            nudge = motion)
      }
      if (call.video) CallPill("Answer with audio only", { Calling.accept(audioOnly = true) }, icon = "phone",
          style = CallButtonStyle.GLASS, size = CallButtonSize.SM)
    }
  }
}

/**
 * The receiving side of a drop-in: chime already played, banner at the top. Chime-then-join
 * counts down 3 s and joins by itself; Ask waits for Join. "Not now" always wins.
 */
@Composable
internal fun CallDropInIncoming(call: ActiveCall, motion: Boolean) {
  val ask = call.phase == CallPhase.RINGING
  var shown by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) { shown = true }
  Box(Modifier.fillMaxSize()) {
    CallRingBackground(call.peer.name, motion)
    AnimatedVisibility(shown, Modifier.align(Alignment.TopCenter).padding(top = 60.dp),
        enter = slideInVertically(spring(Spring.DampingRatioLowBouncy)) { -it } + fadeIn()) {
      Row(Modifier.width(1320.dp).callPanel(Color(0xF21B1D22), RoundedCornerShape(44.dp), CallColors.GlassEdge)
          .padding(horizontal = 40.dp, vertical = 36.dp), verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(34.dp)) {
        CallRipple(140.dp, motion) { CallRoomAvatar(call.peer.name, 140.dp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(CallingCopy.dropInBannerTitle(ask, call.peer.name), color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
          DropInCountdownLine(call, ask)
        }
        CallPill("Not now", Calling::decline, style = CallButtonStyle.BAD)
        CallPill(if (ask) "Join" else "Join now", { Calling.accept(audioOnly = false) }, style = CallButtonStyle.OK)
      }
    }
    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
      Spacer(Modifier.height(120.dp))
      CallGlassChip(if (ask) "Nothing opens unless you say yes" else "Never silent · always a chime and this banner", "shield")
      Text("Drop-ins work only between rooms in this home, never from phones or in quiet hours.",
          color = Color.White.copy(alpha = 0.6f), fontSize = 24.sp, textAlign = TextAlign.Center)
    }
  }
}

@Composable
private fun DropInCountdownLine(call: ActiveCall, ask: Boolean) {
  val now = if (ask) call.phaseSinceMs else rememberCallClock()
  val left = ((CallTimings.DROP_IN_COUNTDOWN_MS - (now - call.phaseSinceMs)) / MS_PER_S + 1).coerceIn(0, COUNTDOWN_S).toInt()
  Text(CallingCopy.dropInBannerSub(ask, left), color = Color.White.copy(alpha = 0.78f), fontSize = 27.sp)
}

private const val MS_PER_S = 1_000L
private const val COUNTDOWN_S = 3L
