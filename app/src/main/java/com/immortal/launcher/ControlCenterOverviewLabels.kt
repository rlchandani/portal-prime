/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable

/**
 * Pure text and colour rules for the overview (unit tested): room captions per lens, the
 * climate heat ramp, light wash mixing, the "Live / Updated N min ago" pill and the honest
 * loading checklist.
 */
/** One floor in the floor switch. */
@Immutable
internal data class CcFloorTab(val id: String, val label: String)

/** The floor switch's tabs (wrapper so equal floors skip). */
@Immutable
internal data class CcFloorTabs(val tabs: List<CcFloorTab>)

/** What the legend pill shows for a lens (primitives only, so equal legends skip). */
@Immutable
internal data class CcLegend(val lens: CcLens, val lightsOn: Int = 0, val cold: String = "", val warm: String = "", val climate: String? = null)

internal object CcOverviewText {
  private const val MS_PER_MIN = 60_000L
  private const val W_PER_KW = 1000.0
  private const val SMALL_KW = 0.1
  private const val BYTE = 255

  /** The caption under a room's name for [lens]: "71° · 2 lights on", "71° · 46%", "0.5 kW". */
  fun caption(v: CcRoomView, lens: CcLens): String = when (lens) {
    CcLens.LIVE -> if (v.room.outdoor) v.temperature?.let { "${Math.round(it)}°" } ?: "" else v.caption()
    CcLens.CLIMATE -> listOfNotNull(v.temperature?.let { "${Math.round(it)}°" }, v.humidity?.let { "$it%" }).joinToString(" · ")
    CcLens.ENERGY -> v.powerW?.let { kw(it) } ?: ""
  }

  fun kw(watts: Double): String {
    val k = watts / W_PER_KW
    return if (k < SMALL_KW) "%.2f kW".format(k) else "%.1f kW".format(k)
  }

  /** The mock's climate ramp (`tempColor`): blue → green → yellow → orange over heat 0..1. Packed RGB. */
  fun heatRgb(heat: Float): Int {
    val h = heat.coerceIn(0f, 1f)
    val i = HEAT_STOPS.indexOfFirst { it.first >= h }.coerceAtLeast(1)
    val (h0, c0) = HEAT_STOPS[i - 1]
    val (h1, c1) = HEAT_STOPS[i]
    val k = if (h1 == h0) 0f else (h - h0) / (h1 - h0)
    return lerpRgb(c0, c1, k)
  }

  /** Average of the lights' colours (the room wash); warm white when none. */
  fun mixRgb(colors: List<Int>): Int {
    if (colors.isEmpty()) return WARM_WASH
    val r = colors.sumOf { (it shr 16) and BYTE } / colors.size
    val g = colors.sumOf { (it shr 8) and BYTE } / colors.size
    val b = colors.sumOf { it and BYTE } / colors.size
    return (r shl 16) or (g shl 8) or b
  }

  fun lerpRgb(a: Int, b: Int, k: Float): Int {
    fun ch(s: Int) = (((a shr s) and BYTE) + (((b shr s) and BYTE) - ((a shr s) and BYTE)) * k.coerceIn(0f, 1f)).toInt()
    return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
  }

  /** Bottom-right pill text, honest about how fresh the map is. */
  fun freshness(c: CcConnection, now: Long, time: (Long) -> String = ::ccTime): String = when (c.phase) {
    CcPhase.LIVE -> "Live from Home Assistant"
    CcPhase.STALE -> if (c.lastLiveAt <= 0) "Reconnecting" else {
      val mins = ((now - c.lastLiveAt) / MS_PER_MIN).coerceAtLeast(0)
      if (mins < 1) "Updated just now" else "Updated $mins min ago"
    }
    else -> if (c.lastLiveAt > 0) "Last known · ${time(c.lastLiveAt)}" else "No live data yet"
  }

  /** Minutes since the last live update, for the stale bar ("last update 6 min ago"). */
  fun staleLine(c: CcConnection, now: Long): String {
    if (c.lastLiveAt <= 0) return "Live updates paused — reconnecting"
    val mins = ((now - c.lastLiveAt) / MS_PER_MIN).coerceAtLeast(0)
    return "Live updates paused — reconnecting · last update ${if (mins < 1) "just now" else "$mins min ago"}"
  }

  enum class Step { DONE, NOW, LATER }

  data class LoadRow(val text: String, val step: Step)

  /** The engine's honest progress ([CcConnection.step]) as the mock's checklist. */
  fun loading(c: CcConnection, registry: CcRegistry?, entities: Int): List<LoadRow> {
    val at = when (c.step) {
      "Loading rooms" -> 1
      "Loading devices" -> 2
      "Waiting for the first update" -> 3
      else -> 0
    }
    val areas = registry?.let { r -> " · ${r.floors.size.coerceAtLeast(1)} floor${if (r.floors.size == 1) "" else "s"}, ${r.areas.size} areas" } ?: ""
    val devices = registry?.entities?.size?.takeIf { it > 0 }?.let { " · $it entities" } ?: ""
    val live = entities.takeIf { it > 0 }?.let { " · $it entities" } ?: ""
    val rows = listOf("Signing in with the saved token", "Floors & areas$areas", "Devices$devices", "Live states$live")
    return rows.mapIndexed { i, t -> LoadRow(t, when { i < at -> Step.DONE; i == at -> Step.NOW; else -> Step.LATER }) }
  }

  /** "Downstairs heating" under the climate legend, from the floor's climate entities. */
  fun climateLine(climates: List<CcEntity>): String? {
    val c = climates.firstOrNull { it.hvacAction != null && it.hvacAction != "off" && it.hvacAction != "idle" } ?: climates.firstOrNull() ?: return null
    val action = c.hvacAction?.takeIf { it.isNotBlank() } ?: c.state
    return "${c.name} ${action.replace('_', ' ')}"
  }

  fun legend(model: CcPlanModel, lens: CcLens, entities: Map<String, CcEntity>, fahrenheit: Boolean): CcLegend = when (lens) {
    CcLens.LIVE -> CcLegend(lens, lightsOn = model.lightsOn)
    CcLens.ENERGY -> CcLegend(lens)
    CcLens.CLIMATE -> {
      val (cold, warm) = heatRange(fahrenheit)
      CcLegend(lens, cold = cold, warm = warm,
          climate = climateLine(model.views.mapNotNull { v -> v.climateId?.let(entities::get) }.distinctBy { it.id }))
    }
  }

  /** Climate legend ends: the ramp's cold and warm temperatures. */
  fun heatRange(fahrenheit: Boolean): Pair<String, String> = if (fahrenheit) "61°" to "75°" else "16°" to "24°"

  /** "Ground floor" → "Ground" for the floor switch; other names stay as HA has them. */
  fun shortFloor(name: String): String =
      name.trim().removeSuffix(" floor").removeSuffix(" Floor").ifBlank { name }

  const val WARM_WASH = 0xFFC77A
  private val HEAT_STOPS = listOf(0f to 0x5AA9FF, 0.44f to 0x61D0A6, 0.72f to 0xF4D36B, 1f to 0xF08A4B)
}
