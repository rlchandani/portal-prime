/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import kotlinx.coroutines.delay

/** Which call screens Grandma mode replaces: incoming rings and the live call (never drop-ins). */
internal object GrandmaScreens {
  fun handles(call: ActiveCall): Boolean = call.kind == CallKind.CALL &&
      (if (call.phase.beforeAnswer) call.direction == CallDirection.INCOMING else true)
}

/** Grandma mode incoming or in call. */
@Composable
internal fun CallGrandma(call: ActiveCall, media: CallMedia?, motion: Boolean) {
  if (call.phase.beforeAnswer) CallGrandmaIncoming(call, motion) else CallGrandmaLive(call, media, motion)
}

/** Grandma mode sizes per window (pure, JVM-tested): everything big, nothing clipped on the Mini. */
internal object GrandmaLayout {
  data class Ring(val avatar: Int, val title: Float, val line: Float, val ringDp: Int, val barWidth: Int?)
  data class InCall(val self: CallsCompactLayout.Box, val stackButtons: Boolean, val edge: Int)

  fun ring(layout: LayoutClass): Ring = when (layout) {
    LayoutClass.LARGE -> Ring(300, 90f, 40f, 70, 1500)
    LayoutClass.COMPACT_PORTRAIT -> Ring(260, 64f, 32f, 56, null)
    LayoutClass.COMPACT_LANDSCAPE -> Ring(150, 56f, 30f, 52, 1100)
  }

  /** Upright Mini (800 wide) can't fit three 160 dp buttons with their words: Hang up gets its own row. */
  fun inCall(layout: LayoutClass): InCall = when (layout) {
    LayoutClass.LARGE -> InCall(CallsCompactLayout.Box(420, 280), stackButtons = false, edge = 40)
    LayoutClass.COMPACT_PORTRAIT -> InCall(CallsCompactLayout.selfView(layout), stackButtons = true, edge = 24)
    LayoutClass.COMPACT_LANDSCAPE -> InCall(CallsCompactLayout.selfView(layout), stackButtons = false, edge = 24)
  }
}

/**
 * Grandma mode incoming (mock `gmIncomingScr`): a big face, "Sam is calling", and either the
 * auto-answer countdown ring or nothing to work out, plus two 160 dp buttons. Always chimes, never
 * silent; the camera opens only when it answers.
 */
@Composable
internal fun CallGrandmaIncoming(call: ActiveCall, motion: Boolean) {
  val p = LocalApprovedPalette.current
  val layout = currentLayoutClass()
  val r = GrandmaLayout.ring(layout)
  val sizes = GrandmaSizes.of(layout)
  val (a1, _) = remember(call.peer.name) { CallColors.avatar(call.peer.name) }
  val bg = Brush.verticalGradient(listOf(lerpColor(p.background, a1, BG_TINT), p.background))
  val autoAt = call.autoAnswerAtMs
  Box(Modifier.fillMaxSize().background(bg).padding(sizes.edge), contentAlignment = Alignment.Center) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (layout.compactLandscape) 18.dp else 36.dp, Alignment.CenterVertically)) {
      CallRipple(r.avatar.dp, motion) { CallRoomAvatar(call.peer.name, r.avatar.dp) }
      Text(GrandmaCopy.calling(call.peer.name), color = p.text, fontSize = r.title.sp, fontWeight = FontWeight.Bold,
          letterSpacing = (-1).sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = (r.title * 1.05f).sp)
      if (autoAt != null) GrandmaCountdown(autoAt, r) else Text(CallingCopy.incomingSub(call.kind, call.video, call.peer.name),
          color = p.secondary, fontSize = r.line.sp, textAlign = TextAlign.Center)
      Row((r.barWidth?.let { Modifier.widthIn(max = it.dp) } ?: Modifier).fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(sizes.gap)) {
        GrandmaButton(GrandmaCopy.NOT_NOW, "x", p.surface, p.text, sizes, Calling::decline, Modifier.weight(1f), edge = p.outline)
        GrandmaButton(if (autoAt != null) "Answer now" else "Answer", "video", CallColors.Ok, Color.White, sizes,
            { Calling.accept(audioOnly = false) }, Modifier.weight(1f))
      }
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        CallIcon("camera", p.secondary, (r.line * 1.1f).dp)
        Text(GrandmaCopy.CAMERA_LIGHT, color = p.secondary, fontSize = (r.line * 0.78f).sp, textAlign = TextAlign.Center)
      }
    }
  }
}

@Composable
private fun GrandmaCountdown(autoAt: Long, r: GrandmaLayout.Ring) {
  val p = LocalApprovedPalette.current
  val now = rememberCallClock()
  val progress = GrandmaCopy.progress(autoAt, now)
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
    Canvas(Modifier.size(r.ringDp.dp)) {
      val stroke = size.minDimension * RING_STROKE
      val inset = stroke / 2
      val arc = Size(size.width - stroke, size.height - stroke)
      drawArc(p.divider, 0f, FULL_TURN, false, Offset(inset, inset), arc, style = Stroke(stroke))
      drawArc(CallColors.Ok, START_ANGLE, FULL_TURN * (1f - progress), false, Offset(inset, inset), arc,
          style = Stroke(stroke, cap = StrokeCap.Round))
    }
    Text(GrandmaCopy.countdown(GrandmaCopy.secondsLeft(autoAt, now)), color = p.secondary, fontSize = r.line.sp,
        fontWeight = FontWeight.SemiBold, maxLines = 2, textAlign = TextAlign.Center)
  }
}

/**
 * Grandma mode in a call (mock `gmIncallScr`): the other side full screen, the live bar, the self
 * view (always visible while the camera is on), and only Quieter, Hang up and Louder.
 */
@Composable
internal fun CallGrandmaLive(call: ActiveCall, media: CallMedia?, motion: Boolean) {
  val context = LocalContext.current
  val layout = currentLayoutClass()
  val g = GrandmaLayout.inCall(layout)
  val sizes = GrandmaSizes.of(layout)
  val volume = remember { CallVolume(context) }
  var level by remember { mutableIntStateOf(volume.level()) }
  var shownAt by remember { mutableStateOf(0L) }
  fun step(delta: Int) { level = volume.step(level, delta); shownAt = Calling.now() }
  Box(Modifier.fillMaxSize().background(CallColors.VideoBg)) {
    GrandmaRemote(call, media, motion)
    CallLiveBar(GrandmaCopy.liveBar(call.cameraOn, call.micOn, call.peer.name), CallingCopy.liveDotActive(call.cameraOn, call.micOn),
        motion, Modifier.align(Alignment.TopStart).padding(start = g.edge.dp, top = g.edge.dp,
            end = (g.edge * 2 + g.self.widthDp).dp))
    CallSelfFrame(media, call.cameraOn, "You", Modifier.align(Alignment.TopEnd).padding(g.edge.dp),
        width = g.self.widthDp.dp, height = g.self.heightDp.dp)
    if (call.phase == CallPhase.CONNECTING) Box(Modifier.align(Alignment.Center)) { CallGlassChip("Connecting securely…", "lock") }
    if (call.phase == CallPhase.RECONNECTING) Box(Modifier.align(Alignment.Center)) {
      CallChip("Reconnecting… the Wi-Fi dropped for a moment", "wifiOff", CallChipTone.WARN)
    }
    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(g.edge.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(sizes.gap)) {
      VolumeFlash(shownAt, CallVolumeMath.percent(level, volume.max))
      val quieter: @Composable (Modifier) -> Unit = { m ->
        GrandmaButton(GrandmaCopy.QUIETER, "volLow", CallColors.Controls, Color.White, sizes, { step(-1) }, m)
      }
      val louder: @Composable (Modifier) -> Unit = { m ->
        GrandmaButton(GrandmaCopy.LOUDER, "vol", CallColors.Controls, Color.White, sizes, { step(1) }, m)
      }
      val hangUp: @Composable (Modifier) -> Unit = { m ->
        GrandmaButton(GrandmaCopy.HANG_UP, "end", CallColors.Bad, Color.White, sizes, Calling::hangUp, m)
      }
      if (g.stackButtons) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(sizes.gap)) {
          quieter(Modifier.weight(1f)); louder(Modifier.weight(1f))
        }
        hangUp(Modifier.fillMaxWidth())
      } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(sizes.gap)) {
        quieter(Modifier.weight(1f)); hangUp(Modifier.weight(HANG_UP_WEIGHT)); louder(Modifier.weight(1f))
      }
    }
  }
}

/** "Volume 70 %" for a moment after Quieter or Louder, so the step is visible as well as audible. */
@Composable
private fun VolumeFlash(shownAt: Long, percent: Int) {
  var visible by remember { mutableStateOf(false) }
  LaunchedEffect(shownAt) {
    if (shownAt == 0L) return@LaunchedEffect
    visible = true
    delay(VOLUME_FLASH_MS)
    visible = false
  }
  Box(Modifier.height(64.dp)) { if (visible) CallGlassChip("Volume $percent%", if (percent == 0) "volLow" else "vol") }
}

@Composable
private fun GrandmaRemote(call: ActiveCall, media: CallMedia?, motion: Boolean) {
  var hasFrames by remember(media) { mutableStateOf(false) }
  LaunchedEffect(media) {
    while (media != null && !hasFrames) { hasFrames = media.remote.frames > 0; delay(FRAME_POLL_MS) }
  }
  if (media != null) CallRemoteVideo(media, Modifier.fillMaxSize())
  if (!hasFrames || !call.remoteCameraOn) Box(Modifier.fillMaxSize()) {
    CallRingBackground(call.peer.name, motion && call.phase != CallPhase.LIVE)
    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(26.dp)) {
      CallRoomAvatar(call.peer.name, AVATAR)
      if (call.phase == CallPhase.LIVE && !call.remoteCameraOn) CallGlassChip("${call.peer.name}’s camera is off", "videoOff")
    }
  }
}

private fun lerpColor(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f)

private const val BG_TINT = 0.3f
private const val RING_STROKE = 0.1f
private const val FULL_TURN = 360f
private const val START_ANGLE = -90f
private const val HANG_UP_WEIGHT = 1.6f
private const val VOLUME_FLASH_MS = 1_800L
private const val FRAME_POLL_MS = 150L
private val AVATAR: Dp = 260.dp
