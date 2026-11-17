/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject
import java.security.MessageDigest

internal enum class DoorbellKind(val wire: String) {
  RING("ring"),
  MOTION("motion");

  companion object {
    fun fromWire(text: String?): DoorbellKind? = entries.firstOrNull { it.wire == text }
  }
}

/**
 * One doorbell press or motion, as every Portal shows it. [id] is the same on every Portal that saw
 * the same Home Assistant state change (HA's context id), so the household can dedupe it.
 * [detectedAtMs] is on THIS Portal's clock; it never travels (the wire carries an age instead).
 */
internal data class DoorbellAlert(
    val id: String,
    val kind: DoorbellKind,
    val entityId: String,
    val camera: String,
    val room: String,
    val detectedAtMs: Long,
) {
  val cameraName: String get() = if (camera.isBlank()) "the door" else DoorbellEntities.prettyName(camera)
  /** The cooldown is per camera; without one, per entity. */
  val cooldownKey: String get() = camera.ifBlank { entityId }
}

/**
 * Turns a Home Assistant state change into a doorbell alert, or null. Pure.
 * - `event.*` (the newer doorbell integrations): every new event timestamp is a press; restoring
 *   from "unavailable" (an HA restart) or an attribute-only update is not.
 * - `binary_sensor.*`: only off -> on rings; on -> off, and unknown/unavailable -> on (restarts),
 *   do not.
 */
internal object DoorbellTriggers {
  private val CONTEXT_ID = Regex("^[A-Za-z0-9]{8,40}$")
  private const val FALLBACK_ID_CHARS = 24
  private val NOT_A_STATE = setOf("", "unknown", "unavailable")

  fun fromChange(
      entityId: String,
      old: JSONObject?,
      new: JSONObject?,
      settings: DoorbellConfig.Settings,
      room: String,
      nowMs: Long,
  ): DoorbellAlert? {
    val kind = kindOf(entityId, settings) ?: return null
    if (!fires(entityId, old, new)) return null
    return DoorbellAlert(eventId(entityId, new!!), kind, entityId, settings.camera.trim(), room, nowMs)
  }

  fun kindOf(entityId: String, settings: DoorbellConfig.Settings): DoorbellKind? = when {
    entityId == settings.trigger.trim() && DoorbellEntities.isTrigger(entityId) -> DoorbellKind.RING
    entityId in DoorbellEntities.motionList(settings.motion) -> DoorbellKind.MOTION
    else -> null
  }

  fun fires(entityId: String, old: JSONObject?, new: JSONObject?): Boolean {
    val now = new?.optString("state", "").orEmpty()
    if (now in NOT_A_STATE) return false
    val before = old?.optString("state", "")
    return when (DoorbellEntities.domainOf(entityId)) {
      DoorbellEntities.EVENT -> before != null && before != "unavailable" && before != now
      DoorbellEntities.BINARY_SENSOR -> now == "on" && before == "off"
      else -> false
    }
  }

  /** HA's context id when it has one; else a stable digest of entity + last_changed. */
  fun eventId(entityId: String, new: JSONObject): String {
    val context = new.optJSONObject("context")?.optString("id", "").orEmpty()
    if (CONTEXT_ID.matches(context)) return context
    val changed = new.optString("last_changed", new.optString("state", ""))
    val digest = MessageDigest.getInstance("SHA-256").digest("$entityId|$changed".toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }.take(FALLBACK_ID_CHARS)
  }
}
