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
 * `/remote/dinner` for the paired phone (auth, body limit and JSON parsing are done upstream).
 * - GET: this room, the rooms it knows, and recent sends with per-room results and replies (the
 *   phone polls this while its results sheet is open).
 * - POST: send (relayed to the other Portals over the household bus) or retry one room; answers
 *   immediately with the send, whose rows resolve within [DinnerCall.ANSWER_WINDOW_MS].
 */
internal object DinnerRoute : RemoteFeatureRoutes.Route {
  override fun handle(context: Context, method: String, body: JSONObject): FleetHttpServer.Response = when (method) {
    "GET" -> RemoteFeatureRoutes.json(200, snapshot(context))
    "POST" -> post(context, body)
    else -> RemoteFeatureRoutes.json(405, RemoteFeatureRoutes.error("method_not_allowed"))
  }

  private fun post(context: Context, body: JSONObject): FleetHttpServer.Response {
    if (!DinnerCall.householdReady(context)) return RemoteFeatureRoutes.json(409, RemoteFeatureRoutes.error("household_not_set_up"))
    val send = when (val parsed = DinnerRequests.parse(body)) {
      is DinnerRequests.Invalid -> return RemoteFeatureRoutes.json(400, RemoteFeatureRoutes.error(parsed.code))
      is DinnerRequests.Send -> DinnerCall.sendFromPhone(context, parsed.message, parsed.rooms, parsed.senderName)
      is DinnerRequests.Retry -> DinnerCall.retry(context, parsed.sendId, parsed.roomId)
          ?: return RemoteFeatureRoutes.json(404, RemoteFeatureRoutes.error("unknown_send"))
    } ?: return RemoteFeatureRoutes.json(503, RemoteFeatureRoutes.error("companion_storage_unavailable"))
    return RemoteFeatureRoutes.json(200, RemoteFeatureRoutes.ok().put("send", send.toJson()))
  }

  private fun snapshot(context: Context): JSONObject {
    DinnerCall.hello(context) // rate-limited; refreshes who is quiet for the phone's subtitle
    val selfId = DinnerCall.selfId(context).orEmpty()
    val config = DinnerConfig.load(context)
    val quiet = DinnerQuiet.isQuiet(ChimeConfig.load(context), DinnerCall.clock(), DinnerCall.zone())
    val self = JSONObject().put("id", selfId).put("name", DinnerCall.selfName(context)).put("self", true)
        .put("quiet", quiet).put("receive", config.receive)
    val rooms = JSONArray().put(self)
    DinnerCall.peers.value.filter { it.id != selfId }.forEach { p ->
      rooms.put(JSONObject().put("id", p.id).put("name", p.name).put("self", false)
          .put("quiet", p.quiet ?: JSONObject.NULL).put("receive", p.receive ?: JSONObject.NULL))
    }
    return RemoteFeatureRoutes.ok().put("ready", DinnerCall.householdReady(context)).put("self", self).put("rooms", rooms)
        .put("sends", JSONArray(DinnerCall.book.value.sends.reversed().map { it.toJson() }))
  }
}
