/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject
import java.security.SecureRandom

/**
 * `POST /remote/homelinkpeer` ([HomeLinkSyncWire]): another Portal in this home sends its sealed
 * vault; we merge it and answer with ours, sealed the other way. Because the vault holds the
 * household identity's private key, a verified household request is still refused unless it
 * comes from a Portal id (never a phone, which also holds the household key) at the address that
 * Portal last announced. Logs device ids only, never vault contents.
 */
internal class HomeLinkSyncRoute(
    private val key: () -> ByteArray?,
    private val selfId: () -> String?,
    private val clock: () -> Long,
    private val replay: HouseholdMessage.ReplayGuard,
    /** The LAN address household Portal [id] last announced in its signed hello, or null if unknown. */
    private val announcedHost: (String) -> String?,
    private val vault: () -> HomeLinkVault,
    private val merge: (HomeLinkVault) -> Unit,
    private val random: SecureRandom,
    private val log: (String) -> Unit,
) {
  fun handle(req: FleetHttpServer.Request): FleetHttpServer.Response {
    if (req.method != "POST") return reply(405, "method_not_allowed")
    if (req.contentLength !in 1..HomeLinkSyncWire.MAX_BODY) return reply(413, "body_too_large")
    val key = key() ?: return reply(503, "no_household_key")
    // Header first: an unsigned or stale request never gets its body read.
    if (!PeerRequestGate.admits(req.header(HomeLinkSyncWire.HEADER), HomeLinkSyncWire.REQUEST_TYPE, key, clock()))
      return reply(401, "unauthorized")
    val text = try { req.bodyText(HomeLinkSyncWire.MAX_BODY) } catch (e: Exception) {
      return FleetHttpServer.requestBodyError(e) ?: reply(400, "bad_body")
    }
    val self = selfId() ?: return reply(503, "no_identity")
    val caller = HomeLinkSyncWire.verifyRequest(req.header(HomeLinkSyncWire.HEADER), text, key, clock(), replay, self)
        ?: return reply(401, "unauthorized")
    refusal(caller, req.remoteAddress)?.let { (status, code) ->
      log("refused home-link sync from ${caller.from}: $code")
      return reply(status, code)
    }
    val theirs = HomeLinkSyncWire.open(key, caller.from, caller.nonce, HomeLinkSyncWire.REQUEST, text)
        ?: return reply(400, "bad_state")
    merge(theirs)
    val payload = HomeLinkSyncWire.seal(key, caller.from, caller.nonce, HomeLinkSyncWire.RESPONSE, vault(), random)
        ?: return reply(500, "seal_failed")
    return RemoteFeatureRoutes.json(200, JSONObject().put("payload", payload)
        .put("sig", HomeLinkSyncWire.signResponse(key, caller.nonce, payload)))
  }

  private fun refusal(caller: HomeLinkSyncWire.Request, remoteAddress: String): Pair<Int, String>? {
    if (CallPhoneId.isPhone(caller.from) || !DinnerWire.isDeviceId(caller.from)) return 403 to "forbidden"
    val announced = announcedHost(caller.from) ?: return 403 to "unknown_peer"
    return if (HaShareRoute.normalizedAddress(remoteAddress) != announced) 403 to "unknown_peer" else null
  }

  private fun reply(status: Int, code: String) = RemoteFeatureRoutes.json(status, RemoteFeatureRoutes.error(code))
}
