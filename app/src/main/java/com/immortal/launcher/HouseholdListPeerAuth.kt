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
 * Authentication for the Portal↔Portal list exchange. Portals don't hold each other's pairing
 * tokens, so the exchange is signed with the phone-provisioned [HouseholdKey]:
 *
 * - The bus carries only a tiny [Signal] (digest + the sender's LAN address) — the full list
 *   (up to [HouseholdListOps.MAX_ITEMS] items, tombstones included) never fits one ~1400-char
 *   datagram, and chunked UDP would need its own loss recovery.
 * - The peer then POSTs its own replica to `/remote/listpeer` with an `X-Immortal-Household`
 *   header: a [HouseholdMessage] (fresh, replay-guarded) whose body pins the SHA-256 of the
 *   request body. The responder merges and answers with its replica plus an HMAC bound to the
 *   request nonce, so a LAN attacker can neither forge, replay, nor substitute either side.
 */
internal object HouseholdListPeerAuth {
  const val HEADER = "x-immortal-household"
  const val REQUEST_TYPE = "list_sync"
  const val SIGNAL_TYPE = "list_rev"
  private val DIGEST = Regex("^[0-9a-f]{12}$")
  private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

  data class Signal(val digest: String, val host: String, val port: Int)
  data class Request(val from: String, val nonce: String)

  fun signalBody(s: Signal): JSONObject = JSONObject().put("d", s.digest).put("h", s.host).put("p", s.port)

  /** The signal if it names a well-formed digest and a private (RFC 1918) IPv4 address + port. */
  fun parseSignal(body: JSONObject): Signal? {
    val d = (body.opt("d") as? String)?.takeIf(DIGEST::matches) ?: return null
    val h = (body.opt("h") as? String)?.takeIf(::isPrivateLan) ?: return null
    val p = (body.opt("p") as? Number)?.toInt()?.takeIf { it in 1..65535 } ?: return null
    return Signal(d, h, p)
  }

  fun isPrivateLan(host: String): Boolean {
    val m = IPV4.matchEntire(host) ?: return false
    val o = m.groupValues.drop(1).map { it.toInt() }
    if (o.any { it > 255 }) return false
    return o[0] == 10 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168)
  }

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

  fun signResponse(key: ByteArray, nonce: String, body: String): String = hmacHex(key, "list_state|$nonce|$body")

  fun verifyResponse(key: ByteArray, nonce: String, body: String, sig: String?): Boolean =
      sig != null && MessageDigest.isEqual(sig.toByteArray(), signResponse(key, nonce, body).toByteArray())

  private fun sha256(text: String): String =
      MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

  private fun hmacHex(key: ByteArray, text: String): String =
      Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
          .doFinal(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
