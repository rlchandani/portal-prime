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
 * Authentication for the Portal↔Portal chores exchange — the household list's scheme
 * ([HouseholdListPeerAuth]) with its own message types, so a list request can never be replayed
 * as a chores one:
 * - bus signal `chores_rev` `{d: digest, h: LAN IPv4, p: port}` ("my chores changed, pull me");
 * - `POST /remote/chorespeer` with `X-Immortal-Household`: a fresh, replay-guarded `chores_sync`
 *   [HouseholdMessage] whose body pins the SHA-256 of the request body (`{name, state}`);
 * - the reply `{payload, sig}` is HMAC-signed over `chores_state|nonce|payload`.
 */
internal object ChorePeerAuth {
  const val HEADER = HouseholdListPeerAuth.HEADER
  const val REQUEST_TYPE = "chores_sync"
  const val SIGNAL_TYPE = "chores_rev"
  private const val RESPONSE_DOMAIN = "chores_state"

  data class Request(val from: String, val nonce: String)

  fun signalBody(s: HouseholdListPeerAuth.Signal): JSONObject = HouseholdListPeerAuth.signalBody(s)

  fun parseSignal(body: JSONObject): HouseholdListPeerAuth.Signal? = HouseholdListPeerAuth.parseSignal(body)

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

  fun signResponse(key: ByteArray, nonce: String, body: String): String = hmacHex(key, "$RESPONSE_DOMAIN|$nonce|$body")

  fun verifyResponse(key: ByteArray, nonce: String, body: String, sig: String?): Boolean =
      sig != null && MessageDigest.isEqual(sig.toByteArray(), signResponse(key, nonce, body).toByteArray())

  private fun sha256(text: String): String =
      MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

  private fun hmacHex(key: ByteArray, text: String): String =
      Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
          .doFinal(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
