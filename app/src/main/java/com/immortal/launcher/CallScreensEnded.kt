/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.media.AudioManager
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/** Ended and every "couldn't" outcome: honest title, what happened, one obvious next step. */
@Composable
internal fun CallEndedScreen(ended: EndedCall, motion: Boolean, onCallBack: (CallKind) -> Unit, onDone: () -> Unit) {
  val context = LocalContext.current
  val use24 = remember { runCatching { ImmortalSettings.use24HourClock(context) }.getOrDefault(false) }
  val time = remember(ended.endedMs) { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ended.endedMs)) }
  val copy = CallingCopy.ended(ended, time, ended.quietEndMin?.let { CallingCopy.minuteLabel(it, use24) })
  val normal = ended.reason in AUTO_CLOSE
  if (normal) LaunchedEffect(ended.id) { delay(CallTimings.ENDED_AUTO_CLOSE_MS); onDone() }
  val layout = currentLayoutClass()
  Box(Modifier.fillMaxSize()) {
    CallRingBackground(ended.peer.name, motion = false)
    if (layout.compact) { EndedCompact(ended, copy, normal, layout, onCallBack, onDone); return@Box }
    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(30.dp)) {
      Box(Modifier.size(150.dp).background(if (copy.warn) CallColors.WarnTint else CallColors.Glass, CircleShape),
          contentAlignment = Alignment.Center) { CallIcon(copy.icon, if (copy.warn) CallColors.WarnIcon else Color.White, 70.dp) }
      CallHeadline(copy.title, copy.body, titleSize = 60.sp)
      Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        when {
          ended.reason == CallEndReason.DROP_INS_OFF -> CallPill("Ring ${ended.peer.name}", { onCallBack(CallKind.CALL) },
              icon = "phone", style = CallButtonStyle.OK, size = CallButtonSize.LG)
          copy.retry -> CallPill(if (ended.connectedMs != null || ended.direction == CallDirection.INCOMING) "Call back" else "Try again",
              { onCallBack(CallKind.CALL) }, icon = "phone", style = CallButtonStyle.OK, size = CallButtonSize.LG)
        }
        if (canLeaveMessage(ended)) CallPill(CallMessageCopy.LEAVE, { leaveMessage(context, ended, onDone) }, icon = "msg",
            style = CallButtonStyle.GLASS, size = CallButtonSize.LG)
        CallPill(if (copy.retry || ended.reason == CallEndReason.DROP_INS_OFF) "Close" else "Done", onDone,
            style = CallButtonStyle.GLASS, size = CallButtonSize.LG)
      }
      if (normal) Text("Closing in 5 s", color = Color.White.copy(alpha = 0.5f), fontSize = 22.sp)
    }
  }
}

/** Compact ended: the same content, smaller icon and title, medium buttons, kept off the edges. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.EndedCompact(ended: EndedCall, copy: CallingCopy.Ended, normal: Boolean,
    layout: LayoutClass, onCallBack: (CallKind) -> Unit, onDone: () -> Unit) {
  val r = CallsCompactLayout.ring(layout)
  Column(Modifier.align(Alignment.Center).padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(r.gap.dp)) {
    Box(Modifier.size(ENDED_ICON_COMPACT).background(if (copy.warn) CallColors.WarnTint else CallColors.Glass, CircleShape),
        contentAlignment = Alignment.Center) { CallIcon(copy.icon, if (copy.warn) CallColors.WarnIcon else Color.White, 56.dp) }
    CallHeadline(copy.title, copy.body, titleSize = r.endedTitle.sp, subSize = r.sub.sp)
    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
      when {
        ended.reason == CallEndReason.DROP_INS_OFF -> CallPill("Ring ${ended.peer.name}", { onCallBack(CallKind.CALL) },
            icon = "phone", style = CallButtonStyle.OK)
        copy.retry -> CallPill(if (ended.connectedMs != null || ended.direction == CallDirection.INCOMING) "Call back" else "Try again",
            { onCallBack(CallKind.CALL) }, icon = "phone", style = CallButtonStyle.OK)
      }
      if (canLeaveMessage(ended)) {
        val context = LocalContext.current
        CallPill(if (layout.compactPortrait) "Message" else CallMessageCopy.LEAVE, { leaveMessage(context, ended, onDone) }, icon = "msg",
            style = CallButtonStyle.GLASS)
      }
      CallPill(if (copy.retry || ended.reason == CallEndReason.DROP_INS_OFF) "Close" else "Done", onDone, style = CallButtonStyle.GLASS)
    }
    if (normal) Text("Closing in 5 s", color = Color.White.copy(alpha = 0.5f), fontSize = 22.sp)
  }
}

/** Mock `noanswerScr` / `callQuietScr`: an outgoing call to a Portal that nobody took can leave a video message. */
internal fun canLeaveMessage(ended: EndedCall): Boolean =
    ended.direction == CallDirection.OUTGOING && ended.kind == CallKind.CALL && ended.reason in MESSAGE_REASONS &&
        CallMessages.canSend(ended.peer)

private fun leaveMessage(context: android.content.Context, ended: EndedCall, onDone: () -> Unit) {
  onDone()
  CallMessages.compose(context, ended.peer)
}

private val MESSAGE_REASONS = setOf(CallEndReason.NO_ANSWER, CallEndReason.DECLINED, CallEndReason.QUIET, CallEndReason.BUSY,
    CallEndReason.MISSED)

/** Volume popover above the controls: slider, Quieter / Louder (±10 %), the echo note. */
@Composable
internal fun CallVolumePopover(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val audio = remember { context.getSystemService(AudioManager::class.java) }
  val max = remember { audio?.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)?.coerceAtLeast(1) ?: 1 }
  var level by remember { mutableIntStateOf(audio?.getStreamVolume(AudioManager.STREAM_VOICE_CALL) ?: 0) }
  fun step(delta: Int) {
    val stepUnits = maxOf(1, Math.round(max * VOLUME_STEP))
    level = (level + delta * stepUnits).coerceIn(0, max)
    runCatching { audio?.setStreamVolume(AudioManager.STREAM_VOICE_CALL, level, 0) }
  }
  val pct = level * PERCENT / max
  // Compact: never wider than the window (the caller insets it); the designed 620 when it fits.
  val width = if (currentLayoutClass().compact) Modifier.widthIn(max = 620.dp).fillMaxWidth() else Modifier.width(620.dp)
  Column(modifier.then(width).callPanel(CallColors.Popover, RoundedCornerShape(36.dp)).padding(32.dp),
      verticalArrangement = Arrangement.spacedBy(22.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
      CallIcon("volLow", Color.White, 32.dp)
      Box(Modifier.weight(1f).height(20.dp).background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(10.dp))) {
        Box(Modifier.fillMaxWidth(pct / PERCENT.toFloat()).height(20.dp).background(Color.White, RoundedCornerShape(10.dp)))
      }
      CallIcon("vol", Color.White, 32.dp)
      Text("$pct%", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      VolumeStep("Quieter", Modifier.weight(1f)) { step(-1) }
      VolumeStep("Louder", Modifier.weight(1f)) { step(1) }
    }
    Text(CallingCopy.VOLUME_NOTE, color = Color.White.copy(alpha = 0.6f), fontSize = 21.sp, textAlign = TextAlign.Start)
  }
}

@Composable
private fun VolumeStep(label: String, modifier: Modifier, onClick: () -> Unit) {
  Box(modifier.height(76.dp).background(CallColors.Glass, RoundedCornerShape(24.dp)).callClick(label, true, onClick),
      contentAlignment = Alignment.Center) { Text(label, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.SemiBold) }
}

private val AUTO_CLOSE = setOf(CallEndReason.HUNG_UP, CallEndReason.LEFT, CallEndReason.CANCELLED, CallEndReason.DECLINED,
    CallEndReason.CAP_REACHED, CallEndReason.MISSED)
private const val VOLUME_STEP = 0.1f
private val ENDED_ICON_COMPACT = 120.dp
private const val PERCENT = 100
