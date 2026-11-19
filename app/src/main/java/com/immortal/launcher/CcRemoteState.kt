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
 * The phone's state answer (pure, unit tested). The phone sends the `rev` and `minute` it holds;
 * when neither moved it gets only the connection (`unchanged`), else the entity delta from
 * [CcRemoteJournal] plus every derived slice, worked out once here.
 *
 * `{ok, v, rev, minute, now, portal, conn, asks, fahrenheit, planAt, full?, unchanged?,
 *   entities[], removed[], alerts[], security{}, cameras[], scenes[], rooms[], tesla?}`
 */
internal object CcRemoteState {
  const val VERSION = 1
  private const val MS_PER_MIN = 60_000L

  fun json(s: CcUiState, journal: CcRemoteJournal, since: Long, minute: Long, now: Long, portal: String): JSONObject {
    val delta = journal.read(s.entities, s.entitiesVersion, since)
    val nowMinute = now / MS_PER_MIN
    val out = JSONObject().put("ok", true).put("v", VERSION).put("rev", delta.rev).put("minute", nowMinute).put("now", now)
        .put("portal", portal).put("conn", conn(s.connection)).put("planAt", s.plan?.updatedAt ?: -1L)
        .put("hasPlan", s.plan?.isEmpty == false)
    if (!delta.full && delta.changed.isEmpty() && delta.removed.isEmpty() && minute == nowMinute) return out.put("unchanged", true)
    return out.put("full", delta.full).put("asks", asks(s.settings)).put("fahrenheit", s.fahrenheit)
        .put("entities", CcRemoteEntities.encodeAll(delta.changed)).put("removed", JSONArray(delta.removed))
        .put("alerts", CcRemoteSlices.alertsJson(s, now)).put("security", CcRemoteSlices.securityJson(s, now))
        .put("cameras", CcRemoteSlices.camerasJson(s)).put("scenes", CcRemoteSlices.scenesJson(s))
        .put("rooms", CcRemoteSlices.roomsJson(s)).putOpt("tesla", CcRemoteTesla.json(s, now))
  }

  fun conn(c: CcConnection): JSONObject = JSONObject().put("phase", c.phase.name.lowercase()).put("step", c.step)
      .put("lastLiveAt", c.lastLiveAt).put("retryAt", c.retryAt).putOpt("reason", c.disabledReason)

  /** The household's asks, so the phone can say up front which taps will want a confirm. */
  private fun asks(s: ControlCenterConfig.Settings): JSONObject = JSONObject().put("unlock", s.askUnlock)
      .put("garage", s.askGarage).put("valve", s.askValve).put("scenes", s.askScenes)

  /** The plan (rooms, openings and placed devices) for the phone's map; [CcPlanJson] is the same shape HA stores. */
  fun plan(s: CcUiState): JSONObject = JSONObject().put("ok", true).put("planAt", s.plan?.updatedAt ?: -1L)
      .put("plan", s.plan?.let(CcPlanJson::encode) ?: JSONObject.NULL)
      .put("floors", JSONArray(s.plan?.floors.orEmpty().map { JSONObject().put("id", it.id).put("name", it.name) }))
}
