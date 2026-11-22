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
 * full-room dashboard needs: the whole house (areas, their entities with rich attributes, and the
 * `scene.*` entities) in ONE `POST /api/template` call, service calls with data payloads, and
 * sensor history for sparklines.
 *
 * Reads return [HaResult] so the screen can tell "sign in again" from "can't reach it" from "no
 * rooms" (it used to get an empty list for all three). If an older HA rejects the rich template
 * (HTTP 400) the legacy ids-only template plus one `/api/states/<id>` per entity is used instead.
 * Pure JSON parsing lives in [HaDashboardParse] and is unit-tested.
 */
object HaDashboardApi {

  /** One HA area with its entity ids and (from the rich template) their details. */
  data class Area(val id: String, val name: String, val entityIds: List<String>,
      val details: List<Detail> = emptyList())

  /** Rich entity detail for dashboard tiles; nulls where a domain doesn't apply. */
  data class Detail(
      val entityId: String,
      val domain: String,
      val friendlyName: String,
      val state: String,
      val brightnessPct: Int?,      // light (null = not dimmable)
      val currentTemp: Float?,      // climate
      val targetTemp: Float?,       // climate
      val hvacAction: String?,      // climate: "heating"/"cooling"/"idle"
      val mediaTitle: String?,      // media_player
      val mediaArtist: String?,     // media_player
      val unit: String,             // sensor
      val deviceClass: String? = null,
      val tempStep: Float? = null,  // climate target_temp_step
  )

  /** A `scene.*` entity HA actually has. */
  data class Scene(val id: String, val name: String)

  /** Everything the dashboard shows, fetched in one call. */
  data class House(val areas: List<Area>, val scenes: List<Scene>)

  internal const val MAX_ENTITIES_PER_ROOM = 12

  private const val DOMAINS = "^(light|switch|climate|media_player|fan|cover|lock|input_boolean|sensor|binary_sensor)\\."
  private const val NOISE =
      ".*_(led|motion_sensor|occupancy|tamper|battery|rssi|consumption|voltage|current|energy|power|signal_strength|linkquality)$"
  private const val KEYS =
      """["brightness","current_temperature","temperature","hvac_action","media_title","media_artist","unit_of_measurement","device_class","target_temp_step"]"""

  // area_entities() lists plain ids; keep the domains the dashboard renders and drop diagnostic
  // suffixes. Attributes are emitted only when set, so a plain light never looks dimmable.
  private val HOUSE_TEMPLATE =
      """{"areas":[{% for a in areas() %}{% set ents = area_entities(a) | select("match","$DOMAINS") | reject("match","$NOISE") | list %}""" +
          """{"id":{{ a | tojson }},"name":{{ area_name(a) | tojson }},"entities":[{% for e in ents %}""" +
          """{"entity_id":{{ e | tojson }},"state":{{ states(e) | tojson }},"attributes":{"friendly_name":{{ (state_attr(e,"friendly_name") or e) | tojson }}""" +
          """{% for k in $KEYS %}{% set v = state_attr(e,k) %}{% if v is not none %},{{ k | tojson }}:{{ v | tojson }}{% endif %}{% endfor %}""" +
          """{% set cm = state_attr(e,"supported_color_modes") %}{% if cm %},"supported_color_modes":{{ cm | list | tojson }}{% endif %}}}""" +
          """{{ "," if not loop.last }}{% endfor %}]}{{ "," if not loop.last }}{% endfor %}],""" +
          """"scenes":[{% for s in states.scene %}{"id":{{ s.entity_id | tojson }},"name":{{ s.name | tojson }}}{{ "," if not loop.last }}{% endfor %}]}"""

  // The pre-1.327 ids-only template, kept as the fallback for installs that reject the one above.
  private const val LEGACY_AREAS_TEMPLATE = """[{% for a in areas() %}{"id":{{ a | tojson }},"name":{{ area_name(a) | tojson }},"entities":{{ area_entities(a) | select("match","^(light|switch|climate|media_player|fan|cover|lock|input_boolean)\\.") | reject("match",".*_(led|motion_sensor|occupancy|tamper|battery|rssi|consumption|voltage|current|energy|power)$") | list | tojson }}}{{ "," if not loop.last }}{% endfor %}]"""

  private const val HTTP_BAD_REQUEST = 400

  // --- reads ------------------------------------------------------------------

  /** The whole house. Blocking. */
  fun fetchHouse(context: Context): HaResult<House> {
    if (!HaConfig.isConfigured(context)) return HaResult.NotConfigured
    val rich = template(context, HOUSE_TEMPLATE)
    if (rich.code != HTTP_BAD_REQUEST) return HaResult.of(rich) { HaDashboardParse.house(it) }
    return fetchLegacyHouse(context)
  }

  private fun fetchLegacyHouse(context: Context): HaResult<House> {
    val ids = HaResult.of(template(context, LEGACY_AREAS_TEMPLATE)) { parseAreas(it) }
    if (ids !is HaResult.Ok) return ids.cast()
    val areas = ids.value.map { a -> a.copy(details = a.entityIds.mapNotNull { fetchDetail(context, it) }) }
    val anyAnswered = areas.isEmpty() || areas.any { it.details.isNotEmpty() }
    return if (anyAnswered) HaResult.Ok(House(areas, emptyList())) else HaResult.Unreachable
  }

  private fun template(context: Context, template: String): Http.Outcome<String> =
      Http.postForOutcome("${HaConfig.baseUrl(context)}/api/template",
          JSONObject().put("template", template).toString(), HaConfig.authHeaders(context))

  /** Rich detail for one entity. Blocking; null on failure. */
  fun fetchDetail(context: Context, entityId: String): Detail? {
    if (!HaConfig.isConfigured(context)) return null
    val url = "${HaConfig.baseUrl(context)}/api/states/$entityId"
    val body = Http.getString(url, headers = HaConfig.authHeaders(context)) ?: return null
    return runCatching { parseDetail(JSONObject(body)) }.getOrNull()
  }

  /**
   * A sensor's recent numeric history for a sparkline (default: last 24h, minimal response).
   * Blocking; empty on failure (a sparkline is decoration, its absence needs no message).
   */
  fun fetchHistory(context: Context, entityId: String): List<Float> {
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

  // --- pure parsing (unit-tested; see HaDashboardParse) -------------------------

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

  internal fun parseDetail(o: JSONObject): Detail = HaDashboardParse.detail(o)

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
