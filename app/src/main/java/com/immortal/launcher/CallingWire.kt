/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject

/**
 * Bodies of the Calling [HouseholdBus] messages. The bus already verifies the household HMAC,
 * freshness (±120 s) and the nonce replay guard; these decoders still treat every field as
 * untrusted (an older or buggy Portal), so anything malformed decodes to null and is dropped.
 * Only small control messages travel here; the SDP offer/answer (too big for one datagram) go
 * over the signed peer HTTP exchange in [CallSignalAuth].
 */
internal object CallWire {
  const val RING = "call_ring"
  const val REPLY = "call_reply"
  const val END = "call_end"
  const val KEEP = "call_keep"
  const val MEDIA = "call_media"
  const val HELLO = "call_hello"
  /** Group: a member vouches that someone joined (they will offer to you). */
  const val JOIN = "call_join"
  private val CALL_ID = Regex("^[a-f0-9]{16}$")

  fun isCallId(text: String?) = text != null && CALL_ID.matches(text)

  data class Ring(val id: String, val to: String, val kind: CallKind, val video: Boolean, val name: String,
                  val host: String, val port: Int, val members: List<CallMemberRef> = emptyList())
  data class Join(val id: String, val to: String, val newcomer: CallContact)
  data class Reply(val id: String, val to: String, val reply: CallReply, val quietEndMin: Int?)
  /** [movedTo]: the sender moved the call to that device (in the recipient's id space) rather than leaving. */
  data class End(val id: String, val to: String, val reason: CallEndReason, val movedTo: String? = null)
  data class Keep(val id: String, val to: String, val capAtMs: Long)
  /** [autoAnswered]: the sender answered by itself (Grandma mode), so this call is never widened or moved. */
  data class Media(val id: String, val to: String, val cameraOn: Boolean, val micOn: Boolean, val autoAnswered: Boolean = false)
  data class Hello(val name: String, val ask: Boolean, val inCall: Boolean, val quiet: Boolean, val acceptsCalls: Boolean,
                   val dropInsOn: Boolean, val dropInPolicies: Map<String, DropInPolicy>,
                   val host: String? = null, val port: Int = 0)

  fun encode(r: Ring): JSONObject = JSONObject().put("i", r.id).put("to", r.to).put("k", r.kind.wire).put("v", r.video)
      .put("fn", r.name).put("h", r.host).put("p", r.port)
      .apply { if (r.members.isNotEmpty()) put("g", JSONArray(r.members.map(::encodeMember))) }
  fun encode(j: Join): JSONObject = JSONObject().put("i", j.id).put("to", j.to).put("n", j.newcomer.deviceId)
      .put("fn", j.newcomer.name)

  /** A phone member (phase 2b) is marked `k:"p"` and has no address: it always offers to the others. */
  private fun encodeMember(m: CallMemberRef): JSONObject = JSONObject().put("d", m.contact.deviceId).put("fn", m.contact.name)
      .apply {
        if (m.contact.phone) put("k", PHONE_MEMBER)
        else if (m.host != null && m.port in 1..65535) { put("h", m.host); put("p", m.port) }
      }
  fun encode(r: Reply): JSONObject = JSONObject().put("i", r.id).put("to", r.to).put("r", r.reply.wire)
      .apply { r.quietEndMin?.let { put("qe", it) } }
  fun encode(e: End): JSONObject = JSONObject().put("i", e.id).put("to", e.to).put("e", e.reason.wire)
      .apply { e.movedTo?.let { put("mv", it) } }
  fun encode(k: Keep): JSONObject = JSONObject().put("i", k.id).put("to", k.to).put("cap", k.capAtMs)
  fun encode(m: Media): JSONObject = JSONObject().put("i", m.id).put("to", m.to).put("cam", m.cameraOn).put("mic", m.micOn)
      .apply { if (m.autoAnswered) put("aa", true) }
  fun encode(h: Hello): JSONObject = JSONObject().put("fn", h.name).put("ask", h.ask).put("c", h.inCall).put("q", h.quiet)
      .put("rc", h.acceptsCalls).put("di", h.dropInsOn).put("dp", DropInGrid.encode(h.dropInPolicies))
      .apply { if (h.host != null && h.port in 1..65535) { put("h", h.host); put("p", h.port) } }

  fun decodeRing(b: JSONObject): Ring? {
    val id = b.optString("i").takeIf(::isCallId) ?: return null
    val to = b.optString("to").takeIf(DinnerWire::isDeviceId) ?: return null
    val kind = CallKind.fromWire(b.optString("k")) ?: return null
    val video = b.opt("v") as? Boolean ?: return null
    val name = DinnerWire.cleanName(b.optString("fn")) ?: return null
    val host = b.optString("h").takeIf(HouseholdListPeerAuth::isPrivateLan) ?: return null
    val port = (b.opt("p") as? Number)?.toInt()?.takeIf { it in 1..65535 } ?: return null
    val members = if (kind == CallKind.CALL) decodeMembers(b.optJSONArray("g")) ?: return null else emptyList()
    return Ring(id, to, kind, video, name, host, port, members)
  }

  /**
   * A group ring's member list: null if malformed or over the cap. Phase 2b: at most one member may
   * be a paired phone (`k:"p"`, a phone id, never an address); a phone id without the mark, or a
   * mark on a Portal id, is malformed.
   */
  private fun decodeMembers(arr: JSONArray?): List<CallMemberRef>? {
    if (arr == null) return emptyList()
    if (arr.length() > CallGroup.MAX_LEGS + 1) return null
    val out = (0 until arr.length()).map { i ->
      val o = arr.optJSONObject(i) ?: return null
      val id = o.optString("d").takeIf(DinnerWire::isDeviceId) ?: return null
      val phone = o.optString("k") == PHONE_MEMBER
      if (phone != CallPhoneId.isPhone(id)) return null
      val name = DinnerWire.cleanName(o.optString("fn")) ?: return null
      if (phone) return@map CallMemberRef(CallContact(id, name, kind = CallContact.Kind.PHONE), null, 0)
      val host = o.optString("h").takeIf(HouseholdListPeerAuth::isPrivateLan)
      val port = (o.opt("p") as? Number)?.toInt()?.takeIf { it in 1..65535 }
      CallMemberRef(CallContact(id, name), host.takeIf { port != null }, if (host != null) port ?: 0 else 0)
    }
    if (out.count { it.contact.phone } > CallGroup.MAX_PHONES) return null
    return out.takeIf { list -> list.map { it.contact.deviceId }.toSet().size == list.size }
  }

  fun decodeJoin(b: JSONObject): Join? {
    val id = b.optString("i").takeIf(::isCallId) ?: return null
    val to = b.optString("to").takeIf(DinnerWire::isDeviceId) ?: return null
    val newcomer = b.optString("n").takeIf(DinnerWire::isDeviceId) ?: return null
    val name = DinnerWire.cleanName(b.optString("fn")) ?: return null
    // Phase 2b: the newcomer may be a paired phone (named by the recipient's own id for it).
    val kind = if (CallPhoneId.isPhone(newcomer)) CallContact.Kind.PHONE else CallContact.Kind.ROOM
    return Join(id, to, CallContact(newcomer, name, kind = kind))
  }

  fun decodeReply(b: JSONObject): Reply? {
    val id = b.optString("i").takeIf(::isCallId) ?: return null
    val to = b.optString("to").takeIf(DinnerWire::isDeviceId) ?: return null
    val reply = CallReply.fromWire(b.optString("r")) ?: return null
    val quietEnd = (b.opt("qe") as? Number)?.toInt()?.takeIf { it in 0 until MINUTES_PER_DAY }
    return Reply(id, to, reply, quietEnd)
  }

  fun decodeEnd(b: JSONObject): End? {
    val id = b.optString("i").takeIf(::isCallId) ?: return null
    val to = b.optString("to").takeIf(DinnerWire::isDeviceId) ?: return null
    // MOVED is local only: on the wire a move is a hang-up plus `mv`.
    val reason = CallEndReason.fromWire(b.optString("e"))?.takeIf { it != CallEndReason.MOVED } ?: return null
    val movedTo = b.optString("mv").takeIf(DinnerWire::isDeviceId)
    return End(id, to, reason, movedTo)
  }

  fun decodeKeep(b: JSONObject): Keep? {
    val id = b.optString("i").takeIf(::isCallId) ?: return null
    val to = b.optString("to").takeIf(DinnerWire::isDeviceId) ?: return null
    val cap = (b.opt("cap") as? Number)?.toLong()?.takeIf { it > 0 } ?: return null
    return Keep(id, to, cap)
  }

  fun decodeMedia(b: JSONObject): Media? {
    val id = b.optString("i").takeIf(::isCallId) ?: return null
    val to = b.optString("to").takeIf(DinnerWire::isDeviceId) ?: return null
    val cam = b.opt("cam") as? Boolean ?: return null
    val mic = b.opt("mic") as? Boolean ?: return null
    return Media(id, to, cam, mic, b.optBoolean("aa", false))
  }

  fun decodeHello(b: JSONObject): Hello? {
    val name = DinnerWire.cleanName(b.optString("fn")) ?: return null
    val policies = DropInGrid.decode(b.optString("dp")) ?: emptyMap()
    // Optional (phase 2a): where this room's agent listens, for video messages. Dropped unless both are valid.
    val host = b.optString("h").takeIf(HouseholdListPeerAuth::isPrivateLan)
    val port = (b.opt("p") as? Number)?.toInt()?.takeIf { it in 1..65535 }
    return Hello(name, b.optBoolean("ask", false), b.optBoolean("c", false), b.optBoolean("q", false),
        b.optBoolean("rc", true), b.optBoolean("di", false), policies,
        host = host.takeIf { port != null }, port = if (host != null) port ?: 0 else 0)
  }

  private const val MINUTES_PER_DAY = 24 * 60
  private const val PHONE_MEMBER = "p"
}
