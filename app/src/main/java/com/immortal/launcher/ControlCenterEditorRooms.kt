/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Step 2 — draw rooms on the grid, or accept suggested boxes, then fix their shapes. */
@Composable
internal fun ColumnScope.CcStepRooms(ctx: CcEdCtx) {
  if (ctx.st.pendingRect != null) { CcRoomNamer(ctx); return }
  val st = ctx.st
  val areas = CcPlanEdit.areasFor(ctx.reg, st.floorId)
  val drawn = areas.count { CcEd.areaStatus(st, it) == CcAreaStatus.PLACED }
  val floorRooms = ctx.floor.rooms
  val tag = if (ctx.hasAreas && areas.isNotEmpty()) "$drawn of ${areas.size} areas drawn" to (if (drawn == areas.size) CcTone.GREEN else CcTone.NEUTRAL)
  else "${floorRooms.size} ${plural(floorRooms.size, "room")} drawn" to CcTone.NEUTRAL
  val body = if (ctx.hasAreas && areas.isNotEmpty()) "Drag on the grid to draw a room — tap an area first and the box takes its name. " +
      "Or let Hearthpane suggest a layout from the area names and you fix the shapes."
  else "Drag on the grid to draw a room, then name it. Use Move to drag a room; pull the orange corner to resize."
  CcWizCard(ctx, body, tag = tag, scroll = true, modifier = Modifier.weight(1f, fill = false)) {
    if (ctx.hasAreas) areas.forEach { a -> key(a.id) {
      val status = CcEd.areaStatus(st, a)
      CcAreaRow(if (status == CcAreaStatus.PLACED) "check" else "plan", a.name, status.label,
          iconTint = if (status == CcAreaStatus.PLACED) LocalCc.current.green else null, highlighted = st.selectedArea == a.id,
          onClick = { ctx.update { s -> if (status == CcAreaStatus.PLACED) CcEd.selectRoom(s, a.id) else CcEd.selectArea(s, a.id) } })
    } }
    floorRooms.filter { r -> areas.none { it.id == r.id } }.forEach { r ->
      CcAreaRow("check", r.name, "drawn", iconTint = LocalCc.current.green, highlighted = st.selectedRoom == r.id,
          onClick = { ctx.update { CcEd.selectRoom(it, r.id) } })
    }
    floorRooms.firstOrNull { it.id == st.selectedRoom }?.let { r ->
      CcButton("Remove ${r.name}", { ctx.update { CcEd.deleteRoom(it, r.id, ctx.reg, ctx.now) } }, icon = "x", height = 64)
    }
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      if (ctx.hasAreas && areas.isNotEmpty()) CcButton("Suggest a layout", { ctx.update { CcEd.suggest(it, ctx.reg) } }, icon = "sparkle")
      if (st.suggestions.isNotEmpty()) CcButton("Accept all ${st.suggestions.size}", { ctx.update { CcEd.acceptAll(it, ctx.reg, ctx.now) } })
    }
    CcWizNav(ctx)
  }
}

/** A box was just drawn: which Home Assistant area is it (or what's it called)? */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.CcRoomNamer(ctx: CcEdCtx) {
  val c = LocalCc.current
  val left = CcEd.unassignedAreas(ctx.st, ctx.reg)
  var name by remember { mutableStateOf("") }
  val body = if (left.isNotEmpty()) "Tap the Home Assistant area this box is. Devices in that area will go in automatically."
  else "Give the new room a name."
  CcWizCard(ctx, body, scroll = true, modifier = Modifier.weight(1f, fill = false)) {
    left.forEach { a -> CcAreaRow("plan", a.name, if (CcPlanEdit.isOutdoor(a.name)) "outdoor" else "area", onClick = { ctx.update { CcEd.assign(it, a, ctx.reg, ctx.now) } }) }
    CcSectionLabel(if (left.isEmpty()) "Room name" else "Or name it yourself", Modifier.padding(top = 8.dp))
    CcNameField(name, { name = it }) { ctx.update { CcEd.nameRoom(it, name, ctx.reg, ctx.now) } }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      QUICK_NAMES.forEach { q ->
        Text(q, color = c.text, fontSize = 19.sp, modifier = Modifier.clip(CcStyle.Pill).background(c.surface2)
            .ccPress(q) { name = q }.padding(horizontal = 20.dp, vertical = 14.dp))
      }
    }
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      CcButton("Add room", { ctx.update { CcEd.nameRoom(it, name, ctx.reg, ctx.now) } }, style = CcButtonStyle.PRIMARY, enabled = name.isNotBlank())
      CcButton("Cancel", { ctx.update(CcEd::cancelPending) })
    }
  }
}

@Composable
private fun CcNameField(value: String, onChange: (String) -> Unit, onDone: () -> Unit) {
  val c = LocalCc.current
  Box(Modifier.fillMaxWidth().height(72.dp).clip(CcStyle.CardSmall).background(c.surface2).padding(horizontal = 20.dp),
      contentAlignment = Alignment.CenterStart) {
    if (value.isEmpty()) Text("e.g. Kitchen", color = c.faint, fontSize = 22.sp)
    BasicTextField(value, onChange, singleLine = true, textStyle = TextStyle(color = c.text, fontSize = 22.sp),
        cursorBrush = SolidColor(c.text), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }), modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Room name" })
  }
}

/** Clickable with a button role and a spoken label (rows and chips). */
internal fun Modifier.ccPress(label: String, onClick: () -> Unit): Modifier =
    this.clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label }

internal fun Modifier.ccOutline(color: Color): Modifier = this.border(3.dp, color, CcStyle.CardSmall)

private val QUICK_NAMES = listOf("Kitchen", "Living room", "Bedroom", "Bathroom", "Hall", "Office", "Garage", "Patio")
