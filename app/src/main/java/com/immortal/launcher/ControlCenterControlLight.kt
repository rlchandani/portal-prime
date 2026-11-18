/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Light page (`ctl-light`): one huge bar you drag from across the room (throttled sends while
 * dragging, the final value on release), the On card, and white/colour swatches the bulb can
 * really make. On/off-only lights get the big power button instead.
 */
@Composable
internal fun CcLightControl(s: CcUiState, ui: CcUi, e: CcEntity, gate: CcGate) {
  if (!e.dimmable) { CcSwitchControl(ui, e, gate); return }
  Row(Modifier.fillMaxWidth().height(BAR_HEIGHT), horizontalArrangement = Arrangement.spacedBy(34.dp)) {
    BrightnessBar(ui, e, gate)
    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
      OnCard(ui, e, gate)
      if (e.supportsColorTemp) Whites(ui, e, gate)
      if (e.supportsColor) Colours(ui, e, gate)
      Spacer(Modifier.weight(1f))
      CcCtlNote("Drag the bar — the room glows on the map behind as you go.")
    }
  }
}

@Composable
private fun BrightnessBar(ui: CcUi, e: CcEntity, gate: CcGate) {
  val c = LocalCc.current
  val actual = if (e.isOn) e.brightnessPct ?: FULL else 0
  var drag by remember(e.id) { mutableStateOf<Int?>(null) }
  var held by remember(e.id) { mutableStateOf<Int?>(null) }
  var throttle by remember(e.id) { mutableStateOf(CcThrottle()) }
  val entity by rememberUpdatedState(e)
  LaunchedEffect(held, actual) {
    val h = held ?: return@LaunchedEffect
    if (kotlin.math.abs(h - actual) <= MATCH_PCT) held = null else { delay(HOLD_MS); held = null }
  }
  fun push(p: Int, final: Boolean) {
    val now = SystemClock.uptimeMillis()
    if (!throttle.shouldSend(now, p, final)) return
    ControlCenter.send(ui.context, CcCalls.brightness(entity, p), undoable = final)
    throttle = throttle.sent(now, p)
  }
  val shown = drag ?: held ?: actual
  val colour = Color(0xFF000000.toInt() or CcRooms.glowColor(e))
  BoxWithConstraints(Modifier.width(240.dp).fillMaxHeight().alpha(if (gate.enabled) 1f else CC_DISABLED_ALPHA)
      .clip(RoundedCornerShape(BAR_RADIUS)).background(c.surface2)
      .semantics { contentDescription = "${e.name} brightness $shown percent" }
      .then(if (!gate.enabled) Modifier else Modifier
          .pointerInput(e.id) {
            detectTapGestures { o -> val p = CcControlMath.pctFromVertical(o.y, size.height.toFloat()); held = p; push(p, true) }
          }
          .pointerInput(e.id) {
            detectVerticalDragGestures(
                onDragStart = { o -> drag = CcControlMath.pctFromVertical(o.y, size.height.toFloat()) },
                onDragEnd = { drag?.let { held = it; push(it, true) }; drag = null },
                onDragCancel = { drag?.let { held = it; push(it, true) }; drag = null },
            ) { change, _ ->
              val p = CcControlMath.pctFromVertical(change.position.y, size.height.toFloat())
              drag = p; push(p, false); change.consume()
            }
          })) {
    val fill = maxHeight * (shown / FULL.toFloat())
    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(fill).background(colour))
    if (shown > 0) Box(Modifier.align(Alignment.BottomCenter).offset(y = -(fill - GRIP_INSET)).size(70.dp, 8.dp).clip(CcStyle.Pill)
        .background(Color.Black.copy(alpha = 0.3f)))
    Text(if (shown > 0) "$shown%" else "Off", color = c.text, fontSize = 44.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 30.dp))
  }
}

@Composable
private fun OnCard(ui: CcUi, e: CcEntity, gate: CcGate) {
  val c = LocalCc.current
  Row(Modifier.fillMaxWidth().ccCard(CcStyle.Tile).padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(if (e.isOn) "On" else "Off", color = c.text, fontSize = 23.sp, modifier = Modifier.weight(1f))
    CcToggle(e.isOn, { on -> ui.act(e, CcCalls.lightPower(e, on)) }, enabled = gate.enabled, label = e.name)
  }
}

@Composable
private fun Whites(ui: CcUi, e: CcEntity, gate: CcGate) {
  val whites = remember(e.minKelvin, e.maxKelvin) { CcControlMath.whites(e.minKelvin, e.maxKelvin) }
  if (whites.isEmpty()) return
  val selected = CcControlMath.selectedWhite(e, whites)
  CcSectionLabel("Whites", Modifier.padding(top = 6.dp))
  SwatchRow(whites.map { Color(0xFF000000.toInt() or CcRooms.kelvinToRgb(it.kelvin)) to it.label }, selected, gate.enabled) { i ->
    ui.act(e, CcCalls.kelvin(e, whites[i].kelvin))
  }
}

@Composable
private fun Colours(ui: CcUi, e: CcEntity, gate: CcGate) {
  CcSectionLabel("Colours", Modifier.padding(top = 6.dp))
  SwatchRow(CcControlMath.COLOURS.map { (rgb, name) -> Color(0xFF000000.toInt() or rgb) to name }, CcControlMath.selectedColour(e), gate.enabled) { i ->
    ui.act(e, CcCalls.color(e, CcControlMath.COLOURS[i].first))
  }
}

/** `.swatches`: five round swatches, the selected one ringed (`.sw.on`). */
@Composable
private fun SwatchRow(items: List<Pair<Color, String>>, selected: Int, enabled: Boolean, onPick: (Int) -> Unit) {
  val c = LocalCc.current
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    items.forEachIndexed { i, (colour, name) ->
      val on = i == selected
      Box(Modifier.weight(1f).aspectRatio(1f).alpha(if (enabled) 1f else CC_DISABLED_ALPHA)
          .then(if (on) Modifier.border(4.dp, c.text, CircleShape).padding(9.dp) else Modifier)
          .clip(CircleShape).background(colour).border(2.dp, Color.Black.copy(alpha = 0.08f), CircleShape)
          .clickable(enabled = enabled, role = Role.RadioButton) { onPick(i) }
          .semantics { contentDescription = if (on) "$name, selected" else name })
    }
  }
}

private val BAR_HEIGHT = 660.dp
private val BAR_RADIUS = 112.dp
private val GRIP_INSET = 20.dp
private const val FULL = 100
private const val MATCH_PCT = 3
/** How long the bar holds the released value while HA catches up. */
private const val HOLD_MS = 3_000L
