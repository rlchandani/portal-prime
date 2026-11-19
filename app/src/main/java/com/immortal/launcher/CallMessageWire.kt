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
 * Portal → Portal delivery of a video message: `POST /remote/callmsg`, body = the sealed chunks
 * ([CallMessageCrypto], transit key). The `X-Immortal-Household` header is a fresh,
 * replay-guarded `vmsg_put` [HouseholdMessage] carrying the message's metadata; the sealed body
 * itself is authenticated chunk by chunk under a key only household members can derive, so the
 * header needs no body hash. The answer is HMAC-signed over the header nonce (`vmsg_ack`). Pure.
 */
internal object CallMessageWire {
  const val PATH = "/remote/callmsg"
  const val HEADER = HouseholdListPeerAuth.HEADER
  const val TYPE = "vmsg_put"
  private const val ACK_DOMAIN = "vmsg_ack"

  /** [callId]: the unanswered call this message follows, when there was one (older senders omit it). */
  data class Put(val id: String, val to: String, val fromName: String, val durationMs: Long, val sealedBytes: Long,
                 val chunks: Int, val audioOnly: Boolean, val callId: String? = null)

  data class Verified(val put: Put, val from: String, val nonce: String)

  fun encode(p: Put): JSONObject = JSONObject().put("i", p.id).put("to", p.to).put("fn", p.fromName).put("ms", p.durationMs)
      .put("z", p.sealedBytes).put("c", p.chunks).put("a", p.audioOnly).apply { p.callId?.let { put("ci", it) } }

  /** Everything checked that can be checked before a byte of the body is read. */
  fun decode(b: JSONObject): Put? {
    val id = b.optString("i").takeIf(CallMessageList::isId) ?: return null
    val to = b.optString("to").takeIf(DinnerWire::isDeviceId) ?: return null
    val name = DinnerWire.cleanName(b.optString("fn")) ?: return null
    val ms = (b.opt("ms") as? Number)?.toLong()?.takeIf { it > 0 } ?: return null
    val z = (b.opt("z") as? Number)?.toLong() ?: return null
    val c = (b.opt("c") as? Number)?.toInt() ?: return null
    val plain = CallMessageCrypto.plainLength(z)
    if (plain <= 0 || CallMessageCrypto.chunkCount(plain) != c) return null
    // An unknown or malformed call id only loses the link; it never refuses the message.
    return Put(id, to, name, ms, z, c, b.optBoolean("a", false), b.optString("ci").takeIf(CallWire::isCallId))
  }

  fun requestHeader(key: ByteArray, from: String, nonce: String, ts: Long, put: Put): String? =
      HouseholdMessage.encodeOrNull(HouseholdMessage(TYPE, from, ts, nonce, encode(put)), key)

  /** The verified sender and metadata, or null (missing/forged/stale/replayed, not for us, malformed). */
  fun verify(header: String?, key: ByteArray, nowMs: Long, replay: HouseholdMessage.ReplayGuard, self: String?): Verified? {
    if (header == null || self == null) return null
    val msg = HouseholdMessage.decode(header, key, nowMs, replay) ?: return null
    // Phones never hold or send messages; a Portal never messages itself.
    if (msg.type != TYPE || msg.from == self || CallPhoneId.isPhone(msg.from)) return null
    val put = decode(msg.body)?.takeIf { it.to == self } ?: return null
    return Verified(put, msg.from, msg.nonce)
  }

  fun ackPayload(id: String): String = JSONObject().put("ok", true).put("i", id).toString()

  fun signAck(key: ByteArray, nonce: String, payload: String): String = hmacHex(key, "$ACK_DOMAIN|$nonce|$payload")

  fun verifyAck(key: ByteArray, nonce: String, payload: String, sig: String?, id: String): Boolean =
      sig != null && MessageDigest.isEqual(sig.toByteArray(), signAck(key, nonce, payload).toByteArray()) &&
          runCatching { JSONObject(payload) }.getOrNull()?.let { it.optBoolean("ok") && it.optString("i") == id } == true

  private fun hmacHex(key: ByteArray, text: String): String =
      Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
          .doFinal(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
