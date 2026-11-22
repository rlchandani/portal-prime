/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject

/** Pure JSON → model parsing for [HaDashboardApi] (unit-tested, no Context). */
internal object HaDashboardParse {

  private const val BRIGHTNESS_MAX = 255f
  private const val PERCENT = 100f
  private const val ONOFF_MODE = "onoff"

  /**
   * The rich house template's `{"areas":[…],"scenes":[…]}`. Null when the body isn't that shape
   * (the caller reports it as a failed answer rather than an empty house).
   */
  fun house(body: String): HaDashboardApi.House? = runCatching {
    val root = JSONObject(body)
    val areasJson = root.optJSONArray("areas") ?: return null
    HaDashboardApi.House(areas = objects(areasJson).map(::area), scenes = scenes(root.optJSONArray("scenes")))
  }.getOrNull()

  private fun area(o: JSONObject): HaDashboardApi.Area {
    val details = objects(o.optJSONArray("entities") ?: JSONArray()).map(::detail).filter { it.entityId.isNotBlank() }
    val id = o.optString("id")
    return HaDashboardApi.Area(id, o.optString("name").ifBlank { id }, details.map { it.entityId }, details)
  }

  private fun scenes(arr: JSONArray?): List<HaDashboardApi.Scene> =
      objects(arr ?: JSONArray()).mapNotNull { o ->
        val id = o.optString("id")
        if (!id.startsWith("scene.")) null
        else HaDashboardApi.Scene(id, o.optString("name").ifBlank { id.substringAfter('.').replace('_', ' ') })
      }

  /** One `/api/states`-shaped object. A light is dimmable when it reports brightness or a non-onoff colour mode. */
  fun detail(o: JSONObject): HaDashboardApi.Detail {
    val id = o.optString("entity_id", "")
    val attrs = o.optJSONObject("attributes") ?: JSONObject()
    val raw = if (attrs.has("brightness") && !attrs.isNull("brightness")) attrs.optInt("brightness", 0) else null
    val dimmable = raw != null || attrs.has("brightness") || dimmableModes(attrs.optJSONArray("supported_color_modes"))
    return HaDashboardApi.Detail(
        entityId = id,
        domain = id.substringBefore('.', ""),
        friendlyName = attrs.optString("friendly_name", "").ifBlank { id },
        state = o.optString("state", ""),
        brightnessPct = if (dimmable) Math.round((raw ?: 0) * PERCENT / BRIGHTNESS_MAX) else null,
        currentTemp = float(attrs, "current_temperature"),
        targetTemp = float(attrs, "temperature"),
        hvacAction = attrs.optString("hvac_action", "").ifBlank { null },
        mediaTitle = attrs.optString("media_title", "").ifBlank { null },
        mediaArtist = attrs.optString("media_artist", "").ifBlank { null },
        unit = attrs.optString("unit_of_measurement", ""),
        deviceClass = attrs.optString("device_class", "").ifBlank { null },
        tempStep = float(attrs, "target_temp_step"),
    )
  }

  private fun dimmableModes(modes: JSONArray?): Boolean =
      modes != null && (0 until modes.length()).any { modes.optString(it).let { m -> m.isNotBlank() && m != ONOFF_MODE } }

  private fun float(o: JSONObject, key: String): Float? =
      if (o.has(key) && !o.isNull(key)) o.optDouble(key).toFloat().takeUnless { it.isNaN() } else null

  private fun objects(arr: JSONArray): List<JSONObject> = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
}
