/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * `POST /remote/portalpin` ([PortalPinWire]): another Portal of this home talks to the pinning
 * mailbox we host. Outer filter first (household `portal_pin` header, a Portal id — never a
 * phone — calling from the address it last announced), then the strict body, then
 * [PortalPinMailbox]'s rules. Logs caller ids and outcomes only, never codes or messages.
 */
internal class PortalPinRoute(
    private val key: () -> ByteArray?,
    private val selfId: () -> String?,
    private val clock: () -> Long,
    private val replay: HouseholdMessage.ReplayGuard,
    /** The LAN address household Portal [id] last announced in its signed hello, or null if unknown. */
    private val announcedHost: (String) -> String?,
    private val mailbox: PortalPinMailbox,
    private val log: (String) -> Unit,
) {
  fun handle(req: FleetHttpServer.Request): FleetHttpServer.Response {
    if (req.method != "POST") return reply(405, "method_not_allowed")
    if (req.contentLength !in 1..PortalPinWire.MAX_BODY) return reply(413, "body_too_large")
    val key = key() ?: return reply(503, "no_household_key")
    // Header first: an unsigned or stale request never gets its body read.
    if (!PeerRequestGate.admits(req.header(PortalPinWire.HEADER), PortalPinWire.REQUEST_TYPE, key, clock()))
      return reply(401, "unauthorized")
    val text = try { req.bodyText(PortalPinWire.MAX_BODY) } catch (e: Exception) {
      return FleetHttpServer.requestBodyError(e) ?: reply(400, "bad_body")
    }
    val self = selfId() ?: return reply(503, "no_identity")
    val from = PortalPinWire.verifyRequest(req.header(PortalPinWire.HEADER), text, key, clock(), replay, self)
        ?: return reply(401, "unauthorized")
    refusal(from, req.remoteAddress)?.let { code -> log("refused pinning from $from: $code"); return reply(403, code) }
    val request = PortalPinWire.parse(text) ?: return reply(400, "bad_request")
    val outcome = mailbox.handle(from, request)
    if (outcome.status != 404) log("pinning ${request.op.wire} from $from: ${outcome.status}")
    val body = outcome.reply ?: return reply(outcome.status, outcome.error ?: "failed")
    return FleetHttpServer.Response(outcome.status, PortalPinWire.encodeReply(body))
  }

  private fun refusal(from: String, remoteAddress: String): String? {
    if (!HomeLinkWire.validDevice(from)) return "forbidden"
    val announced = announcedHost(from) ?: return "unknown_peer"
    return if (HaShareRoute.normalizedAddress(remoteAddress) != announced) "unknown_peer" else null
  }

  private fun reply(status: Int, code: String) = RemoteFeatureRoutes.json(status, RemoteFeatureRoutes.error(code))
}
