/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable

/** The overview side column's car card: just enough to glance at and tap through. */
@Immutable
internal data class CcCarMini(val label: String, val batteryPct: Int?, val range: String?, val line: String, val charging: Boolean)

/** Pure wording for the Tesla screen (unit tested): headline, charge line, ago, durations, money. */
internal object CcTeslaText {
  private const val MS_PER_MIN = 60_000L
  private const val MIN_PER_H = 60L
  private const val H_PER_DAY = 24L

  /** The hero's status line under the battery, e.g. "Charging · 7.2 kW", "Car is asleep · updated 2 h ago". */
  fun headline(v: CcCarView, now: Long): String = when (v.life) {
    CcCarLife.STALE -> "Last known · reconnecting to Home Assistant"
    CcCarLife.OFFLINE -> "Car is offline · Tesla isn't reaching it"
    CcCarLife.ASLEEP -> "Car is asleep · updated ${ago(v.updatedAt, now)}"
    CcCarLife.AWAKE -> charge(v)
  }

  fun charge(v: CcCarView): String = when (v.charge) {
    CcChargeState.CHARGING -> "Charging" + (v.powerKw?.let { " · ${kw(it)} kW" } ?: "")
    CcChargeState.STARTING -> "Starting to charge"
    CcChargeState.STOPPED -> "Plugged in · charging paused"
    CcChargeState.COMPLETE -> "Charge complete" + (v.limitPct?.let { " · $it% limit" } ?: "")
    CcChargeState.PLUGGED -> "Plugged in"
    CcChargeState.NO_POWER -> "Plugged in · charger has no power"
    CcChargeState.UNPLUGGED -> if (v.home == false) "Parked · away" else "Parked"
    CcChargeState.UNKNOWN -> "Parked"
  }

  /** "just now", "12 min ago", "2 h ago", "3 d ago"; "a while ago" when unknown. */
  fun ago(at: Long, now: Long): String {
    if (at <= 0) return "a while ago"
    val mins = ((now - at) / MS_PER_MIN).coerceAtLeast(0)
    return when {
      mins < 1 -> "just now"
      mins < MIN_PER_H -> "$mins min ago"
      mins < MIN_PER_H * H_PER_DAY -> "${mins / MIN_PER_H} h ago"
      else -> "${mins / (MIN_PER_H * H_PER_DAY)} d ago"
    }
  }

  /** Time until [fullAt]: "1 h 20 min", "45 min"; null when it's not coming. */
  fun toFull(fullAt: Long?, now: Long): String? {
    val mins = fullAt?.let { (it - now) / MS_PER_MIN }?.takeIf { it > 0 } ?: return null
    return if (mins < MIN_PER_H) "$mins min" else "${mins / MIN_PER_H} h ${mins % MIN_PER_H} min"
  }

  fun kw(v: Double): String = "%.1f".format(v)

  fun kwh(v: Double): String = if (v >= TEN) "%.0f".format(v) else "%.1f".format(v)

  fun money(v: Double): String = "$" + "%.2f".format(v)

  /** "All closed" or "Frunk open", "Frunk and 2 more open". */
  fun openings(open: List<String>): String = when (open.size) {
    0 -> "All closed"
    1 -> "${open.first()} open"
    else -> "${open.first()} and ${open.size - 1} more open"
  }

  fun software(v: CcCarView): String? = when {
    v.updateTo != null -> "Update ready" + v.updateTo.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
    v.installed != null -> "Up to date · ${v.installed}"
    else -> null
  }

  /** Day initials for the week chart, e.g. "Th" for a Thursday bucket. */
  fun day(start: Long, zone: java.util.TimeZone): String {
    val c = java.util.Calendar.getInstance(zone).apply { timeInMillis = start }
    return DAYS[c.get(java.util.Calendar.DAY_OF_WEEK) - 1]
  }

  private val DAYS = listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa")
  private const val TEN = 10.0

  fun mini(ids: CcTeslaIds, v: CcCarView, now: Long): CcCarMini =
      CcCarMini(ids.label, v.batteryPct, v.range, headline(v, now), v.charging && v.life == CcCarLife.AWAKE)

  /** Spoken summary of the side card. */
  fun speech(m: CcCarMini): String =
      listOfNotNull(m.label, m.batteryPct?.let { "battery $it percent" }, m.range, m.line).joinToString(", ") + ". Opens ${m.label}"
}

/** One cell of the car status grid. */
@Immutable
internal data class CcStatusRow(val icon: String, val label: String, val value: String, val tone: CcTone = CcTone.NEUTRAL)

/** The status grid (pure): only what the car reports, in a fixed order. */
internal object CcTeslaStatus {
  fun rows(v: CcCarView): List<CcStatusRow> = listOfNotNull(
      v.locked?.let { CcStatusRow(if (it) "lock" else "unlock", "Doors", if (it) "Locked" else "Unlocked", if (it) CcTone.GREEN else CcTone.AMBER) },
      cabin(v),
      v.sentry?.let { CcStatusRow("eye", "Sentry mode", if (it) "On" else "Off", if (it) CcTone.GREEN else CcTone.NEUTRAL) },
      tyres(v),
      CcStatusRow("door", "Doors, windows, frunk, trunk", CcTeslaText.openings(v.open), if (v.open.isEmpty()) CcTone.GREEN else CcTone.AMBER)
          .takeIf { v.openingsKnown },
      v.place?.let { CcStatusRow("home", "Location", it, if (v.home == true) CcTone.GREEN else CcTone.NEUTRAL) },
      CcTeslaText.software(v)?.let { CcStatusRow("refresh", "Software", it, if (v.updateTo != null) CcTone.AMBER else CcTone.NEUTRAL) },
      v.odometer?.let { CcStatusRow("car", "Odometer", it) },
  )

  private fun cabin(v: CcCarView): CcStatusRow? {
    val inside = v.insideTemp ?: return v.climateOn?.let { CcStatusRow("therm", "Climate", if (it) "On" else "Off") }
    val value = listOfNotNull("$inside inside", v.outsideTemp?.let { "$it out" }).joinToString(" · ")
    return CcStatusRow("therm", if (v.climateOn == true) "Climate on" else "Climate off", value, if (v.climateOn == true) CcTone.GREEN else CcTone.NEUTRAL)
  }

  /** "43 · 43 · 43 · 43 psi" (front left, front right, rear left, rear right); amber with a warning. */
  private fun tyres(v: CcCarView): CcStatusRow? {
    if (v.tyres.none { it.pressure != null }) return null
    val text = v.tyres.joinToString(" · ") { it.pressure?.toString() ?: "—" } + " ${v.tyreUnit}"
    val warn = v.tyres.any { it.warn }
    return CcStatusRow("warn".takeIf { warn } ?: "check", if (warn) "Tyres · check pressure" else "Tyres", text,
        if (warn) CcTone.AMBER else CcTone.NEUTRAL)
  }
}
