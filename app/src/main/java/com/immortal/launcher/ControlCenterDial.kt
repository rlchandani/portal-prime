/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
internal fun CcThermostatDial(ui: CcUi, e: CcEntity, gate: CcGate, humidity: Int?) {
  val c = LocalCc.current
  val target = e.targetTemp
  var drag by remember(e.id) { mutableStateOf<Double?>(null) }
  var held by remember(e.id) { mutableStateOf<Double?>(null) }
  var throttle by remember(e.id) { mutableStateOf(CcThrottle()) }
  val entity by rememberUpdatedState(e)
  LaunchedEffect(held, target) {
    val h = held ?: return@LaunchedEffect
    if (h == target) held = null else { delay(DIAL_HOLD_MS); held = null }
  }
  fun valueAt(o: Offset, w: Float, h: Float): Double =
      CcControlMath.dialValue(CcControlMath.dialFraction(o.x - w / 2f, o.y - h / 2f), e.minTemp, e.maxTemp, e.tempStep)
  fun push(t: Double, final: Boolean) {
    val now = SystemClock.uptimeMillis()
    val key = Math.round(t * TENTHS).toInt()
    if (!throttle.shouldSend(now, key, final)) return
    ControlCenter.send(ui.context, CcCalls.temperature(entity, t), undoable = final)
    throttle = throttle.sent(now, key)
  }
  val shown = drag ?: held ?: target
  val canDrag = gate.enabled && target != null && e.state != "off"
  Box(Modifier.size(DIAL).alpha(if (e.state == "off") OFF_ALPHA else 1f)
      .semantics { contentDescription = "${e.name} target ${shown?.let { CcSheetLogic.temp(it) } ?: "not set"}" }
      .then(if (!canDrag) Modifier else Modifier
          .pointerInput(e.id) { detectTapGestures { o -> val t = valueAt(o, size.width.toFloat(), size.height.toFloat()); held = t; push(t, true) } }
          .pointerInput(e.id) {
            detectDragGestures(
                onDragStart = { o -> drag = valueAt(o, size.width.toFloat(), size.height.toFloat()) },
                onDragEnd = { drag?.let { held = it; push(it, true) }; drag = null },
                onDragCancel = { drag?.let { held = it; push(it, true) }; drag = null },
            ) { change, _ ->
              val t = valueAt(change.position, size.width.toFloat(), size.height.toFloat())
              drag = t; push(t, false); change.consume()
            }
          }),
      contentAlignment = Alignment.Center) {
    val trackColour = c.surface2
    val textColour = c.text
    Canvas(Modifier.fillMaxSize()) {
      val k = shown?.let { CcControlMath.dialFractionOf(it, e.minTemp, e.maxTemp) } ?: 0f
      val cur = e.currentTemp?.let { CcControlMath.dialFractionOf(it, e.minTemp, e.maxTemp) }
      drawDial(trackColour, textColour, k, cur, shown != null)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text(dialHeadline(e), color = c.sec, fontSize = 22.sp)
      Text(shown?.let { CcSheetLogic.temp(it) } ?: "—", color = c.text, fontSize = 120.sp, fontWeight = FontWeight.Light, lineHeight = 124.sp)
      val now = listOfNotNull(e.currentTemp?.let { "Now ${CcSheetLogic.temp(it)}" }, humidity?.let { "$it% humidity" }).joinToString(" · ")
      if (now.isNotEmpty()) Text(now, color = c.sec, fontSize = 22.sp)
    }
  }
  if (target == null && e.state != "off") CcCtlNote("This thermostat uses a range — set it in Home Assistant.", Modifier.fillMaxWidth().padding(top = 4.dp))
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
  else -> "Set to"
}

private fun DrawScope.drawDial(track: Color, ink: Color, k: Float, cur: Float?, hasTarget: Boolean) {
  val scale = size.minDimension / DIAL_VIEWBOX
  val r = DIAL_RADIUS * scale
  val stroke = ARC_STROKE * scale
  val topLeft = Offset(center.x - r, center.y - r)
  val arcSize = Size(r * 2, r * 2)
  drawArc(track, CcControlMath.DIAL_START_DEG, CcControlMath.DIAL_SPAN_DEG, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
  if (!hasTarget) return
  val sweep = (CcControlMath.DIAL_SPAN_DEG * k).coerceAtLeast(MIN_SWEEP)
  val brush = Brush.horizontalGradient(listOf(DIAL_COLD, DIAL_MID, DIAL_WARM), startX = center.x - r, endX = center.x + r)
  drawArc(brush, CcControlMath.DIAL_START_DEG, sweep, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
  cur?.let {
    val (x, y) = CcControlMath.dialPoint(it, r, center.x, center.y)
    drawCircle(ink, CUR_DOT * scale, Offset(x, y))
  }
  val (hx, hy) = CcControlMath.dialPoint(k, r, center.x, center.y)
  drawCircle(Color.White, KNOB * scale, Offset(hx, hy))
  drawCircle(Color.Black.copy(alpha = 0.15f), KNOB * scale, Offset(hx, hy), style = Stroke(2f * scale))
}

private val DIAL = 520.dp
private const val DIAL_VIEWBOX = 520f
private const val DIAL_RADIUS = 220f
private const val ARC_STROKE = 34f
private const val KNOB = 28f
private const val CUR_DOT = 7f
private const val MIN_SWEEP = 0.5f
private const val OFF_ALPHA = 0.5f
private const val TENTHS = 10
private const val DIAL_HOLD_MS = 3_000L
private val DIAL_COLD = Color(0xFF5AA9FF)
private val DIAL_MID = Color(0xFFF4D36B)
private val DIAL_WARM = Color(0xFFF08A4B)
