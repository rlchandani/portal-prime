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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
internal fun CcLightControl(ui: CcUi, e: CcEntity, gate: CcGate) {
  if (!e.dimmable) { CcSwitchControl(ui, e, gate); return }
  // Stable callbacks that always act on the latest entity (children skip when only e changed).
  val latest = rememberUpdatedState(e)
  val onPower = remember(ui) { { on: Boolean -> ui.act(latest.value, CcCalls.lightPower(latest.value, on)) } }
  val onKelvin = remember(ui) { { k: Int -> ui.act(latest.value, CcCalls.kelvin(latest.value, k)) } }
  val onColour = remember(ui) { { rgb: Int -> ui.act(latest.value, CcCalls.color(latest.value, rgb)) } }
  val whites = remember(e.minKelvin, e.maxKelvin) { CcControlMath.whites(e.minKelvin, e.maxKelvin) }
  Row(Modifier.fillMaxWidth().height(BAR_HEIGHT), horizontalArrangement = Arrangement.spacedBy(34.dp)) {
    BrightnessBar(ui, e, if (e.isOn) e.brightnessPct ?: FULL else 0, CcRooms.glowColor(e), gate.enabled)
    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
      OnCard(e.isOn, e.name, gate.enabled, onPower)
      if (e.supportsColorTemp && whites.isNotEmpty()) Whites(whites, CcControlMath.selectedWhite(e, whites), gate.enabled, onKelvin)
      if (e.supportsColor) Colours(CcControlMath.selectedColour(e), gate.enabled, onColour)
      Spacer(Modifier.weight(1f))
      CcCtlNote("Drag the bar — the room glows on the map behind as you go.")
    }
  }
}

/**
 * The big bar. The drag lives in snapshot state read only by the draw lambda (fill + grip) and by
 * the tiny [BarLabel], so dragging never recomposes the page.
 */
@Composable
private fun BrightnessBar(ui: CcUi, e: CcEntity, actual: Int, rgb: Int, enabled: Boolean) {
  val c = LocalCc.current
  val drag = remember(e.id) { mutableStateOf<Int?>(null) }
  val held = remember(e.id) { mutableStateOf<Int?>(null) }
  val throttle = remember(e.id) { mutableStateOf(CcThrottle()) }
  val entity = rememberUpdatedState(e)
  val actualState = rememberUpdatedState(actual)
  val shown = remember(e.id) { derivedStateOf { drag.value ?: held.value ?: actualState.value } }
  LaunchedEffect(held.value, actual) {
    val h = held.value ?: return@LaunchedEffect
    if (kotlin.math.abs(h - actual) <= MATCH_PCT) held.value = null else { delay(HOLD_MS); held.value = null }
  }
  val push = remember(e.id) {
    { p: Int, final: Boolean ->
      val now = SystemClock.uptimeMillis()
      if (throttle.value.shouldSend(now, p, final)) {
        ControlCenter.send(ui.context, CcCalls.brightness(entity.value, p), undoable = final)
        throttle.value = throttle.value.sent(now, p)
      }
    }
  }
  val colour = Color(0xFF000000.toInt() or rgb)
  val grip = Color.Black.copy(alpha = GRIP_ALPHA)
  Box(Modifier.width(240.dp).fillMaxHeight().alpha(if (enabled) 1f else CC_DISABLED_ALPHA)
      .clip(RoundedCornerShape(BAR_RADIUS)).background(c.surface2)
      .drawBehind { drawBar(shown.value, colour, grip) }
      .semantics { contentDescription = "${e.name} brightness $actual percent" }
      .then(if (!enabled) Modifier else Modifier
          .pointerInput(e.id) {
            detectTapGestures { o -> val p = CcControlMath.pctFromVertical(o.y, size.height.toFloat()); held.value = p; push(p, true) }
          }
          .pointerInput(e.id) {
            fun release() { drag.value?.let { held.value = it; push(it, true) }; drag.value = null }
            detectVerticalDragGestures(
                onDragStart = { o -> drag.value = CcControlMath.pctFromVertical(o.y, size.height.toFloat()) },
                onDragEnd = { release() },
                onDragCancel = { release() },
            ) { change, _ ->
              val p = CcControlMath.pctFromVertical(change.position.y, size.height.toFloat())
              if (p != drag.value) { drag.value = p; push(p, false) }
              change.consume()
            }
          })) {
    BarLabel(shown)
  }
}

/** Fill from the bottom plus the grip 20 dp below its top edge (no allocation per frame). */
private fun DrawScope.drawBar(pct: Int, colour: Color, grip: Color) {
  if (pct <= 0) return
  val fill = size.height * pct / FULL.toFloat()
  drawRect(colour, Offset(0f, size.height - fill), Size(size.width, fill))
  val gw = GRIP_W.toPx(); val gh = GRIP_H.toPx()
  drawRoundRect(grip, Offset((size.width - gw) / 2f, size.height - fill + GRIP_INSET.toPx() - gh), Size(gw, gh), CornerRadius(gh / 2f))
}

/** Only this text recomposes while dragging. */
@Composable
private fun BoxScope.BarLabel(shown: State<Int>) {
  val v = shown.value
  Text(if (v > 0) "$v%" else "Off", color = LocalCc.current.text, fontSize = 44.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
      modifier = Modifier.align(Alignment.TopCenter).padding(top = 30.dp))
}

@Composable
private fun OnCard(on: Boolean, name: String, enabled: Boolean, onPower: (Boolean) -> Unit) {
  val c = LocalCc.current
  Row(Modifier.fillMaxWidth().ccCard(CcStyle.Tile).padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(if (on) "On" else "Off", color = c.text, fontSize = 23.sp, modifier = Modifier.weight(1f))
    CcToggle(on, onPower, enabled = enabled, label = name)
  }
}

@Composable
private fun Whites(whites: List<CcControlMath.White>, selected: Int, enabled: Boolean, onKelvin: (Int) -> Unit) {
  val items = remember(whites) { whites.map { Color(0xFF000000.toInt() or CcRooms.kelvinToRgb(it.kelvin)) to it.label } }
  CcSectionLabel("Whites", Modifier.padding(top = 6.dp))
  SwatchRow(items, selected, enabled) { i -> onKelvin(whites[i].kelvin) }
}

@Composable
private fun Colours(selected: Int, enabled: Boolean, onColour: (Int) -> Unit) {
  CcSectionLabel("Colours", Modifier.padding(top = 6.dp))
  SwatchRow(COLOUR_ITEMS, selected, enabled) { i -> onColour(CcControlMath.COLOURS[i].first) }
}

private val COLOUR_ITEMS = CcControlMath.COLOURS.map { (rgb, name) -> Color(0xFF000000.toInt() or rgb) to name }

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
private val GRIP_W = 70.dp
private val GRIP_H = 8.dp
private const val GRIP_ALPHA = 0.3f
private const val FULL = 100
private const val MATCH_PCT = 3
/** How long the bar holds the released value while HA catches up. */
private const val HOLD_MS = 3_000L
