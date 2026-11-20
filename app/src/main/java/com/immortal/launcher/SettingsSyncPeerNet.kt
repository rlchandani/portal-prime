/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject

/** One HTTP POST to a peer: the reply body when it answered 200 within the size cap, else null. May throw. */
internal fun interface SyncTransport {
  fun post(host: String, port: Int, path: String, authHeader: String, body: String): String?
}

/** Our end of the signed `/remote/settingspeer` exchange: sign, POST, verify the reply against our nonce. */
internal class SyncPeerClient(
    private val key: () -> ByteArray?,
    /** May throw while the companion store is unreadable (the exchange then fails). */
    private val selfId: () -> String,
    private val transport: SyncTransport,
    private val clock: () -> Long,
    private val nonce: () -> String,
) {
  /** The peer's verified payload text, or null. Only private-LAN peers are ever contacted. */
  fun post(host: String, port: Int, body: String): String? {
    if (!HouseholdListPeerAuth.isPrivateLan(host)) return null
    val key = key() ?: return null
    val self = selfId()
    val nonce = nonce()
    val header = SettingsSyncWire.requestHeader(key, self, nonce, clock(), body) ?: return null
    val text = transport.post(host, port, SettingsSyncNet.PATH, header, body) ?: return null
    val envelope = JSONObject(text)
    val payload = envelope.optString("payload")
    return payload.takeIf { SettingsSyncWire.verifyResponse(key, nonce, it, envelope.optString("sig")) }
  }
}

/** The route: verify the household signature, merge, reply signed against the caller's nonce. */
internal class SyncPeerRoute(
    private val key: () -> ByteArray?,
    private val selfId: () -> String?,
    private val clock: () -> Long,
    private val replay: HouseholdMessage.ReplayGuard,
    /** (verified caller, body) → our payload, or null while sync can't answer. */
    private val handlePeer: (String, String) -> String?,
) {
  fun handle(req: FleetHttpServer.Request): FleetHttpServer.Response {
    if (req.method != "POST") return RemoteFeatureRoutes.json(405, RemoteFeatureRoutes.error("method_not_allowed"))
    if (req.contentLength !in 1..SettingsSyncNet.MAX_BODY) return RemoteFeatureRoutes.json(413, RemoteFeatureRoutes.error("body_too_large"))
    val key = key() ?: return RemoteFeatureRoutes.json(503, RemoteFeatureRoutes.error("no_household_key"))
    // Header first: an unsigned or stale request never gets its body read.
    if (!PeerRequestGate.admits(req.header(SettingsSyncWire.HEADER), SettingsSyncWire.REQUEST_TYPE, key, clock()))
      return RemoteFeatureRoutes.json(401, RemoteFeatureRoutes.error("unauthorized"))
    val text = try { req.bodyText(SettingsSyncNet.MAX_BODY) } catch (e: Exception) {
      return FleetHttpServer.requestBodyError(e) ?: RemoteFeatureRoutes.json(400, RemoteFeatureRoutes.error("bad_body"))
    }
    val caller = SettingsSyncWire.verifyRequest(req.header(SettingsSyncWire.HEADER), text, key, clock(), replay, selfId())
        ?: return RemoteFeatureRoutes.json(401, RemoteFeatureRoutes.error("unauthorized"))
    val payload = handlePeer(caller.from, text) ?: return RemoteFeatureRoutes.json(503, RemoteFeatureRoutes.error("unavailable"))
    return RemoteFeatureRoutes.json(200, JSONObject().put("payload", payload)
        .put("sig", SettingsSyncWire.signResponse(key, caller.nonce, payload)))
  }
}
