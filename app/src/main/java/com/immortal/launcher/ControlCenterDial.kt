/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.os.SystemClock
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * The thermostat dial (`ctl-thermo`): a 270° arc in the mock's blue → amber → orange gradient
 * with a white knob you drag (sent throttled, final on release), the current temperature dot,
 * "Heating to 70°" / "Now 69° · 44% humidity", ± buttons around the modes.
 */
@Composable
internal fun CcThermostatDial(ui: CcUi, e: CcEntity, gate: CcGate, humidity: Int?, fahrenheit: Boolean) {
  val c = LocalCc.current
  val target = e.targetTemp
  val drag = remember(e.id) { mutableStateOf<Double?>(null) }
  val held = remember(e.id) { mutableStateOf<Double?>(null) }
  val throttle = remember(e.id) { mutableStateOf(CcThrottle()) }
  val entity = rememberUpdatedState(e)
  val range = rememberUpdatedState(CcControlMath.tempRange(e, fahrenheit))
  val targetState = rememberUpdatedState(target)
  val shown = remember(e.id) { derivedStateOf { drag.value ?: held.value ?: targetState.value } }
  LaunchedEffect(held.value, target) {
    val h = held.value ?: return@LaunchedEffect
    if (h == target) held.value = null else { delay(DIAL_HOLD_MS); held.value = null }
  }
  val push = remember(e.id) {
    { t: Double, final: Boolean ->
      val now = SystemClock.uptimeMillis()
      val key = Math.round(t * TENTHS).toInt()
      if (throttle.value.shouldSend(now, key, final)) {
        ControlCenter.send(ui.context, CcCalls.temperature(entity.value, t), undoable = final)
        throttle.value = throttle.value.sent(now, key)
      }
    }
  }
  fun valueAt(o: Offset, w: Float, h: Float): Double {
    val (lo, hi) = range.value
    return CcControlMath.dialValue(CcControlMath.dialFraction(o.x - w / 2f, o.y - h / 2f), lo, hi, entity.value.tempStep)
  }
  val canDrag = gate.enabled && target != null && e.state != "off"
  val track = c.surface2
  val ink = c.text
  val (min, max) = range.value
  val current = e.currentTemp?.let { CcControlMath.dialFractionOf(it, min, max) }
  Box(Modifier.size(DIAL).alpha(if (e.state == "off") OFF_ALPHA else 1f)
      .semantics { contentDescription = "${e.name} target ${target?.let { CcSheetLogic.temp(it) } ?: "not set"}" }
      .drawWithCache {
        val g = DialGeometry(size)
        onDrawBehind {
          val v = shown.value
          drawDial(g, track, ink, v?.let { CcControlMath.dialFractionOf(it, min, max) } ?: 0f, current, v != null)
        }
      }
      .then(if (!canDrag) Modifier else Modifier
          .pointerInput(e.id) { detectTapGestures { o -> val t = valueAt(o, size.width.toFloat(), size.height.toFloat()); held.value = t; push(t, true) } }
          .pointerInput(e.id) {
            fun release() { drag.value?.let { held.value = it; push(it, true) }; drag.value = null }
            detectDragGestures(
                onDragStart = { o -> drag.value = valueAt(o, size.width.toFloat(), size.height.toFloat()) },
                onDragEnd = { release() },
                onDragCancel = { release() },
            ) { change, _ ->
              val t = valueAt(change.position, size.width.toFloat(), size.height.toFloat())
              if (t != drag.value) { drag.value = t; push(t, false) }
              change.consume()
            }
          }),
      contentAlignment = Alignment.Center) {
    val now = listOfNotNull(e.currentTemp?.let { "Now ${CcSheetLogic.temp(it)}" }, humidity?.let { "$it% humidity" }).joinToString(" · ")
    DialLabel(shown, dialHeadline(e), now)
  }
  if (target == null && e.state != "off") CcCtlNote("This thermostat uses a range — set it in Home Assistant.", Modifier.fillMaxWidth().padding(top = 4.dp))
}

/** The centre text; only the big number recomposes while the knob moves. */
@Composable
private fun DialLabel(shown: State<Double?>, headline: String, now: String) {
  val c = LocalCc.current
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(headline, color = c.sec, fontSize = 22.sp)
    DialNumber(shown)
    if (now.isNotEmpty()) Text(now, color = c.sec, fontSize = 22.sp)
  }
}

@Composable
private fun DialNumber(shown: State<Double?>) {
  Text(shown.value?.let { CcSheetLogic.temp(it) } ?: "—", color = LocalCc.current.text, fontSize = 120.sp, fontWeight = FontWeight.Light, lineHeight = 124.sp)
}

/** ± round buttons around the modes segment (`.seg` from hvac_modes). */
@Composable
internal fun CcThermostatModes(ui: CcUi, e: CcEntity, gate: CcGate) {
  val target = e.targetTemp
  val step = e.tempStep.takeIf { it > 0 } ?: 1.0
  val modes = e.hvacModes
  Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
      verticalAlignment = Alignment.CenterVertically) {
    if (target != null) CcRoundButton("minus", { ui.act(e, CcCalls.temperature(e, target - step)) }, "Lower ${e.name}", size = 72,
        enabled = gate.enabled && e.state != "off")
    if (modes.isNotEmpty()) Box(Modifier.alpha(if (gate.enabled) 1f else CC_DISABLED_ALPHA)) {
      CcSeg(modes.map { Triple(it, CcSheetLogic.hvacLabel(it), null) }, e.state, { m -> if (gate.enabled) ui.act(e, CcCalls.hvacMode(e, m)) })
    }
    if (target != null) CcRoundButton("plus", { ui.act(e, CcCalls.temperature(e, target + step)) }, "Raise ${e.name}", size = 72,
        enabled = gate.enabled && e.state != "off")
  }
}

internal fun dialHeadline(e: CcEntity): String = when {
  e.state == "off" -> "Off"
  e.hvacAction == "heating" -> "Heating to"
  e.hvacAction == "cooling" -> "Cooling to"
  e.hvacAction == null && e.state == "heat" -> "Heating to"
  e.hvacAction == null && e.state == "cool" -> "Cooling to"
  else -> "Set to"
}

/** Everything the dial draws that depends only on its size (built once per size, not per frame). */
private class DialGeometry(size: Size) {
  val scale = size.minDimension / DIAL_VIEWBOX
  val r = DIAL_RADIUS * scale
  val cx = size.width / 2f
  val cy = size.height / 2f
  val topLeft = Offset(cx - r, cy - r)
  val arcSize = Size(r * 2, r * 2)
  val arcStroke = Stroke(ARC_STROKE * scale, cap = StrokeCap.Round)
  val ringStroke = Stroke(KNOB_RING * scale)
  val brush = Brush.horizontalGradient(listOf(DIAL_COLD, DIAL_MID, DIAL_WARM), startX = cx - r, endX = cx + r)
}

private fun DrawScope.drawDial(g: DialGeometry, track: Color, ink: Color, k: Float, cur: Float?, hasTarget: Boolean) {
  drawArc(track, CcControlMath.DIAL_START_DEG, CcControlMath.DIAL_SPAN_DEG, false, g.topLeft, g.arcSize, style = g.arcStroke)
  if (!hasTarget) return
  val sweep = (CcControlMath.DIAL_SPAN_DEG * k).coerceAtLeast(MIN_SWEEP)
  drawArc(g.brush, CcControlMath.DIAL_START_DEG, sweep, false, g.topLeft, g.arcSize, style = g.arcStroke)
  cur?.let { drawCircle(ink, CUR_DOT * g.scale, dialAt(g, it)) }
  val knob = dialAt(g, k)
  drawCircle(Color.White, KNOB * g.scale, knob)
  drawCircle(KNOB_EDGE, KNOB * g.scale, knob, style = g.ringStroke)
}

private fun dialAt(g: DialGeometry, f: Float): Offset {
  val a = Math.toRadians((CcControlMath.DIAL_START_DEG + CcControlMath.DIAL_SPAN_DEG * f.coerceIn(0f, 1f)).toDouble())
  return Offset(g.cx + g.r * kotlin.math.cos(a).toFloat(), g.cy + g.r * kotlin.math.sin(a).toFloat())
}

private val DIAL = 520.dp
private const val DIAL_VIEWBOX = 520f
private const val DIAL_RADIUS = 220f
private const val ARC_STROKE = 34f
private const val KNOB = 28f
private const val CUR_DOT = 7f
private const val KNOB_RING = 2f
private val KNOB_EDGE = Color(0x26000000)
private const val MIN_SWEEP = 0.5f
private const val OFF_ALPHA = 0.5f
private const val TENTHS = 10
private const val DIAL_HOLD_MS = 3_000L
private val DIAL_COLD = Color(0xFF5AA9FF)
private val DIAL_MID = Color(0xFFF4D36B)
private val DIAL_WARM = Color(0xFFF08A4B)
