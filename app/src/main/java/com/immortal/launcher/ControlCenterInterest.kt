/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Which entities the shared socket subscribes to (pure, unit tested). On screen: the plan plus
 * everything the side column, alerts, scenes, cameras, energy and the editor read — never the
 * whole house's diagnostics. In the background: only what can raise a banner.
 */
internal object CcInterest {
  private val GLOBAL_DOMAINS = setOf("alarm_control_panel", "person", "camera", "scene", "script", "weather", "climate", "lock", "valve", "media_player")
  private val ALERT_CLASSES = setOf("moisture", "smoke", "gas", "carbon_monoxide", "safety") + CcEntity.OPENING_CLASSES
  private val SENSOR_CLASSES = setOf("power", "battery", "temperature", "humidity")
  private val APPLIANCE_HINTS = listOf("washer", "washing", "dryer", "dishwasher")
  /** Upper bound so a huge install can't flood a Portal (HA sends a full snapshot first). */
  const val MAX_FOREGROUND = 600
  const val MAX_BACKGROUND = 200

  /**
   * Null = registry unavailable (non-admin token): subscribe to everything while on screen; the
   * plan, scenes, people and alarm all need entities the plan alone doesn't list.
   */
  fun foreground(reg: CcRegistry?, plan: CcPlan?): Set<String>? {
    if (reg == null || reg.entities.isEmpty()) return null
    val wanted = reg.entities.filter { id ->
      val domain = id.substringBefore('.')
      domain in GLOBAL_DOMAINS || CcPlanEdit.planWorthy(id, reg, null) ||
          (domain == "sensor" && reg.entityClass[id] in SENSOR_CLASSES) || alertWorthy(id, reg)
    }
    val planIds = plan?.entityIds.orEmpty()
    return (planIds + wanted.sortedBy { priority(it) }).take(MAX_FOREGROUND).toSet()
  }

  fun background(reg: CcRegistry?): Set<String>? {
    if (reg == null || reg.entities.isEmpty()) return null
    return reg.entities.filter { alertWorthy(it, reg) }.sorted().take(MAX_BACKGROUND).toSet()
  }

  fun alertWorthy(id: String, reg: CcRegistry): Boolean {
    val domain = id.substringBefore('.')
    val cls = reg.entityClass[id]
    return when (domain) {
      "binary_sensor" -> cls in ALERT_CLASSES || APPLIANCE_HINTS.any { it in id }
      "cover" -> cls == "garage" || cls == "gate"
      "sensor", "switch" -> APPLIANCE_HINTS.any { it in id }
      else -> false
    }
  }

  /** Controls and alert sensors first, plain sensors last, when the cap bites. */
  private fun priority(id: String): Int = when (id.substringBefore('.')) {
    "light", "switch", "cover", "lock", "climate", "fan", "media_player", "valve", "alarm_control_panel" -> 0
    "binary_sensor", "camera", "scene", "script", "person", "weather" -> 1
    else -> 2
  }
}
