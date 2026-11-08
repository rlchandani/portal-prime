/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject

/** What [GuestApi] needs from the device; the Android side is [GuestRoute]. */
internal interface GuestPort {
  /** The non-secret card values, keyed like [GuestConfig.Settings]. */
  fun card(): JSONObject
  /** Applies already-validated card values through the settings registry. */
  fun applyCard(values: JSONObject)
  fun hasPassword(): Boolean
  /** Blank clears. False when storage failed. */
  fun setPassword(value: String): Boolean
  fun mode(): GuestModeState
  fun start(schedule: GuestSchedule)
  fun end()
  fun nowMs(): Long
}

/**
 * `/remote/guests` behavior (auth, body limits and JSON parsing are done upstream).
 * GET: the card, `hasPassword` and the mode; never the password. POST `action`:
 * `save` (`card` values and/or `password` / `clearPassword`), `start` (`days`, or `untilHour` +
 * `untilMinute` for "until tomorrow at", or neither for "until ended") and `end`.
 */
internal class GuestApi(private val port: GuestPort) {
  fun handle(method: String, body: JSONObject): Pair<Int, JSONObject> = when (method) {
    "GET" -> 200 to snapshot()
    "POST" -> post(body)
    else -> 405 to err("method_not_allowed")
  }

  private fun post(body: JSONObject): Pair<Int, JSONObject> = when (val action = body.optString("action")) {
    "save" -> save(body)
    "start" -> start(body)
    "end" -> { port.end(); 200 to snapshot() }
    else -> 400 to err(if (action.isEmpty()) "missing_action" else "unknown_action")
  }

  private fun save(body: JSONObject): Pair<Int, JSONObject> {
    val card = body.optJSONObject("card")
    if (card != null) GuestRequests.cardError(card)?.let { return 400 to err(it) }
    val password = body.opt("password")
    val clear = body.optBoolean("clearPassword", false)
    if (password != null && (password !is String || !GuestPasswordStore.isValid(password))) return 400 to err("invalid_password")
    if (password != null && clear) return 400 to err("conflicting_password")
    if (card == null && password == null && !clear) return 400 to err("nothing_to_save")
    if (card != null) port.applyCard(card)
    val stored = when {
      password is String -> port.setPassword(password)
      clear -> port.setPassword("")
      else -> true
    }
    return if (stored) 200 to snapshot() else 503 to err("secret_storage_unavailable")
  }

  private fun start(body: JSONObject): Pair<Int, JSONObject> {
    val schedule = GuestSchedule.parse(body.intOrNull("days"), body.intOrNull("untilHour"), body.intOrNull("untilMinute"))
        ?: return 400 to err("invalid_schedule")
    port.start(schedule)
    return 200 to snapshot()
  }

  private fun snapshot(): JSONObject {
    val card = port.card()
    val hasPassword = port.hasPassword()
    val mode = port.mode()
    val on = mode.isOn(port.nowMs())
    val ready = card.optString("ssid").isNotBlank() &&
        (card.optString("security") == GuestWifi.Security.NONE.wire || hasPassword)
    return JSONObject().put("ok", true).put("card", card).put("hasPassword", hasPassword).put("ready", ready)
        .put("mode", JSONObject().put("active", on).put("until", if (on) mode.untilMs ?: JSONObject.NULL else JSONObject.NULL))
  }

  private fun err(code: String) = JSONObject().put("ok", false).put("error", code)

  /** Absent → null; a non-integer → [NOT_AN_INT], which every schedule range rejects. */
  private fun JSONObject.intOrNull(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    val v = opt(key)
    return if (v is Int || (v is Long && v in Int.MIN_VALUE..Int.MAX_VALUE)) (v as Number).toInt() else NOT_AN_INT
  }

  private companion object {
    const val NOT_AN_INT = -1
  }
}

/** Card validation for the phone route; the registry's own guards run again on apply. */
internal object GuestRequests {
  val TEXT_KEYS = setOf("ssid", "welcome", "doorCode", "checkout", "houseNote", "emergency")
  val BOOL_KEYS = setOf("hiddenNetwork", "hidePassword")

  /** Null when every key is known and well-typed; otherwise the error code. */
  fun cardError(card: JSONObject): String? {
    for (key in card.keys()) {
      val v = card.opt(key)
      val ok = when (key) {
        in TEXT_KEYS -> v is String && GuestConfig.isValidText(v)
        in BOOL_KEYS -> v is Boolean
        "security" -> v is String && GuestWifi.Security.entries.any { it.wire == v }
        else -> return "unknown_field"
      }
      if (!ok) return "invalid_$key"
    }
    return null
  }
}
