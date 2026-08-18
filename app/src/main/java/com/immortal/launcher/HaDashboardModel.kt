/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

/**
 * Pure entity→tile mapping for the HA dashboard: decides which tile shape each entity gets and
 * in what order they lay out, plus the room's lit-light count. No Context/network — unit-tested
 * ([HaDashboardModelTest]); the Compose layer ([HaDashboardActivity]) just renders the result.
 */
object HaDashboardModel {

  /** The tile shapes the dashboard renders (mirrors mock 08). */
  enum class Kind { DIMMER, CLIMATE, MEDIA, TOGGLE, SENSOR }

  /** A resolved tile: the source detail plus its chosen shape. */
  data class Tile(val kind: Kind, val detail: HaDashboardApi.Detail)

  // Domains that render as an on/off glow toggle (persistent state, single tap).
  private val TOGGLE_DOMAINS = setOf("light", "switch", "fan", "input_boolean", "lock", "cover")
  private val SENSOR_DOMAINS = setOf("sensor", "binary_sensor")

  /**
   * Map a room's entity details to tiles, dropping domains the dashboard doesn't render (scenes
   * live in the rail, not a room; update/automation/etc. are noise on a wall display). A light
   * that reports brightness becomes a DIMMER; a plain light/switch a TOGGLE. Big tiles
   * (climate, media, dimmer) sort ahead of the small sensor/toggle tiles so the grid reads
   * top-heavy like the mock.
   */
  fun tilesFor(details: List<HaDashboardApi.Detail>): List<Tile> {
    val tiles = details.mapNotNull { d ->
      val kind = when {
        d.domain == "light" && d.brightnessPct != null -> Kind.DIMMER
        d.domain == "climate" -> Kind.CLIMATE
        d.domain == "media_player" -> Kind.MEDIA
        d.domain in TOGGLE_DOMAINS -> Kind.TOGGLE
        d.domain in SENSOR_DOMAINS -> Kind.SENSOR
        else -> null
      }
      kind?.let { Tile(it, d) }
    }
    // Stable sort by a rank so equal-rank tiles keep HA's (area registry) order.
    return tiles.sortedBy { rank(it.kind) }
  }

  private fun rank(k: Kind): Int = when (k) {
    Kind.DIMMER -> 0
    Kind.CLIMATE -> 1
    Kind.MEDIA -> 2
    Kind.TOGGLE -> 3
    Kind.SENSOR -> 4
  }

  /** How many lights/switches are on — for the room subtitle and the house-wide hero count. */
  fun litCount(details: List<HaDashboardApi.Detail>): Int =
      details.count { (it.domain == "light" || it.domain == "switch") && it.state.equals("on", true) }
}
