/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import kotlin.math.hypot

/** What a tap on the plan hit. */
internal sealed interface CcPlanHit {
  data class Room(val roomId: String) : CcPlanHit
  data class Device(val roomId: String, val entityId: String, val kind: CcMapBadgeKind?) : CcPlanHit
}

/**
 * Tap → room or device (pure, unit tested), in either view. [view] maps the 2D plan's pixels to
 * the screen ([CcAffine.IDENTITY] for the flat plan, the isometric map in 3D). Markers stay
 * upright in 3D, so a device is the nearest marker on screen within [radiusPx]; a room is found by
 * taking the tap back through the inverse projection to the floor and asking which room is there.
 */
internal object CcPlanHitTest {
  fun hit(model: CcPlanModel, fit: CcPlanFit, view: CcAffine, x: Float, y: Float, radiusPx: Float): CcPlanHit? {
    device(model, fit, view, x, y, radiusPx)?.let { return it }
    val back = view.inverse() ?: return null
    val px = back.x(x, y)
    val py = back.y(x, y)
    return CcPlanGeo.roomAt(model.floor, fit.cellX(px), fit.cellY(py))?.let { CcPlanHit.Room(it.id) }
  }

  private fun device(model: CcPlanModel, fit: CcPlanFit, view: CcAffine, x: Float, y: Float, radiusPx: Float): CcPlanHit.Device? {
    var best: String? = null
    var bestD = Float.MAX_VALUE
    fun consider(id: String, cx: Float, cy: Float) {
      val px = fit.x(cx)
      val py = fit.y(cy)
      val d = hypot(view.x(px, py) - x, view.y(px, py) - y)
      if (d <= radiusPx && d < bestD) { best = id; bestD = d }
    }
    for (b in model.badges) if (b.kind != CcMapBadgeKind.APPLIANCE_DONE) consider(b.entityId, b.x, b.y)
    for (ch in model.chips) consider(ch.entityId, ch.x, ch.y)
    for (d in model.dots) consider(d.entityId, d.x, d.y)
    val id = best ?: return null
    val room = model.floor.rooms.firstOrNull { r -> r.devices.any { it.entityId == id } }
        ?: model.floor.openings.firstOrNull { it.entityId == id }?.let { o -> model.floor.rooms.firstOrNull { it.id == o.roomId } }
        ?: return null
    return CcPlanHit.Device(room.id, id, model.badges.firstOrNull { it.entityId == id }?.kind)
  }
}
