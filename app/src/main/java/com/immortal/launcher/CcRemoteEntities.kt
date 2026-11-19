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
 * Entities as the phone receives them (pure, unit tested): id, state, name, last change and only
 * the attributes its controls read. An allow-list, never a copy: HA attributes carry secrets and
 * private data (a camera's `access_token` and `entity_picture` URL, a person's GPS position,
 * device trackers' IPs), and none of those may leave the Portal.
 */
internal object CcRemoteEntities {
  /** Attributes a phone control or label reads. Everything else stays on the Portal. */
  val ALLOWED_ATTRS: Set<String> = setOf(
      "device_class", "unit_of_measurement",
      "brightness", "rgb_color", "color_mode", "color_temp_kelvin", "min_color_temp_kelvin", "max_color_temp_kelvin",
      "supported_color_modes",
      "current_temperature", "temperature", "min_temp", "max_temp", "target_temp_step", "hvac_modes", "hvac_action",
      "current_humidity", "humidity",
      "current_position", "percentage",
      "media_title", "media_artist", "media_album_name", "volume_level", "is_volume_muted",
      "supported_features", "code_format", "code_arm_required", "changed_by", "battery_level",
      "temperature_unit",
  )

  /** The car's and the plan's sensors are numbers; a long string attribute is never a control's. */
  private const val MAX_STRING = 200
  private const val MAX_LIST = 16

  fun encode(e: CcEntity): JSONObject {
    val attrs = JSONObject()
    e.attrs.forEach { (k, v) -> if (k in ALLOWED_ATTRS) value(v)?.let { attrs.put(k, it) } }
    return JSONObject().put("id", e.id).put("s", e.state.take(MAX_STRING)).put("n", e.name.take(MAX_STRING))
        .put("lc", e.lastChanged).put("a", attrs)
  }

  fun encodeAll(list: Collection<CcEntity>): JSONArray = JSONArray().also { out -> list.forEach { out.put(encode(it)) } }

  private fun value(v: Any?): Any? = when (v) {
    null -> null
    is Boolean, is Int, is Long -> v
    is Number -> v.toDouble().takeIf { it.isFinite() }
    is String -> v.take(MAX_STRING)
    is List<*> -> JSONArray().also { a -> v.take(MAX_LIST).forEach { x -> value(x)?.takeIf { it !is JSONArray }?.let(a::put) } }
    else -> null
  }
}
