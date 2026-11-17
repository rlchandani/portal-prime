/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject

/**
 * [HouseholdBus] body for a doorbell alert, `doorbell_alert`:
 * `{i: id, k: "ring"|"motion", e: entity, c: camera or "", r: room that saw it, age: ms since}`.
 * Like timers, the time travels as an AGE, never a wall clock, so Portal clocks need not agree.
 * The bus already checks signature, freshness and replay; every field is still untrusted here.
 */
internal object DoorbellWire {
  const val ALERT = "doorbell_alert"
  /** An alert older than this when it arrives is not shown (the visitor has long gone). */
  const val MAX_AGE_MS = 60_000L
  private val ID = Regex("^[A-Za-z0-9]{8,40}$")

  fun isId(text: String?): Boolean = text != null && ID.matches(text)

  fun encode(alert: DoorbellAlert, nowMs: Long): JSONObject = JSONObject()
      .put("i", alert.id).put("k", alert.kind.wire).put("e", alert.entityId).put("c", alert.camera)
      .put("r", alert.room).put("age", (nowMs - alert.detectedAtMs).coerceAtLeast(0L))

  /** A peer's alert re-anchored to this Portal's clock at [nowMs], or null when anything is off. */
  fun decode(b: JSONObject, nowMs: Long): DoorbellAlert? {
    val id = (b.opt("i") as? String)?.takeIf(::isId) ?: return null
    val kind = DoorbellKind.fromWire(b.opt("k") as? String) ?: return null
    val entity = (b.opt("e") as? String)?.takeIf { if (kind == DoorbellKind.RING) DoorbellEntities.isTrigger(it)
        else DoorbellEntities.isMotion(it) } ?: return null
    val camera = (b.opt("c") as? String ?: return null).takeIf { it.isEmpty() || DoorbellEntities.isCamera(it) } ?: return null
    val room = DinnerWire.cleanName(b.opt("r") as? String) ?: return null
    val age = (b.opt("age") as? Number)?.toLong()?.takeIf { it in 0L..MAX_AGE_MS } ?: return null
    return DoorbellAlert(id, kind, entity, camera, room, detectedAtMs = nowMs - age)
  }
}
