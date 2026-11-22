/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

/**
 * Pure entity→tile mapping for the HA dashboard: which tile shape each entity gets, in what
 * order, how many grid cells it spans, and the lit-light counts. No Context/network — unit-tested
 * ([HaDashboardModelTest]); the Compose layer only renders the result.
 */
object HaDashboardModel {

  /** The tile shapes the dashboard renders. */
  enum class Kind { DIMMER, CLIMATE, MEDIA, TOGGLE, SENSOR }

  /** A resolved tile: the source detail plus its chosen shape. */
  data class Tile(val kind: Kind, val detail: HaDashboardApi.Detail)

  /** Grid cells a tile covers: dimmer and media are 2 wide, climate 2×2, the rest 1×1. */
  data class Span(val cols: Int, val rows: Int)

  // Domains that render as an on/off toggle (persistent state, single tap).
  private val TOGGLE_DOMAINS = setOf("light", "switch", "fan", "input_boolean", "lock", "cover")
  private val SENSOR_CLASSES = setOf("temperature", "humidity", "carbon_dioxide", "pm25", "pm10", "aqi",
      "volatile_organic_compounds", "illuminance")
  private val BINARY_CLASSES = setOf("door", "window", "garage_door", "opening", "moisture", "smoke", "gas",
      "carbon_monoxide")
  private val TEMP_UNITS = setOf("°C", "°F")

  /**
   * Map a room's entity details to tiles, dropping what a wall display shouldn't show (scenes
   * live in the side column; battery/signal sensors are noise). A light that reports brightness
   * becomes a DIMMER. Big tiles sort first so the dense grid reads top-heavy; capped at
   * [HaDashboardApi.MAX_ENTITIES_PER_ROOM].
   */
  fun tilesFor(details: List<HaDashboardApi.Detail>): List<Tile> {
    val tiles = details.mapNotNull { d -> kindOf(d)?.let { Tile(it, d) } }
    // Stable sort by a rank so equal-rank tiles keep HA's (area registry) order.
    return tiles.sortedBy { rank(it.kind) }.take(HaDashboardApi.MAX_ENTITIES_PER_ROOM)
  }

  private fun kindOf(d: HaDashboardApi.Detail): Kind? = when {
    d.domain == "light" && d.brightnessPct != null -> Kind.DIMMER
    d.domain == "climate" -> Kind.CLIMATE
    d.domain == "media_player" -> Kind.MEDIA
    d.domain in TOGGLE_DOMAINS -> Kind.TOGGLE
    d.domain == "sensor" && meaningfulSensor(d) -> Kind.SENSOR
    d.domain == "binary_sensor" && d.deviceClass in BINARY_CLASSES -> Kind.SENSOR
    else -> null
  }

  /** A sensor worth a tile: an air/climate reading, or an unclassed one in °C/°F. */
  private fun meaningfulSensor(d: HaDashboardApi.Detail): Boolean =
      d.deviceClass?.let { it in SENSOR_CLASSES } ?: (d.unit in TEMP_UNITS)

  private fun rank(k: Kind): Int = when (k) {
    Kind.DIMMER -> 0
    Kind.CLIMATE -> 1
    Kind.MEDIA -> 2
    Kind.TOGGLE -> 3
    Kind.SENSOR -> 4
  }

  fun span(kind: Kind): Span = when (kind) {
    Kind.DIMMER, Kind.MEDIA -> Span(2, 1)
    Kind.CLIMATE -> Span(2, 2)
    Kind.TOGGLE, Kind.SENSOR -> Span(1, 1)
  }

  /** How many lights/switches are on — the room count, header chip and greeting subline. */
  fun litCount(details: List<HaDashboardApi.Detail>): Int =
      details.count { (it.domain == "light" || it.domain == "switch") && it.state.equals("on", true) }

  /** Lights and switches that are on: what the room's "All off" turns off. */
  fun litEntities(details: List<HaDashboardApi.Detail>): List<HaDashboardApi.Detail> =
      details.filter { (it.domain == "light" || it.domain == "switch") && it.state.equals("on", true) }

  /** The room's first temperature/humidity reading (header chips, greeting subline). */
  fun temperature(details: List<HaDashboardApi.Detail>): HaDashboardApi.Detail? =
      details.firstOrNull { it.domain == "sensor" && it.unit in TEMP_UNITS && it.state.toFloatOrNull() != null }

  fun humidity(details: List<HaDashboardApi.Detail>): HaDashboardApi.Detail? =
      details.firstOrNull { it.domain == "sensor" && it.unit == "%" &&
          (it.deviceClass == null || it.deviceClass == "humidity") && it.state.toFloatOrNull() != null }

  private val ROOM_WORDS = listOf(
      listOf("living", "lounge", "family", "den", "sitting") to "sofa",
      listOf("kitchen", "dining", "pantry") to "pot",
      listOf("bed", "master", "nursery", "suite") to "bed",
      listOf("office", "study", "desk", "work") to "desk",
      listOf("hall", "entry", "foyer", "landing", "stair", "corridor") to "hall",
      listOf("kid", "child", "play", "toy") to "toy",
      listOf("garage", "carport", "driveway") to "car",
      listOf("patio", "garden", "yard", "porch", "deck", "outdoor", "balcony", "terrace") to "tree",
      listOf("guest") to "guest",
  )

  /** The room's glyph, from its area id/name ("living_room" → sofa); a house otherwise. */
  fun roomIcon(id: String, name: String): String {
    val key = "$id $name".lowercase()
    // Guest first: "Guest Bedroom" is the guest room, not a bedroom.
    if ("guest" in key) return "guest"
    return ROOM_WORDS.firstOrNull { (words, _) -> words.any { it in key } }?.second ?: "home"
  }
}
