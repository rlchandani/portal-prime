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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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
  Row(Modifier.fillMaxSize().padding(start = 30.dp, end = 30.dp, top = 4.dp, bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
    Column(Modifier.weight(MAIN_WEIGHT).fillMaxHeight().ccCard().padding(36.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(26.dp, Alignment.CenterVertically)) {
      val panel = s.alarm
      if (panel == null) NoPanel(s) else AlarmPanel(s, ui, panel)
    }
    CcSecuritySide(s, Modifier.weight(1f).fillMaxHeight())
  }
}

private const val MAIN_WEIGHT = 1.2f

@Composable
private fun NoPanel(s: CcUiState) {
  val c = LocalCc.current
  Ring(CcRingTone.OFF, null)
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(if (s.ready) "No alarm panel" else "Loading…", color = c.text, fontSize = 54.sp, fontFamily = CcStyle.Serif)
    Text(if (s.ready) "No alarm panel in Home Assistant. Add one (Manual alarm, Alarmo or your alarm's integration) to arm from here."
    else "Connecting to Home Assistant", color = c.sec, fontSize = 22.sp, textAlign = TextAlign.Center)
  }
}

@Composable
private fun AlarmPanel(s: CcUiState, ui: CcUi, panel: CcEntity) {
  val c = LocalCc.current
  val tone = CcSecurity.tone(panel.state)
  val delay = s.settings.exitDelaySec
  val clock = rememberSecondClock(tone == CcRingTone.ARMING)
  val remaining = CcSecurity.exitRemaining(panel.lastChanged, delay, clock)
  Ring(tone, if (tone == CcRingTone.ARMING) CcSecurity.exitFraction(remaining, delay) else null)
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(CcSecurity.title(panel), color = c.text, fontSize = 54.sp, fontFamily = CcStyle.Serif)
    Text(CcSecurity.subtitle(panel, remaining), color = c.sec, fontSize = 22.sp, textAlign = TextAlign.Center)
  }
  val current = CcSecurity.currentMode(panel)
  val enabled = s.connection.canControl && !panel.unavailable
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    CcAlarmMode.offered(panel).forEach { m ->
      ModeButton(m, on = m == current, enabled = enabled, modifier = Modifier.weight(1f)) {
        CcSecurity.tap(panel, m)?.let(ui::modal)
      }
    }
  }
  val reason = s.connection.disabledReason ?: if (panel.unavailable) "The alarm panel is unavailable in Home Assistant" else null
  reason?.let { Text(it, color = c.faint, fontSize = 19.sp) }
}

/** Wall-clock that ticks four times a second while [running] (the exit-delay text always updates). */
@Composable
private fun rememberSecondClock(running: Boolean): Long {
  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
  if (running) LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(CLOCK_TICK_MS) } }
  return now
}

private const val CLOCK_TICK_MS = 250L

/** `.shield`: 330 dp disc; armed has a soft ripple, arming an amber countdown arc. */
@Composable
private fun Ring(tone: CcRingTone, fraction: Float?) {
  val c = LocalCc.current
  val motion = LocalCcMotion.current
  val (bg, fg) = when (tone) {
    CcRingTone.OFF -> c.surface2 to c.sec
    CcRingTone.ARMED -> c.greenBg to c.green
    CcRingTone.ARMING -> c.amberBg to c.amber
    CcRingTone.ALERT -> c.redBg to c.red
  }
  val sweep by animateFloatAsState(fraction ?: 0f, if (motion) tween(ARC_MS, easing = LinearEasing) else snap(), label = "exit")
  Box(Modifier.size(RING_OUTER.dp), contentAlignment = Alignment.Center) {
    if (tone == CcRingTone.ARMED) Ripple(c.green, motion)
    Box(Modifier.size(RING.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
      CcIcon(when (tone) { CcRingTone.ARMED -> "shieldck"; CcRingTone.ALERT -> "warn"; else -> "shield" }, fg, 140.dp)
    }
    if (fraction != null) Canvas(Modifier.size(RING_OUTER.dp)) {
      val w = ARC_WIDTH * density
      drawArc(c.amber, -QUARTER, FULL * sweep, false, topLeft = Offset(w / 2, w / 2),
          size = Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round))
    }
  }
}

/** The armed ripple (`.shield.armed::after`): one ring, still while motion is off. */
@Composable
private fun Ripple(color: Color, motion: Boolean) {
  val t = if (motion) rememberInfiniteTransition(label = "ripple")
      .animateFloat(0f, 1f, infiniteRepeatable(tween(RIPPLE_MS, easing = LinearEasing), RepeatMode.Restart), label = "r").value else 0f
  Box(Modifier.size((RING + RIPPLE_INSET * 2).dp).scale(1f + t * RIPPLE_GROW).alpha(RIPPLE_ALPHA * (1f - t))
      .border(4.dp, color, CircleShape))
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
