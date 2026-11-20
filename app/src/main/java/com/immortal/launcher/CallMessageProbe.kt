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
 * message (`?action=delete&id=` just one); POST `?action=send&to=<room id or phone id>` opens a test-frames message to that room that records
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
        // Night tests clean up only what they made (never the household's own messages).
        "delete" -> { req.queryParam("id")?.takeIf(CallMessageList::isId)?.let { CallMessageStore.delete(context, it) }
          RemoteFeatureRoutes.json(200, state(context)) }
        "send" -> {
          // A room, or (Calling 2b) a paired phone this Portal has heard from: its message waits in the outbox.
          val peer = req.queryParam("to")?.let(CallMessages::recipient)?.takeIf(CallMessages::canSend)
              ?: return RemoteFeatureRoutes.json(404, RemoteFeatureRoutes.error("no_such_room"))
          val contact = if (CallPhoneId.isPhone(peer.id)) CallContact(peer.id, peer.name, kind = CallContact.Kind.PHONE) else peer.contact()
          CallMessages.compose(context, contact, testFrames = true, auto = true)
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
            .put("outgoing", m.outgoing).put("linkedCall", m.callId != null)
      }))
      .put("bytesUsed", CallMessageStore.bytesUsed(context))
      .put("postersInMemory", CallMessagePosters.stats().first).put("posterBytes", CallMessagePosters.stats().second)
      .put("outgoingTempFiles", CallMessageStore.outgoingCount(context))
      .put("phoneOutbox", JSONArray(CallPhoneOutbox.list(context).map { JSONObject().put("id", it.id).put("bytes", it.sealedBytes) }))
      .put("rooms", JSONArray(CallingStore.peers.value.filter(CallMessages::canSend).map { JSONObject().put("id", it.id).put("name", it.name) }))
}
