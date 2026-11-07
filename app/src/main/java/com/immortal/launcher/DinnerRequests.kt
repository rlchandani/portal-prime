/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject

/**
 * Validation of the phone's `POST /remote/dinner` body (the companion layer already authenticated
 * the session and parsed JSON). Accepted shapes:
 * - `{"kind":"preset","preset":"dinner","rooms":[id…],"sender":"Rohit’s Pixel"}`
 * - `{"kind":"custom","text":"Pizza’s here","rooms":[…]}` / `{"kind":"ping","rooms":[…]}`
 * - `{"retry":{"id":"<send id>","room":"<device id>"}}`
 */
internal object DinnerRequests {
  const val DEFAULT_SENDER = "Your phone"
  private val SEND_FIELDS = setOf("kind", "preset", "text", "rooms", "sender")

  sealed class Parsed
  data class Send(val message: DinnerMessage, val rooms: List<String>, val senderName: String) : Parsed()
  data class Retry(val sendId: String, val roomId: String) : Parsed()
  data class Invalid(val code: String) : Parsed()

  fun parse(body: JSONObject): Parsed {
    if (body.has("retry")) return parseRetry(body)
    if (body.keys().asSequence().any { it !in SEND_FIELDS }) return Invalid("unknown_field")
    val message = when (body.optString("kind")) {
      "preset" -> DinnerMessage.preset(body.optString("preset")) ?: return Invalid("invalid_preset")
      "custom" -> DinnerMessage.custom((body.opt("text") as? String)) ?: return Invalid("invalid_text")
      "ping" -> DinnerMessage.ping()
      else -> return Invalid("invalid_kind")
    }
    val rooms = DinnerWire.decodeIds(body.opt("rooms")) ?: return Invalid("invalid_rooms")
    val sender = when (val raw = body.opt("sender")) {
      null -> DEFAULT_SENDER
      is String -> DinnerWire.cleanName(raw) ?: DEFAULT_SENDER
      else -> return Invalid("invalid_sender")
    }
    return Send(message, rooms, sender)
  }

  private fun parseRetry(body: JSONObject): Parsed {
    if (body.length() != 1) return Invalid("unknown_field")
    val retry = body.optJSONObject("retry") ?: return Invalid("invalid_retry")
    val id = retry.optString("id").takeIf(DinnerWire::isSendId) ?: return Invalid("invalid_retry")
    val room = retry.optString("room").takeIf(DinnerWire::isDeviceId) ?: return Invalid("invalid_retry")
    return Retry(id, room)
  }
}
