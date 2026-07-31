/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * REST client for Home Assistant, the read/control counterpart to the publish-only MQTT path.
 * Uses HA's documented API with bearer-token auth ([HaConfig.authHeaders]):
 *  - `GET  /api/states`                          → all entity states (for discovery)
 *  - `GET  /api/states/<entity_id>`              → one entity's state (for the widget)
 *  - `POST /api/services/<domain>/<service>`     → call a service (e.g. light.toggle)
 *
 * All calls go through the shared [Http] helper and return null on failure — the widget then shows
 * a disconnected/unknown state rather than crashing. Domains are derived from the entity_id prefix.
 */
object HaClient {

  /** An entity's current state for the widget: id, friendly name, on/off, and whether it's toggleable. */
  data class EntityState(
      val entityId: String,
      val friendlyName: String,
      val on: Boolean,
      val toggleable: Boolean,
      val activatable: Boolean = false,
      val readable: Boolean = false,
      val rawState: String = "",
      val unit: String = "",
  ) {
    /** A compact display value for a read-only tile, e.g. "21.5°C", "63%", "Home". */
    val displayValue: String
      get() = if (unit.isNotBlank()) "$rawState$unit" else rawState
  }

  /** Domains we know how to toggle on/off from the widget (have a persistent on/off state). */
  private val TOGGLEABLE_DOMAINS = setOf("light", "switch", "fan", "input_boolean", "automation", "script")

  /** Momentary "run once" domains — no on/off state; a tap just fires `<domain>.turn_on`. */
  private val ACTIVATABLE_DOMAINS = setOf("scene")

  /** Read-only domains shown as value tiles (no control): sensors, binary sensors, weather, etc. */
  private val READABLE_DOMAINS = setOf("sensor", "binary_sensor", "weather", "device_tracker", "person", "sun")

  private fun domainOf(entityId: String): String = entityId.substringBefore('.', "")

  // --- reads ------------------------------------------------------------------

  /** Fetch one entity's state for the widget. Blocking; null if unconfigured or on failure. */
  fun fetchState(context: Context, entityId: String): EntityState? {
    if (!HaConfig.isConfigured(context)) return null
    val url = "${HaConfig.baseUrl(context)}/api/states/$entityId"
    val body = Http.getString(url, headers = HaConfig.authHeaders(context)) ?: return null
    return runCatching { parseState(JSONObject(body)) }.getOrNull()
  }

  /** Fetch the states of several entities, dropping any that fail. Blocking. */
  fun fetchStates(context: Context, entityIds: List<String>): List<EntityState> =
      entityIds.mapNotNull { fetchState(context, it) }

  /**
   * Discover all controllable entities on the server (for the picker). Returns entities that are
   * either toggleable or activatable (scenes), sorted by friendly name. Blocking; empty on failure.
   */
  fun discover(context: Context): List<EntityState> {
    if (!HaConfig.isConfigured(context)) return emptyList()
    val url = "${HaConfig.baseUrl(context)}/api/states"
    val body = Http.getString(url, headers = HaConfig.authHeaders(context)) ?: return emptyList()
    return runCatching {
          val arr = JSONArray(body)
          (0 until arr.length())
              .mapNotNull { i -> arr.optJSONObject(i)?.let(::parseState) }
              .filter { it.toggleable || it.activatable || it.readable }
              .sortedBy { it.friendlyName.lowercase() }
        }
        .getOrDefault(emptyList())
  }

  internal fun parseState(o: JSONObject): EntityState {
    val id = o.optString("entity_id", "")
    val state = o.optString("state", "")
    val attrs = o.optJSONObject("attributes")
    val name = attrs?.optString("friendly_name", "").orEmpty().ifBlank { id }
    val unit = attrs?.optString("unit_of_measurement", "").orEmpty()
    val domain = domainOf(id)
    return EntityState(
        entityId = id,
        friendlyName = name,
        on = state.equals("on", ignoreCase = true) || state.equals("playing", ignoreCase = true),
        toggleable = domain in TOGGLEABLE_DOMAINS,
        activatable = domain in ACTIVATABLE_DOMAINS,
        readable = domain in READABLE_DOMAINS,
        rawState = state,
        unit = unit,
    )
  }

  // --- control ----------------------------------------------------------------

  /**
   * Toggle an entity via `POST /api/services/<domain>/toggle`. Returns true on a 2xx. Blocking.
   * For domains without a native `toggle` (rare in our set), HA still accepts `homeassistant.toggle`
   * as a fallback — we try the domain's own toggle first, then the generic one.
   */
  fun toggle(context: Context, entityId: String): Boolean {
    if (!HaConfig.isConfigured(context)) return false
    val domain = domainOf(entityId).ifBlank { "homeassistant" }
    if (callService(context, domain, "toggle", entityId)) return true
    return callService(context, "homeassistant", "toggle", entityId)
  }

  /**
   * Activate a momentary entity (scene/script) via `POST /api/services/<domain>/turn_on`. Returns
   * true on a 2xx. Blocking. Scenes have no on/off state — this just fires them.
   */
  fun activate(context: Context, entityId: String): Boolean {
    if (!HaConfig.isConfigured(context)) return false
    val domain = domainOf(entityId).ifBlank { "homeassistant" }
    return callService(context, domain, "turn_on", entityId)
  }

  /** Fire the right service for [entity] based on its kind: toggle if toggleable, else activate. */
  fun trigger(context: Context, entity: EntityState): Boolean =
      if (entity.toggleable) toggle(context, entity.entityId) else activate(context, entity.entityId)

  /** Call an arbitrary service with `{"entity_id": …}`. Returns true on a 2xx. Blocking. */
  fun callService(context: Context, domain: String, service: String, entityId: String): Boolean {
    if (!HaConfig.isConfigured(context)) return false
    val url = "${HaConfig.baseUrl(context)}/api/services/$domain/$service"
    val body = JSONObject().put("entity_id", entityId).toString()
    // Http.postString returns null on non-2xx, so a non-null result means success.
    return Http.postString(url, body, headers = HaConfig.authHeaders(context)) != null
  }

  /** True if `GET /api/` returns the expected running message — used by the settings "Test" action. */
  fun ping(context: Context): Boolean {
    if (!HaConfig.isConfigured(context)) return false
    val url = "${HaConfig.baseUrl(context)}/api/"
    val body = Http.getString(url, headers = HaConfig.authHeaders(context)) ?: return false
    return body.contains("API running", ignoreCase = true) || body.contains("message", ignoreCase = true)
  }
}
