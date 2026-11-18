/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Everything one editor frame draws (mock `edSVG`). */
internal class CcEdScene(
    val geom: CcEdGeom, val st: CcEdState, val floor: CcFloor, val hot: String?, val drag: CcEdDrag?,
    val reg: CcRegistry, val live: Map<String, CcEntity>, val c: CcColors, val text: TextMeasurer,
)

internal fun DrawScope.drawEditor(s: CcEdScene) {
  drawGrid(s)
  val showCounts = s.st.step != CcEdStep.FOUND
  s.floor.rooms.forEach { drawRoom(s, it, RoomLook.of(s, it), if (showCounts) devicesLine(it.devices.size) else "") }
  if (s.st.step == CcEdStep.ROOMS) s.st.suggestions.forEach { drawRoom(s, it, RoomLook.SUGGESTED, "tap to accept") }
  drawOpenings(s)
  s.floor.rooms.firstOrNull { it.id == s.st.selectedRoom && s.st.step == CcEdStep.ROOMS }?.let { drawHandle(s, it.rect) }
  (s.drag as? CcEdDrag.Draw)?.let { drawDraft(s, it.rect, s.c.accent) }
  s.st.pendingRect?.let { drawDraft(s, it, CcStyle.Ember) }
}

private enum class RoomLook {
  NORMAL, SELECTED, HOT, SUGGESTED;

  companion object {
    fun of(s: CcEdScene, r: CcRoom) = when {
      r.id == s.hot -> HOT
      r.id == s.st.selectedRoom && s.st.step == CcEdStep.ROOMS -> SELECTED
      else -> NORMAL
    }
  }
}

private fun devicesLine(n: Int): String = when (n) { 0 -> ""; 1 -> "1 device"; else -> "$n devices" }

/** Mock `.edCanvas` background: a line on every snap cell, across the whole canvas. */
private fun DrawScope.drawGrid(s: CcEdScene) {
  val g = s.geom
  val line = s.c.div.copy(alpha = GRID_ALPHA)
  var x = g.originX - g.cell * kotlin.math.floor(g.originX / g.cell)
  while (x <= size.width) { drawLine(line, Offset(x, 0f), Offset(x, size.height), 1f); x += g.cell }
  var y = g.originY - g.cell * kotlin.math.floor(g.originY / g.cell)
  while (y <= size.height) { drawLine(line, Offset(0f, y), Offset(size.width, y), 1f); y += g.cell }
}

private fun DrawScope.drawRoom(s: CcEdScene, r: CcRoom, look: RoomLook, sub: String) {
  val g = s.geom
  val tl = Offset(g.px(r.rect.x.toFloat()), g.py(r.rect.y.toFloat()))
  val sz = Size(r.rect.w * g.cell, r.rect.h * g.cell)
  val corner = CornerRadius((if (r.outdoor) OUTDOOR_RADIUS else ROOM_RADIUS).dp.toPx())
  val fill = when (look) {
    RoomLook.HOT -> lerp(s.c.floor, s.c.green, HOT_MIX)
    RoomLook.SUGGESTED -> CcStyle.EmberLight.copy(alpha = SUGGEST_ALPHA)
    else -> s.c.floor.copy(alpha = ROOM_ALPHA)
  }
  drawRoundRect(fill, tl, sz, corner)
  val stroke = when (look) {
    RoomLook.SUGGESTED -> Stroke(3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx())))
    RoomLook.SELECTED -> Stroke(4.dp.toPx())
    else -> Stroke(3.dp.toPx())
  }
  val edge = when (look) { RoomLook.SUGGESTED, RoomLook.SELECTED -> CcStyle.Ember; else -> s.c.accent }
  drawRoundRect(edge, tl, sz, corner, style = stroke)
  val name = if (look == RoomLook.SUGGESTED) "${r.name} ?" else r.name
  drawLabel(s, name, tl + Offset(sz.width / 2f, sz.height / 2f), sub)
}

private fun DrawScope.drawLabel(s: CcEdScene, name: String, center: Offset, sub: String) {
  val title = s.text.measure(name, TextStyle(color = s.c.text, fontSize = 22.sp, fontWeight = FontWeight.Bold), maxLines = 1)
  val subLayout = sub.takeIf { it.isNotEmpty() }?.let {
    s.text.measure(it, TextStyle(color = s.c.sec, fontSize = 16.sp, fontWeight = FontWeight.Bold), maxLines = 1)
  }
  val total = title.size.height + (subLayout?.size?.height ?: 0)
  val top = center.y - total / 2f
  drawText(title, topLeft = Offset(center.x - title.size.width / 2f, top))
  subLayout?.let { drawText(it, topLeft = Offset(center.x - it.size.width / 2f, top + title.size.height)) }
}

private fun DrawScope.drawOpenings(s: CcEdScene) {
  val g = s.geom
  val dragged = (s.drag as? CcEdDrag.Opening)?.entityId
  s.floor.openings.forEach { o ->
    val room = s.floor.rooms.firstOrNull { it.id == o.roomId } ?: return@forEach
    val seg = CcEdGeo.segment(room.rect, o)
    val kind = CcEdGeo.openingKind(o.entityId, s.reg, s.live[o.entityId])
    val color = if (kind == CcEdOpening.WINDOW) CcWindowBlue else CcStyle.Ember
    val width = (if (kind == CcEdOpening.GARAGE) GARAGE_WIDTH else OPENING_WIDTH) + if (o.entityId == dragged) DRAG_GROW else 0
    drawLine(color, Offset(g.px(seg.x1), g.py(seg.y1)), Offset(g.px(seg.x2), g.py(seg.y2)), width.dp.toPx(), StrokeCap.Round)
  }
}

private fun DrawScope.drawHandle(s: CcEdScene, r: CcRect) {
  val center = Offset(s.geom.px(r.right.toFloat()), s.geom.py(r.bottom.toFloat()))
  drawCircle(CcStyle.Ember, HANDLE_RADIUS.dp.toPx(), center)
  drawCircle(Color.White, HANDLE_RADIUS.dp.toPx(), center, style = Stroke(3.dp.toPx()))
}

private fun DrawScope.drawDraft(s: CcEdScene, r: CcRect, color: Color) {
  val g = s.geom
  val tl = Offset(g.px(r.x.toFloat()), g.py(r.y.toFloat()))
  val sz = Size(r.w * g.cell, r.h * g.cell)
  drawRect(color.copy(alpha = DRAFT_ALPHA), tl, sz)
  drawRect(color, tl, sz, style = Stroke(3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))))
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
