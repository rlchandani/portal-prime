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
 * appliance / leak badges, the garage "OPEN 17m" chip, and room names + captions. Sizes are
 * the mock's, in dp.
 */
internal fun CcPlanDraw.drawMarks(s: DrawScope, view: CcAffine) {
  for (d in model.dots) {
    val p = view.at(px.pt(d.x, d.y))
    s.drawCircle(if (d.rgb != null) Color(OPAQUE or d.rgb) else c.dotOff, DOT_R * dp, p)
    s.drawCircle(c.floor, DOT_R * dp, p, style = dotStroke)
  }
  for (b in model.badges) drawBadge(s, b, view)
  for (ch in model.chips) {
    val t = chips.chip[ch.entityId] ?: continue
    val p = view.at(px.pt(ch.x, ch.y))
    val w = t.width + CHIP_PAD * 2 * dp
    s.drawRoundRect(c.amber, Offset(p.x, p.y - CHIP_H / 2 * dp), Size(w, CHIP_H * dp), CornerRadius(CHIP_H / 2 * dp))
    t.draw(s, p.x + CHIP_PAD * dp, p.y - t.height / 2f)
  }
  for (pp in model.people) {
    val p = view.at(px.pt(pp.x, pp.y))
    s.drawCircle(Color(pp.rgb or OPAQUE), PERSON_R * dp, p)
    s.drawCircle(c.floor, PERSON_R * dp, p, style = personStroke)
    chips.person[pp.key]?.let { t -> t.draw(s, p.x - t.width / 2f, p.y - t.height / 2f) }
  }
}

private fun CcPlanDraw.drawBadge(s: DrawScope, b: CcMapBadge, view: CcAffine) {
  val p = view.at(px.pt(b.x, b.y))
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

/**
 * Names centred in each room with the caption below; other rooms fade while [focus] is open. In 3D
 * the text stays upright at the projected centre, and the caption shows only if the room's
 * projected height still holds it.
 */
internal fun CcPlanDraw.drawLabels(s: DrawScope, view: CcAffine, focus: CcPlanFocus) {
  for (v in model.views) {
    val r = px.rects[v.room.id] ?: continue
    val t = labels[v.room.id] ?: continue
    val alpha = if (focus.veiled(v.room.id)) FADED else 1f
    val tall = if (view.isIdentity) r.height else kotlin.math.abs(view.b) * r.width + kotlin.math.abs(view.d) * r.height
    val cap = t.caption?.takeIf { tall > (t.name.height + it.height) * FIT_SLACK }
    val total = t.name.height + (cap?.height ?: 0f) + if (cap != null) LINE_GAP * dp else 0f
    val centre = view.at(r.center)
    val top = centre.y - total / 2f
    t.name.draw(s, centre.x - t.name.width / 2f, top, alpha)
    cap?.draw(s, centre.x - cap.width / 2f, top + t.name.height + LINE_GAP * dp, alpha)
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
/** The mock's `.pp circle`: r 20 with a 4 px floor-coloured ring. */
private const val PERSON_R = 20f
private const val FADED = 0.45f
private const val LINE_GAP = 2f
private const val FIT_SLACK = 1.1f

/** A 2D plan pixel placed on screen through [this] view (free for the flat plan). */
internal fun CcAffine.at(p: Offset): Offset = if (isIdentity) p else Offset(x(p.x, p.y), y(p.x, p.y))
