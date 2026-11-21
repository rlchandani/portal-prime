/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.SecureRandom
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Both ends of `/remote/hapeer` on fakes: the real [HaShareClient] posts straight into the real
 * [HaShareRoute] (loopback), so the gate, the roster/address binding, the rate limit, the seal and
 * the reply signature are all exercised end to end.
 */
class HaShareRouteTest {
  private val key = ByteArray(32) { (it * 7).toByte() }
  private val token = "eyJhbGciOiJIUzI1NiJ9.family-ha-token.sig"
  private val source = "family-1"
  private val requester = "living-1"
  private val requesterHost = "10.9.8.21"
  private var now = 1_700_000_000_000L
  private var connected: HaShareWire.Connection? = HaShareWire.Connection.of("http://ha.local:8123", token)
  private var announced = mapOf(requester to requesterHost)
  private val logs = ArrayList<String>()
  private val limiter = HaShareLimiter()
  private var nonceSeq = 0
  private val route = HaShareRoute({ key }, { source }, { "Family Area" }, { now }, HouseholdMessage.ReplayGuard(), limiter,
      { announced[it] }, { connected }, SecureRandom(), { logs += it })
  private val peer = HaSharePeer(source, "Family Area", "10.9.8.10", 8723)

  /** Delivers to [route] as if from [from], keeping the last request for replay tests. */
  private var lastHeader: String? = null
  private var lastBody: String? = null
  private fun transport(from: String = requesterHost) = HaShareTransport { _, _, header, body ->
    lastHeader = header; lastBody = body
    val resp = route.handle(request(header, body, from))
    HaShareTransport.Reply(resp.status, FleetReq.text(resp))
  }

  private fun request(header: String?, body: String, from: String = requesterHost, method: String = "POST",
                      input: InputStream = ByteArrayInputStream(body.toByteArray()), length: Int = body.length) =
      FleetHttpServer.Request(method, HaShareWire.PATH, "", header?.let { mapOf(HaShareWire.HEADER to it) } ?: emptyMap(),
          input, length, remoteAddress = from)

  private fun client(self: String = requester, from: String = requesterHost, k: ByteArray? = key) =
      HaShareClient({ k }, { self }, transport(from), { now }, { "nonce${nonceSeq++}" })

  @Test fun aHouseholdPortalGetsTheSealedConnectionAndCanOpenIt() {
    val answer = client().share(peer) as HaShareAnswer.Shared
    assertEquals("Family Area", answer.name)
    assertEquals(token, answer.connection.token)
    assertEquals("http://ha.local:8123", answer.connection.url)
    assertEquals(listOf("shared the Home Assistant connection with $requester"), logs)
  }

  @Test fun theProbeSaysConnectedWithoutAnySecret() {
    assertEquals(HaShareAnswer.Connected("Family Area"), client().probe(peer))
    val body = HaShareWire.requestBody(HaShareWire.Op.PROBE)
    val text = FleetReq.text(route.handle(request(HaShareWire.requestHeader(key, requester, "probe-x", now, body), body)))
    assertFalse(text.contains(token))
    assertFalse(text.contains("ha.local"))
  }

  @Test fun theWireNeverCarriesTheTokenInTheClear() {
    var seen = ""
    val spy = HaShareTransport { h, p, header, body ->
      transport().post(h, p, header, body).also { seen = it?.body.orEmpty() }
    }
    val answer = HaShareClient({ key }, { requester }, spy, { now }, { "nonce-spy" }).share(peer)
    assertTrue(answer is HaShareAnswer.Shared)
    assertTrue(seen.isNotEmpty())
    assertFalse(seen.contains(token))
    assertFalse(seen.contains("ha.local"))
  }

  @Test fun anUnsignedRequestIsRefusedBeforeItsBodyIsRead() {
    val exploding = object : InputStream() { override fun read(): Int = error("body must not be read") }
    val resp = route.handle(request(null, "{}", input = exploding, length = 2))
    assertEquals("401 unauthorized", FleetReq.outcome(resp))
    val forged = HaShareWire.requestHeader(ByteArray(32), requester, "n1", now, "{}")
    assertEquals("401 unauthorized", FleetReq.outcome(route.handle(request(forged, "{}", input = exploding, length = 2))))
  }

  @Test fun methodAndSizeAreCheckedFirst() {
    assertEquals("405 method_not_allowed", FleetReq.outcome(route.handle(request(null, "", method = "GET"))))
    assertEquals("413 body_too_large", FleetReq.outcome(route.handle(request(null, "x", length = HaShareWire.MAX_BODY + 1))))
    assertEquals("413 body_too_large", FleetReq.outcome(route.handle(request(null, "", length = 0))))
  }

  @Test fun aReplayedRequestIsRefused() {
    assertTrue(client().share(peer) is HaShareAnswer.Shared)
    assertEquals("401 unauthorized", FleetReq.outcome(route.handle(request(lastHeader, lastBody!!))))
  }

  @Test fun aPairedPhoneNeverGetsTheToken() {
    val phone = CallPhoneId.of("session-token")
    announced = announced + (phone to requesterHost)
    assertEquals(HaShareAnswer.Refused(403, "forbidden"), client(self = phone).share(peer))
    assertTrue(logs.single().startsWith("refused share from $phone"))
  }

  @Test fun aPortalThisOneNeverHeardIsRefused() {
    assertEquals(HaShareAnswer.Refused(403, "unknown_peer"), client(self = "stranger-9").share(peer))
  }

  @Test fun aKnownIdCallingFromAnotherAddressIsRefused() {
    // A phone (or anything else holding the household key) claiming Living's id from its own address.
    assertEquals(HaShareAnswer.Refused(403, "unknown_peer"), client(from = "10.9.8.99").share(peer))
    assertTrue(client(from = "::ffff:$requesterHost").share(peer) is HaShareAnswer.Shared)
  }

  @Test fun theRouteIsOffWhileTheSourceIsNotConnected() {
    connected = null
    assertEquals(HaShareAnswer.Refused(404, "not_connected"), client().share(peer))
    assertEquals(HaShareAnswer.Refused(404, "not_connected"), client().probe(peer))
    assertTrue(logs.isEmpty())
  }

  @Test fun sharesAreRateLimitedPerPortalAndInTotal() {
    repeat(HaShareLimiter.SHARES_PER_PEER) { assertTrue(client().share(peer) is HaShareAnswer.Shared) }
    assertEquals(HaShareAnswer.Refused(429, "rate_limited"), client().share(peer))
    // Another Portal still has its own allowance, up to the total.
    announced = announced + ("mini-1" to "10.9.8.30")
    repeat(HaShareLimiter.SHARES_TOTAL - HaShareLimiter.SHARES_PER_PEER) {
      assertTrue(client(self = "mini-1", from = "10.9.8.30").share(peer) is HaShareAnswer.Shared)
    }
    announced = announced + ("kitchen-1" to "10.9.8.31")
    assertEquals(HaShareAnswer.Refused(429, "rate_limited"), client(self = "kitchen-1", from = "10.9.8.31").share(peer))
    now += HaShareLimiter.SHARE_WINDOW_MS
    assertTrue(client().share(peer) is HaShareAnswer.Shared)
  }

  @Test fun probesHaveTheirOwnLimit() {
    repeat(HaShareLimiter.PROBES_PER_MINUTE) { assertTrue(client().probe(peer) is HaShareAnswer.Connected) }
    assertEquals(HaShareAnswer.Refused(429, "rate_limited"), client().probe(peer))
    assertTrue(client().share(peer) is HaShareAnswer.Shared)
  }

  @Test fun noLogLineEverCarriesTheTokenOrAddress() {
    client().share(peer); client(self = "stranger-9").share(peer); client(self = CallPhoneId.of("t")).share(peer)
    assertTrue(logs.isNotEmpty())
    assertTrue(logs.none { it.contains(token) || it.contains("ha.local") })
  }

  @Test fun theClientRefusesNonLanPeersAndForgedReplies() {
    var posted = 0
    val counting = HaShareTransport { _, _, _, _ -> posted++; null }
    val c = HaShareClient({ key }, { requester }, counting, { now }, { "n" })
    assertEquals(HaShareAnswer.Refused(0, "not_household_lan"), c.share(peer.copy(host = "8.8.8.8")))
    assertEquals(HaShareAnswer.Refused(0, "not_household_lan"), c.share(peer.copy(port = 22)))
    assertEquals(0, posted)
    assertEquals(HaShareAnswer.Unreachable, c.share(peer))
    val forged = HaShareTransport { _, _, _, _ -> HaShareTransport.Reply(200, JSONObject().put("payload", "{}").put("sig", "00").toString()) }
    assertEquals(HaShareAnswer.Invalid, HaShareClient({ key }, { requester }, forged, { now }, { "n" }).share(peer))
    val garbage = HaShareTransport { _, _, _, _ -> HaShareTransport.Reply(200, "<html>") }
    assertEquals(HaShareAnswer.Invalid, HaShareClient({ key }, { requester }, garbage, { now }, { "n" }).share(peer))
    val throwing = HaShareTransport { _, _, _, _ -> error("boom") }
    assertEquals(HaShareAnswer.Unreachable, HaShareClient({ key }, { requester }, throwing, { now }, { "n" }).share(peer))
    assertEquals(HaShareAnswer.Refused(0, "no_household_key"), client(k = null).share(peer))
  }

  @Test fun aReplyMeantForAnotherRequesterDoesNotOpen() {
    // The source sealed for Living; a Mini that intercepts and replays it under its own nonce gets nothing.
    val livingReply = transport().post(peer.host, peer.port,
        HaShareWire.requestHeader(key, requester, "nonce-l", now, HaShareWire.requestBody(HaShareWire.Op.SHARE))!!,
        HaShareWire.requestBody(HaShareWire.Op.SHARE))!!
    val replay = HaShareTransport { _, _, _, _ -> livingReply }
    assertEquals(HaShareAnswer.Invalid, HaShareClient({ key }, { "mini-1" }, replay, { now }, { "nonce-l" }).share(peer))
  }

  @Test fun anOddErrorBodyBecomesAGenericCode() {
    val weird = HaShareTransport { _, _, _, _ -> HaShareTransport.Reply(500, """{"error":"<script>"}""") }
    assertEquals(HaShareAnswer.Refused(500, "http_error"), HaShareClient({ key }, { requester }, weird, { now }, { "n" }).share(peer))
  }
}
