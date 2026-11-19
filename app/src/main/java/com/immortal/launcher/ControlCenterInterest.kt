/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Which entities the shared socket subscribes to (pure, unit tested). On screen: the plan plus
 * everything the side column, alerts, scenes, cameras, the Tesla screen and the editor read — never
 * the whole house's diagnostics (the car's own are the exception: its doors, tyres and charge
 * details are diagnostic in HA). In the background: only what can raise a banner.
 */
internal object CcInterest {
  private val GLOBAL_DOMAINS = setOf("alarm_control_panel", "person", "camera", "scene", "script", "weather", "climate", "lock", "valve", "media_player")
  private val ALERT_CLASSES = setOf("moisture", "smoke", "gas", "carbon_monoxide", "safety") + CcEntity.OPENING_CLASSES
  private val SENSOR_CLASSES = setOf("power", "battery", "temperature", "humidity")
  private val APPLIANCE_HINTS = listOf("washer", "washing", "dryer", "dishwasher")
  /** What the Security screen lists (beyond locks, covers and the alarm, which are always in). */
  private val SECURITY_CLASSES = ALERT_CLASSES + setOf("motion", "occupancy", "presence", CcSecurityList.TAMPER_CLASS)
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
          (domain == "sensor" && reg.entityClass[id] in SENSOR_CLASSES) || alertWorthy(id, reg) || securityWorthy(id, reg)
    } + reg.diagnostics.filter { reg.classOf(it) == CcSecurityList.TAMPER_CLASS } + reg.snapshotButtons.values.flatten()
    val planIds = plan?.entityIds.orEmpty()
    return (planIds + wanted.sortedBy { priority(it) }).take(MAX_FOREGROUND).toSet() + reg.tesla.all
  }

  /** Doors, windows, garage, smoke/CO/leak, motion and tamper sensors (classes fetched on connect). */
  fun securityWorthy(id: String, reg: CcRegistry): Boolean =
      id.startsWith("binary_sensor.") && reg.classOf(id) in SECURITY_CLASSES

  /**
   * Every entity that can raise a background banner, by its device class: the registry's own class
   * or, for binary sensors, the class fetched with `get_entries` (the plan plays no part).
   */
  fun background(reg: CcRegistry?): Set<String>? {
    if (reg == null || reg.entities.isEmpty()) return null
    return reg.entities.filter { alertWorthy(it, reg) }.sorted().take(MAX_BACKGROUND).toSet()
  }

  fun alertWorthy(id: String, reg: CcRegistry): Boolean {
    val domain = id.substringBefore('.')
    val cls = reg.classOf(id)
    return when (domain) {
      "binary_sensor" -> CcBackgroundPolicy.subscribes(cls) || APPLIANCE_HINTS.any { it in id }
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
