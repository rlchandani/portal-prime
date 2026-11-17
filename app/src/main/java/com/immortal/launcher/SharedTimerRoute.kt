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
 * `/remote/timers` for the paired phone (auth, body limit and JSON parsing are done upstream).
 * Every Portal holds the same household list, so the phone may use any one of them.
 * - GET: `{ok, ready, self:{id, name, quiet}, timers:[{id, name, room, durationMs, remainingMs,
 *   state, paused, seq, startedHere}]}` — live timers first (ringing, then soonest), then recently
 *   stopped/cancelled ones (so the phone can tell "done" from "gone"). Times are durations only.
 * - POST: see [SharedTimerRequests]; answers `{ok, timer}`. Changes are relayed to the other
 *   Portals over the household bus (or stay on this Portal until the household key is set up).
 */
internal object SharedTimerRoute : RemoteFeatureRoutes.Route {
  override fun handle(context: Context, method: String, body: JSONObject): FleetHttpServer.Response = when (method) {
    "GET" -> RemoteFeatureRoutes.json(200, snapshot(context))
    "POST" -> post(context, body)
    else -> RemoteFeatureRoutes.json(405, RemoteFeatureRoutes.error("method_not_allowed"))
  }

  private fun post(context: Context, body: JSONObject): FleetHttpServer.Response {
    val now = SharedTimers.clock()
    val self = SharedTimers.selfId(context)
        ?: return RemoteFeatureRoutes.json(503, RemoteFeatureRoutes.error("companion_storage_unavailable"))
    val timer = when (val request = SharedTimerRequests.parse(body)) {
      is SharedTimerRequest.Invalid -> return RemoteFeatureRoutes.json(400, RemoteFeatureRoutes.error(request.code))
      is SharedTimerRequest.Start -> SharedTimers.start(context, request.name, request.durationMs,
          room = request.from, origin = SharedTimer.PHONE_ORIGIN)
          ?: return RemoteFeatureRoutes.json(409, RemoteFeatureRoutes.error("too_many_timers"))
      is SharedTimerRequest.Act -> when (val result = SharedTimers.act(context, request.action, request.id)) {
        is SharedTimers.ActResult.Changed -> result.timer
        SharedTimers.ActResult.Unknown -> return RemoteFeatureRoutes.json(404, RemoteFeatureRoutes.error("unknown_timer"))
        SharedTimers.ActResult.NotApplicable -> return RemoteFeatureRoutes.json(409, RemoteFeatureRoutes.error("invalid_state"))
      }
    }
    return RemoteFeatureRoutes.json(200, RemoteFeatureRoutes.ok().put("timer", SharedTimerWire.toRouteJson(timer, now, self)))
  }

  private fun snapshot(context: Context): JSONObject {
    val now = SharedTimers.clock()
    val selfId = SharedTimers.selfId(context)
    val all = SharedTimers.snapshot(context)
    val live = SharedTimerMerge.display(all, now)
    val ended = all.filterNot { it.state.live }.sortedByDescending { it.changedAtMs }
    val quiet = runCatching { SharedTimerRing.isQuiet(ChimeConfig.load(context), now, SharedTimers.zone()) }.getOrDefault(false)
    val self = JSONObject().put("id", selfId ?: JSONObject.NULL).put("name", SharedTimers.selfName(context)).put("quiet", quiet)
    return RemoteFeatureRoutes.ok().put("ready", HouseholdKey.get(context) != null).put("self", self)
        .put("timers", JSONArray((live + ended).map { SharedTimerWire.toRouteJson(it, now, selfId) }))
  }
}
