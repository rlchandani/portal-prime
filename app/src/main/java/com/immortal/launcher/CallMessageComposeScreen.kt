/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** `leaveScr`: self view as the preview, Record → Stop (ring fills to 2:00) → Record again / Watch it / Send. */
@Composable
internal fun CallMessageComposeScreen(c: CallMessageComposer, auto: Boolean) {
  val s = c.state
  val layout = currentLayoutClass()
  val r = CallsCompactLayout.ring(layout)
  val edge = if (layout.compact) CallsCompactLayout.edge(layout).dp else 40.dp
  BackHandler { c.dispatch(ComposeEvent.Discard) }
  if (auto) AutoRun(c)
  LaunchedEffect(s.phase) { if (s.phase == ComposePhase.SENT) { delay(SENT_CLOSE_MS); c.dispatch(ComposeEvent.Discard) } }
  val previewing = s.cameraOn && (s.phase == ComposePhase.READY || s.phase == ComposePhase.RECORDING)
  Box(Modifier.fillMaxSize()) {
    when {
      previewing -> CallMessagePreview(c.recorder, Modifier.fillMaxSize())
      s.phase == ComposePhase.REVIEW || s.phase == ComposePhase.FAILED || s.phase == ComposePhase.SENDING ->
        if (s.cameraOn) ReviewVideo(c) else CallRingBackground(c.peer.name, motion = false)
      s.phase == ComposePhase.SENT -> CallRingBackground(c.peer.name, motion = false)
      else -> CallShutter(Modifier.fillMaxSize(), compact = layout.compact)
    }
    if (!s.cameraOn && s.phase != ComposePhase.SENT) Box(Modifier.align(Alignment.Center).padding(top = 300.dp)) {
      CallGlassChip(CallMessageCopy.AUDIO_ONLY, "mic")
    }
    Box(Modifier.fillMaxWidth().height(220.dp).background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent))))
    TopRow(c, s, edge, layout)
    if (c.cameraProblem && previewing) Box(Modifier.align(Alignment.Center)) {
      CallChip("The camera is busy · you can still leave an audio message", "camera", CallChipTone.WARN)
    }
    Bottom(c, s, r.big.dp, Modifier.align(Alignment.BottomCenter).padding(bottom = if (layout.compact) 40.dp else 60.dp))
  }
}

@Composable
private fun BoxScope.TopRow(c: CallMessageComposer, s: ComposeState, edge: Dp, layout: LayoutClass) {
  Column(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(edge), verticalArrangement = Arrangement.spacedBy(20.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
      Box(Modifier.size(88.dp).background(Color(0x73000000), CircleShape).callClick("Close", true) { c.dispatch(ComposeEvent.Discard) },
          contentAlignment = Alignment.Center) { CallIcon("x", Color.White, 38.dp) }
      CallRoomAvatar(c.peer.name, 88.dp)
      Column(Modifier.weight(1f)) {
        Text(CallMessageCopy.title(c.peer.name), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        Text(CallMessageCopy.sub(s.phase, s.failure), color = Color.White.copy(alpha = 0.75f), fontSize = 24.sp, maxLines = 2,
            overflow = TextOverflow.Ellipsis)
      }
      if (s.phase == ComposePhase.RECORDING) RecChip()
      if (!layout.compactPortrait) CallGlassChip("End-to-end encrypted", "lock")
    }
    if (s.phase == ComposePhase.READY || s.phase == ComposePhase.RECORDING) {
      CallLiveBar(CallMessageCopy.liveBar(s.phase, s.cameraOn), live = s.cameraOn || s.phase == ComposePhase.RECORDING, motion = true)
    }
  }
}

@Composable
private fun RecChip() {
  Row(Modifier.height(64.dp).background(CallColors.GlassChip, RoundedCornerShape(50)).border(1.5.dp, CallColors.GlassChipEdge, RoundedCornerShape(50))
      .padding(horizontal = 26.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Box(Modifier.size(16.dp).background(CallColors.LiveRed, CircleShape))
    Text("REC", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
  }
}

@Composable
private fun Bottom(c: CallMessageComposer, s: ComposeState, big: Dp, modifier: Modifier) {
  val now = rememberCallClock()
  Box(modifier) {
    when (s.phase) {
      ComposePhase.READY -> Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(64.dp)) {
        CallSideToggle(if (s.cameraOn) "video" else "videoOff", if (s.cameraOn) "Camera on" else "Camera off", off = !s.cameraOn,
            inset = (big - 110.dp) / 2) { c.dispatch(ComposeEvent.ToggleCamera) }
        RecordButton(big, "Record") { c.dispatch(ComposeEvent.Record(Calling.now())) }
        Box(Modifier.size(110.dp))
      }
      ComposePhase.RECORDING -> StopButton(big, s.elapsed(now)) { c.dispatch(ComposeEvent.Stop(Calling.now())) }
      ComposePhase.REVIEW, ComposePhase.FAILED -> Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(48.dp)) {
        CallBigButton("redo", "Record again", CallColors.Glass, { c.dispatch(ComposeEvent.RecordAgain) }, size = big * 0.7f, iconSize = 48.dp)
        if (s.cameraOn) CallBigButton("play", "Watch it", CallColors.Glass, { c.watch() }, size = big * 0.7f, iconSize = 48.dp)
        CallBigButton("send", if (s.phase == ComposePhase.FAILED) "Try again" else "Send to ${c.peer.name}", CallColors.Ok,
            { c.dispatch(ComposeEvent.Send) }, size = big, iconSize = 64.dp)
      }
      ComposePhase.SENDING -> CallGlassChip("Sending to ${c.peer.name}…", "lock")
      ComposePhase.SENT -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        CallGlassChip(CallMessageCopy.sent(c.peer.name), "check")
        CallPill("Done", { c.dispatch(ComposeEvent.Discard) }, style = CallButtonStyle.GLASS, size = CallButtonSize.LG)
      }
    }
  }
}

/** White ring with a red dot: Record. */
@Composable
private fun RecordButton(size: Dp, label: String, onClick: () -> Unit) {
  Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
    Box(Modifier.size(size).background(Color.White, CircleShape).callClick(label, true, onClick), contentAlignment = Alignment.Center) {
      Box(Modifier.size(size * 0.4f).background(RecRed, CircleShape))
    }
    Text(label, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
  }
}

/** The ring fills toward 2:00 around a stop square. */
@Composable
private fun StopButton(size: Dp, elapsedMs: Long, onClick: () -> Unit) {
  val share = (elapsedMs.toFloat() / CallMessageLimits.MAX_DURATION_MS).coerceIn(0f, 1f)
  Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
    Box(Modifier.size(size).callClick("Stop recording", true, onClick).drawBehind {
      val w = size.toPx() * 0.06f
      drawCircle(Color.White.copy(alpha = 0.2f), radius = this.size.minDimension / 2 - w / 2, style = Stroke(w))
      drawArc(RecRed, -90f, 360f * share, false, Offset(w / 2, w / 2), Size(this.size.width - w, this.size.height - w), style = Stroke(w))
      drawCircle(Color(0xFF1A1B20), radius = this.size.minDimension / 2 - w * 1.6f)
    }, contentAlignment = Alignment.Center) {
      Box(Modifier.size(size * 0.32f).background(RecRed, RoundedCornerShape(size * 0.08f)))
    }
    Text(CallMessageCopy.stopLabel(elapsedMs), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
  }
}

/** The take, paused on its first frame until Watch it. Built once per review; released on leave. */
@Composable
private fun ReviewVideo(c: CallMessageComposer) {
  val context = LocalContext.current
  val player = remember(c.state.durationMs) { CallMessagePlayers.take(context, c.recorder.take) }
  DisposableEffect(player) {
    c.reviewPlayer = player
    onDispose { if (c.reviewPlayer === player) c.reviewPlayer = null; player.release() }
  }
  CallMessageVideo(player, Modifier.fillMaxSize())
}

/** Measurement builds: record 5 s of test frames, then send, so a night test needs no taps. */
@Composable
private fun AutoRun(c: CallMessageComposer) {
  var started by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) {
    if (!BuildConfig.PORTAL_CALL_LOOPBACK || started) return@LaunchedEffect
    started = true
    delay(AUTO_WAIT_MS); c.dispatch(ComposeEvent.Record(Calling.now()))
    delay(AUTO_RECORD_MS); c.dispatch(ComposeEvent.Stop(Calling.now()))
    while (c.state.phase == ComposePhase.RECORDING) delay(100)
    delay(AUTO_WAIT_MS); c.dispatch(ComposeEvent.Send)
  }
}

private val RecRed = Color(0xFFE5343F)
private const val SENT_CLOSE_MS = 2_500L
private const val AUTO_WAIT_MS = 1_500L
private const val AUTO_RECORD_MS = 5_000L
