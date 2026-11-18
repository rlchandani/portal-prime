/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Side cards: the 2×2 scene grid (mock `.scenes`), Energy now (`.enMini`) and Cameras (`.camRow`). */
@Composable
internal fun CcScenesCard(s: CcUiState, ui: CcUi) {
  val c = LocalCc.current
  val scenes = remember(s.entities, s.settings) { CcSideLogic.pick(s.ofDomain("scene") + s.ofDomain("script"), s.settings.kids) }
  Column(Modifier.fillMaxWidth().ccCard().padding(horizontal = 24.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
    CcSectionLabel("Scenes")
    if (scenes.isEmpty()) {
      Text("Add scenes in Home Assistant and they'll appear here — Good night, Movie time, Leaving home…",
          color = c.sec, fontSize = 19.sp, lineHeight = 26.sp)
      return@Column
    }
    scenes.chunked(2).forEach { pair ->
      Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        pair.forEach { SceneTile(it, s, ui) }
        if (pair.size == 1) Box(Modifier.weight(1f))
      }
    }
  }
}

@Composable
private fun RowScope.SceneTile(e: CcEntity, s: CcUiState, ui: CcUi) {
  val c = LocalCc.current
  val tone = CcSideLogic.tone(e)
  val look = remember(tone, c) { SceneLook.of(tone, c) }
  val sub = remember(e, s.plan) { CcSideLogic.subtitle(e, s.plan) }
  val enabled = s.connection.canControl
  Column(Modifier.weight(1f).height(118.dp).alpha(if (enabled) 1f else CC_DISABLED_ALPHA).clip(SCENE).background(look.bg)
      .clickable(enabled = enabled, role = Role.Button) { ui.runScene(e) }.padding(horizontal = 22.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
    CcIcon(CcSideLogic.icon(tone), look.fg, 36.dp)
    Text(e.name, color = look.fg, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(sub, color = look.sub, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}

/** The mock's scene gradients (`.scene.night` …); neutral scenes use the plain tile. */
private class SceneLook(val bg: Brush, val fg: Color, val sub: Color) {
  companion object {
    fun of(t: CcSceneTone, c: CcColors): SceneLook = when (t) {
      CcSceneTone.NIGHT -> SceneLook(grad(0xFF262A47, 0xFF151729), Color(0xFFE9EBFF), Color(0xFFA9AED6))
      CcSceneTone.MOVIE -> SceneLook(grad(0xFF3B1F3F, 0xFF1D1022), Color(0xFFFFE9F6), Color(0xFFD7A9C8))
      CcSceneTone.LEAVE -> SceneLook(grad(0xFF16352C, 0xFF0F211B), Color(0xFFDCFFF1), Color(0xFF9BD2BD))
      CcSceneTone.MORNING -> SceneLook(grad(0xFFFFD7A1, 0xFFF6A96B), Color(0xFF3A1F08), Color(0xFF7A4A22))
      CcSceneTone.NEUTRAL -> SceneLook(Brush.linearGradient(listOf(c.surface2, c.surface2)), c.text, c.sec)
    }

    private fun grad(a: Long, b: Long) = Brush.linearGradient(listOf(Color(a), Color(b)))
  }
}

@Composable
internal fun CcEnergyNowCard(s: CcUiState, ui: CcUi) {
  if (CcPolicy.screenHidden(CcScreen.ENERGY, s.settings)) return
  val now = remember(s.entities) { CcEnergy.now(s.entities) }
  if (!now.hasFlow && now.batteryPct == null) return
  Column(Modifier.fillMaxWidth().ccCard().clickable(role = Role.Button) { ui.go(CcScreen.ENERGY) }.padding(horizontal = 24.dp, vertical = 22.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp)) {
    CcSectionLabel("Energy now")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      EnergyCell(CcEnergy.kw(now.homeW), "home", "kW use")
      EnergyCell(CcEnergy.kw(now.solarW), "sun", "solar")
      EnergyCell(now.batteryPct?.let { "$it%" } ?: "—", "battery", "battery")
      EnergyCell(CcEnergy.kw(now.gridW), "grid", "grid")
    }
  }
}

@Composable
private fun RowScope.EnergyCell(value: String, icon: String, label: String) {
  val c = LocalCc.current
  Column(Modifier.weight(1f).clip(CELL).background(c.surface2).padding(horizontal = 16.dp, vertical = 14.dp)) {
    Text(value, color = c.text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      CcIcon(icon, c.sec, 22.dp)
      Text(label, color = c.sec, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

@Composable
internal fun CcCamerasCard(s: CcUiState, ui: CcUi) {
  if (CcPolicy.screenHidden(CcScreen.CAMERAS, s.settings)) return
  val cams = remember(s.entities, s.settings) { s.ofDomain("camera").take(CcSideLogic.CAMERA_TILES) }
  if (cams.isEmpty()) return
  Column(Modifier.fillMaxWidth().ccCard()) {
    CcSectionLabel("Cameras", Modifier.padding(start = 24.dp, end = 24.dp, top = 22.dp))
    Row(Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 22.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
      cams.forEach { cam ->
        CcCameraTile(s, cam.id, live = false, modifier = Modifier.weight(1f).aspectRatio(CAMERA_ASPECT).clip(CELL),
            label = cam.name, onClick = { ui.camera(cam.id) })
      }
      if (cams.size == 1) Box(Modifier.weight(1f))
    }
  }
}

private val SCENE = RoundedCornerShape(24.dp)
private val CELL = RoundedCornerShape(20.dp)
private const val CAMERA_ASPECT = 16f / 10f
