/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A room's pre-measured label (name + "N devices" / "tap to accept"), measured once per plan change, never per frame. */
@Immutable
internal class CcEdLabel(val title: TextLayoutResult, val sub: TextLayoutResult?)

/** Labels for every room and suggestion (suggestions keyed "?id"), rebuilt only when they change. */
@Composable
internal fun rememberEdLabels(floor: CcFloor, suggestions: List<CcRoom>, showCounts: Boolean, c: CcColors, text: TextMeasurer): Map<String, CcEdLabel> =
    remember(floor.rooms, suggestions, showCounts, c, text) {
      val title = TextStyle(color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
      val sub = TextStyle(color = c.sec, fontSize = 16.sp, fontWeight = FontWeight.Bold)
      fun label(name: String, line: String) = CcEdLabel(text.measure(name, title, maxLines = 1),
          line.takeIf { it.isNotEmpty() }?.let { text.measure(it, sub, maxLines = 1) })
      floor.rooms.associate { it.id to label(it.name, if (showCounts) devicesLine(it.devices.size) else "") } +
          suggestions.associate { "?${it.id}" to label("${it.name} ?", "tap to accept") }
    }

/** What the static layer draws: [floor] is the floor without the item being dragged. */
internal class CcEdScene(val geom: CcEdGeom, val st: CcEdState, val floor: CcFloor, val reg: CcRegistry, val live: Map<String, CcEntity>,
                         val c: CcColors, val labels: Map<String, CcEdLabel>, density: Float) {
  /** Dash patterns made once per scene, not per frame. */
  val suggestDash: PathEffect = PathEffect.dashPathEffect(floatArrayOf(SUGGEST_DASH * density, SUGGEST_GAP * density))
  val draftDash: PathEffect = PathEffect.dashPathEffect(floatArrayOf(DRAFT_DASH * density, DRAFT_GAP * density))
}

/** Static layer: rooms, suggestions, openings, the selection handle and a box waiting for its name. */
internal fun DrawScope.drawEditorStatic(s: CcEdScene) {
  s.floor.rooms.forEach { r -> drawRoom(s, r.rect, r.outdoor, if (r.id == s.st.selectedRoom && s.st.step == CcEdStep.ROOMS) RoomLook.SELECTED else RoomLook.NORMAL, s.labels[r.id]) }
  if (s.st.step == CcEdStep.ROOMS) s.st.suggestions.forEach { drawRoom(s, it.rect, it.outdoor, RoomLook.SUGGESTED, s.labels["?${it.id}"]) }
  s.floor.openings.forEach { o -> s.floor.rooms.firstOrNull { it.id == o.roomId }?.let { drawOpening(s, it.rect, o, false) } }
  s.floor.rooms.firstOrNull { it.id == s.st.selectedRoom && s.st.step == CcEdStep.ROOMS }?.let { drawHandle(s.geom, it.rect) }
  s.st.pendingRect?.let { drawDraft(s, it, CcStyle.Ember) }
}

/** Drag layer, redrawn per pointer move from the drag State: only the dragged item. [full] is the floor before the drag. */
internal fun DrawScope.drawEditorDrag(s: CcEdScene, full: CcFloor, d: CcEdDrag?) {
  when (d) {
    null -> Unit
    is CcEdDrag.Draw -> drawDraft(s, d.rect, s.c.accent)
    is CcEdDrag.Move, is CcEdDrag.Resize -> {
      val id = if (d is CcEdDrag.Move) d.roomId else (d as CcEdDrag.Resize).roomId
      val room = full.rooms.firstOrNull { it.id == id } ?: return
      val rect = CcEdGesture.liveRect(id, room.rect, d)
      drawRoom(s, rect, room.outdoor, RoomLook.SELECTED, s.labels[id])
      full.openings.forEach { o -> if (o.roomId == id) drawOpening(s, rect, o, false) }
      drawHandle(s.geom, rect)
    }
    is CcEdDrag.Opening -> {
      val room = CcEdGeo.nearestRoom(full, d.gx, d.gy) ?: return
      val old = full.openings.firstOrNull { it.entityId == d.entityId } ?: return
      val x = d.gx.coerceIn(room.rect.x.toFloat(), room.rect.right.toFloat())
      val y = d.gy.coerceIn(room.rect.y.toFloat(), room.rect.bottom.toFloat())
      drawOpening(s, room.rect, CcPlanEdit.nearestWall(d.entityId, room, x, y).copy(span = old.span), true)
    }
    is CcEdDrag.Device -> CcEdGesture.hotRoomAt(s.floor, d)?.let { r ->
      val g = s.geom
      drawRoundRect(s.c.green.copy(alpha = HOT_MIX), Offset(g.px(r.rect.x.toFloat()), g.py(r.rect.y.toFloat())),
          Size(r.rect.w * g.cell, r.rect.h * g.cell), CornerRadius(radius(r.outdoor).dp.toPx()))
      s.labels[r.id]?.let { drawLabel(it, Offset(g.px(r.rect.x + r.rect.w / 2f), g.py(r.rect.y + r.rect.h / 2f))) }
    }
  }
}

internal enum class RoomLook { NORMAL, SELECTED, SUGGESTED }

private fun devicesLine(n: Int): String = when (n) { 0 -> ""; 1 -> "1 device"; else -> "$n devices" }

private fun radius(outdoor: Boolean) = if (outdoor) OUTDOOR_RADIUS else ROOM_RADIUS

/** Mock `.edCanvas` background: a line on every snap cell, across the whole canvas (its own layer). */
internal fun DrawScope.drawEditorGrid(g: CcEdGeom, c: CcColors) {
  val line = c.div.copy(alpha = GRID_ALPHA)
  var x = g.originX - g.cell * kotlin.math.floor(g.originX / g.cell)
  while (x <= size.width) { drawLine(line, Offset(x, 0f), Offset(x, size.height), 1f); x += g.cell }
  var y = g.originY - g.cell * kotlin.math.floor(g.originY / g.cell)
  while (y <= size.height) { drawLine(line, Offset(0f, y), Offset(size.width, y), 1f); y += g.cell }
}

private fun DrawScope.drawRoom(s: CcEdScene, rect: CcRect, outdoor: Boolean, look: RoomLook, label: CcEdLabel?) {
  val g = s.geom
  val tl = Offset(g.px(rect.x.toFloat()), g.py(rect.y.toFloat()))
  val sz = Size(rect.w * g.cell, rect.h * g.cell)
  val corner = CornerRadius(radius(outdoor).dp.toPx())
  val fill = if (look == RoomLook.SUGGESTED) CcStyle.EmberLight.copy(alpha = SUGGEST_ALPHA) else s.c.floor.copy(alpha = ROOM_ALPHA)
  drawRoundRect(fill, tl, sz, corner)
  val stroke = when (look) {
    RoomLook.SUGGESTED -> Stroke(3.dp.toPx(), pathEffect = s.suggestDash)
    RoomLook.SELECTED -> Stroke(4.dp.toPx())
    RoomLook.NORMAL -> Stroke(3.dp.toPx())
  }
  drawRoundRect(if (look == RoomLook.NORMAL) s.c.accent else CcStyle.Ember, tl, sz, corner, style = stroke)
  label?.let { drawLabel(it, tl + Offset(sz.width / 2f, sz.height / 2f)) }
}

private fun DrawScope.drawLabel(l: CcEdLabel, center: Offset) {
  val total = l.title.size.height + (l.sub?.size?.height ?: 0)
  val top = center.y - total / 2f
  drawText(l.title, topLeft = Offset(center.x - l.title.size.width / 2f, top))
  l.sub?.let { drawText(it, topLeft = Offset(center.x - it.size.width / 2f, top + l.title.size.height)) }
}

private fun DrawScope.drawOpening(s: CcEdScene, rect: CcRect, o: CcOpening, dragged: Boolean) {
  val g = s.geom
  val seg = CcEdGeo.segment(rect, o)
  val kind = CcEdGeo.openingKind(o.entityId, s.reg, s.live[o.entityId])
  val color = if (kind == CcEdOpening.WINDOW) CcWindowBlue else CcStyle.Ember
  val width = (if (kind == CcEdOpening.GARAGE) GARAGE_WIDTH else OPENING_WIDTH) + if (dragged) DRAG_GROW else 0
  drawLine(color, Offset(g.px(seg.x1), g.py(seg.y1)), Offset(g.px(seg.x2), g.py(seg.y2)), width.dp.toPx(), StrokeCap.Round)
}

private fun DrawScope.drawHandle(g: CcEdGeom, r: CcRect) {
  val center = Offset(g.px(r.right.toFloat()), g.py(r.bottom.toFloat()))
  drawCircle(CcStyle.Ember, HANDLE_RADIUS.dp.toPx(), center)
  drawCircle(Color.White, HANDLE_RADIUS.dp.toPx(), center, style = Stroke(3.dp.toPx()))
}

private fun DrawScope.drawDraft(s: CcEdScene, r: CcRect, color: Color) {
  val g = s.geom
  val tl = Offset(g.px(r.x.toFloat()), g.py(r.y.toFloat()))
  val sz = Size(r.w * g.cell, r.h * g.cell)
  drawRect(color.copy(alpha = DRAFT_ALPHA), tl, sz)
  drawRect(color, tl, sz, style = Stroke(3.dp.toPx(), pathEffect = s.draftDash))
}

private const val GRID_ALPHA = 0.7f
private const val ROOM_ALPHA = 0.88f
private const val SUGGEST_ALPHA = 0.14f
private const val DRAFT_ALPHA = 0.14f
private const val HOT_MIX = 0.25f
private const val ROOM_RADIUS = 4
private const val OUTDOOR_RADIUS = 14
private const val OPENING_WIDTH = 8
private const val GARAGE_WIDTH = 12
private const val DRAG_GROW = 4
private const val HANDLE_RADIUS = 16
private const val SUGGEST_DASH = 10f
private const val SUGGEST_GAP = 8f
private const val DRAFT_DASH = 8f
private const val DRAFT_GAP = 6f
