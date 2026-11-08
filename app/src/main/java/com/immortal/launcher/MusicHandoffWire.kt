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

/** How the receiving Portal answered a move. [wire] travels in `music_ack`. */
internal enum class MusicHandoffStatus(val wire: String) {
  PLAYING("p"),
  DECLINED_QUIET("q"),
  DECLINED_ROUTINE("r"),
  DECLINED_CALL("c"),
  /** The queue is there but the WebView wants a tap before it plays. */
  NEEDS_TAP("t"),
  FAILED("x"),
  /** "Bring it back" reached a Portal with nothing to send. */
  NOTHING("n");

  companion object {
    fun fromWire(text: String?): MusicHandoffStatus? = entries.firstOrNull { it.wire == text }
  }
}

/**
 * [HouseholdBus] bodies for "Move to…". The bus already checks signature, freshness and replay;
 * these decoders still treat every field as untrusted. Only small control messages ride UDP —
 * the queue itself is fetched over the signed peer route ([MusicHandoffAuth]).
 */
internal object MusicHandoffWire {
  /** Source → target: "fetch my queue for offer [Offer.id] from host:port". */
  const val OFFER = "music_offer"
  /** Target → source: the outcome ([MusicHandoffStatus]). */
  const val ACK = "music_ack"
  /** Original source → current player: "send it back to me". */
  const val RECALL = "music_recall"

  data class Offer(val id: String, val to: String, val host: String, val port: Int, val fromName: String)
  data class Ack(val id: String, val to: String, val status: MusicHandoffStatus, val roomName: String)
  data class Recall(val id: String, val to: String, val fromName: String)

  fun encode(o: Offer): JSONObject = JSONObject().put("o", o.id).put("to", o.to).put("h", o.host).put("p", o.port)
      .put("fn", o.fromName)
  fun encode(a: Ack): JSONObject = JSONObject().put("o", a.id).put("to", a.to).put("st", a.status.wire).put("fn", a.roomName)
  fun encode(r: Recall): JSONObject = JSONObject().put("o", r.id).put("to", r.to).put("fn", r.fromName)

  fun decodeOffer(b: JSONObject): Offer? {
    val id = b.optString("o").takeIf(DinnerWire::isSendId) ?: return null
    val to = b.optString("to").takeIf(DinnerWire::isDeviceId) ?: return null
    val host = (b.opt("h") as? String)?.takeIf(HouseholdListPeerAuth::isPrivateLan) ?: return null
    val port = (b.opt("p") as? Number)?.toInt()?.takeIf { it in 1..65535 } ?: return null
    val name = DinnerWire.cleanName(b.optString("fn")) ?: return null
    return Offer(id, to, host, port, name)
  }

  fun decodeAck(b: JSONObject): Ack? {
    val id = b.optString("o").takeIf(DinnerWire::isSendId) ?: return null
    val to = b.optString("to").takeIf(DinnerWire::isDeviceId) ?: return null
    val status = MusicHandoffStatus.fromWire(b.optString("st")) ?: return null
    val name = DinnerWire.cleanName(b.optString("fn")) ?: return null
    return Ack(id, to, status, name)
  }

  fun decodeRecall(b: JSONObject): Recall? {
    val id = b.optString("o").takeIf(DinnerWire::isSendId) ?: return null
    val to = b.optString("to").takeIf(DinnerWire::isDeviceId) ?: return null
    val name = DinnerWire.cleanName(b.optString("fn")) ?: return null
    return Recall(id, to, name)
  }
}

/**
 * Authentication for the queue fetch (`/remote/musicpeer`), modelled on [HouseholdListPeerAuth]:
 * the target POSTs `{"o": offerId}` with a [HouseholdKey]-signed header (own message type, fresh,
 * replay-guarded) that pins the body's SHA-256; the source answers with the queue plus an HMAC
 * bound to the request nonce under its own domain, so neither side can be forged, replayed, or
 * swapped for a list-sync exchange.
 */
internal object MusicHandoffAuth {
  const val HEADER = HouseholdListPeerAuth.HEADER
  const val FETCH_TYPE = "music_fetch"

  data class Request(val from: String, val nonce: String, val offerId: String)

  fun fetchBody(offerId: String): String = JSONObject().put("o", offerId).toString()

  fun requestHeader(key: ByteArray, from: String, nonce: String, ts: Long, body: String): String? =
      HouseholdMessage.encodeOrNull(HouseholdMessage(FETCH_TYPE, from, ts, nonce, JSONObject().put("s", sha256(body))), key)

  /** The verified caller and offer, or null for a missing/forged/stale/replayed header, a swapped body or a self call. */
  fun verifyRequest(header: String?, body: String, key: ByteArray, nowMs: Long,
                    replay: HouseholdMessage.ReplayGuard, self: String?): Request? {
    if (header == null) return null
    val msg = HouseholdMessage.decode(header, key, nowMs, replay) ?: return null
    if (msg.type != FETCH_TYPE || msg.from == self) return null
    val pinned = msg.body.opt("s") as? String ?: return null
    if (!MessageDigest.isEqual(pinned.toByteArray(), sha256(body).toByteArray())) return null
    val offerId = runCatching { JSONObject(body).optString("o") }.getOrNull()?.takeIf(DinnerWire::isSendId) ?: return null
    return Request(msg.from, msg.nonce, offerId)
  }

  fun signResponse(key: ByteArray, nonce: String, body: String): String = hmacHex(key, "music_queue|$nonce|$body")

  fun verifyResponse(key: ByteArray, nonce: String, body: String, sig: String?): Boolean =
      sig != null && MessageDigest.isEqual(sig.toByteArray(), signResponse(key, nonce, body).toByteArray())

  private fun sha256(text: String): String =
      MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

  private fun hmacHex(key: ByteArray, text: String): String =
      Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
          .doFinal(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
