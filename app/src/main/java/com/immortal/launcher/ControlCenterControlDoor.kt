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
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Lock page (`ctl-lock`): big state, who & when, Lock now (at once) / Unlock… (asks first). */
@Composable
internal fun CcLockControl(s: CcUiState, ui: CcUi, e: CcEntity, gate: CcGate) {
  val c = LocalCc.current
  val locked = e.state == "locked" || e.state == "locking"
  val jammed = e.state == "jammed"
  Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
    when {
      jammed -> BigState("warn", c.redBg, c.red)
      locked -> BigState("lock", c.greenBg, c.green)
      else -> BigState("unlock", c.amberBg, c.amber)
    }
    Text(CcSheetLogic.lockText(e.state), color = if (jammed) c.red else c.text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
    Text(lockWhoWhen(s, e), color = c.sec, fontSize = 21.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp, bottom = 22.dp))
    if (locked && !jammed) CcButton("Unlock…", { ui.act(e, CcCalls.unlock(e)) }, icon = "unlock", style = CcButtonStyle.PRIMARY, enabled = gate.enabled)
    else CcButton("Lock now", { ui.act(e, CcCalls.lock(e)) }, icon = "lock", style = CcButtonStyle.PRIMARY, enabled = gate.enabled)
  }
  if (s.settings.askUnlock) CcCtlNote("Unlocking always asks first on this Portal.", Modifier.padding(top = 24.dp))
}

/** "Locked by Sam · 7:31 pm" (HA's changed_by or the person behind the change). */
internal fun lockWhoWhen(s: CcUiState, e: CcEntity): String {
  val who = CcSheetLogic.who(s, e)
  val verb = CcSheetLogic.lockText(e.state)
  val parts = listOfNotNull(who?.let { "$verb by $it" } ?: verb.takeIf { e.lastChanged > 0 }, e.lastChanged.takeIf { it > 0 }?.let { ccTime(it) })
  return parts.joinToString(" · ").ifEmpty { "Home Assistant hasn't said who changed it" }
}

/** Garage page (`ctl-garage`): door picture, state + since, Close…/Open… (camera confirm), Stop. */
@Composable
internal fun CcGarageControl(s: CcUiState, ui: CcUi, e: CcEntity, gate: CcGate) {
  val c = LocalCc.current
  val camera = CcSheetLogic.garageCamera(s, e)
  val moving = e.state == "opening" || e.state == "closing"
  val isCover = e.domain == "cover"
  if (camera != null) CcCameraTile(s, camera, live = true, modifier = Modifier.fillMaxWidth().height(330.dp).clip(RoundedCornerShape(30.dp)), label = "Garage")
  else GarageDoor(e)
  Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    Column(Modifier.weight(1f)) {
      Text(CcSheetLogic.garageText(e, s.now), color = c.text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
      Text(garageSince(s, e), color = c.sec, fontSize = 20.sp)
    }
    when {
      !isCover -> {}
      moving -> CcButton("Stop", { ui.act(e, CcCalls.coverStop(e)) }, style = CcButtonStyle.DANGER, enabled = gate.enabled)
      e.isOpen -> CcButton("Close…", { ui.act(e, CcCalls.coverClose(e)) }, icon = "garage", style = CcButtonStyle.PRIMARY, enabled = gate.enabled)
      else -> CcButton("Open…", { ui.act(e, CcCalls.coverOpen(e)) }, icon = "garage", enabled = gate.enabled)
    }
  }
  if (camera != null && moving) GarageDoor(e, Modifier.height(160.dp))
  CcCtlNote(when {
    !isCover -> "This is a door sensor — Home Assistant can't move this door."
    moving -> "Watching the camera while it moves — Stop is always one tap."
    s.settings.askGarage -> "Opening and closing always ask first. The camera shows the doorway before you confirm."
    else -> "Opening and closing happen straight away on this Portal."
  }, Modifier.padding(top = 12.dp))
}

private fun garageSince(s: CcUiState, e: CcEntity): String {
  if (e.lastChanged <= 0) return ""
  val who = CcSheetLogic.who(s, e)
  val verb = if (e.isOpen) "Since" else "Closed at"
  return listOfNotNull("$verb ${ccTime(e.lastChanged)}", who).joinToString(" · ")
}

/**
 * `.garageVis`: the door drawn in its opening; the panel follows `current_position` and, while
 * moving without one, glides in the direction of travel (stopped when motion is off).
 */
@Composable
private fun GarageDoor(e: CcEntity, modifier: Modifier = Modifier.height(330.dp)) {
  val moving = e.state == "opening" || e.state == "closing"
  val known = (e.attrs["current_position"] as? Number)?.toFloat()?.div(FULL)
  val base = known ?: if (e.isOpen && e.state != "closing") 1f else 0f
  val motion = LocalCcMotion.current
  val glide = if (moving && known == null && motion) {
    val t = rememberInfiniteTransition(label = "garage")
    t.animateFloat(0f, 1f, infiniteRepeatable(tween(GLIDE_MS, easing = LinearEasing), RepeatMode.Restart), label = "garageGlide").value
  } else null
  val openness = glide?.let { if (e.state == "opening") it else 1f - it } ?: base
  val shown by animateFloatAsState(openness, tween(if (glide != null) 0 else PANEL_MS), label = "garagePanel")
  BoxWithConstraints(modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(Brush.verticalGradient(listOf(FRAME_TOP, FRAME_BOTTOM)))) {
    Canvas(Modifier.fillMaxSize()) {
      val slatPx = SLAT.dp.toPx(); val gapPx = SLAT_GAP.dp.toPx()
      val left = size.width * OPENING_SIDE; val top = size.height * OPENING_TOP
      val w = size.width * (1 - 2 * OPENING_SIDE); val h = size.height - top
      drawRect(Brush.verticalGradient(listOf(NIGHT_A, NIGHT_B, NIGHT_C), startY = top, endY = size.height), Offset(left, top), Size(w, h))
      val panel = h * (1f - shown)
      var y = top
      while (y < top + panel) {
        val slat = minOf(slatPx, top + panel - y)
        drawRect(SLAT_LIGHT, Offset(left, y), Size(w, slat))
        if (slat >= slatPx) drawRect(SLAT_DARK, Offset(left, y + slatPx - gapPx), Size(w, gapPx))
        y += slatPx
      }
    }
  }
}

private const val FULL = 100f
private const val GLIDE_MS = 12_000
private const val PANEL_MS = 600
private const val OPENING_SIDE = 0.12f
private const val OPENING_TOP = 0.18f
private const val SLAT = 42f
private const val SLAT_GAP = 4f
private val FRAME_TOP = Color(0xFF2B2F36)
private val FRAME_BOTTOM = Color(0xFF1B1D22)
private val NIGHT_A = Color(0xFF0D1A2B)
private val NIGHT_B = Color(0xFF2C3D52)
private val NIGHT_C = Color(0xFF3D4A3A)
private val SLAT_LIGHT = Color(0xFFCFD5DC)
private val SLAT_DARK = Color(0xFFB5BDC6)
