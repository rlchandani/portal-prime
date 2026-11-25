/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable
/**
 * Alerts from live Home Assistant state (pure, unit tested). Three severities, as in the mock:
 * critical (leak, smoke, gas, CO) take over every Portal; warnings (garage/door/window left open)
 * are banners on Full Portals after a delay; info (appliance done, low battery) is quiet.
 */
internal enum class CcSeverity { CRITICAL, WARNING, INFO }

internal enum class CcAlertAction { CLOSE, SHUT_WATER, VIEW, DISMISS, SNOOZE, REMIND }

@Immutable
internal data class CcAlert(
    /** Stable per occurrence: entity + when it started, so a new leak is a new alert. */
    val id: String,
    val severity: CcSeverity,
    val kind: CcKind,
    val entityId: String,
    val title: String,
    val body: String,
    val since: Long,
    val action: CcAlertAction,
    /** HA device class behind the alert (moisture, smoke, carbon_monoxide, gas, door…), when known. */
    val deviceClass: String? = null,
)

internal object CcAlerts {
  private const val MS_PER_MIN = 60_000L
  private const val LOW_BATTERY = 15
  private val DONE_STATES = setOf("off", "idle", "complete", "completed", "finished", "done", "standby", "end")
  private val RUN_STATES = setOf("on", "running", "wash", "washing", "drying", "rinse", "spin", "run")

  /** Everything that needs attention now, most serious first, newest first within a severity. */
  fun active(
      entities: Map<String, CcEntity>,
      now: Long,
      warnAfterMin: Int,
      rooms: (String) -> String? = { null },
      appliancesDone: Map<String, Long> = emptyMap(),
  ): List<CcAlert> {
    val out = ArrayList<CcAlert>()
    entities.values.forEach { e -> alertFor(e, now, warnAfterMin, rooms)?.let(out::add) }
    appliancesDone.forEach { (id, at) -> entities[id]?.let { e -> out += done(e, at, rooms) } }
    // entityId breaks ties so the surviving row of a duplicate group is the same one every snapshot.
    return dedupe(out.sortedWith(compareBy<CcAlert> { it.severity.ordinal }.thenByDescending { it.since }.thenBy { it.entityId }))
  }

  /** How two alerts read to the eye: identical here means identical rows on screen. */
  private data class CcAlertFace(val severity: CcSeverity, val kind: CcKind, val title: String, val body: String)

  /**
   * Collapses alerts that read identically. One physical thing — a door, a leak — exposed as
   * several Home Assistant entities raises one alert per entity, which the house would otherwise
   * show as three identical "… is open" rows. The first survivor (most serious, newest) stands for
   * the group. This runs before the dismissed filter on purpose: snoozing the shown row hides the
   * whole group instead of surfacing the next identical copy next snapshot.
   */
  fun dedupe(alerts: List<CcAlert>): List<CcAlert> =
      alerts.distinctBy { CcAlertFace(it.severity, it.kind, it.title, it.body) }

  private fun alertFor(e: CcEntity, now: Long, warnAfterMin: Int, rooms: (String) -> String?): CcAlert? {
    if (e.unavailable) return null
    val kind = CcKind.of(e)
    val where = rooms(e.id)
    return when {
      kind == CcKind.LEAK && e.state == "on" -> CcAlert(id(e), CcSeverity.CRITICAL, kind, e.id, "Water leak${where?.let { " · $it" } ?: ""}",
          "${e.name} is wet. Shut off the water?", e.lastChanged, CcAlertAction.SHUT_WATER, e.deviceClass ?: "moisture")
      kind == CcKind.SMOKE && e.state == "on" -> CcAlert(id(e), CcSeverity.CRITICAL, kind, e.id, "${hazard(e)}${where?.let { " · $it" } ?: ""}",
          "${e.name} detected it. Get everyone out and check.", e.lastChanged, CcAlertAction.VIEW, e.deviceClass)
      kind in OPEN_WARN && e.isOpen && openLongEnough(e, now, warnAfterMin) -> openAlert(e, kind, now, where)
      kind == CcKind.BATTERY || e.battery != null -> battery(e, where)
      else -> null
    }
  }

  private val OPEN_WARN = setOf(CcKind.GARAGE, CcKind.DOOR, CcKind.WINDOW)

  private fun openLongEnough(e: CcEntity, now: Long, warnAfterMin: Int): Boolean =
      e.lastChanged > 0 && now - e.lastChanged >= warnAfterMin * MS_PER_MIN

  private fun openAlert(e: CcEntity, kind: CcKind, now: Long, where: String?): CcAlert {
    val mins = ((now - e.lastChanged) / MS_PER_MIN).coerceAtLeast(0)
    // A door reads by its friendly name ("Office door is open"), never "<hardware> is open is open".
    val what = when (kind) {
      CcKind.GARAGE -> "Garage door"
      CcKind.WINDOW -> "${where ?: e.name} window"
      else -> CcSecNames.friendly(e.name, where, "door")
    }
    val action = if (kind == CcKind.GARAGE) CcAlertAction.CLOSE else CcAlertAction.REMIND
    return CcAlert(id(e), CcSeverity.WARNING, kind, e.id, "$what is open", "Open ${duration(mins)}", e.lastChanged, action)
  }

  private fun battery(e: CcEntity, where: String?): CcAlert? {
    val level = if (CcKind.of(e) == CcKind.BATTERY && e.domain == "sensor") e.numeric?.toInt() else e.battery
    val low = if (e.domain == "binary_sensor" && CcKind.of(e) == CcKind.BATTERY) e.state == "on" else (level != null && level <= LOW_BATTERY)
    if (!low) return null
    val name = e.name.removeSuffix(" battery").removeSuffix(" Battery")
    return CcAlert("battery:${e.id}", CcSeverity.INFO, CcKind.BATTERY, e.id,
        "$name battery${level?.let { " $it%" } ?: " low"}", "Replace it in the next few weeks${where?.let { " · $it" } ?: ""}",
        e.lastChanged, CcAlertAction.SNOOZE)
  }

  private fun done(e: CcEntity, at: Long, rooms: (String) -> String?): CcAlert = CcAlert("done:${e.id}:$at", CcSeverity.INFO, CcKind.APPLIANCE,
      e.id, "${applianceName(e)} is done", rooms(e.id) ?: "Finished", at, CcAlertAction.DISMISS)

  /** True when [before] → [after] is an appliance finishing (running state to a done state). */
  fun finished(before: CcEntity?, after: CcEntity): Boolean =
      before != null && CcKind.of(after) == CcKind.APPLIANCE && before.state.lowercase() in RUN_STATES && after.state.lowercase() in DONE_STATES

  private fun applianceName(e: CcEntity): String = when {
    "dishwasher" in e.id -> "Dishwasher"
    "dryer" in e.id -> "Dryer"
    "wash" in e.id -> "Washer"
    else -> e.name
  }

  private fun hazard(e: CcEntity) = when (e.deviceClass) { "gas" -> "Gas detected"; "carbon_monoxide" -> "Carbon monoxide"; else -> "Smoke alarm" }

  private fun id(e: CcEntity) = "${e.id}@${e.lastChanged}"

  /** "just now", "17 min", "1 h 5 min", then whole days ("1 day", "12 days") instead of "310 h 15 min". */
  fun duration(mins: Long): String = when {
    mins < 1 -> "just now"
    mins < MIN_PER_H -> "$mins min"
    mins < MIN_PER_DAY -> "${mins / MIN_PER_H} h ${mins % MIN_PER_H} min"
    else -> (mins / MIN_PER_DAY).let { d -> if (d == 1L) "1 day" else "$d days" }
  }

  private const val MIN_PER_H = 60L
  private const val MIN_PER_DAY = 24 * MIN_PER_H
}

/** How an alert reaches this Portal (pure). The Portal-wide banner policy from Decisions #5. */
internal object CcBannerPolicy {
  enum class Show { TAKEOVER, BANNER, NONE }

  data class Decision(val show: Show, val sound: Boolean)

  /** The three alarms that may sound during quiet hours. */
  val NIGHT_SOUND_CLASSES = setOf("moisture", "smoke", "carbon_monoxide")

  fun soundsAtNight(alert: CcAlert): Boolean =
      alert.severity == CcSeverity.CRITICAL && (alert.deviceClass in NIGHT_SOUND_CLASSES ||
          alert.deviceClass == null && alert.kind == CcKind.LEAK)

  fun decide(alert: CcAlert, s: ControlCenterConfig.Settings, quietHours: Boolean): Decision = when (alert.severity) {
    // Critical shows everywhere: kids mode, banners-only Portals, quiet hours. In quiet hours only a
    // leak, smoke or carbon-monoxide alarm makes a sound (owner's decision, on by default); every
    // other alert — gas and other critical kinds included — stays silent at night.
    CcSeverity.CRITICAL -> Decision(Show.TAKEOVER, sound = !quietHours || (s.criticalSoundAtNight && soundsAtNight(alert)))
    // Left-open warnings are for shared rooms only, never a takeover, silent day and night.
    CcSeverity.WARNING -> if (s.bannersWarnings && s.sharedRoom && s.portalMode == ControlCenterConfig.MODE_FULL &&
        !(s.kids && alert.kind == CcKind.GARAGE)) Decision(Show.BANNER, sound = false) else Decision(Show.NONE, false)
    CcSeverity.INFO -> if (s.bannersInfo && s.portalMode == ControlCenterConfig.MODE_FULL && !quietHours &&
        alert.kind == CcKind.APPLIANCE) Decision(Show.BANNER, sound = false) else Decision(Show.NONE, false)
  }
}
