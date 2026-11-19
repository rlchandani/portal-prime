/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Security (`security`, `security-arming`): the alarm ring and modes left; who's home and the checklist right. */
@Composable
internal fun CcSecurityScreen(s: CcUiState, ui: CcUi) {
  val panel = remember(s.entitiesVersion, s.settings) { s.alarm }
  val side = remember(s.entitiesVersion, s.settings, s.registry, s.plan, s.connection.canControl) {
    CcSecurity.side(s.people, s.entities.values, ::ccTime, s.alarm).copy(
        sections = CcSecurityList.sections(s.entities.values, s.settings) { id -> s.registry?.areaName(id) ?: s.plan?.roomOf(id)?.name },
        canControl = s.connection.canControl, reason = s.connection.disabledReason)
  }
  val reason = s.connection.disabledReason
  Row(Modifier.fillMaxSize().padding(start = 30.dp, end = 30.dp, top = 4.dp, bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
    Column(Modifier.weight(MAIN_WEIGHT).fillMaxHeight().ccCard().padding(36.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(26.dp, Alignment.CenterVertically)) {
      if (panel == null) NoPanel(s.ready) else AlarmPanel(panel, s.settings.exitDelaySec, reason, ui)
    }
    CcSecuritySide(side, Modifier.weight(1f).fillMaxHeight(), ui)
  }
}

private const val MAIN_WEIGHT = 1.2f

@Composable
private fun NoPanel(ready: Boolean) {
  val c = LocalCc.current
  Ring(CcRingTone.OFF, 0L, 0)
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(if (ready) "No alarm panel" else "Loading…", color = c.text, fontSize = 54.sp, fontFamily = CcStyle.Serif)
    Text(if (ready) "No alarm panel in Home Assistant. Add one (Manual alarm, Alarmo or your alarm's integration) to arm from here."
    else "Connecting to Home Assistant", color = c.sec, fontSize = 22.sp, textAlign = TextAlign.Center)
  }
}

@Composable
private fun AlarmPanel(panel: CcEntity, delaySec: Int, connectionReason: String?, ui: CcUi) {
  val c = LocalCc.current
  val tone = CcSecurity.tone(panel.state)
  Ring(tone, panel.lastChanged, delaySec)
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(CcSecurity.title(panel), color = c.text, fontSize = 54.sp, fontFamily = CcStyle.Serif)
    if (tone == CcRingTone.ARMING) ArmingSubtitle(panel, delaySec)
    else Text(CcSecurity.subtitle(panel, 0), color = c.sec, fontSize = 22.sp, textAlign = TextAlign.Center)
  }
  val current = CcSecurity.currentMode(panel)
  val enabled = connectionReason == null && !panel.unavailable
  val modes = remember(panel.supportedFeatures) { CcAlarmMode.offered(panel) }
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    modes.forEach { m ->
      val tap = remember(panel, m, ui) { { CcSecurity.tap(panel, m)?.let(ui::modal); Unit } }
      ModeButton(m, on = m == current, enabled = enabled, modifier = Modifier.weight(1f), onClick = tap)
    }
  }
  val reason = connectionReason ?: if (panel.unavailable) "The alarm panel is unavailable in Home Assistant" else null
  reason?.let { Text(it, color = c.faint, fontSize = 19.sp) }
}

/** "Leave now · 21s to go": its own scope, so the 4 Hz clock recomposes only this line (once a second). */
@Composable
private fun ArmingSubtitle(panel: CcEntity, delaySec: Int) {
  val c = LocalCc.current
  val remaining by rememberExitRemaining(panel.lastChanged, delaySec)
  Text(CcSecurity.subtitle(panel, remaining), color = c.sec, fontSize = 22.sp, textAlign = TextAlign.Center)
}

/** Seconds of exit delay left; polled 4×/s, but the State only changes when the second does. */
@Composable
private fun rememberExitRemaining(since: Long, delaySec: Int): State<Int> =
    produceState(CcSecurity.exitRemaining(since, delaySec, System.currentTimeMillis()), since, delaySec) {
      while (true) { value = CcSecurity.exitRemaining(since, delaySec, System.currentTimeMillis()); delay(CLOCK_TICK_MS) }
    }

private const val CLOCK_TICK_MS = 250L

/** `.shield`: 330 dp disc; armed has a soft ripple, arming an amber countdown arc. */
@Composable
private fun Ring(tone: CcRingTone, armingSince: Long, delaySec: Int) {
  val c = LocalCc.current
  val motion = LocalCcMotion.current
  val (bg, fg) = when (tone) {
    CcRingTone.OFF -> c.surface2 to c.sec
    CcRingTone.ARMED -> c.greenBg to c.green
    CcRingTone.ARMING -> c.amberBg to c.amber
    CcRingTone.ALERT -> c.redBg to c.red
  }
  Box(Modifier.size(RING_OUTER.dp), contentAlignment = Alignment.Center) {
    if (tone == CcRingTone.ARMED) Ripple(c.green, motion)
    Box(Modifier.size(RING.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
      CcIcon(when (tone) { CcRingTone.ARMED -> "shieldck"; CcRingTone.ALERT -> "warn"; else -> "shield" }, fg, 140.dp)
    }
    if (tone == CcRingTone.ARMING) CountdownArc(armingSince, delaySec, c.amber, motion)
  }
}

/** The exit-delay arc; the animated sweep is read only while drawing. */
@Composable
private fun CountdownArc(since: Long, delaySec: Int, color: Color, motion: Boolean) {
  val remaining by rememberExitRemaining(since, delaySec)
  val sweep = animateFloatAsState(CcSecurity.exitFraction(remaining, delaySec), if (motion) tween(ARC_MS, easing = LinearEasing) else snap(), label = "exit")
  Canvas(Modifier.size(RING_OUTER.dp).graphicsLayer()) {
    val w = ARC_WIDTH * density
    drawArc(color, -QUARTER, FULL * sweep.value, false, topLeft = Offset(w / 2, w / 2),
        size = Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round))
  }
}

/** The armed ripple (`.shield.armed::after`): one ring animated in its layer, still while motion is off. */
@Composable
private fun Ripple(color: Color, motion: Boolean) {
  val t = if (motion) rememberInfiniteTransition(label = "ripple")
      .animateFloat(0f, 1f, infiniteRepeatable(tween(RIPPLE_MS, easing = LinearEasing), RepeatMode.Restart), label = "r") else null
  Box(Modifier.size((RING + RIPPLE_INSET * 2).dp).graphicsLayer {
    val v = t?.value ?: 0f
    scaleX = 1f + v * RIPPLE_GROW; scaleY = scaleX; alpha = RIPPLE_ALPHA * (1f - v)
  }.border(4.dp, color, CircleShape))
}

/** `.mode`: 140 tall, icon over label; the current mode is inverted. */
@Composable
private fun ModeButton(m: CcAlarmMode, on: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
  val c = LocalCc.current
  val fg = if (on) c.bg else c.text
  Column(modifier.height(140.dp).alpha(if (enabled) 1f else CC_DISABLED_ALPHA).clip(RoundedCornerShape(28.dp))
      .background(if (on) c.text else c.surface2).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
      .semantics { contentDescription = "${CcSecurity.modeWord(m)}${if (on) ", current" else ""}" },
      horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
    CcIcon(CcSecurity.modeIcon(m), fg, 42.dp)
    Text(CcSecurity.modeWord(m), color = fg, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
  }
}

private const val RING = 330
private const val RING_OUTER = 362
private const val ARC_WIDTH = 10f
private const val ARC_MS = 1000
private const val QUARTER = 90f
private const val FULL = 360f
private const val RIPPLE_MS = 2600
private const val RIPPLE_INSET = 14
private const val RIPPLE_GROW = 0.08f
private const val RIPPLE_ALPHA = 0.4f
