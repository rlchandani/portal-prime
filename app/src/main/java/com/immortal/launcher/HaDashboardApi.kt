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
 * The HA dashboard's data layer, extending [HaClient]'s widget-sized REST surface with what a
 * full-room dashboard needs: the house's area→entities map, rich per-domain attributes
 * (brightness, climate temps/action, media track), service calls with data payloads
 * (brightness %, target temperature), and sensor history for sparklines.
 *
 * Areas come from HA's template endpoint (`POST /api/template`) because the REST API has no
 * native /api/areas — the template below asks HA to serialize its own area registry. One call,
 * standard install, no custom integration needed.
 *
 * Same conventions as [HaClient]: blocking calls through [Http], null/empty on failure, pure
 * JSON parsing split out and unit-tested ([HaDashboardApiTest]).
 */
object HaDashboardApi {

  /** One HA area with the entity ids assigned to it (directly or via its devices). */
  data class Area(val id: String, val name: String, val entityIds: List<String>)

  /** Rich entity detail for dashboard tiles; nulls where a domain doesn't apply. */
  data class Detail(
      val entityId: String,
      val domain: String,
      val friendlyName: String,
      val state: String,
      val brightnessPct: Int?,      // light
      val currentTemp: Float?,      // climate
      val targetTemp: Float?,       // climate
      val hvacAction: String?,      // climate: "heating"/"cooling"/"idle"
      val mediaTitle: String?,      // media_player
      val mediaArtist: String?,     // media_player
      val unit: String,             // sensor
  )

  // Jinja template that serializes HA's area registry: id, name, and all entity ids in the
  // area. HA evaluates this server-side; the response is the rendered JSON string.
  private const val AREAS_TEMPLATE =
      """[{% for a in areas() %}{"id":{{ a | tojson }},"name":{{ area_name(a) | tojson }},""" +
          """"entities":{{ area_entities(a) | list | tojson }}}{{ "," if not loop.last }}{% endfor %}]"""

  // --- reads ------------------------------------------------------------------

  /** The house's rooms (areas with ≥1 entity). Blocking; empty on failure/unconfigured. */
  fun fetchAreas(context: Context): List<Area> {
    if (!HaConfig.isConfigured(context)) return emptyList()
    val url = "${HaConfig.baseUrl(context)}/api/template"
    val body = JSONObject().put("template", AREAS_TEMPLATE).toString()
    val resp = Http.postString(url, body, headers = HaConfig.authHeaders(context)) ?: return emptyList()
    return parseAreas(resp)
  }

  /** Rich detail for one entity. Blocking; null on failure. */
  fun fetchDetail(context: Context, entityId: String): Detail? {
    if (!HaConfig.isConfigured(context)) return null
    val url = "${HaConfig.baseUrl(context)}/api/states/$entityId"
    val body = Http.getString(url, headers = HaConfig.authHeaders(context)) ?: return null
    return runCatching { parseDetail(JSONObject(body)) }.getOrNull()
  }

  /** Rich details for several entities, dropping failures. Blocking. */
  fun fetchDetails(context: Context, entityIds: List<String>): List<Detail> =
      entityIds.mapNotNull { fetchDetail(context, it) }

  /**
   * A sensor's recent numeric history for a sparkline (default: last 24h, minimal response).
   * Blocking; empty on failure.
   */
  fun fetchHistory(context: Context, entityId: String, hours: Int = 24): List<Float> {
    if (!HaConfig.isConfigured(context)) return emptyList()
    val url =
        "${HaConfig.baseUrl(context)}/api/history/period" +
            "?filter_entity_id=$entityId&minimal_response&no_attributes" +
            "&significant_changes_only"
    val body = Http.getString(url, headers = HaConfig.authHeaders(context)) ?: return emptyList()
    return parseHistory(body)
  }

  // --- control ----------------------------------------------------------------

  /** Set a light's brightness (0–100%; 0 turns it off). Returns true on 2xx. Blocking. */
  fun setBrightness(context: Context, entityId: String, pct: Int): Boolean {
    if (!HaConfig.isConfigured(context)) return false
    if (pct <= 0) return HaClient.callService(context, "light", "turn_off", entityId)
    val url = "${HaConfig.baseUrl(context)}/api/services/light/turn_on"
    val body = JSONObject().put("entity_id", entityId).put("brightness_pct", pct.coerceIn(1, 100)).toString()
    return Http.postString(url, body, headers = HaConfig.authHeaders(context)) != null
  }

  /** Set a climate entity's target temperature. Returns true on 2xx. Blocking. */
  fun setTemperature(context: Context, entityId: String, temp: Float): Boolean {
    if (!HaConfig.isConfigured(context)) return false
    val url = "${HaConfig.baseUrl(context)}/api/services/climate/set_temperature"
    val body = JSONObject().put("entity_id", entityId).put("temperature", temp).toString()
    return Http.postString(url, body, headers = HaConfig.authHeaders(context)) != null
  }

  // --- pure parsing (unit-tested) ----------------------------------------------

  internal fun parseAreas(body: String): List<Area> =
      runCatching {
            val arr = JSONArray(body)
            (0 until arr.length()).mapNotNull { i ->
              val o = arr.optJSONObject(i) ?: return@mapNotNull null
              val ents = o.optJSONArray("entities") ?: JSONArray()
              val ids = (0 until ents.length()).mapNotNull { j -> ents.optString(j).takeIf { it.isNotBlank() } }
              if (ids.isEmpty()) null
              else Area(o.optString("id"), o.optString("name").ifBlank { o.optString("id") }, ids)
            }
          }
          .getOrDefault(emptyList())

  internal fun parseDetail(o: JSONObject): Detail {
    val id = o.optString("entity_id", "")
    val attrs = o.optJSONObject("attributes") ?: JSONObject()
    val brightness = if (attrs.has("brightness")) attrs.optInt("brightness", 0) else null
    return Detail(
        entityId = id,
        domain = id.substringBefore('.', ""),
        friendlyName = attrs.optString("friendly_name", "").ifBlank { id },
        state = o.optString("state", ""),
        brightnessPct = brightness?.let { Math.round(it * 100f / 255f) },
        currentTemp = if (attrs.has("current_temperature")) attrs.optDouble("current_temperature").toFloat() else null,
        targetTemp = if (attrs.has("temperature")) attrs.optDouble("temperature").toFloat() else null,
        hvacAction = attrs.optString("hvac_action", "").ifBlank { null },
        mediaTitle = attrs.optString("media_title", "").ifBlank { null },
        mediaArtist = attrs.optString("media_artist", "").ifBlank { null },
        unit = attrs.optString("unit_of_measurement", ""),
    )
  }

  internal fun parseHistory(body: String): List<Float> =
      runCatching {
            val outer = JSONArray(body)
            val inner = outer.optJSONArray(0) ?: return emptyList()
            (0 until inner.length()).mapNotNull { i ->
              inner.optJSONObject(i)?.optString("state")?.toFloatOrNull()
            }
          }
          .getOrDefault(emptyList())
}
