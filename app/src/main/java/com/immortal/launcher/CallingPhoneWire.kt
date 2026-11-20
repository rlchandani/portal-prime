/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * How a paired phone (Portal Toolkit) is named in Calling. The id is derived from that phone's
 * session token on this Portal, so it can't be claimed without the token, and it never looks like
 * a Portal's UUID. Pure.
 */
internal object CallPhoneId {
  private const val PREFIX = "ph"
  private const val HEX_CHARS = 16
  private val ID = Regex("^$PREFIX[0-9a-f]{$HEX_CHARS}$")

  fun of(token: String): String = PREFIX + sha256Hex("hearthpane-phone|$token").take(HEX_CHARS)

  fun isPhone(id: String?): Boolean = id != null && ID.matches(id)

  internal fun sha256Hex(text: String): String =
      MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/**
 * Bodies a phone sends to `POST /remote/call/send`, and the phone-only `call_go`. Everything is
 * untrusted input: malformed decodes to null. Phones never drop in and have no server, so their
 * ring carries no host/port (see /tmp/calling-2a/phone-protocol.md, mirrored in the Toolkit).
 */
internal object CallPhoneWire {
  const val GO = "call_go"
  /** Portal → phone: "Move it back here" was tapped on the Portal that moved this call to the phone. */
  const val PULL = "call_pull"
  /** Either way: the device a call moved to is connected to everyone; the old one may leave. */
  const val MOVED = "call_moved"
  val SENDABLE = setOf(CallWire.RING, CallWire.REPLY, CallWire.END, CallWire.MEDIA, CallWire.JOIN, MOVED)
  /** A phone may only say these about a ring: ringing there, declined, or busy. */
  private val PHONE_REPLIES = setOf(CallReply.RINGING, CallReply.DECLINED, CallReply.BUSY)

  /** [members]: the Portals already in the call (phase 2b group/move ring); [move]: "To Portal". */
  data class PhoneRing(val id: String, val to: String, val video: Boolean, val name: String,
                       val members: List<CallContact> = emptyList(), val move: Boolean = false)

  /** Portal → phone ring. Group/move rings name the other Portals in the call (phones use their own sessions). */
  fun encodeRing(id: String, to: String, video: Boolean, name: String, members: List<CallContact> = emptyList(),
                 move: Boolean = false): JSONObject =
      JSONObject().put("i", id).put("to", to).put("k", CallKind.CALL.wire).put("v", video).put("fn", name).apply {
        val portals = members.filterNot { it.phone }
        if (portals.isNotEmpty()) put("g", JSONArray(portals.map { JSONObject().put("d", it.deviceId).put("fn", it.name) }))
        if (move) put("mv", true)
      }

  fun encodeGo(id: String, to: String): JSONObject = JSONObject().put("i", id).put("to", to)
  fun encodePull(id: String, to: String): JSONObject = JSONObject().put("i", id).put("to", to)
  fun encodeMoved(id: String, to: String): JSONObject = JSONObject().put("i", id).put("to", to)

  data class Moved(val id: String, val to: String)

  fun decodeMoved(b: JSONObject): Moved? {
    val id = b.optString("i").takeIf(CallWire::isCallId) ?: return null
    val to = b.optString("to").takeIf(DinnerWire::isDeviceId) ?: return null
    return Moved(id, to)
  }

  fun decodeRing(b: JSONObject): PhoneRing? {
    val id = b.optString("i").takeIf(CallWire::isCallId) ?: return null
    val to = b.optString("to").takeIf(DinnerWire::isDeviceId) ?: return null
    if (CallKind.fromWire(b.optString("k")) != CallKind.CALL) return null
    val video = b.opt("v") as? Boolean ?: return null
    val name = DinnerWire.cleanName(b.optString("fn")) ?: return null
    val members = decodePortals(b.optJSONArray("g"), to) ?: return null
    return PhoneRing(id, to, video, name, members, b.optBoolean("mv", false))
  }

  /** A phone's member list names Portals only (never a phone, never the recipient), at most the cap. */
  private fun decodePortals(arr: JSONArray?, self: String): List<CallContact>? {
    if (arr == null) return emptyList()
    if (arr.length() > CallGroup.MAX_LEGS) return null
    val out = (0 until arr.length()).map { i ->
      val o = arr.optJSONObject(i) ?: return null
      val d = o.optString("d").takeIf(DinnerWire::isDeviceId)?.takeUnless(CallPhoneId::isPhone)?.takeIf { it != self } ?: return null
      CallContact(d, DinnerWire.cleanName(o.optString("fn")) ?: return null)
    }
    return out.takeIf { list -> list.map { it.deviceId }.toSet().size == list.size }
  }

  /** A phone vouching for the Portal it moved its call to: the newcomer is always a Portal. */
  fun decodeJoin(b: JSONObject): CallWire.Join? = CallWire.decodeJoin(b)?.takeUnless { it.newcomer.phone }

  /** What a phone's poll says about itself (phase 2b): its id on each paired Portal, ringing in the background, long-poll. */
  data class PollInfo(val name: String, val aliases: Map<String, String>, val background: Boolean, val waitMs: Long)

  fun pollInfo(body: JSONObject): PollInfo {
    val aliases = LinkedHashMap<String, String>()
    body.optJSONObject("pa")?.let { o ->
      val keys = o.keys()
      while (keys.hasNext() && aliases.size < MAX_ALIASES) {
        val portal = keys.next()
        val phone = o.optString(portal)
        if (DinnerWire.isDeviceId(portal) && !CallPhoneId.isPhone(portal) && CallPhoneId.isPhone(phone)) aliases[portal] = phone
      }
    }
    val wait = (body.opt("w") as? Number)?.toLong()?.coerceIn(0L, CallPhones.MAX_WAIT_MS) ?: 0L
    return PollInfo(phoneName(body), aliases, body.optBoolean("bg", false), wait)
  }

  private const val MAX_ALIASES = 16

  /** A phone's reply, limited to what a phone can truthfully say. */
  fun decodeReply(b: JSONObject): CallWire.Reply? =
      CallWire.decodeReply(b)?.takeIf { it.reply in PHONE_REPLIES }?.copy(quietEndMin = null)

  /** The phone's poll body: its display name (falls back to "Phone"). */
  fun phoneName(body: JSONObject): String = DinnerWire.cleanName(body.optString("fn")) ?: DEFAULT_NAME

  const val DEFAULT_NAME = "Phone"
}

/**
 * Phone request authentication, on top of the paired-session Bearer token (checked by the route):
 * the `X-Immortal-Household` header must be a fresh, unreplayed `call_phone` [HouseholdMessage]
 * from this phone's token-derived id that pins the SHA-256 of the body and the request path, and
 * every response is HMAC-signed over the request nonce. A LAN eavesdropper who sniffs the
 * cleartext token still can't ring this Portal, inject call messages or answer an offer.
 */
internal object CallPhoneAuth {
  const val HEADER = HouseholdListPeerAuth.HEADER
  const val TYPE = "call_phone"
  const val RESPONSE_DOMAIN = "call_phone_resp"

  data class Request(val phoneId: String, val nonce: String)

  fun requestHeader(key: ByteArray, phoneId: String, nonce: String, ts: Long, path: String, body: String): String? =
      HouseholdMessage.encodeOrNull(HouseholdMessage(TYPE, phoneId, ts, nonce,
          JSONObject().put("s", CallPhoneId.sha256Hex(body)).put("p", path)), key)

  fun verify(header: String?, path: String, body: String, key: ByteArray, phoneId: String, nowMs: Long,
             replay: HouseholdMessage.ReplayGuard): Request? {
    if (header == null || !CallPhoneId.isPhone(phoneId)) return null
    val msg = HouseholdMessage.decode(header, key, nowMs, replay) ?: return null
    if (msg.type != TYPE || msg.from != phoneId) return null
    if (msg.body.opt("p") != path) return null
    val pinned = msg.body.opt("s") as? String ?: return null
    if (!MessageDigest.isEqual(pinned.toByteArray(), CallPhoneId.sha256Hex(body).toByteArray())) return null
    return Request(phoneId, msg.nonce)
  }

  fun envelope(key: ByteArray, nonce: String, payload: String, domain: String = RESPONSE_DOMAIN): JSONObject =
      JSONObject().put("payload", payload).put("sig", sign(key, domain, nonce, payload))

  /** `HMAC(key, domain|nonce|payload)`; with `call_answer` this is exactly [CallSignalAuth.signResponse]. */
  fun sign(key: ByteArray, domain: String, nonce: String, payload: String): String = hmacHex(key, "$domain|$nonce|$payload")

  const val ANSWER_DOMAIN = "call_answer"

  private fun hmacHex(key: ByteArray, text: String): String =
      javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256")) }
          .doFinal(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
