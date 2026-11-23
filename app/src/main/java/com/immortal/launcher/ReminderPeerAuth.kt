/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

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

  private val auth = PeerExchangeAuth(REQUEST_TYPE, RESPONSE_DOMAIN)

  fun requestHeader(key: ByteArray, from: String, nonce: String, ts: Long, body: String): String? =
      auth.requestHeader(key, from, nonce, ts, body)

  /** The verified caller, or null for a missing/forged/stale/replayed header or a swapped body. */
  fun verifyRequest(header: String?, body: String, key: ByteArray, nowMs: Long,
                    replay: HouseholdMessage.ReplayGuard, self: String?): PeerExchangeAuth.Request? =
      auth.verifyRequest(header, body, key, nowMs, replay, self)

  fun signResponse(key: ByteArray, nonce: String, body: String): String = auth.signResponse(key, nonce, body)

  fun verifyResponse(key: ByteArray, nonce: String, body: String, sig: String?): Boolean =
      auth.verifyResponse(key, nonce, body, sig)

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
}
