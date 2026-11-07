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
 * Bodies of the Call everyone [HouseholdBus] messages. The bus already verifies the household
 * signature, freshness and replay; these decoders still treat every field as untrusted (a paired
 * Portal could run a buggy or older build), so anything malformed decodes to null and is dropped.
 * Keys are short to stay well inside [HouseholdMessage.MAX_WIRE_CHARS].
 */
internal object DinnerWire {
  const val CALL = "dinner_call"
  const val ACK = "dinner_ack"
  const val REPLY = "dinner_reply"
  const val HELLO = "dinner_hello"
  const val MAX_NAME = 40
  const val MAX_TARGETS = 8
  private val SEND_ID = Regex("^[a-f0-9]{8,16}$")
  private val DEVICE_ID = Regex("^[A-Za-z0-9_-]{1,64}$")

  fun isSendId(text: String?) = text != null && SEND_ID.matches(text)
  fun isDeviceId(text: String?) = text != null && DEVICE_ID.matches(text)

  /** Display names from peers/phone: printable, single line, clipped to [MAX_NAME]. */
  fun cleanName(raw: String?): String? {
    val text = DinnerMessage.cleanText(raw?.take(MAX_NAME * 4), Int.MAX_VALUE) ?: return null
    if (text.codePointCount(0, text.length) <= MAX_NAME) return text
    return text.substring(0, text.offsetByCodePoints(0, MAX_NAME)).trimEnd()
  }

  /** A call. [to] null means every Portal; [replyTo] is the Portal that collects acks and replies. */
  data class Call(
      val sid: String,
      val message: DinnerMessage,
      val senderName: String,
      val senderIsPhone: Boolean,
      val replyTo: String,
      val to: List<String>?,
  ) {
    fun isFor(deviceId: String) = to == null || deviceId in to
  }

  data class Ack(val sid: String, val to: String, val status: DinnerStatus, val roomName: String)
  data class Reply(val sid: String, val to: String, val reply: DinnerReply, val roomName: String)
  data class Hello(val roomName: String, val ask: Boolean, val quiet: Boolean, val receive: Boolean)

  fun encode(c: Call): JSONObject = JSONObject().put("s", c.sid).put("k", c.message.kind.wire)
      .put("fn", c.senderName).put("ph", c.senderIsPhone).put("rt", c.replyTo).apply {
        c.message.presetId?.let { put("p", it) }
        c.message.customText?.let { put("x", it) }
        c.to?.let { put("to", JSONArray(it)) }
      }

  fun encode(a: Ack): JSONObject = JSONObject().put("s", a.sid).put("to", a.to).put("st", a.status.wire).put("fn", a.roomName)
  fun encode(r: Reply): JSONObject = JSONObject().put("s", r.sid).put("to", r.to).put("r", r.reply.wire).put("fn", r.roomName)
  fun encode(h: Hello): JSONObject = JSONObject().put("fn", h.roomName).put("ask", h.ask).put("q", h.quiet).put("rc", h.receive)

  fun decodeCall(b: JSONObject): Call? {
    val sid = b.optString("s").takeIf(::isSendId) ?: return null
    val replyTo = b.optString("rt").takeIf(::isDeviceId) ?: return null
    val name = cleanName(b.optString("fn")) ?: return null
    val message = when (DinnerKind.fromWire(b.optString("k"))) {
      DinnerKind.PRESET -> DinnerMessage.preset(b.optString("p"))
      DinnerKind.CUSTOM -> DinnerMessage.custom(b.optString("x").takeIf { b.opt("x") is String })
      DinnerKind.PING -> DinnerMessage.ping()
      null -> null
    } ?: return null
    val to = if (!b.has("to")) null else decodeIds(b.opt("to")) ?: return null
    return Call(sid, message, name, b.optBoolean("ph", false), replyTo, to)
  }

  fun decodeAck(b: JSONObject): Ack? {
    val sid = b.optString("s").takeIf(::isSendId) ?: return null
    val to = b.optString("to").takeIf(::isDeviceId) ?: return null
    val status = DinnerStatus.fromWire(b.optString("st")) ?: return null
    val name = cleanName(b.optString("fn")) ?: return null
    return Ack(sid, to, status, name)
  }

  fun decodeReply(b: JSONObject): Reply? {
    val sid = b.optString("s").takeIf(::isSendId) ?: return null
    val to = b.optString("to").takeIf(::isDeviceId) ?: return null
    val reply = DinnerReply.fromWire(b.optString("r")) ?: return null
    val name = cleanName(b.optString("fn")) ?: return null
    return Reply(sid, to, reply, name)
  }

  fun decodeHello(b: JSONObject): Hello? {
    val name = cleanName(b.optString("fn")) ?: return null
    return Hello(name, b.optBoolean("ask", false), b.optBoolean("q", false), b.optBoolean("rc", true))
  }

  /** 1..[MAX_TARGETS] distinct device ids, or null when anything is off. */
  fun decodeIds(raw: Any?): List<String>? {
    val arr = raw as? JSONArray ?: return null
    if (arr.length() !in 1..MAX_TARGETS) return null
    val ids = (0 until arr.length()).map { arr.opt(it) as? String ?: return null }
    if (!ids.all(::isDeviceId)) return null
    return ids.distinct()
  }
}
