/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import org.json.JSONObject

/**
 * `/remote/chores` for the paired phone (session auth, body limit and JSON parsing upstream).
 * Every Portal holds the same replica, so the phone may use any of them and merge what it reads.
 * - GET → `{ok, ready, portal, day, weekStart, digest, state}`: `state` is the [ChoreCodec] replica
 *   (tombstones included) so the phone can merge copies from several Portals the same way.
 * - POST → see [ChoreRequests]; answers the same body as GET, or `{ok:false, error}`.
 * Changes reach the other Portals through [ChoreSync].
 */
internal object ChoresRoute : RemoteFeatureRoutes.Route {
  const val PATH = "/remote/chores"

  override fun handle(context: Context, method: String, body: JSONObject): FleetHttpServer.Response = when (method) {
    "GET" -> RemoteFeatureRoutes.json(200, view(context))
    "POST" -> post(context, body)
    else -> RemoteFeatureRoutes.json(405, RemoteFeatureRoutes.error("method_not_allowed"))
  }

  private fun post(context: Context, body: JSONObject): FleetHttpServer.Response {
    val request = ChoreRequests.parse(body, ChoresFeature::newId)
    if (request is ChoreRequest.Invalid) return RemoteFeatureRoutes.json(400, RemoteFeatureRoutes.error(request.code))
    val by = ChoresFeature.selfId(context)
        ?: return RemoteFeatureRoutes.json(503, RemoteFeatureRoutes.error("companion_storage_unavailable"))
    return when (val r = ChoreStore.apply(context, ChoreStore.Source.PHONE) { s -> ChoreRequests.apply(s, request, by) }) {
      is ChoreResult.Ok -> RemoteFeatureRoutes.json(200, view(context))
      is ChoreResult.Refused -> RemoteFeatureRoutes.json(status(r.code), RemoteFeatureRoutes.error(r.code))
    }
  }

  fun status(code: String): Int = when (code) {
    "unknown_kid", "unknown_chore", "unknown_tick" -> 404
    "too_many_kids", "too_many_chores", "invalid_state", "already_done" -> 409
    else -> 400
  }

  private fun view(context: Context): JSONObject {
    val s = ChoreStore.current(context)
    val day = ChoreStore.today()
    return RemoteFeatureRoutes.ok().put("ready", HouseholdKey.get(context) != null)
        .put("portal", HouseholdListSync.selfName(context)).put("day", day).put("weekStart", ChoreDays.weekStart(day))
        .put("digest", ChoreCodec.digest(s)).put("state", ChoreCodec.encodeState(s))
  }
}

/**
 * `/remote/chorespeer`: the Portal↔Portal exchange. Not session-authenticated (Portals hold no
 * tokens for each other); every request carries a fresh [HouseholdKey]-signed header pinning the
 * body, and the reply is signed against the request nonce (see [ChorePeerAuth]).
 */
internal object ChoresPeerRoute {
  const val PATH = "/remote/chorespeer"
  private const val MAX_BODY = 512 * 1024
  private val replay = HouseholdMessage.ReplayGuard()

  fun handle(context: Context, req: FleetHttpServer.Request): FleetHttpServer.Response {
    if (req.method != "POST") return RemoteFeatureRoutes.json(405, RemoteFeatureRoutes.error("method_not_allowed"))
    if (req.contentLength !in 1..MAX_BODY) return RemoteFeatureRoutes.json(413, RemoteFeatureRoutes.error("body_too_large"))
    val key = HouseholdKey.get(context) ?: return RemoteFeatureRoutes.json(503, RemoteFeatureRoutes.error("no_household_key"))
    val text = try { req.bodyText(MAX_BODY) } catch (e: Exception) {
      return FleetHttpServer.requestBodyError(e) ?: RemoteFeatureRoutes.json(400, RemoteFeatureRoutes.error("bad_body"))
    }
    val self = ChoresFeature.selfId(context)
    val caller = ChorePeerAuth.verifyRequest(req.header(ChorePeerAuth.HEADER), text, key, System.currentTimeMillis(), replay, self)
        ?: return RemoteFeatureRoutes.json(401, RemoteFeatureRoutes.error("unauthorized"))
    val theirs = ChoreCodec.decodeState(runCatching { JSONObject(text) }.getOrNull()?.optJSONObject("state"))
        ?: return RemoteFeatureRoutes.json(400, RemoteFeatureRoutes.error("bad_state"))
    val merged = ChoreStore.update(context, ChoreStore.Source.PEER) { s -> ChoreMerge.merge(s, theirs).let { it to it } }
    val payload = JSONObject().put("name", HouseholdListSync.selfName(context))
        .put("state", ChoreCodec.encodeState(ChoreOps.prune(merged, ChoreStore.today()))).toString()
    return RemoteFeatureRoutes.json(200, JSONObject().put("payload", payload)
        .put("sig", ChorePeerAuth.signResponse(key, caller.nonce, payload)))
  }
}
