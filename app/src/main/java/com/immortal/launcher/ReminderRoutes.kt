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
 * `/remote/reminders` for the paired phone (session auth, body limit and JSON parsing upstream).
 * GET → [ReminderRequests.view]: shared reminders only; private ones are just a count per person.
 * POST → `{action: add|edit|done|undo|delete, id?, title?, person?, due?, private?, base?}`, answering
 * the GET body or `{ok:false, error}`. Changes reach the other Portals through [ReminderSync].
 */
internal object RemindersRoute : RemoteFeatureRoutes.Route {
  const val PATH = "/remote/reminders"

  override fun handle(context: Context, method: String, body: JSONObject): FleetHttpServer.Response = when (method) {
    "GET" -> RemoteFeatureRoutes.json(200, view(context))
    "POST" -> post(context, body)
    else -> RemoteFeatureRoutes.json(405, RemoteFeatureRoutes.error("method_not_allowed"))
  }

  private fun post(context: Context, body: JSONObject): FleetHttpServer.Response {
    val request = ReminderRequests.parse(body)
    if (request is ReminderRequest.Invalid) return RemoteFeatureRoutes.json(400, RemoteFeatureRoutes.error(request.code))
    val by = ChoresFeature.selfId(context)
        ?: return RemoteFeatureRoutes.json(503, RemoteFeatureRoutes.error("companion_storage_unavailable"))
    val people = WhosThereStore.householdState.value.people
    val r = ReminderStore.apply(context, ReminderStore.Source.PHONE) { s ->
      ReminderRequests.apply(s, request, people, ChoresFeature.newId(), by, ReminderStore.clock())
    }
    return when (r) {
      is ReminderResult.Ok -> RemoteFeatureRoutes.json(200, view(context))
      is ReminderResult.Refused -> RemoteFeatureRoutes.json(status(r.code), RemoteFeatureRoutes.error(r.code))
    }
  }

  fun status(code: String): Int = when (code) {
    "unknown_reminder", "unknown_person" -> 404
    "too_many_reminders", "duplicate_reminder" -> 409
    else -> 400
  }

  private fun view(context: Context): JSONObject = ReminderRequests.view(ReminderStore.current(context),
      WhosThereStore.householdState.value.people, HouseholdListSync.selfName(context), HouseholdKey.get(context) != null)
}

/**
 * `/remote/reminderspeer`: the Portal↔Portal exchange. Not session-authenticated (Portals hold no
 * tokens for each other); every request carries a fresh [HouseholdKey]-signed header pinning the
 * body, the replica inside is sealed under the request nonce, and so is the signed reply.
 */
internal object RemindersPeerRoute {
  const val PATH = "/remote/reminderspeer"
  private const val MAX_BODY = 1024 * 1024
  private val replay = HouseholdMessage.ReplayGuard()

  fun handle(context: Context, req: FleetHttpServer.Request): FleetHttpServer.Response {
    if (req.method != "POST") return RemoteFeatureRoutes.json(405, RemoteFeatureRoutes.error("method_not_allowed"))
    if (req.contentLength !in 1..MAX_BODY) return RemoteFeatureRoutes.json(413, RemoteFeatureRoutes.error("body_too_large"))
    val key = HouseholdKey.get(context) ?: return RemoteFeatureRoutes.json(503, RemoteFeatureRoutes.error("no_household_key"))
    // Header first: an unsigned or stale request never gets its body read.
    if (!PeerRequestGate.admits(req.header(ReminderPeerAuth.HEADER), ReminderPeerAuth.REQUEST_TYPE, key, System.currentTimeMillis()))
      return RemoteFeatureRoutes.json(401, RemoteFeatureRoutes.error("unauthorized"))
    val text = try { req.bodyText(MAX_BODY) } catch (e: Exception) {
      return FleetHttpServer.requestBodyError(e) ?: RemoteFeatureRoutes.json(400, RemoteFeatureRoutes.error("bad_body"))
    }
    val caller = ReminderPeerAuth.verifyRequest(req.header(ReminderPeerAuth.HEADER), text, key, System.currentTimeMillis(),
        replay, ChoresFeature.selfId(context)) ?: return RemoteFeatureRoutes.json(401, RemoteFeatureRoutes.error("unauthorized"))
    val theirs = ReminderPeerAuth.openBody(key, caller.nonce, ReminderSeal.REQUEST, text)
        ?: return RemoteFeatureRoutes.json(400, RemoteFeatureRoutes.error("bad_state"))
    val merged = ReminderStore.update(context, ReminderStore.Source.PEER) { s -> ReminderMerge.merge(s, theirs).let { it to it } }
    val payload = ReminderPeerAuth.sealedBody(key, caller.nonce, ReminderSeal.RESPONSE, HouseholdListSync.selfName(context),
        ReminderOps.prune(merged, ReminderStore.clock()))
    return RemoteFeatureRoutes.json(200, JSONObject().put("payload", payload)
        .put("sig", ReminderPeerAuth.signResponse(key, caller.nonce, payload)))
  }
}
