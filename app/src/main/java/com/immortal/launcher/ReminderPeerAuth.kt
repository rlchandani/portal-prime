/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * Authentication for the Portal↔Portal reminders exchange — the chores scheme ([ChorePeerAuth])
 * with its own message types, so no other feature's request can be replayed as a reminders one:
 * - bus signal `reminders_rev` `{d: keyed digest, h: LAN IPv4, p: port}`;
 * - `POST /remote/reminderspeer` with `X-Immortal-Household`: a fresh, replay-guarded
 *   `reminders_sync` [HouseholdMessage] pinning the SHA-256 of the body `{name, sealed}`;
 * - the reply `{payload, sig}` is HMAC-signed over `reminders_state|nonce|payload`.
 * The replica itself only travels sealed ([ReminderSeal]) under the request nonce.
 */
internal object ReminderPeerAuth {
  const val HEADER = HouseholdListPeerAuth.HEADER
  const val REQUEST_TYPE = "reminders_sync"
  const val SIGNAL_TYPE = "reminders_rev"
  private const val RESPONSE_DOMAIN = "reminders_state"

  data class Request(val from: String, val nonce: String)

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

  /** `{name, sealed}`: the replica sealed for [direction] of exchange [nonce]. */
  fun sealedBody(key: ByteArray, nonce: String, direction: String, name: String, s: ReminderState): String =
      JSONObject().put("name", name)
          .put("sealed", ReminderSeal.seal(key, nonce, direction, ReminderCodec.encodeState(s).toString())).toString()

  /** The replica inside a `{name, sealed}` body, or null if it doesn't open or validate. */
  fun openBody(key: ByteArray, nonce: String, direction: String, body: String): ReminderState? {
    val sealed = runCatching { JSONObject(body).opt("sealed") as? String }.getOrNull() ?: return null
    val plain = ReminderSeal.open(key, nonce, direction, sealed) ?: return null
    return ReminderCodec.decodeState(runCatching { JSONObject(plain) }.getOrNull())
  }

  private fun sha256(text: String): String =
      MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

  private fun hmacHex(key: ByteArray, text: String): String =
      Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
          .doFinal(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
