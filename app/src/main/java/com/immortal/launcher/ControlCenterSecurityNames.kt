/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Friendly sensor names for the Security card (pure, unit tested). Home Assistant often names a
 * contact sensor after its hardware ("Tilt Shock XS Sensor Window/door is open") or a bridge tag
 * ("[Ring]: Back door"). The mock reads "Office window" / "Front & back", so: the tag and a trailing
 * state are dropped, a hardware name becomes "<area> <noun>", "Pantry Door Sensor" becomes "Pantry
 * door", and names left equal in one group are numbered. Anything already human is kept as typed.
 */
internal object CcSecNames {
  private val TAG = Regex("""^\s*\[[^\]]{1,40}]\s*[:\-–·]?\s*""")
  private val STATE_SUFFIX = Regex("""\s+is\s+(open|opened|closed|on|off|locked|unlocked|detected|clear)\s*$""", RegexOption.IGNORE_CASE)
  private val SENSOR_SUFFIX = Regex("""\s+(contact\s+sensor|sensor|contact)\s*$""", RegexOption.IGNORE_CASE)
  /** Words that only ever describe the hardware, never the place. */
  private val HARDWARE = Regex("""\b(tilt|shock|xs|multisensor|multi-sensor|window/door|door/window|zigbee|z-wave|zwave|aqara|sonoff)\b""",
      RegexOption.IGNORE_CASE)
  /** Model numbers: ZSE40, DWZWAVE2, MCT-350. */
  private val MODEL_CODE = Regex("""\b[A-Z]{2,}-?\d+[A-Z0-9-]*\b""")
  private val BARE = setOf("door", "window", "sensor", "motion", "contact", "opening", "garage door", "lock", "door sensor",
      "window sensor", "motion sensor", "occupancy", "contact sensor")
  /** A trailing noun that HA title-cases ("Deck Door"); the mock writes it lower-case ("Office window"). */
  private val NOUNS = setOf("Door", "Window", "Motion", "Lock", "Gate", "Sensor")

  /** The noun a generic sensor in [group] gets after its room ("Office window"); null keeps the raw name. */
  fun noun(group: CcSecGroup, id: String): String? = when (group) {
    CcSecGroup.DOORS -> if (id.startsWith("lock.")) "lock" else "door"
    CcSecGroup.LOCKS -> "lock"
    CcSecGroup.WINDOWS -> "window"
    CcSecGroup.GARAGE -> "garage door"
    CcSecGroup.MOTION -> "motion"
    CcSecGroup.HAZARDS -> "sensor"
    CcSecGroup.TAMPER -> "tamper"
    CcSecGroup.ALARM -> null
  }

  fun friendly(raw: String, area: String?, noun: String?): String {
    val trimmed = raw.trim()
    val untagged = TAG.replace(trimmed, "").takeIf { s -> s.any { it.isLetterOrDigit() } } ?: trimmed
    val stated = STATE_SUFFIX.replace(untagged, "").ifBlank { untagged }
    val place = area?.trim()?.takeIf { it.isNotEmpty() }
    if (place != null && noun != null && generic(stated)) return "$place $noun"
    val plain = SENSOR_SUFFIX.replace(stated, "").ifBlank { stated }
    return lowerTrailingNoun(plain)
  }

  fun generic(name: String): Boolean =
      HARDWARE.containsMatchIn(name) || MODEL_CODE.containsMatchIn(name) || name.trim().lowercase() in BARE

  private fun lowerTrailingNoun(name: String): String {
    val cut = name.lastIndexOf(' ')
    if (cut <= 0) return name
    val last = name.substring(cut + 1)
    return if (last in NOUNS) name.substring(0, cut + 1) + last.lowercase() else name
  }

  /** Every row of a group renamed; equal names are numbered ("Office door", "Office door 2") by entity id. */
  fun rename(items: List<CcSecItem>, group: CcSecGroup): List<CcSecItem> {
    val named = items.map { it.copy(name = friendly(it.name, it.area, noun(group, it.id))) }
    val dupes = named.groupBy { it.name.lowercase() }.filterValues { it.size > 1 }
    if (dupes.isEmpty()) return named
    val numbered = dupes.values.flatMap { same -> same.sortedBy { it.id }.mapIndexed { i, item -> item.id to if (i == 0) item.name else "${item.name} ${i + 1}" } }
        .toMap()
    return named.map { item -> numbered[item.id]?.let { item.copy(name = it) } ?: item }
  }

  /** The row's second line: the room only when the name doesn't already start with it. */
  fun subline(name: String, area: String?, since: String): String {
    val room = area?.takeIf { it.isNotBlank() && !name.startsWith(it, ignoreCase = true) }
    return listOfNotNull(room, since.ifEmpty { null }).joinToString(" · ")
  }
}
