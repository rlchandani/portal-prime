/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * `/remote/doorbell` for the paired phone (auth, body limit and JSON parsing are done upstream).
 * GET only: `{ok, configured, listening, events:[{id, kind, title, camera, room, ageMs}]}`, newest
 * first. Times are ages (durations), so phone and Portal clocks need not agree; the phone dedupes
 * by `id` across Portals. Never includes the Home Assistant address or token.
 */
internal object DoorbellRoute : RemoteFeatureRoutes.Route {
  override fun handle(context: Context, method: String, body: JSONObject): FleetHttpServer.Response =
      if (method == "GET") RemoteFeatureRoutes.json(200, snapshot(context, DoorbellAlerts.clock()))
      else RemoteFeatureRoutes.json(405, RemoteFeatureRoutes.error("method_not_allowed"))

  private fun snapshot(context: Context, now: Long): JSONObject {
    val settings = DoorbellConfig.load(context)
    return RemoteFeatureRoutes.ok()
        .put("configured", settings.watched.isNotEmpty() && HaConfig.isConfigured(context))
        .put("listening", DoorbellListener.connected)
        .put("events", JSONArray(DoorbellAlerts.recent.value.map { eventJson(it, now) }))
  }

  fun eventJson(alert: DoorbellAlert, now: Long): JSONObject = JSONObject()
      .put("id", alert.id).put("kind", alert.kind.wire).put("title", DoorbellRing.title(alert))
      .put("camera", if (alert.camera.isBlank()) "" else alert.cameraName).put("room", alert.room)
      .put("ageMs", (now - alert.detectedAtMs).coerceAtLeast(0L))
}
