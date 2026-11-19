/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject

/** A phone request turned into one of the Portal's own calls, or why not. */
internal sealed interface CcRemoteIntent {
  /** [key] binds a confirm ticket to exactly this call (entity, action and value). */
  data class Call(val entity: CcEntity, val call: CcCall, val key: String) : CcRemoteIntent
  data class Invalid(val status: Int, val code: String) : CcRemoteIntent
}

/**
 * The phone's control vocabulary (pure, unit tested). The phone never names a Home Assistant
 * service: it names an entity this Portal already watches and one action from this list, and the
 * call is built with the same [CcCalls] / [CcTeslaCalls] builders as the Portal's own controls.
 * Values out of range are refused, not clamped. Car entities are driven only through `car`.
 */
internal object CcRemoteActions {
  private val ALARM_MODES = CcAlarmMode.entries.associateBy { it.name.lowercase() }
  private val CODE = Regex("[0-9A-Za-z*#]{1,16}")
  private const val MAX_RGB = 0xFFFFFF
  private const val PCT = 100

  fun build(s: CcUiState, body: JSONObject): CcRemoteIntent {
    val action = body.optString("action")
    if (action == "car" || action == "car_limit") return car(s, body, action)
    val id = body.optString("entity")
    if (!ENTITY.matches(id)) return bad("bad_entity")
    val e = s.entity(id) ?: return CcRemoteIntent.Invalid(404, "unknown_entity")
    if (id in s.registry?.tesla?.all.orEmpty()) return bad("use_car_action")
    val call = call(s, e, action, body) ?: return bad("bad_action")
    return CcRemoteIntent.Call(e, call, key(id, action, body))
  }

  private fun call(s: CcUiState, e: CcEntity, action: String, body: JSONObject): CcCall? = when (action) {
    "power" -> body.bool("on")?.let { on -> if (e.domain == "media_player") CcCalls.mediaPower(e, on) else power(e, on) }
    "brightness" -> body.int("value", 0, PCT)?.takeIf { e.domain == "light" }?.let { CcCalls.brightness(e, it) }
    "kelvin" -> body.int("value", e.minKelvin, e.maxKelvin)?.takeIf { e.domain == "light" && e.supportsColorTemp }?.let { CcCalls.kelvin(e, it) }
    "color" -> body.int("value", 0, MAX_RGB)?.takeIf { e.domain == "light" && e.supportsColor }?.let { CcCalls.color(e, it) }
    "fan" -> body.int("value", 0, PCT)?.takeIf { e.domain == "fan" }?.let { CcCalls.fanSpeed(e, it) }
    "lock" -> if (e.domain == "lock") CcCalls.lock(e) else null
    "unlock" -> if (e.domain == "lock") CcCalls.unlock(e) else null
    "open" -> when (e.domain) { "cover" -> CcCalls.coverOpen(e); "valve" -> CcCalls.valveOpen(e); else -> null }
    "close" -> when (e.domain) { "cover" -> CcCalls.coverClose(e); "valve" -> CcCalls.valveClose(e); else -> null }
    "stop" -> if (e.domain == "cover") CcCalls.coverStop(e) else null
    "position" -> body.int("value", 0, PCT)?.takeIf { e.domain == "cover" && CcKind.of(e) != CcKind.GARAGE }?.let { CcCalls.coverPosition(e, it) }
    "temperature" -> body.num("value", e.minTemp, e.maxTemp)?.takeIf { e.domain == "climate" }?.let { CcCalls.temperature(e, it) }
    "hvac" -> body.optString("value").takeIf { e.domain == "climate" && it in e.hvacModes }?.let { CcCalls.hvacMode(e, it) }
    "play_pause" -> media(e) { CcCalls.mediaPlayPause(it) }
    "next" -> media(e) { CcCalls.mediaNext(it) }
    "previous" -> media(e) { CcCalls.mediaPrevious(it) }
    "volume" -> body.num("value", 0.0, 1.0)?.takeIf { e.domain == "media_player" }?.let { CcCalls.mediaVolume(e, it.toFloat()) }
    "run" -> if (e.domain == "scene" || e.domain == "script") CcCalls.run(e) else null
    "alarm" -> alarm(e, body)
    "camera_refresh" -> if (e.domain == "camera") CcCamInfo.of(s, e.id).refreshCall(e.id) else null
    else -> null
  }

  /** Switches, fans, input booleans and appliances (lights go through their own builder). */
  private fun power(e: CcEntity, on: Boolean): CcCall? =
      if (e.domain in setOf("light", "switch", "fan", "input_boolean")) CcCalls.power(e, on) else null

  private fun media(e: CcEntity, build: (CcEntity) -> CcCall): CcCall? = if (e.domain == "media_player") build(e) else null

  private fun alarm(e: CcEntity, body: JSONObject): CcCall? {
    if (e.domain != "alarm_control_panel") return null
    val mode = ALARM_MODES[body.optString("mode")]?.takeIf { it in CcAlarmMode.offered(e) } ?: return null
    val code = body.optString("code").takeIf { it.isNotEmpty() }
    if (code != null && !CODE.matches(code)) return null
    return CcCalls.alarm(e, mode, code)
  }

  /** `car` names a control tile by key ([CcTeslaActions]); `car_limit` sets the charge limit. */
  private fun car(s: CcUiState, body: JSONObject, action: String): CcRemoteIntent {
    val ids = s.registry?.tesla?.takeIf { it.hasCar } ?: return CcRemoteIntent.Invalid(404, "no_car")
    val view = CcTeslaCar.view(ids, s.entities, s.connection.phase) ?: return CcRemoteIntent.Invalid(404, "no_car")
    if (action == "car_limit") {
      val limit = ids.car[CcCarRole.CHARGE_LIMIT]?.let(s::entity)?.takeIf { !it.unavailable } ?: return bad("no_limit")
      val pct = body.int("value", view.limitMin, view.limitMax) ?: return bad("bad_value")
      return CcRemoteIntent.Call(limit, CcTeslaCalls.limit(limit, pct), "car_limit|$pct")
    }
    val key = body.optString("key")
    val tile = CcTeslaActions.list(ids, s.entities, view).firstOrNull { it.key == key } ?: return bad("bad_action")
    val target = tile.call.entityId?.let(s::entity) ?: return CcRemoteIntent.Invalid(404, "unknown_entity")
    return CcRemoteIntent.Call(target, tile.call, "car|$key|${tile.call.service}")
  }

  /** Entity, action and the value it acts with: what a confirm ticket is bound to. The code never is. */
  private fun key(id: String, action: String, body: JSONObject): String =
      listOf(id, action, body.optString("mode"), body.opt("value")?.toString().orEmpty(), body.opt("on")?.toString().orEmpty()).joinToString("|")

  private fun bad(code: String) = CcRemoteIntent.Invalid(400, code)

  private val ENTITY = Regex("[a-z_]{2,40}\\.[a-z0-9_]{1,200}")

  private fun JSONObject.bool(k: String): Boolean? = (opt(k) as? Boolean)

  private fun JSONObject.int(k: String, min: Int, max: Int): Int? =
      (opt(k) as? Number)?.takeIf { it.toDouble() % 1.0 == 0.0 }?.toInt()?.takeIf { it in min..max }

  private fun JSONObject.num(k: String, min: Double, max: Double): Double? =
      (opt(k) as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= min && it <= max }
}
