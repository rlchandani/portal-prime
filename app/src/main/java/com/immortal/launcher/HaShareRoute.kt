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
 * The source Portal's end of `/remote/hapeer` ([HaShareWire]). Pure: the key, identity, clock,
 * roster and connection come in as functions.
 *
 * Who is answered: a request whose household-signed header passes [PeerRequestGate] before its
 * body is read, then [HaShareWire.verifyRequest] (replay guard, body pin, not ourselves); from a
 * Portal id, never a paired phone's; from a Portal this one has heard announce itself on the
 * household bus, calling **from the LAN address it announced**; while this Portal is connected to
 * Home Assistant; within [HaShareLimiter]. Every share and every refusal of a signed request is
 * logged by device id — never the token or the address of Home Assistant.
 */
internal class HaShareRoute(
    private val key: () -> ByteArray?,
    private val selfId: () -> String?,
    private val selfName: () -> String,
    private val clock: () -> Long,
    private val replay: HouseholdMessage.ReplayGuard,
    private val limiter: HaShareLimiter,
    /** The LAN address household Portal [id] last announced in its signed hello, or null if unknown. */
    private val announcedHost: (String) -> String?,
    /** This Portal's Home Assistant connection, or null while it is not connected. */
    private val connection: () -> HaShareWire.Connection?,
    private val random: SecureRandom,
    private val log: (String) -> Unit,
) {
  fun handle(req: FleetHttpServer.Request): FleetHttpServer.Response {
    if (req.method != "POST") return reply(405, "method_not_allowed")
    if (req.contentLength !in 1..HaShareWire.MAX_BODY) return reply(413, "body_too_large")
    val key = key() ?: return reply(503, "no_household_key")
    // Header first: an unsigned or stale request never gets its body read.
    if (!PeerRequestGate.admits(req.header(HaShareWire.HEADER), HaShareWire.REQUEST_TYPE, key, clock()))
      return reply(401, "unauthorized")
    val text = try { req.bodyText(HaShareWire.MAX_BODY) } catch (e: Exception) {
      return FleetHttpServer.requestBodyError(e) ?: reply(400, "bad_body")
    }
    val self = selfId() ?: return reply(503, "no_identity")
    val caller = HaShareWire.verifyRequest(req.header(HaShareWire.HEADER), text, key, clock(), replay, self)
        ?: return reply(401, "unauthorized")
    refusal(caller, req.remoteAddress)?.let { (status, code) ->
      log("refused ${caller.op.wire} from ${caller.from}: $code")
      return reply(status, code)
    }
    val current = connection() ?: return reply(404, "not_connected")
    val payload = when (caller.op) {
      HaShareWire.Op.PROBE -> HaShareWire.probePayload(selfName())
      HaShareWire.Op.SHARE -> HaShareWire.sharePayload(current, selfName(), key, caller.from, self, caller.nonce, random)
          ?: return reply(500, "seal_failed")
    }
    if (caller.op == HaShareWire.Op.SHARE) log("shared the Home Assistant connection with ${caller.from}")
    return RemoteFeatureRoutes.json(200, JSONObject().put("payload", payload)
        .put("sig", HaShareWire.signResponse(key, caller.nonce, payload)))
  }

  /** Why a verified caller is still refused (status to error code), or null to answer. */
  private fun refusal(caller: HaShareWire.Request, remoteAddress: String): Pair<Int, String>? {
    // A paired phone holds the household key too; the token never goes to the phone side.
    if (CallPhoneId.isPhone(caller.from) || !DinnerWire.isDeviceId(caller.from)) return 403 to "forbidden"
    val announced = announcedHost(caller.from) ?: return 403 to "unknown_peer"
    if (normalizedAddress(remoteAddress) != announced) return 403 to "unknown_peer"
    val allowed = when (caller.op) {
      HaShareWire.Op.PROBE -> limiter.probe(clock())
      HaShareWire.Op.SHARE -> limiter.share(caller.from, clock())
    }
    return if (allowed) null else 429 to "rate_limited"
  }

  private fun reply(status: Int, code: String) = RemoteFeatureRoutes.json(status, RemoteFeatureRoutes.error(code))

  companion object {
    /** The peer's IPv4 literal; an IPv4-mapped IPv6 form (`::ffff:a.b.c.d`) compares as the IPv4. */
    fun normalizedAddress(address: String): String = address.removePrefix("::ffff:")
  }
}

/**
 * Bounds for `/remote/hapeer`: at most [SHARES_PER_PEER] shares per requesting Portal and
 * [SHARES_TOTAL] in all within [SHARE_WINDOW_MS], and [PROBES_PER_MINUTE] probes a minute.
 * Thread-safe; state is swapped as immutable values. Sender ids are only remembered while their
 * window lasts, so claimed ids cannot grow it without bound.
 */
internal class HaShareLimiter {
  private var shares: List<Pair<String, Long>> = emptyList()
  private var probes: List<Long> = emptyList()

  @Synchronized fun share(from: String, nowMs: Long): Boolean {
    val recent = shares.filter { nowMs - it.second in 0 until SHARE_WINDOW_MS }
    val allowed = recent.size < SHARES_TOTAL && recent.count { it.first == from } < SHARES_PER_PEER
    shares = if (allowed) recent + (from to nowMs) else recent
    return allowed
  }

  @Synchronized fun probe(nowMs: Long): Boolean {
    val recent = probes.filter { nowMs - it in 0 until MINUTE_MS }
    val allowed = recent.size < PROBES_PER_MINUTE
    probes = if (allowed) recent + nowMs else recent
    return allowed
  }

  companion object {
    const val SHARE_WINDOW_MS = 10 * 60_000L
    const val SHARES_PER_PEER = 3
    const val SHARES_TOTAL = 6
    const val PROBES_PER_MINUTE = 30
    private const val MINUTE_MS = 60_000L
  }
}
