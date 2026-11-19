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
 * `/perf/callmsg` (fleet-token authed, measurement builds only — 404 otherwise). GET lists stored
 * messages (ids, sizes, sender, duration: never content) and the sender temp file count, which
 * must be 0 whenever no message is being composed. POST `?action=deleteAll` removes every stored
 * message; POST `?action=send&to=<room id>` opens a test-frames message to that room that records
 * 5 s and sends itself (no camera, no mic).
 */
internal object CallMessageProbe {
  fun handle(context: Context, req: FleetHttpServer.Request): FleetHttpServer.Response {
    if (!BuildConfig.PORTAL_CALL_LOOPBACK) return RemoteFeatureRoutes.json(404, RemoteFeatureRoutes.error("not_found"))
    CallMessageStore.ensureLoaded(context)
    return when (req.method) {
      "GET" -> RemoteFeatureRoutes.json(200, state(context))
      "POST" -> when (req.queryParam("action")) {
        "deleteAll" -> { CallMessageStore.deleteAll(context); RemoteFeatureRoutes.json(200, state(context)) }
        "send" -> {
          val peer = CallingStore.peers.value.firstOrNull { it.id == req.queryParam("to") }?.takeIf(CallMessages::canSend)
              ?: return RemoteFeatureRoutes.json(404, RemoteFeatureRoutes.error("no_such_room"))
          CallMessages.compose(context, peer.contact(), testFrames = true, auto = true)
          RemoteFeatureRoutes.json(200, RemoteFeatureRoutes.ok().put("to", peer.name))
        }
        else -> RemoteFeatureRoutes.json(400, RemoteFeatureRoutes.error("bad_action"))
      }
      else -> RemoteFeatureRoutes.json(405, RemoteFeatureRoutes.error("method_not_allowed"))
    }
  }

  private fun state(context: Context): JSONObject = RemoteFeatureRoutes.ok()
      .put("messages", JSONArray(CallMessageStore.messages.value.map { m ->
        JSONObject().put("id", m.id).put("from", m.fromName).put("fromId", m.fromId).put("durationMs", m.durationMs)
            .put("bytes", m.sizeBytes).put("atMs", m.atMs).put("seen", m.seen).put("audioOnly", m.audioOnly)
      }))
      .put("bytesUsed", CallMessageStore.bytesUsed(context))
      .put("outgoingTempFiles", CallMessageStore.outgoingCount(context))
      .put("rooms", JSONArray(CallingStore.peers.value.filter(CallMessages::canSend).map { JSONObject().put("id", it.id).put("name", it.name) }))
}
