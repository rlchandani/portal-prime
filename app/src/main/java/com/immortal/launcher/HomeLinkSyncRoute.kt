/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.security.SecureRandom

/**
 * `POST /remote/homelinkpeer` ([HomeLinkSyncWire] v2): another Portal in this home pulls our
 * vault. Layered so the household key alone (which paired phones hold) gets nothing:
 *
 * 1. the household `home_link_sync` header (signed, fresh, not replayed, pins the body) from a
 *    Portal id at the address that Portal last announced — a cheap filter, not the trust anchor;
 * 2. the pull must be signed by a device key in our [PortalRoster] (pinned in person) that is
 *    pinned to the calling Portal's household id, and the answer is sealed to the pull's
 *    one-time ECDH key and signed by our device key.
 *
 * If the caller's vault differs from ours and this wasn't already a pull-back, [pullBack] asks
 * for theirs in turn. Logs device ids and key ids only, never vault contents.
 */
internal class HomeLinkSyncRoute(
    private val key: () -> ByteArray?,
    private val selfId: () -> String?,
    private val device: () -> HomeLinkSigner?,
    private val roster: () -> PortalRoster,
    private val clock: () -> Long,
    private val replay: HouseholdMessage.ReplayGuard,
    /** The LAN address household Portal [id] last announced in its signed hello, or null if unknown. */
    private val announcedHost: (String) -> String?,
    private val vault: () -> HomeLinkVault,
    private val pullBack: (from: String) -> Unit,
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
    val device = device() ?: return reply(503, "no_device_key")
    val from = HomeLinkSyncWire.verifyRequest(req.header(HomeLinkSyncWire.HEADER), text, key, clock(), replay, self)
        ?: return reply(401, "unauthorized")
    refusal(from, req.remoteAddress)?.let { code -> return refuse(from, code) }
    val pull = HomeLinkSyncWire.parsePull(text) ?: return reply(400, "bad_pull")
    val pin = HomeLinkSyncWire.admit(pull, roster(), HomeLinkIdentity.fingerprint(device.publicKey), clock())
        ?: return refuse(from, "not_pinned")
    // The pin is bound to the household id that Portal signed when it was pinned: another id with its key gets nothing.
    if (pin.deviceId != from) return refuse(from, "device_mismatch")
    val ours = vault()
    val answer = HomeLinkSyncWire.give(device, pull, ours, random) ?: return reply(500, "seal_failed")
    if (!pull.back && pull.digest != HomeLinkSyncWire.digest(ours, key)) pullBack(from)
    log("home-link vault to ${pin.kid} ($from)")
    return FleetHttpServer.Response(200, answer)
  }

  private fun refusal(from: String, remoteAddress: String): String? {
    if (CallPhoneId.isPhone(from) || !DinnerWire.isDeviceId(from)) return "forbidden"
    val announced = announcedHost(from) ?: return "unknown_peer"
    return if (HaShareRoute.normalizedAddress(remoteAddress) != announced) "unknown_peer" else null
  }

  private fun refuse(from: String, code: String): FleetHttpServer.Response {
    log("refused home-link sync from $from: $code")
    return reply(403, code)
  }

  private fun reply(status: Int, code: String) = RemoteFeatureRoutes.json(status, RemoteFeatureRoutes.error(code))
}
