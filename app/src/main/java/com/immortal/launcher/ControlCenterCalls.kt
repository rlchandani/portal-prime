/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/**
 * A Home Assistant service call plus the state the UI shows until HA echoes it ([optimistic]).
 * Built only by [CcCalls]; sent by the runtime over the websocket (`call_service`). [data] values
 * are JSON-safe (numbers, strings, booleans, lists of numbers).
 */
@Immutable
internal data class CcCall(
    val domain: String,
    val service: String,
    val entityId: String?,
    val data: Map<String, Any> = emptyMap(),
    val optimistic: CcOptimistic? = null,
    /** Short past-tense label for the Undo toast, e.g. "Floor lamp off". */
    val label: String = "",
) {
  fun toMessage(id: Int): JSONObject = JSONObject().put("id", id).put("type", "call_service")
      .put("domain", domain).put("service", service)
      .put("service_data", JSONObject().apply { data.forEach { (k, v) -> put(k, if (v is List<*>) JSONArray(v) else v) } })
      .apply { entityId?.let { put("target", JSONObject().put("entity_id", it)) } }
}

/** Fields the UI overrides while a call is in flight; null = leave as HA says. */
@Immutable
internal data class CcOptimistic(
    val state: String? = null,
    val brightnessPct: Int? = null,
    val rgb: Int? = null,
    val kelvin: Int? = null,
    val position: Int? = null,
    val targetTemp: Double? = null,
    val hvacMode: String? = null,
    val percentage: Int? = null,
    val volume: Float? = null,
) {
  /** [e] as the user should see it right now. */
  fun applyTo(e: CcEntity): CcEntity {
    var a = e.attrs
    brightnessPct?.let { a = a + ("brightness" to Math.round(it * BYTE / PCT)) }
    rgb?.let { a = a + ("rgb_color" to listOf((it shr 16) and BYTE, (it shr 8) and BYTE, it and BYTE)) + ("color_mode" to "rgb") }
    kelvin?.let { a = a + ("color_temp_kelvin" to it) + ("color_mode" to "color_temp") }
    position?.let { a = a + ("current_position" to it) }
    targetTemp?.let { a = a + ("temperature" to it) }
    percentage?.let { a = a + ("percentage" to it) }
    volume?.let { a = a + ("volume_level" to it.toDouble()) }
    return e.copy(state = hvacMode ?: state ?: e.state, attrs = a)
  }

  /** HA's echo matches what we asked for (so the override can go). */
  fun matches(e: CcEntity): Boolean =
      (state == null || e.state == state) &&
          (hvacMode == null || e.state == hvacMode) &&
          (brightnessPct == null || e.brightnessPct?.let { kotlin.math.abs(it - brightnessPct) <= 2 } == true) &&
          (position == null || e.position?.let { kotlin.math.abs(it - position) <= 2 } == true) &&
          (targetTemp == null || e.targetTemp == targetTemp) &&
          (percentage == null || e.percentage == percentage) &&
          (rgb == null || e.rgb != null) && (kelvin == null || e.colorTempK != null) &&
          (volume == null || e.volume?.let { kotlin.math.abs(it - volume) < 0.02f } == true)

  private companion object {
    const val BYTE = 255
    const val PCT = 100f
  }
}

/** Builders for every control the Control Center offers (pure, unit tested). */
internal object CcCalls {
  fun lightPower(e: CcEntity, on: Boolean) = CcCall("light", if (on) "turn_on" else "turn_off", e.id,
      optimistic = CcOptimistic(state = if (on) "on" else "off"), label = "${e.name} ${if (on) "on" else "off"}")

  fun brightness(e: CcEntity, pct: Int): CcCall {
    val p = pct.coerceIn(0, 100)
    return if (p == 0) lightPower(e, false)
    else CcCall("light", "turn_on", e.id, mapOf("brightness_pct" to p), CcOptimistic(state = "on", brightnessPct = p), "${e.name} $p%")
  }

  fun color(e: CcEntity, rgb: Int) = CcCall("light", "turn_on", e.id,
      mapOf("rgb_color" to listOf((rgb shr 16) and 0xff, (rgb shr 8) and 0xff, rgb and 0xff)),
      CcOptimistic(state = "on", rgb = rgb), "${e.name} colour")

  fun kelvin(e: CcEntity, k: Int): CcCall {
    val clamped = k.coerceIn(e.minKelvin, e.maxKelvin)
    return CcCall("light", "turn_on", e.id, mapOf("color_temp_kelvin" to clamped), CcOptimistic(state = "on", kelvin = clamped), "${e.name} white")
  }

  /** On/off for switches, fans, input_booleans and appliances. */
  fun power(e: CcEntity, on: Boolean): CcCall {
    val domain = if (e.domain == "light") return lightPower(e, on) else e.domain
    return CcCall(domain, if (on) "turn_on" else "turn_off", e.id, optimistic = CcOptimistic(state = if (on) "on" else "off"),
        label = "${e.name} ${if (on) "on" else "off"}")
  }

  fun fanSpeed(e: CcEntity, pct: Int): CcCall {
    val p = pct.coerceIn(0, 100)
    return if (p == 0) power(e, false)
    else CcCall("fan", "set_percentage", e.id, mapOf("percentage" to p), CcOptimistic(state = "on", percentage = p), "${e.name} $p%")
  }

  fun lock(e: CcEntity) = CcCall("lock", "lock", e.id, optimistic = CcOptimistic(state = "locking"), label = "${e.name} locked")
  fun unlock(e: CcEntity) = CcCall("lock", "unlock", e.id, optimistic = CcOptimistic(state = "unlocking"), label = "${e.name} unlocked")

  fun coverOpen(e: CcEntity) = CcCall("cover", "open_cover", e.id, optimistic = CcOptimistic(state = "opening"), label = "${e.name} opening")
  fun coverClose(e: CcEntity) = CcCall("cover", "close_cover", e.id, optimistic = CcOptimistic(state = "closing"), label = "${e.name} closing")
  fun coverStop(e: CcEntity) = CcCall("cover", "stop_cover", e.id, label = "${e.name} stopped")
  fun coverPosition(e: CcEntity, pct: Int): CcCall {
    val p = pct.coerceIn(0, 100)
    return CcCall("cover", "set_cover_position", e.id, mapOf("position" to p), CcOptimistic(position = p), "${e.name} $p% open")
  }

  fun valveClose(e: CcEntity) = CcCall("valve", "close_valve", e.id, optimistic = CcOptimistic(state = "closing"), label = "${e.name} closed")
  fun valveOpen(e: CcEntity) = CcCall("valve", "open_valve", e.id, optimistic = CcOptimistic(state = "opening"), label = "${e.name} opened")

  fun temperature(e: CcEntity, t: Double): CcCall {
    val step = e.tempStep.takeIf { it > 0 } ?: 1.0
    val snapped = (Math.round(t / step) * step).coerceIn(e.minTemp, e.maxTemp)
    return CcCall("climate", "set_temperature", e.id, mapOf("temperature" to snapped), CcOptimistic(targetTemp = snapped),
        "${e.name} ${format(snapped)}°")
  }

  fun hvacMode(e: CcEntity, mode: String) = CcCall("climate", "set_hvac_mode", e.id, mapOf("hvac_mode" to mode),
      CcOptimistic(hvacMode = mode), "${e.name} ${mode.replace('_', ' ')}")

  fun mediaPlayPause(e: CcEntity) = CcCall("media_player", "media_play_pause", e.id,
      optimistic = CcOptimistic(state = if (e.state == "playing") "paused" else "playing"), label = e.name)
  fun mediaNext(e: CcEntity) = CcCall("media_player", "media_next_track", e.id, label = e.name)
  fun mediaPrevious(e: CcEntity) = CcCall("media_player", "media_previous_track", e.id, label = e.name)
  fun mediaVolume(e: CcEntity, v: Float): CcCall {
    val level = (Math.round(v.coerceIn(0f, 1f) * 100) / 100.0)
    return CcCall("media_player", "volume_set", e.id, mapOf("volume_level" to level), CcOptimistic(volume = level.toFloat()), e.name)
  }
  fun mediaPower(e: CcEntity, on: Boolean) = CcCall("media_player", if (on) "turn_on" else "turn_off", e.id,
      optimistic = CcOptimistic(state = if (on) "on" else "off"), label = "${e.name} ${if (on) "on" else "off"}")

  /** Scenes and scripts stay in HA; the Portal only fires them. */
  fun run(e: CcEntity) = CcCall(e.domain, "turn_on", e.id, label = e.name)

  /** Snapshot the members first so the whole scene can be undone (`scene.create`). */
  fun snapshot(members: List<String>): CcCall? = members.takeIf { it.isNotEmpty() }?.let {
    CcCall("scene", "create", null, mapOf("scene_id" to UNDO_SCENE, "snapshot_entities" to it))
  }
  fun undoScene() = CcCall("scene", "turn_on", "scene.$UNDO_SCENE", label = "Undone")

  fun alarm(e: CcEntity, mode: CcAlarmMode, code: String?): CcCall = CcCall("alarm_control_panel", mode.service, e.id,
      if (code.isNullOrEmpty()) emptyMap() else mapOf("code" to code), label = mode.label)

  /**
   * The call that puts [before] back after [call], for the 5 s Undo. Null where undo isn't safe or
   * meaningful (locks, garages, valves, alarms and scenes have their own paths).
   */
  fun undo(before: CcEntity, call: CcCall): CcCall? = when (call.domain) {
    "light" -> if (!before.isOn) lightPower(before, false)
    else before.brightnessPct?.let { brightness(before, it) } ?: lightPower(before, true)
    "switch", "fan", "input_boolean" -> if (call.service == "set_percentage") before.percentage?.let { fanSpeed(before, it) } else power(before, before.isOn)
    "cover" -> if (CcKind.of(before) == CcKind.GARAGE) null else before.position?.let { coverPosition(before, it) }
    "climate" -> if (call.service == "set_temperature") before.targetTemp?.let { temperature(before, it) } else hvacMode(before, before.state)
    "media_player" -> if (call.service == "volume_set") before.volume?.let { mediaVolume(before, it) } else null
    else -> null
  }?.copy(label = "Undone")

  private fun format(t: Double) = if (t % 1.0 == 0.0) t.toInt().toString() else "%.1f".format(t)

  const val UNDO_SCENE = "hearthpane_undo"
}

internal enum class CcAlarmMode(val service: String, val label: String, val feature: Int) {
  DISARM("alarm_disarm", "Disarmed", 0),
  HOME("alarm_arm_home", "Armed · Home", 1),
  AWAY("alarm_arm_away", "Armed · Away", 2),
  NIGHT("alarm_arm_night", "Armed · Night", 4),
  VACATION("alarm_arm_vacation", "Armed · Vacation", 32);

  companion object {
    /** Modes this panel offers (HA supported_features bits); disarm is always available. */
    fun offered(e: CcEntity): List<CcAlarmMode> =
        listOf(DISARM) + listOf(HOME, AWAY, NIGHT).filter { e.supportedFeatures and it.feature != 0 }
  }
}

/** Which actions ask first and what kids mode hides (pure; Portal-side only — see Feasibility). */
internal object CcPolicy {
  enum class Ask { NONE, CONFIRM, CAMERA_CONFIRM, PIN }

  fun ask(e: CcEntity, call: CcCall, s: ControlCenterConfig.Settings, scenesSeen: Set<String> = emptySet()): Ask = when {
    call.domain == "lock" && call.service == "unlock" -> if (s.askUnlock) Ask.CONFIRM else Ask.NONE
    call.domain == "cover" && CcKind.of(e) == CcKind.GARAGE && call.service != "stop_cover" -> if (s.askGarage) Ask.CAMERA_CONFIRM else Ask.NONE
    call.domain == "valve" -> if (s.askValve) Ask.CONFIRM else Ask.NONE
    call.domain == "alarm_control_panel" -> if (call.service == "alarm_disarm" || e.attrs["code_arm_required"] == true && e.codeFormat != null) Ask.PIN else Ask.NONE
    // A scene/script that arms, unlocks or opens something is a security action in disguise: it always asks.
    (call.domain == "scene" || call.domain == "script") && sensitive(e) -> Ask.CONFIRM
    call.domain == "scene" && call.service == "turn_on" && s.askScenes && e.id !in scenesSeen && e.members.size >= WHOLE_HOUSE -> Ask.CONFIRM
    else -> Ask.NONE
  }

  /** Hidden from this Portal entirely while in kids mode. */
  fun hidden(e: CcEntity, s: ControlCenterConfig.Settings): Boolean = s.kids && hiddenKind(CcKind.of(e), e, s)

  fun hiddenKind(kind: CcKind, e: CcEntity?, s: ControlCenterConfig.Settings): Boolean = when (kind) {
    CcKind.LOCK -> s.kidsHideLocks
    CcKind.GARAGE -> s.kidsHideGarage
    CcKind.ALARM -> s.kidsHideSecurity
    CcKind.CAMERA -> s.kidsHideCameras
    CcKind.VALVE -> s.kidsHideValve
    else -> e != null && e.id.startsWith("switch.") && VALVE_HINTS.any { it in e.id } && s.kidsHideValve
  }

  /** Thermostats shown but not adjustable in kids mode when the parent chose so. */
  fun readOnly(e: CcEntity, s: ControlCenterConfig.Settings): Boolean = s.kids && e.domain == "climate" && s.kidsLockThermostat

  /** Screens kids mode removes from the rail. */
  fun screenHidden(screen: CcScreen, s: ControlCenterConfig.Settings): Boolean = s.kids && when (screen) {
    CcScreen.CAMERAS -> s.kidsHideCameras
    CcScreen.SECURITY -> s.kidsHideSecurity
    CcScreen.EDIT, CcScreen.SETTINGS -> true
    else -> false
  }

  /** Names that mark a scene/script as touching security (alarm, locks, garage, doors, water). */
  fun sensitive(e: CcEntity): Boolean {
    val words = "${e.id} ${e.name}".lowercase().split(NON_LETTERS).toSet()
    return words.any { it in SENSITIVE_WORDS } || e.members.any { m -> SENSITIVE_DOMAINS.any { m.startsWith(it) } }
  }

  private val NON_LETTERS = Regex("[^a-z]+")
  private val SENSITIVE_WORDS = setOf("arm", "arming", "armed", "disarm", "alarm", "lock", "unlock", "garage", "gate", "valve",
      "security", "away")
  private val SENSITIVE_DOMAINS = listOf("lock.", "alarm_control_panel.", "valve.", "cover.garage")

  /** A scene touching this many entities is "whole house" and gets a first-run preview. */
  const val WHOLE_HOUSE = 6
  private val VALVE_HINTS = listOf("valve", "water_main", "main_water")
}

internal enum class CcScreen { MAP, CAMERAS, ENERGY, ALERTS, SECURITY, EDIT, SETTINGS }
