/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end authentication for a call, anchored on the [HouseholdKey].
 *
 * The SDP offer/answer is far bigger than one bus datagram, so it travels the way the household
 * list does ([HouseholdListPeerAuth]), with its own message types so nothing else can be replayed
 * as a call:
 * - the callee (after Accept) POSTs `{i, to, sdp, fp}` to the caller's [PATH] with an
 *   `X-Immortal-Household` header: a fresh, replay-guarded `call_offer` [HouseholdMessage] whose
 *   body pins the SHA-256 of the request body;
 * - the caller answers `{payload, sig}`, HMAC-signed over `call_answer|nonce|payload`, so the
 *   answer is bound to that exact request.
 *
 * Each side's DTLS certificate fingerprint (`a=fingerprint`) is inside its signed SDP and also
 * repeated in `fp`, which must match. WebRTC then refuses any DTLS peer whose certificate doesn't
 * hash to the signed fingerprint, so a LAN attacker without the household key can neither join
 * nor sit in the middle: the media keys are authenticated end to end by the household key.
 */
internal object CallSignalAuth {
  const val PATH = "/remote/callpeer"
  const val HEADER = HouseholdListPeerAuth.HEADER
  const val REQUEST_TYPE = "call_offer"
  private const val RESPONSE_DOMAIN = "call_answer"
  const val MAX_SDP = 32 * 1024
  private val FINGERPRINT = Regex("^sha-256 ([0-9A-F]{2}:){31}[0-9A-F]{2}$")
  private val FINGERPRINT_LINE = Regex("^a=fingerprint:(\\S+) (\\S+)\\s*$", RegexOption.MULTILINE)
  private val CANDIDATE = Regex("^a=candidate:\\S+ \\d+ (\\S+) \\d+ (\\S+) \\d+ typ (\\S+).*$")

  data class Offer(val callId: String, val to: String, val sdp: String, val fingerprint: String)
  data class Answer(val callId: String, val sdp: String, val fingerprint: String)
  data class Request(val from: String, val nonce: String)

  fun offerBody(o: Offer): String =
      JSONObject().put("i", o.callId).put("to", o.to).put("sdp", o.sdp).put("fp", o.fingerprint).toString()

  /** The offer if well-formed and its `fp` is exactly the SDP's single DTLS fingerprint. */
  fun parseOffer(text: String): Offer? {
    val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
    val id = o.optString("i").takeIf(CallWire::isCallId) ?: return null
    val to = o.optString("to").takeIf(DinnerWire::isDeviceId) ?: return null
    val sdp = o.optString("sdp").takeIf { it.length in 1..MAX_SDP } ?: return null
    val fp = o.optString("fp").takeIf { it == fingerprintOf(sdp) } ?: return null
    return Offer(id, to, sdp, fp)
  }

  fun answerPayload(a: Answer): String =
      JSONObject().put("i", a.callId).put("sdp", a.sdp).put("fp", a.fingerprint).toString()

  fun parseAnswer(payload: String, expectedCallId: String): Answer? {
    val o = runCatching { JSONObject(payload) }.getOrNull() ?: return null
    val id = o.optString("i").takeIf { it == expectedCallId } ?: return null
    val sdp = o.optString("sdp").takeIf { it.length in 1..MAX_SDP } ?: return null
    val fp = o.optString("fp").takeIf { it == fingerprintOf(sdp) } ?: return null
    return Answer(id, sdp, fp)
  }

  /**
   * The SDP's DTLS fingerprint as `sha-256 AA:BB:…`, or null unless every `a=fingerprint` line
   * names the same SHA-256 value (a second, different certificate would be a way around the
   * binding, so it is refused).
   */
  fun fingerprintOf(sdp: String): String? {
    val all = FINGERPRINT_LINE.findAll(sdp).map { "${it.groupValues[1].lowercase()} ${it.groupValues[2].uppercase()}" }.toSet()
    return all.singleOrNull()?.takeIf(FINGERPRINT::matches)
  }

  /**
   * Phase 1 is LAN-only: keep only host candidates on private IPv4 addresses (no STUN, no
   * TURN, no IPv6, no mDNS names), so a call can never leave the home network.
   */
  fun lanOnly(sdp: String): String = sdp.split("\r\n").filter { line ->
    if (!line.startsWith("a=candidate:")) return@filter true
    val m = CANDIDATE.matchEntire(line) ?: return@filter false
    m.groupValues[1].equals("udp", ignoreCase = true) && m.groupValues[3] == "host" &&
        HouseholdListPeerAuth.isPrivateLan(m.groupValues[2])
  }.joinToString("\r\n")

  /** True when the SDP still carries at least one usable LAN candidate. */
  fun hasLanCandidate(sdp: String): Boolean = sdp.split("\r\n").any { it.startsWith("a=candidate:") }

  fun requestHeader(key: ByteArray, from: String, nonce: String, ts: Long, body: String): String? =
      HouseholdMessage.encodeOrNull(HouseholdMessage(REQUEST_TYPE, from, ts, nonce, JSONObject().put("s", sha256(body))), key)

  /** The verified caller, or null for a missing/forged/stale/replayed header or a swapped body. */
  fun verifyRequest(header: String?, body: String, key: ByteArray, nowMs: Long,
                    replay: HouseholdMessage.ReplayGuard, self: String?): Request? {
    if (header == null) return null
    val msg = HouseholdMessage.decode(header, key, nowMs, replay) ?: return null
    if (msg.type != REQUEST_TYPE || msg.from == self) return null
    val pinned = msg.body.opt("s") as? String ?: return null
    if (!MessageDigest.isEqual(pinned.toByteArray(), sha256(body).toByteArray())) return null
    return Request(msg.from, msg.nonce)
  }

  fun signResponse(key: ByteArray, nonce: String, payload: String): String = hmacHex(key, "$RESPONSE_DOMAIN|$nonce|$payload")

  fun verifyResponse(key: ByteArray, nonce: String, payload: String, sig: String?): Boolean =
      sig != null && MessageDigest.isEqual(sig.toByteArray(), signResponse(key, nonce, payload).toByteArray())

  private fun sha256(text: String): String =
      MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

  private fun hmacHex(key: ByteArray, text: String): String =
      Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
          .doFinal(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
