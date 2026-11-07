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
 * `/remote/list` for the paired phone (Toolkit's List tab). Session auth, body limits and JSON
 * parsing happen in [RemoteCompanionRoutes]; this validates the batch itself.
 *
 * GET → the list view. POST {device, expectedRevision?, actions[]} → applies the batch (see
 * [HouseholdListRemoteActions]) and returns the same view plus per-action `results` and `stale`.
 */
internal object HouseholdListRoute {
  const val PATH = "/remote/list"

  fun handle(context: Context, method: String, body: JSONObject): FleetHttpServer.Response = when (method) {
    "GET" -> RemoteFeatureRoutes.json(200, view(context, HouseholdListStore.current(context)))
    "POST" -> post(context, body)
    else -> RemoteFeatureRoutes.json(405, RemoteFeatureRoutes.error("method_not_allowed"))
  }

  private fun post(context: Context, body: JSONObject): FleetHttpServer.Response {
    val now = HouseholdListStore.clock()
    val outcome = HouseholdListStore.update(context, HouseholdListStore.Source.PHONE) { s ->
      when (val r = HouseholdListRemoteActions.apply(s, body, now)) {
        is HouseholdListRemoteActions.Applied -> r.state to r
        is HouseholdListRemoteActions.Rejected -> s to r
      }
    }
    return when (outcome) {
      is HouseholdListRemoteActions.Rejected -> RemoteFeatureRoutes.json(outcome.status, RemoteFeatureRoutes.error(outcome.code))
      is HouseholdListRemoteActions.Applied -> {
        HouseholdListSync.notePhone(outcome.device)
        RemoteFeatureRoutes.json(200, view(context, HouseholdListStore.current(context))
            .put("results", outcome.results).put("stale", outcome.stale))
      }
    }
  }

  /** Toolkit contract: ok | partial | syncing | offline | failing | first. */
  fun wireState(kind: HouseholdListSyncStatus.Kind): String =
      if (kind == HouseholdListSyncStatus.Kind.BUSY) "syncing" else kind.name.lowercase()

  fun view(context: Context, s: HouseholdListState): JSONObject {
    val sync = HouseholdListSync.state.value
    val status = sync.status(HouseholdListStore.clock(), s)
    val peers = JSONArray()
    sync.peers.values.sortedBy { it.name }.forEach { peers.put(JSONObject().put("name", it.name).put("synced", it.reachable)) }
    return RemoteFeatureRoutes.ok()
        .put("revision", s.digest)
        .put("portal", HouseholdListSync.selfName(context))
        .put("items", HouseholdListCodec.publicItems(s))
        .put("usedBefore", JSONArray(HouseholdListOps.usedBefore(s, limit = 8)))
        .put("sync", JSONObject().put("state", wireState(status.kind)).put("peers", peers))
  }
}

/**
 * `/remote/listpeer`: the Portal↔Portal exchange. Not session-authenticated (Portals hold no
 * tokens for each other) — every request must carry a fresh [HouseholdKey]-signed header that pins
 * the body, and the reply is signed against the request's nonce (see [HouseholdListPeerAuth]).
 */
internal object HouseholdListPeerRoute {
  const val PATH = "/remote/listpeer"
  private const val MAX_BODY = 512 * 1024
  private val replay = HouseholdMessage.ReplayGuard()

  fun handle(context: Context, req: FleetHttpServer.Request): FleetHttpServer.Response {
    if (req.method != "POST") return RemoteFeatureRoutes.json(405, RemoteFeatureRoutes.error("method_not_allowed"))
    if (req.contentLength !in 1..MAX_BODY) return RemoteFeatureRoutes.json(413, RemoteFeatureRoutes.error("body_too_large"))
    val key = HouseholdKey.get(context) ?: return RemoteFeatureRoutes.json(503, RemoteFeatureRoutes.error("no_household_key"))
    val text = try { req.bodyText(MAX_BODY) } catch (e: Exception) {
      return FleetHttpServer.requestBodyError(e) ?: RemoteFeatureRoutes.json(400, RemoteFeatureRoutes.error("bad_body"))
    }
    val self = runCatching { RemoteCompanionStore.id(context) }.getOrNull()
    val caller = HouseholdListPeerAuth.verifyRequest(req.header(HouseholdListPeerAuth.HEADER), text, key,
        System.currentTimeMillis(), replay, self) ?: return RemoteFeatureRoutes.json(401, RemoteFeatureRoutes.error("unauthorized"))
    val json = runCatching { JSONObject(text) }.getOrNull()
    val theirs = HouseholdListCodec.decodeState(json?.optJSONObject("state"), HouseholdListStore.clock())
        ?: return RemoteFeatureRoutes.json(400, RemoteFeatureRoutes.error("bad_state"))
    val name = HouseholdListText.cleanName(json?.optString("name")) ?: "the other Portal"
    HouseholdListSync.noteExchangeFrom(caller.from, name)
    val merged = HouseholdListStore.update(context, HouseholdListStore.Source.PEER) { s ->
      HouseholdListMerge.merge(s, theirs).let { it to it }
    }
    val payload = JSONObject().put("name", HouseholdListSync.selfName(context))
        .put("state", HouseholdListCodec.encodeState(merged)).toString()
    return RemoteFeatureRoutes.json(200, JSONObject().put("payload", payload)
        .put("sig", HouseholdListPeerAuth.signResponse(key, caller.nonce, payload)))
  }
}
