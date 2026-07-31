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
 * Home Assistant connection + the entities the user pinned to the control widget. Distinct from the
 * existing MQTT integration ([MqttConfig]), which is publish-only (the Portal announces *itself* to
 * HA) — this is the read/control path: HA's REST API with a long-lived access token.
 *
 * Base URL + token are stored in plain SharedPreferences (matching [MqttConfig]/[AlpacaConfig];
 * the app has no encrypted-prefs dependency). The token is a bearer credential — treated like the
 * other secrets: never logged, never hardcoded.
 *
 * Pinned entities are a JSON array of (entity_id, friendly name) so the widget renders toggle tiles
 * without a full entity discovery on every open.
 */
object HaConfig {

  private const val PREFS = "immortal_ha"
  private const val KEY_URL = "base_url"
  private const val KEY_TOKEN = "token"
  private const val KEY_ENTITIES = "entities"

  /** A pinned controllable entity. [entityId] is HA's id (e.g. "light.kitchen"); [name] is the label. */
  data class Entity(val entityId: String, val name: String)

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  /** Base URL without a trailing slash, e.g. "http://homeassistant.local:8123". */
  fun baseUrl(context: Context): String = prefs(context).getString(KEY_URL, "").orEmpty().trimEnd('/')

  fun token(context: Context): String = prefs(context).getString(KEY_TOKEN, "").orEmpty()

  fun setConnection(context: Context, url: String, token: String) {
    prefs(context).edit()
        .putString(KEY_URL, url.trim().trimEnd('/'))
        .putString(KEY_TOKEN, token.trim())
        .apply()
  }

  fun isConfigured(context: Context): Boolean =
      baseUrl(context).isNotBlank() && token(context).isNotBlank()

  /** Bearer auth headers for every HA REST call, or empty if not configured. */
  fun authHeaders(context: Context): Map<String, String> {
    val t = token(context)
    if (t.isBlank()) return emptyMap()
    return mapOf("Authorization" to "Bearer $t", "Content-Type" to "application/json")
  }

  // --- pinned entities --------------------------------------------------------

  fun entities(context: Context): List<Entity> {
    val raw = prefs(context).getString(KEY_ENTITIES, null) ?: return emptyList()
    return runCatching {
          val arr = JSONArray(raw)
          (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entity(o.optString("id", "").trim(), o.optString("name", "").trim())
          }.filter { it.entityId.isNotBlank() }
        }
        .getOrDefault(emptyList())
  }

  private fun saveEntities(context: Context, entities: List<Entity>) {
    val arr = JSONArray()
    entities.forEach { e -> arr.put(JSONObject().put("id", e.entityId).put("name", e.name)) }
    prefs(context).edit().putString(KEY_ENTITIES, arr.toString()).apply()
  }

  fun addEntity(context: Context, entityId: String, name: String) {
    val clean = entityId.trim()
    if (clean.isBlank()) return
    val current = entities(context)
    if (current.any { it.entityId == clean }) return
    saveEntities(context, current + Entity(clean, name.trim().ifBlank { clean }))
  }

  fun removeEntity(context: Context, entityId: String) =
      saveEntities(context, entities(context).filterNot { it.entityId == entityId.trim() })
}
