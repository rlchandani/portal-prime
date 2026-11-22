/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable

/** One row of the rooms list. [lit] lights on; [hasEntities] false shows "—". */
internal data class HaRoomEntry(val id: String, val name: String, val icon: String, val lit: Int, val hasEntities: Boolean)

/** Landscape side column: ROOMS (scrolls) over SCENES (2-column grid). */
@Composable
internal fun HaSideColumn(rooms: List<HaRoomEntry>, selected: String?, scenes: List<HaDashboardDay.SceneChip>,
    fired: String?, onRoom: (String) -> Unit, onScene: (HaDashboardDay.SceneChip) -> Unit, modifier: Modifier) {
  val d = LocalHaDims.current
  Column(modifier.width(d.sideWidth.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
    HaLabel("Rooms", Modifier.padding(start = 6.dp))
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(2.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
      rooms.forEach { HaRoomRow(it, it.id == selected, strip = false) { onRoom(it.id) } }
    }
    val shown = scenes.take(d.sideScenes)
    if (shown.isNotEmpty()) {
      HaLabel("Scenes", Modifier.padding(start = 6.dp, top = 6.dp))
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        shown.chunked(2).forEach { pair ->
          Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            pair.forEach { s -> HaSceneButton(s, s.id == fired, strip = false, Modifier.weight(1f)) { onScene(s) } }
            if (pair.size == 1) Box(Modifier.weight(1f))
          }
        }
      }
    }
  }
}

/** Portrait: rooms then scenes as two sideways strips. */
@Composable
internal fun ColumnScope.HaPortraitStrips(rooms: List<HaRoomEntry>, selected: String?,
    scenes: List<HaDashboardDay.SceneChip>, fired: String?, onRoom: (String) -> Unit,
    onScene: (HaDashboardDay.SceneChip) -> Unit) {
  Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(2.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    rooms.forEach { HaRoomRow(it, it.id == selected, strip = true) { onRoom(it.id) } }
  }
  if (scenes.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(2.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    scenes.forEach { s -> HaSceneButton(s, s.id == fired, strip = true) { onScene(s) } }
  }
}

@Composable
private fun HaRoomRow(room: HaRoomEntry, selected: Boolean, strip: Boolean, onClick: () -> Unit) {
  val d = LocalHaDims.current
  val c = LocalHaColors.current
  val fill = when { strip && selected -> c.sel; selected || strip -> c.surface; else -> Color.Transparent }
  val line = when { strip && selected -> c.accent; selected || strip -> c.outline; else -> Color.Transparent }
  val width = if (strip) Modifier else Modifier.fillMaxWidth()
  Row(width.heightIn(min = (if (strip) d.target else d.roomRow).dp).clip(HaShape.Room).background(fill, HaShape.Room)
      .border(HaShape.Outline, line, HaShape.Room)
      .selectable(selected, role = Role.Tab, onClick = onClick)
      .semantics { contentDescription = "${room.name}, ${countWords(room)}" }
      .padding(start = if (strip) 10.dp else 14.dp, end = if (strip) 20.dp else 18.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    val well = if (strip) 44.dp else 52.dp
    Box(Modifier.size(well).clip(if (strip) HaShape.Room else HaShape.SmallWell)
        .background(if (selected && !strip) c.sel else c.surface2), contentAlignment = Alignment.Center) {
      HaIcon(room.icon, if (selected) c.text else c.sec, 28.dp)
    }
    HaText(room.name, d.room, c.text, if (strip) Modifier else Modifier.weight(1f), FontWeight.SemiBold)
    if (room.lit > 0) {
      HaText("${room.lit} on", d.small, c.amber, Modifier.clip(HaShape.Pill).background(c.amberBg)
          .padding(horizontal = 12.dp, vertical = 5.dp), FontWeight.Bold)
    } else {
      HaText(if (room.hasEntities) "Off" else "—", d.small, c.sec, Modifier.padding(horizontal = 12.dp), FontWeight.Medium)
    }
  }
}

private fun countWords(room: HaRoomEntry) = when {
  room.lit > 0 -> "${room.lit} on"
  room.hasEntities -> "all off"
  else -> "nothing to control"
}

@Composable
private fun HaSceneButton(scene: HaDashboardDay.SceneChip, fired: Boolean, strip: Boolean,
    modifier: Modifier = Modifier, onClick: () -> Unit) {
  val d = LocalHaDims.current
  val c = LocalHaColors.current
  val shape = if (strip) HaShape.Pill else HaShape.Scene
  val line = when { fired -> c.green; scene.now -> c.ember; else -> c.outline }
  val fill = if (scene.now) Brush.verticalGradient(listOf(c.surface, c.ember.mix(SUGGESTED_TINT, c.surface)))
      else Brush.verticalGradient(listOf(c.surface, c.surface))
  Box(modifier.height((if (strip) d.target else d.sceneHeight).dp).clip(shape).background(fill, shape)
      .border(HaShape.Outline, line, shape)
      .clickable(role = Role.Button, onClickLabel = "Run ${scene.name} scene", onClick = onClick)
      .padding(horizontal = if (strip) 22.dp else 18.dp)) {
    Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      HaIcon(if (fired) "check" else scene.icon, if (scene.now) c.ember else c.sec, 28.dp)
      HaText(scene.name, d.button, c.text, weight = FontWeight.SemiBold)
    }
    if (scene.now && !strip) HaText("NOW", NOW_TAG_SP, c.ember, Modifier.align(Alignment.TopEnd).padding(top = 8.dp),
        FontWeight.Bold, spacing = NOW_TAG_SP * 0.04f)
  }
}

private const val SUGGESTED_TINT = 0.10f
private const val NOW_TAG_SP = 12
