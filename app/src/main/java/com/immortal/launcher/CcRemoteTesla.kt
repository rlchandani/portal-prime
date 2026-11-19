/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject

/**
 * The Tesla screen for the phone (pure, unit tested): the same [CcCarView], wording, status grid
 * and control tiles the Portal's Tesla screen derives. Location is a zone name ("Home", "Away"),
 * never coordinates. Null when HA has no car.
 */
internal object CcRemoteTesla {
  fun json(s: CcUiState, now: Long): JSONObject? {
    val ids = s.registry?.tesla ?: return null
    val v = CcTeslaCar.view(ids, s.entities, s.connection.phase) ?: return null
    val charger = CcTeslaCharger.view(ids, s.entities)
    val idle = CcTeslaIdle.of(v, charger, ids.model)
    return JSONObject().put("label", ids.label).putOpt("model", ids.model).put("life", v.life.name.lowercase())
        .put("paintTop", v.paint.top).put("paintBottom", v.paint.bottom).put("paintName", v.paint.name)
        .putOpt("battery", v.batteryPct).putOpt("range", v.range).put("headline", CcTeslaText.headline(v, now))
        .put("charging", v.charging).putOpt("powerKw", v.powerKw).putOpt("addedKwh", v.energyAddedKwh)
        .putOpt("toFull", CcTeslaText.toFull(v.fullAt, now))
        .putOpt("limit", v.limitPct).put("limitMin", v.limitMin).put("limitMax", v.limitMax)
        .put("limitEntity", ids.car[CcCarRole.CHARGE_LIMIT] != null && v.limitPct != null)
        .put("idleTitle", idle.title).putOpt("idleDetail", idle.detail)
        .put("rows", JSONArray(CcTeslaStatus.rows(v).map { r ->
          JSONObject().put("icon", r.icon).put("label", r.label).put("value", r.value).put("tone", r.tone.name.lowercase())
        }))
        .put("actions", JSONArray(CcTeslaActions.list(ids, s.entities, v).map { a ->
          JSONObject().put("key", a.key).put("icon", a.icon).put("label", a.label).put("on", a.on)
              .put("confirm", a.call.guard == CcGuard.CONFIRM)
        }))
        .putOpt("charger", charger?.let { c ->
          JSONObject().put("source", c.source).putOpt("powerKw", c.powerKw).putOpt("sessionKwh", c.sessionKwh)
              .putOpt("status", c.status).putOpt("connected", c.connected).putOpt("cost", c.sessionCost)
        })
        .putOpt("week", s.chargeWeek?.let { w ->
          JSONObject().put("kwh", JSONArray(w.kwh)).put("days", JSONArray(w.starts.map { CcTeslaText.day(it, java.util.TimeZone.getDefault()) }))
              .putOpt("price", w.pricePerKwh)
        })
  }
}
