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
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * The plan's top layers, drawn over the floor: light dots, lock / thermostat / camera /
 * appliance / leak badges, the garage "OPEN 17m" chip, room names + captions, and the
 * energy lens's home hub and source nodes. Sizes are the mock's, in dp.
 */
internal fun CcPlanDraw.drawMarks(s: DrawScope) {
  for (d in model.dots) {
    val p = px.pt(d.x, d.y)
    s.drawCircle(if (d.rgb != null) Color(OPAQUE or d.rgb) else c.dotOff, DOT_R * dp, p)
    s.drawCircle(c.floor, DOT_R * dp, p, style = dotStroke)
  }
  for (b in model.badges) drawBadge(s, b)
  for (ch in model.chips) {
    val t = chips.chip[ch.entityId] ?: continue
    val p = px.pt(ch.x, ch.y)
    val w = t.width + CHIP_PAD * 2 * dp
    s.drawRoundRect(c.amber, Offset(p.x, p.y - CHIP_H / 2 * dp), Size(w, CHIP_H * dp), CornerRadius(CHIP_H / 2 * dp))
    t.draw(s, p.x + CHIP_PAD * dp, p.y - t.height / 2f)
  }
}

private fun CcPlanDraw.drawBadge(s: DrawScope, b: CcMapBadge) {
  val p = px.pt(b.x, b.y)
  val white = paint.tint(Color.White)
  when (b.kind) {
    CcMapBadgeKind.LOCKED, CcMapBadgeKind.UNLOCKED -> {
      s.drawCircle(if (b.kind == CcMapBadgeKind.LOCKED) c.green else c.amber, BADGE_R * dp, p)
      s.ccIcon(paint, b.icon, p, LOCK_ICON * dp, white)
    }
    CcMapBadgeKind.CLIMATE -> {
      val t = chips.badge[b.entityId]
      val w = (t?.width ?: 0f) + THERMO_PAD * dp
      s.drawRoundRect(c.surface, Offset(p.x - THERMO_LEFT * dp, p.y - CHIP_H / 2 * dp), Size(w, CHIP_H * dp), CornerRadius(CHIP_H / 2 * dp), alpha = THERMO_ALPHA)
      s.ccIcon(paint, "heat", Offset(p.x - THERMO_ICON_X * dp, p.y), THERMO_ICON * dp, paint.tint(if (b.hot) HEATING else c.sec))
      t?.draw(s, p.x + THERMO_TEXT_X * dp, p.y - t.height / 2f)
    }
    CcMapBadgeKind.CAMERA -> s.ccIcon(paint, "cam", p, CAM_ICON * dp, paint.tint(c.sec))
    CcMapBadgeKind.APPLIANCE, CcMapBadgeKind.APPLIANCE_DONE -> {
      val done = b.kind == CcMapBadgeKind.APPLIANCE_DONE
      s.drawCircle(if (done) c.green else c.surface, BADGE_R * dp, p)
      s.ccIcon(paint, b.icon, p, APPLIANCE_ICON * dp, if (done) white else paint.tint(c.sec))
    }
    CcMapBadgeKind.LEAK -> {
      s.drawCircle(c.red, LEAK_R * dp, p)
      s.ccIcon(paint, "leak", p, LEAK_ICON * dp, white)
    }
  }
}

/** Names centred in each room with the caption below; other rooms fade while one is focused. */
internal fun CcPlanDraw.drawLabels(s: DrawScope) {
  for (v in model.views) {
    val r = px.rects[v.room.id] ?: continue
    val t = labels[v.room.id] ?: continue
    val alpha = if (focus != null && focus != v.room.id) FADED else 1f
    val cap = t.caption?.takeIf { r.height > (t.name.height + it.height) * FIT_SLACK }
    val total = t.name.height + (cap?.height ?: 0f) + if (cap != null) LINE_GAP * dp else 0f
    val top = r.center.y - total / 2f
    t.name.draw(s, r.center.x - t.name.width / 2f, top, alpha)
    cap?.draw(s, r.center.x - cap.width / 2f, top + t.name.height + LINE_GAP * dp, alpha)
  }
}

/** Energy lens: the "2.3 kW home" hub and the solar / grid / battery nodes the flows start from. */
internal fun CcPlanDraw.drawEnergyNodes(s: DrawScope) {
  val f = flows ?: return
  f.solar?.let { node(s, it, "sun", Color(OPAQUE or CcFlowPx.SOLAR_RGB), chips.solar) }
  f.grid?.let { node(s, it, "grid", Color(OPAQUE or CcFlowPx.GRID_RGB), chips.grid) }
  f.battery?.let { node(s, it, "battery", Color(OPAQUE or CcFlowPx.BATTERY_RGB), chips.battery) }
  val home = chips.hub ?: return
  val unit = chips.hubUnit
  s.drawCircle(c.text, HUB_R * dp, f.hub)
  val h = home.height + (unit?.height ?: 0f)
  home.draw(s, f.hub.x - home.width / 2f, f.hub.y - h / 2f)
  unit?.draw(s, f.hub.x - unit.width / 2f, f.hub.y - h / 2f + home.height)
}

private fun CcPlanDraw.node(s: DrawScope, at: Offset, icon: String, color: Color, label: CcPlanLabel?) {
  s.drawCircle(c.surface, NODE_R * dp, at)
  s.drawCircle(color, NODE_R * dp, at, style = nodeStroke)
  s.ccIcon(paint, icon, at, NODE_ICON * dp, paint.tint(color))
  label?.let {
    val top = Offset(at.x - it.width / 2f, at.y + (NODE_R + NODE_LABEL_GAP) * dp)
    s.drawRoundRect(c.surface, Offset(top.x - NODE_LABEL_PAD * dp, top.y - NODE_LABEL_PAD / 2 * dp),
        Size(it.width + NODE_LABEL_PAD * 2 * dp, it.height + NODE_LABEL_PAD * dp), CornerRadius(it.height), alpha = THERMO_ALPHA)
    it.draw(s, top.x, top.y)
  }
}

private const val OPAQUE = 0xFF000000.toInt()
private val HEATING = Color(0xFFF08A4B)
private const val DOT_R = 8f
private const val BADGE_R = 20f
private const val LOCK_ICON = 24f
private const val CHIP_H = 40f
private const val CHIP_PAD = 18f
private const val THERMO_PAD = 54f
private const val THERMO_LEFT = 34f
private const val THERMO_ICON_X = 14f
private const val THERMO_ICON = 20f
private const val THERMO_TEXT_X = 4f
private const val THERMO_ALPHA = 0.92f
private const val CAM_ICON = 22f
private const val APPLIANCE_ICON = 22f
private const val LEAK_R = 24f
private const val LEAK_ICON = 26f
private const val FADED = 0.45f
private const val LINE_GAP = 2f
private const val FIT_SLACK = 1.1f
private const val HUB_R = 46f
private const val NODE_R = 30f
private const val NODE_ICON = 28f
private const val NODE_LABEL_GAP = 8f
private const val NODE_LABEL_PAD = 8f
