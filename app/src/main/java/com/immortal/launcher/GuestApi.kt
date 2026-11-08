/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject

/** What [GuestApi] needs from the device; the Android side is [GuestFeature]'s port. */
internal interface GuestPort {
  /** The non-secret card values, keyed like [GuestConfig.Settings]. */
  fun card(): JSONObject
  /** Applies already-validated card values through the settings registry. */
  fun applyCard(values: JSONObject)
  fun hasPassword(): Boolean
  /** Blank clears. False when storage failed. */
  fun setPassword(value: String): Boolean
  fun hasDoorCode(): Boolean
  /** A valid [GuestDoorCode] or blank (clears). False when storage failed. */
  fun setDoorCode(value: String): Boolean
  /** A free-text door code from an older Portal Toolkit ([GuestDoorCode.isStorable]). */
  fun setLegacyDoorCode(value: String): Boolean
  fun mode(): GuestModeState
  fun start(schedule: GuestSchedule)
  fun end()
  fun nowMs(): Long
}

/**
 * `/remote/guests` behavior (auth, body limits and JSON parsing are done upstream). Responses carry
 * `version` ([VERSION]); older phones ignore it, newer ones use it to spot an older Portal.
 * GET: the card, `hasPassword`, `hasDoorCode` and the mode; never a secret. POST `action`:
 * `save` (`card` values and/or `password` / `clearPassword`, `doorCode` / `clearDoorCode`),
 * `start` (`days`, or `untilHour` + `untilMinute` for "until tomorrow at", or neither for "until
 * ended") and `end`. A version-1 card (free-text basics, door code inside `card`) is still
 * accepted and folded into the choice-based card by [GuestRequests.normalize].
 */
internal class GuestApi(private val port: GuestPort) {
  private class Secret(val key: String, val clearKey: String, val valid: (String) -> Boolean, val write: (String) -> Boolean)

  private val password = Secret("password", "clearPassword", GuestPasswordStore::isValid, port::setPassword)
  private val doorCode = Secret("doorCode", "clearDoorCode", GuestDoorCode::isValid, port::setDoorCode)

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
    listOf(password, doorCode).forEach { s -> secretError(body, s)?.let { return 400 to err(it) } }
    val touchesSecret = listOf(password, doorCode).any { body.has(it.key) || body.optBoolean(it.clearKey, false) }
    if (card == null && !touchesSecret) return 400 to err("nothing_to_save")
    val normalized = card?.let { GuestRequests.normalize(it, port.card()) }
    normalized?.let { port.applyCard(it.values) }
    val stored = listOf(password, doorCode).all { writeSecret(body, it) } &&
        (normalized?.legacyDoorCode?.let(port::setLegacyDoorCode) ?: true)
    return if (stored) 200 to snapshot() else 503 to err("secret_storage_unavailable")
  }

  private fun secretError(body: JSONObject, s: Secret): String? {
    val value = body.opt(s.key)
    if (value != null && (value !is String || !s.valid(value))) return "invalid_${s.key}"
    if (value != null && body.optBoolean(s.clearKey, false)) return "conflicting_${s.key}"
    return null
  }

  private fun writeSecret(body: JSONObject, s: Secret): Boolean = when {
    body.opt(s.key) is String -> s.write(body.getString(s.key))
    body.optBoolean(s.clearKey, false) -> s.write("")
    else -> true
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
        (!GuestWifi.Security.fromWire(card.optString("security")).needsPassword || hasPassword)
    return JSONObject().put("ok", true).put("version", VERSION).put("card", card).put("hasPassword", hasPassword)
        .put("hasDoorCode", port.hasDoorCode()).put("ready", ready)
        .put("mode", JSONObject().put("active", on).put("until", if (on) mode.untilMs ?: JSONObject.NULL else JSONObject.NULL))
  }

  private fun err(code: String) = JSONObject().put("ok", false).put("version", VERSION).put("error", code)

  /** Absent → null; a non-integer → [NOT_AN_INT], which every schedule range rejects. */
  private fun JSONObject.intOrNull(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    val v = opt(key)
    return if (v is Int || (v is Long && v in Int.MIN_VALUE..Int.MAX_VALUE)) (v as Number).toInt() else NOT_AN_INT
  }

  companion object {
    /** 2: choice-based basics and the door code as a secret. */
    const val VERSION = 2
    private const val NOT_AN_INT = -1
  }
}
