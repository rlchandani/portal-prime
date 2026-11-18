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
import androidx.compose.runtime.key
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
internal fun CcScenesCard(grid: CcSceneGrid, canControl: Boolean, ui: CcUi) {
  val tiles = grid.tiles
  val c = LocalCc.current
  Column(Modifier.fillMaxWidth().ccCard().padding(horizontal = 24.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
    CcSectionLabel("Scenes")
    if (tiles.isEmpty()) {
      Text("Add scenes in Home Assistant and they'll appear here — Good night, Movie time, Leaving home…",
          color = c.sec, fontSize = 19.sp, lineHeight = 26.sp)
      return@Column
    }
    tiles.chunked(2).forEach { pair ->
      Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        pair.forEach { key(it.entity.id) { SceneTile(it, canControl, ui) } }
        if (pair.size == 1) Box(Modifier.weight(1f))
      }
    }
  }
}

@Composable
private fun RowScope.SceneTile(tile: CcSceneTile, enabled: Boolean, ui: CcUi) {
  val c = LocalCc.current
  val look = remember(tile.tone, c) { SceneLook.of(tile.tone, c) }
  val run = remember(tile.entity, ui) { { ui.runScene(tile.entity) } }
  Column(Modifier.weight(1f).height(118.dp).alpha(if (enabled) 1f else CC_DISABLED_ALPHA).clip(SCENE).background(look.bg)
      .clickable(enabled = enabled, role = Role.Button, onClick = run).ccSpeak(CcSpeech.scene(tile.entity.name, tile.subtitle))
      .padding(horizontal = 22.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
    CcIcon(CcSideLogic.icon(tile.tone), look.fg, 36.dp)
    Text(tile.entity.name, color = look.fg, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(tile.subtitle, color = look.sub, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
internal fun CcEnergyNowCard(now: CcEnergyNow, ui: CcUi) {
  val open = remember(ui) { { ui.go(CcScreen.ENERGY) } }
  val home = CcEnergy.kw(now.homeW)
  val solar = CcEnergy.kw(now.solarW)
  val battery = now.batteryPct?.let { "$it%" } ?: "—"
  val grid = CcEnergy.kw(now.gridW)
  Column(Modifier.fillMaxWidth().ccCard().clickable(role = Role.Button, onClick = open).ccSpeak(CcSpeech.energyNow(home, solar, battery, grid))
      .padding(horizontal = 24.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
    CcSectionLabel("Energy now")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      EnergyCell(home, "home", "kW use")
      EnergyCell(solar, "sun", "solar")
      EnergyCell(battery, "battery", "battery")
      EnergyCell(grid, "grid", "grid")
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

/** [cams] are (id, name); [feed] is all the pictures read from the state. */
@Composable
internal fun CcCamerasCard(feed: CcCameraFeedState, list: CcCameraList, ui: CcUi) {
  if (feed.hidden) return
  val cams = list.cams
  Column(Modifier.fillMaxWidth().ccCard()) {
    CcSectionLabel("Cameras", Modifier.padding(start = 24.dp, end = 24.dp, top = 22.dp))
    Row(Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 22.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
      cams.forEach { cam ->
        key(cam.id) {
          CcCameraPicture(cam.id, cam.label, live = false, configured = feed.configured,
              modifier = Modifier.weight(1f).aspectRatio(CAMERA_ASPECT).clip(CELL),
              onClick = remember(cam.id, ui) { { ui.camera(cam.id) } }, size = CcCameraTileSize.GRID)
        }
      }
      if (cams.size == 1) Box(Modifier.weight(1f))
    }
  }
}

private val SCENE = RoundedCornerShape(24.dp)
private val CELL = RoundedCornerShape(20.dp)
private const val CAMERA_ASPECT = 16f / 10f
