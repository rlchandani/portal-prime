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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import org.webrtc.RendererCommon
import kotlinx.coroutines.delay

/** In a call (connecting, live, reconnecting): remote video, live bar, self view, controls. */
@Composable
internal fun CallLiveScreen(call: ActiveCall, media: CallMedia?, motion: Boolean, toast: CallToastState) {
  var volumeOpen by remember { mutableStateOf(false) }
  var addOpen by remember(call.id) { mutableStateOf(false) }
  var moveOpen by remember(call.id) { mutableStateOf(false) }
  var busyDismissed by remember(call.id) { mutableStateOf(false) }
  val dropIn = call.kind == CallKind.DROP_IN
  CallToggleToasts(call, toast)
  LaunchedEffect(call.notice) { call.notice?.let { toast.show(CallAddPeople.notice(it), "users") } }
  val layout = currentLayoutClass()
  val add = CallAddControl.of(call, LocalContext.current) { addOpen = true }
  val move = rememberMoveControl(call, toast) { moveOpen = true }
  val probe by CallMediaHub.probeOn.collectAsState()
  Box(Modifier.fillMaxSize()) {
    if (call.group || probe) {
      CallGroupLive(call, media, motion, layout, volumeOpen, onVolume = { volumeOpen = !volumeOpen }, add = add, toast = toast, probe = probe,
          move = move)
    } else {
      CallLiveSolo(call, media, motion, toast, layout, volumeOpen, { volumeOpen = !volumeOpen }, busyDismissed, { busyDismissed = true }, add,
          move)
    }
    CallMovingBanner(call, layout.compact)
    if (addOpen) CallAddPersonSheet(call, layout) { addOpen = false }
    if (moveOpen) CallMovePicker(call, layout) { moveOpen = false }
  }
}

/** The 1:1 call (phase 1 layout), now with Add person. */
@Composable
private fun CallLiveSolo(call: ActiveCall, media: CallMedia?, motion: Boolean, toast: CallToastState, layout: LayoutClass,
                         volumeOpen: Boolean, onVolume: () -> Unit, busyDismissed: Boolean, onBusyDone: () -> Unit, add: CallAddControl,
                         move: CallMoveAction) {
  val dropIn = call.kind == CallKind.DROP_IN
  if (layout.compact) {
    // The Mini, either way up (rotation stays in this branch, so the remote surface is kept).
    CallLiveCompact(call, media, motion, layout, volumeOpen, onVolume = onVolume,
        busy = call.cameraIssue == CameraIssue.BUSY && !busyDismissed, onBusyDone = onBusyDone, toast = toast, add = add, move = move)
    return
  }
  Box(Modifier.fillMaxSize()) {
    RemoteStage(call, media, motion)
    if (dropIn) DropInBar(call, Modifier.align(Alignment.TopCenter))
    else {
      CallLiveBar(CallingCopy.liveBar(call.cameraOn, call.micOn), CallingCopy.liveDotActive(call.cameraOn, call.micOn), motion,
          Modifier.align(Alignment.TopStart).padding(40.dp))
      CallTitle(call, Modifier.align(Alignment.TopCenter).padding(top = 40.dp))
    }
    CallSelfFrame(media, call.cameraOn, CallingCopy.SELF_LABEL,
        Modifier.align(Alignment.TopEnd).padding(top = if (dropIn) 150.dp else 40.dp, end = 40.dp))
    ConnectionChip(call, Modifier.align(Alignment.Center))
    if (volumeOpen) CallVolumePopover(Modifier.align(Alignment.BottomCenter).padding(bottom = 210.dp))
    CallControls(call, volumeOpen, onVolume = onVolume,
        onFraming = { toast.show("Portal frames you automatically", "follow") }, add = add, move = move,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp))
    if (call.cameraIssue == CameraIssue.BUSY && !busyDismissed) CameraBusySheet(onAudio = onBusyDone,
        onRetry = { onBusyDone(); Calling.toggleCamera() }, modifier = Modifier.align(Alignment.Center))
  }
}

/**
 * Compact call screen. Upright (800 wide): live bar and title stacked top-left beside a 9:16 self
 * view, smaller controls (≈624 dp), the drop-in bar on two rows, and the remote picture
 * balanced-cropped (a landscape room keeps ≥ 56 % of its width). Turned (1280×740): the Portal+
 * arrangement with tighter insets, a 300×200 self view and the same smaller controls, so
 * nothing overlaps vertically.
 */
@Composable
private fun CallLiveCompact(call: ActiveCall, media: CallMedia?, motion: Boolean, layout: LayoutClass, volumeOpen: Boolean,
                            onVolume: () -> Unit, busy: Boolean, onBusyDone: () -> Unit, toast: CallToastState, add: CallAddControl,
                            move: CallMoveAction) {
  val dropIn = call.kind == CallKind.DROP_IN
  val portrait = layout.compactPortrait
  val edge = CallsCompactLayout.edge(layout).dp
  val self = CallsCompactLayout.selfView(layout)
  val ctl = CallsCompactLayout.controls(layout)
  Box(Modifier.fillMaxSize()) {
    RemoteStage(call, media, motion, avatar = CallsCompactLayout.remoteAvatar(layout).dp,
        scaling = if (portrait) RendererCommon.ScalingType.SCALE_ASPECT_BALANCED else RendererCommon.ScalingType.SCALE_ASPECT_FILL)
    val liveBar = CallingCopy.liveBar(call.cameraOn, call.micOn)
    val liveDot = CallingCopy.liveDotActive(call.cameraOn, call.micOn)
    when {
      dropIn -> CompactDropInBar(call, portrait, Modifier.align(Alignment.TopCenter))
      portrait -> Column(Modifier.align(Alignment.TopStart).padding(start = edge, top = edge, end = edge + self.widthDp.dp + SELF_GAP),
          verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CallLiveBar(liveBar, liveDot, motion)
        CallTitle(call, Modifier)
      }
      else -> {
        CallLiveBar(liveBar, liveDot, motion, Modifier.align(Alignment.TopStart).padding(edge))
        CallTitle(call, Modifier.align(Alignment.TopCenter).padding(top = edge))
      }
    }
    val selfTop = if (dropIn) (if (portrait) DROP_BAR_TALL else DROP_BAR) + edge else edge
    CallSelfFrame(media, call.cameraOn, CallingCopy.SELF_LABEL, Modifier.align(Alignment.TopEnd).padding(top = selfTop, end = edge),
        width = self.widthDp.dp, height = self.heightDp.dp)
    ConnectionChip(call, Modifier.align(Alignment.Center), top = CallsCompactLayout.connectionChipOffset(layout).dp)
    if (volumeOpen) CallVolumePopover(Modifier.align(Alignment.BottomCenter)
        .padding(start = edge, end = edge, bottom = (ctl.bottom + ctl.height).dp + POPOVER_GAP))
    CompactCallControls(call, ctl, volumeOpen, onVolume, onFraming = { toast.show("Portal frames you automatically", "follow") },
        add = add, move = move, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = ctl.bottom.dp))
    if (busy) CameraBusySheet(onAudio = onBusyDone, onRetry = { onBusyDone(); Calling.toggleCamera() },
        modifier = Modifier.align(Alignment.Center).padding(horizontal = edge), layout = layout)
  }
}

/** Compact `.ctl`: the same five controls at [CallsCompactLayout.controls] size. */
@Composable
internal fun CompactCallControls(call: ActiveCall, ctl: CallsCompactLayout.Controls, volumeOpen: Boolean, onVolume: () -> Unit,
                                onFraming: () -> Unit, add: CallAddControl, move: CallMoveAction, modifier: Modifier) {
  Row(modifier.background(CallColors.Controls, RoundedCornerShape(52.dp)).padding(start = ctl.padding.dp, end = ctl.padding.dp, top = 20.dp, bottom = 16.dp),
      verticalAlignment = Alignment.Top) {
    CompactControl(ctl, if (call.micOn) "mic" else "micOff", if (call.micOn) "Mute" else "Unmute", ControlLook.of(off = !call.micOn)) { Calling.toggleMic() }
    CompactControl(ctl, if (call.cameraOn) "video" else "videoOff", if (call.cameraOn) "Camera off" else "Camera on",
        ControlLook.of(off = !call.cameraOn)) { Calling.toggleCamera() }
    // The Mini's strip has five slots: once a paired phone is known, "To phone" takes Auto frame's (an info-only toast).
    if (move.known) CompactControl(ctl, "handoff", move.label, move.look, move.onClick)
    else CompactControl(ctl, "follow", "Auto frame", if (call.cameraOn) ControlLook.ON else ControlLook.DISABLED, onFraming)
    CompactControl(ctl, "vol", "Volume", if (volumeOpen) ControlLook.ON else ControlLook.PLAIN, onVolume)
    CompactControl(ctl, "addp", add.label, add.look, add.onClick)
    Column(Modifier.width(ctl.endColumn.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Box(Modifier.width(ctl.endWidth.dp).height(ctl.endHeight.dp).background(CallColors.Bad, RoundedCornerShape(50))
          .callClick("End call", true, Calling::hangUp), contentAlignment = Alignment.Center) { CallIcon("end", Color.White, ctl.endIcon.dp) }
      Text("End", color = Color.White, fontSize = ctl.label.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
    }
  }
}

@Composable
private fun CompactControl(ctl: CallsCompactLayout.Controls, icon: String, label: String, look: ControlLook, onClick: () -> Unit) {
  val fill = when (look) { ControlLook.OFF -> Color.White; ControlLook.ON -> CallColors.EmberOn; else -> CallColors.ControlFill }
  val tint = when (look) { ControlLook.OFF -> CallColors.OffIcon; ControlLook.ON -> CallColors.EmberOnIcon; else -> Color.White }
  Column(Modifier.width(ctl.column.dp).alpha(if (look == ControlLook.DISABLED || look == ControlLook.DIMMED) 0.38f else 1f), horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Box(Modifier.size(ctl.circle.dp).background(fill, CircleShape).callClick(label, look != ControlLook.DISABLED, onClick),
        contentAlignment = Alignment.Center) { CallIcon(icon, tint, ctl.icon.dp) }
    Text(label, color = Color.White, fontSize = ctl.label.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 1,
        overflow = TextOverflow.Ellipsis)
  }
}

/** Compact drop-in bar. Upright: the state line and E2E on top, the timer, Keep going and End below. */
@Composable
private fun CompactDropInBar(call: ActiveCall, portrait: Boolean, modifier: Modifier) {
  val line = CallingCopy.dropInBar(call.peer.name, call.direction == CallDirection.INCOMING, call.cameraOn, call.micOn)
  val brush = Brush.verticalGradient(listOf(CallColors.DropTop, CallColors.DropBottom))
  if (!portrait) {
    Row(modifier.fillMaxWidth().height(DROP_BAR).background(brush).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp)) {
      Box(Modifier.size(18.dp).background(Color.White, CircleShape))
      Text(line, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false))
      DropInE2E()
      Box(Modifier.weight(1f)) { DropInTimer(call) }
      DropInActions()
    }
    return
  }
  Column(modifier.fillMaxWidth().height(DROP_BAR_TALL).background(brush).padding(horizontal = 24.dp, vertical = 14.dp),
      verticalArrangement = Arrangement.SpaceBetween) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      Box(Modifier.size(18.dp).background(Color.White, CircleShape))
      Text(line, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f))
      DropInE2E()
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      Box(Modifier.weight(1f)) { DropInTimer(call) }
      DropInActions()
    }
  }
}

@Composable
private fun DropInE2E() {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    CallIcon("lock", Color.White, 24.dp)
    Text(CallingCopy.E2E, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
  }
}

@Composable
private fun DropInTimer(call: ActiveCall) {
  val cap = call.capAtMs
  val start = call.connectedMs
  if (cap != null && start != null) Box(Modifier.alpha(0.75f)) {
    CallTimer(call) { elapsed -> "$elapsed of ${CallingCopy.clock(cap - start)}" }
  }
}

@Composable
private fun DropInActions() {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    CallPill("Keep going", Calling::keepGoing, style = CallButtonStyle.GLASS, size = CallButtonSize.SM)
    Box(Modifier.height(72.dp).background(Color.White, RoundedCornerShape(50)).callClick("End", true, Calling::hangUp)
        .padding(horizontal = 34.dp), contentAlignment = Alignment.Center) {
      Text("End", color = CallColors.DropBottom, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    }
  }
}

@Composable
private fun RemoteStage(call: ActiveCall, media: CallMedia?, motion: Boolean, avatar: Dp = 260.dp,
                        scaling: RendererCommon.ScalingType = RendererCommon.ScalingType.SCALE_ASPECT_FILL) {
  // The remote SurfaceView stays composed for the whole call (no surface churn); the camera-off
  // card simply covers it.
  val sink = media?.sink(call.peer.deviceId)
  var hasFrames by remember(sink) { mutableStateOf(false) }
  LaunchedEffect(sink) {
    while (sink != null && !hasFrames) { hasFrames = sink.frames > 0; delay(FRAME_POLL_MS) }
  }
  if (media != null && sink != null) CallRemoteVideo(media, Modifier.fillMaxSize(), scaling, sink)
  if (!hasFrames || !call.remoteCameraOn) {
    Box(Modifier.fillMaxSize()) {
      CallRingBackground(call.peer.name, motion && call.phase != CallPhase.LIVE)
      Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(26.dp)) {
        CallRoomAvatar(call.peer.name, avatar)
        if (call.phase == CallPhase.LIVE && !call.remoteCameraOn) {
          CallGlassChip("${call.peer.name}’s camera is off", "videoOff")
        }
      }
    }
  }
}

@Composable
private fun CallTitle(call: ActiveCall, modifier: Modifier) {
  Column(modifier.background(CallColors.Bar, RoundedCornerShape(36.dp)).padding(horizontal = 30.dp, vertical = 12.dp),
      horizontalAlignment = Alignment.CenterHorizontally) {
    Text(call.peer.name, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
    CallTimer(call) { elapsed -> "$elapsed · ${if (call.remoteMicOn) "Portal in this home" else "${call.peer.name} is muted"}" }
  }
}

@Composable
private fun CallTimer(call: ActiveCall, format: (String) -> String) {
  val now = rememberCallClock(TIMER_STEP_MS)
  val elapsed = call.connectedMs?.let { now - it } ?: 0L
  Text(format(CallingCopy.clock(elapsed)), color = Color.White.copy(alpha = 0.75f), fontSize = 24.sp)
}

/** Drop-in live bar: red, full width, "Drop-in with X · Camera on · Mic on", "2:14 of 15:00", Keep going, End. */
@Composable
private fun DropInBar(call: ActiveCall, modifier: Modifier) {
  Row(modifier.fillMaxWidth().height(110.dp).background(Brush.verticalGradient(listOf(CallColors.DropTop, CallColors.DropBottom)))
      .padding(horizontal = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
    Box(Modifier.size(18.dp).background(Color.White, CircleShape))
    Text(CallingCopy.dropInBar(call.peer.name, call.direction == CallDirection.INCOMING, call.cameraOn, call.micOn),
        color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      CallIcon("lock", Color.White, 24.dp)
      Text(CallingCopy.E2E, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
    }
    Box(Modifier.weight(1f)) {
      val cap = call.capAtMs
      val start = call.connectedMs
      if (cap != null && start != null) Box(Modifier.alpha(0.75f)) {
        CallTimer(call) { elapsed -> "$elapsed of ${CallingCopy.clock(cap - start)}" }
      }
    }
    CallPill("Keep going", Calling::keepGoing, style = CallButtonStyle.GLASS, size = CallButtonSize.SM)
    Box(Modifier.height(72.dp).background(Color.White, RoundedCornerShape(50)).callClick("End", true, Calling::hangUp)
        .padding(horizontal = 34.dp), contentAlignment = Alignment.Center) {
      Text("End", color = CallColors.DropBottom, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
  }
}

@Composable
private fun ConnectionChip(call: ActiveCall, modifier: Modifier, top: Dp = 360.dp) {
  when (call.phase) {
    CallPhase.CONNECTING -> Box(modifier.padding(top = top)) { CallGlassChip("Connecting securely…", "lock") }
    CallPhase.RECONNECTING -> Box(modifier.padding(top = top)) {
      CallChip("Reconnecting… the Wi-Fi dropped for a moment", "wifiOff", CallChipTone.WARN)
    }
    else -> Unit
  }
}

/** Toasts for each toggle, as in the mock ("Muted · they can’t hear you", "Camera off · shutter closed"). */
@Composable
private fun CallToggleToasts(call: ActiveCall, toast: CallToastState) {
  var mic by remember(call.id) { mutableStateOf(call.micOn) }
  var camera by remember(call.id) { mutableStateOf(call.cameraOn) }
  LaunchedEffect(call.micOn) {
    if (call.micOn != mic) toast.show(if (call.micOn) "Mic on" else "Muted · they can’t hear you", if (call.micOn) "mic" else "micOff")
    mic = call.micOn
  }
  LaunchedEffect(call.cameraOn, call.cameraIssue) {
    if (call.cameraOn != camera) toast.show(when {
      call.cameraOn -> "Camera on"
      call.cameraIssue == CameraIssue.SHUTTER -> "Camera off · shutter closed. Audio carries on"
      call.cameraIssue == CameraIssue.PERMISSION -> "Camera not allowed · audio only"
      call.cameraIssue == CameraIssue.BUSY -> "The camera is busy · audio carries on"
      else -> "Camera off · audio carries on"
    }, if (call.cameraOn) "video" else "videoOff")
    camera = call.cameraOn
  }
}

/** `.ctl`: Mute, Camera, Auto frame, Volume, End. */
@Composable
internal fun CallControls(call: ActiveCall, volumeOpen: Boolean, onVolume: () -> Unit, onFraming: () -> Unit, add: CallAddControl,
                          move: CallMoveAction, modifier: Modifier) {
  Row(modifier.background(CallColors.Controls, RoundedCornerShape(56.dp)).padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 16.dp),
      verticalAlignment = Alignment.Top) {
    CallControl(if (call.micOn) "mic" else "micOff", if (call.micOn) "Mute" else "Unmute", ControlLook.of(off = !call.micOn)) { Calling.toggleMic() }
    CallControl(if (call.cameraOn) "video" else "videoOff", if (call.cameraOn) "Camera off" else "Camera on",
        ControlLook.of(off = !call.cameraOn)) { Calling.toggleCamera() }
    CallControl("follow", "Auto frame", if (call.cameraOn) ControlLook.ON else ControlLook.DISABLED, onFraming)
    CallControl("vol", "Volume", if (volumeOpen) ControlLook.ON else ControlLook.PLAIN, onVolume)
    CallControl("addp", add.label, add.look, add.onClick)
    // Mock: "To phone" sits just before End (dimmed when no phone can take it; a tap says why).
    if (move.known) CallControl("handoff", move.label, move.look, move.onClick)
    Column(Modifier.width(170.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Box(Modifier.width(150.dp).height(96.dp).background(CallColors.Bad, RoundedCornerShape(50.dp)).callClick("End call", true, Calling::hangUp),
          contentAlignment = Alignment.Center) { CallIcon("end", Color.White, 46.dp) }
      Text("End", color = Color.White, fontSize = 19.5.sp, fontWeight = FontWeight.SemiBold)
    }
  }
}

/** [DIMMED]: looks disabled but a tap still explains why (e.g. "To phone" with no phone ready). */
internal enum class ControlLook { PLAIN, OFF, ON, DISABLED, DIMMED;
  companion object { fun of(off: Boolean) = if (off) OFF else PLAIN }
}

@Composable
private fun CallControl(icon: String, label: String, look: ControlLook, onClick: () -> Unit) {
  val fill = when (look) { ControlLook.OFF -> Color.White; ControlLook.ON -> CallColors.EmberOn; else -> CallColors.ControlFill }
  val tint = when (look) { ControlLook.OFF -> CallColors.OffIcon; ControlLook.ON -> CallColors.EmberOnIcon; else -> Color.White }
  Column(Modifier.width(134.dp).alpha(if (look == ControlLook.DISABLED || look == ControlLook.DIMMED) 0.38f else 1f), horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Box(Modifier.size(96.dp).background(fill, CircleShape).callClick(label, look != ControlLook.DISABLED, onClick),
        contentAlignment = Alignment.Center) { CallIcon(icon, tint, 42.dp) }
    Text(label, color = Color.White, fontSize = 19.5.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 1)
  }
}

/** "The camera is busy": honest, with audio carrying on. */
@Composable
private fun CameraBusySheet(onAudio: () -> Unit, onRetry: () -> Unit, modifier: Modifier, layout: LayoutClass = LayoutClass.LARGE) {
  // Compact: as wide as the window allows (at most the designed 980); upright the two choices stack.
  val width = if (layout.compact) Modifier.widthIn(max = 980.dp).fillMaxWidth() else Modifier.width(980.dp)
  Column(modifier.then(width).callPanel(CallColors.Popover, RoundedCornerShape(40.dp), CallColors.GlassEdge).padding(48.dp),
      horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
    Box(Modifier.size(110.dp).background(CallColors.WarnTint, CircleShape), contentAlignment = Alignment.Center) {
      CallIcon("camera", CallColors.WarnIcon, 54.dp)
    }
    Text("The camera is busy", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
    Text("Portal’s built-in camera service is holding the camera right now. You can keep talking with audio, " +
        "and turn the camera on when it frees up.", color = Color.White.copy(alpha = 0.75f), fontSize = 26.sp, textAlign = TextAlign.Center)
    if (layout.compactPortrait) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
      CallPill("Carry on with audio", onAudio, Modifier.fillMaxWidth(), icon = "phone", style = CallButtonStyle.PRIMARY)
      CallPill("Try the camera again", onRetry, Modifier.fillMaxWidth(), icon = "redo")
    } else Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
      CallPill("Carry on with audio", onAudio, icon = "phone", style = CallButtonStyle.PRIMARY)
      CallPill("Try the camera again", onRetry, icon = "redo")
    }
  }
}

private const val FRAME_POLL_MS = 150L
/** Compact drop-in bar heights: one row (turned), two rows (upright). */
private val DROP_BAR = 110.dp
private val DROP_BAR_TALL = 172.dp
private val SELF_GAP = 16.dp
private val POPOVER_GAP = 24.dp
private const val TIMER_STEP_MS = 1_000L
