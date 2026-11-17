/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * The doorbell picker's choices from Home Assistant's `/api/states`: doorbell buttons (`event.*`
 * and `binary_sensor.*`, likely doorbells first), cameras, and motion sensors (motion-like
 * `binary_sensor.*`). [parse] is pure.
 */
internal object DoorbellDiscovery {
  data class Option(val entityId: String, val name: String)

  data class Choices(val buttons: List<Option>, val cameras: List<Option>, val motion: List<Option>)

  private val MOTION_CLASSES = setOf("motion", "occupancy", "presence", "moving")
  private val DOORBELL_HINTS = listOf("doorbell", "ding", "ring", "press", "button", "visitor")

  private const val TIMEOUT_S = 10L
  private val client by lazy {
    OkHttpClient.Builder().connectTimeout(TIMEOUT_S, TimeUnit.SECONDS).readTimeout(TIMEOUT_S, TimeUnit.SECONDS).build()
  }

  /**
   * Blocking; null when Home Assistant can't be reached (unconfigured, offline or token refused).
   * Uses its own client so a failure is never logged with the address.
   */
  fun fetch(context: Context): Choices? {
    if (!HaConfig.isConfigured(context)) return null
    val body = runCatching {
      val request = Request.Builder().url("${HaConfig.baseUrl(context)}/api/states")
          .apply { HaConfig.authHeaders(context).forEach { (k, v) -> header(k, v) } }.build()
      client.newCall(request).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    }.getOrNull() ?: return null
    return parse(body)
  }

  fun parse(body: String): Choices? {
    val array = runCatching { JSONArray(body) }.getOrNull() ?: return null
    val buttons = ArrayList<Pair<Option, Boolean>>()
    val cameras = ArrayList<Option>()
    val motion = ArrayList<Option>()
    for (i in 0 until array.length()) {
      val o = array.optJSONObject(i) ?: continue
      val id = o.optString("entity_id")
      if (!DoorbellEntities.isEntity(id)) continue
      val attrs = o.optJSONObject("attributes")
      val option = Option(id, attrs?.optString("friendly_name").orEmpty().ifBlank { id })
      val deviceClass = attrs?.optString("device_class").orEmpty()
      if (DoorbellEntities.isTrigger(id)) buttons += option to looksLikeDoorbell(option, deviceClass)
      if (DoorbellEntities.isCamera(id)) cameras += option
      if (DoorbellEntities.isMotion(id) && deviceClass in MOTION_CLASSES) motion += option
    }
    val byName = compareBy<Option> { it.name.lowercase() }
    return Choices(
        buttons = buttons.sortedWith(compareBy<Pair<Option, Boolean>> { !it.second }.thenBy { it.first.name.lowercase() })
            .map { it.first },
        cameras = cameras.sortedWith(byName),
        motion = motion.sortedWith(byName),
    )
  }

  private fun looksLikeDoorbell(option: Option, deviceClass: String): Boolean =
      deviceClass == "doorbell" || DOORBELL_HINTS.any { option.entityId.contains(it) || option.name.lowercase().contains(it) }
}
