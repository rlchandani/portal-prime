/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Currency
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** The live-flow diagram's nodes (positions follow the mock's 800×700 SVG). */
internal enum class CcEnergyNode(val x: Float, val y: Float, val radius: Float) {
  SOLAR(400f, 70f, 70f), GRID(90f, 330f, 70f), HOME(400f, 330f, 92f), BATTERY(710f, 330f, 70f), CHARGER(400f, 590f, 70f)
}

/** One line between a node and Home; dots move [from] → [to] (the direction power flows). */
internal data class CcEnergyLine(val node: CcEnergyNode, val from: CcEnergyNode, val to: CcEnergyNode, val kw: Double) {
  val active: Boolean get() = kw >= CcEnergyFlow.ACTIVE_KW
  /** Stroke width in the 800×700 design space (mock: 8 + min(kW, 3) × 4). */
  val width: Float get() = (CcEnergyFlow.BASE_WIDTH + kw.coerceAtMost(CcEnergyFlow.MAX_KW) * CcEnergyFlow.WIDTH_PER_KW).toFloat()
  /** One dash period per this many ms: faster for more power (mock: 1.9 s − min(kW, 3) × 0.4 s). */
  val periodMs: Int get() = ((CcEnergyFlow.BASE_PERIOD_S - kw.coerceAtMost(CcEnergyFlow.MAX_KW) * CcEnergyFlow.PERIOD_PER_KW) * MS).roundToInt()
  private companion object { const val MS = 1000 }
}

internal enum class CcEnergyTone { GREEN, AMBER, NEUTRAL }

internal data class CcEnergyStatus(val text: String, val icon: String, val tone: CcEnergyTone)

/** Pure rules for the "Right now" flow card (unit tested). */
internal object CcEnergyFlow {
  const val ACTIVE_KW = 0.05
  const val MAX_KW = 3.0
  const val BASE_WIDTH = 8.0
  const val WIDTH_PER_KW = 4.0
  const val BASE_PERIOD_S = 1.9
  const val PERIOD_PER_KW = 0.4
  /** Dash pattern from the mock (`stroke-dasharray="3 26"`). */
  const val DASH_ON = 3f
  const val DASH_OFF = 26f
  const val VIEW_W = 800f
  const val VIEW_H = 700f
  private const val W_PER_KW = 1000.0
  private const val PCT = 100

  /** Only the nodes this house actually measures; Home whenever anything is measured. */
  fun nodes(n: CcEnergyNow): List<CcEnergyNode> {
    if (!n.hasFlow && n.chargerW == null) return emptyList()
    return listOfNotNull(
        CcEnergyNode.SOLAR.takeIf { n.solarW != null }, CcEnergyNode.GRID.takeIf { n.gridW != null }, CcEnergyNode.HOME,
        CcEnergyNode.BATTERY.takeIf { n.batteryW != null }, CcEnergyNode.CHARGER.takeIf { n.chargerW != null })
  }

  /** Lines to Home with the direction power moves: import/discharge toward Home, export/charge away. */
  fun lines(n: CcEnergyNow): List<CcEnergyLine> = listOfNotNull(
      n.solarW?.let { CcEnergyLine(CcEnergyNode.SOLAR, CcEnergyNode.SOLAR, CcEnergyNode.HOME, kw(it).coerceAtLeast(0.0)) },
      n.gridW?.let { line(CcEnergyNode.GRID, it) },
      n.batteryW?.let { line(CcEnergyNode.BATTERY, it) },
      n.chargerW?.let { CcEnergyLine(CcEnergyNode.CHARGER, CcEnergyNode.HOME, CcEnergyNode.CHARGER, kw(it).coerceAtLeast(0.0)) },
  )

  private fun line(node: CcEnergyNode, w: Double): CcEnergyLine =
      if (w >= 0) CcEnergyLine(node, node, CcEnergyNode.HOME, kw(w)) else CcEnergyLine(node, CcEnergyNode.HOME, node, kw(-w))

  private fun kw(w: Double) = abs(w) / W_PER_KW

  /** "0.4 kW" (absolute; the label says the direction); "idle" for a charger drawing nothing. */
  fun value(node: CcEnergyNode, w: Double?): String = when {
    w == null -> "—"
    node == CcEnergyNode.CHARGER && kw(w) < ACTIVE_KW -> "idle"
    else -> "%.1f kW".format(Locale.US, kw(w))
  }

  fun label(node: CcEnergyNode, n: CcEnergyNow): String = when (node) {
    CcEnergyNode.SOLAR -> "Solar"
    CcEnergyNode.GRID -> n.gridW?.let { g -> when { kw(g) < ACTIVE_KW -> "Grid · idle"; g > 0 -> "Grid · importing"; else -> "Grid · exporting" } } ?: "Grid"
    CcEnergyNode.HOME -> "Home"
    CcEnergyNode.BATTERY -> listOfNotNull("Battery ${n.batteryPct?.let { "$it%" } ?: ""}".trim(), charging(n)).joinToString(" · ")
    CcEnergyNode.CHARGER -> "Car charger"
  }

  private fun charging(n: CcEnergyNow): String? = n.batteryW?.takeIf { it < 0 && kw(it) >= ACTIVE_KW }?.let { "charging" }

  /** The tag on the card: what is carrying the house right now. */
  fun status(n: CcEnergyNow): CcEnergyStatus? {
    val home = n.homeW?.takeIf { it > 0 } ?: return null
    val battery = n.batteryW ?: 0.0
    val solar = n.solarW ?: 0.0
    val grid = n.gridW ?: 0.0
    fun share(w: Double) = (w / home * PCT).roundToInt().coerceIn(0, PCT)
    return when {
      grid < 0 && kw(grid) >= ACTIVE_KW -> CcEnergyStatus("Exporting ${value(CcEnergyNode.GRID, grid)}", "sun", CcEnergyTone.GREEN)
      battery > 0 && kw(battery) >= ACTIVE_KW && battery >= solar -> CcEnergyStatus("Battery covering ${share(battery)}%", "battery", CcEnergyTone.GREEN)
      solar > 0 && kw(solar) >= ACTIVE_KW -> CcEnergyStatus("Solar covering ${share(solar)}%", "sun", CcEnergyTone.GREEN)
      grid > 0 && kw(grid) >= ACTIVE_KW -> CcEnergyStatus("All from the grid", "grid", CcEnergyTone.NEUTRAL)
      else -> null
    }
  }
}

/** A drawn chart: values in kW (today) or kWh (week) per bucket, scaled to [max]. */
internal data class CcChartModel(
    val week: Boolean,
    val use: List<Double>,
    val solar: List<Double>,
    val max: Double,
    val ticks: List<Double>,
    /** Bucket index → axis label. */
    val labels: List<Pair<Int, String>>,
    /** Fractional bucket position of "now" (today only). */
    val now: Float?,
    /** Buckets after this index are still to come (drawn dimmer). */
    val lastPast: Int,
) {
  fun y(v: Double): Float = if (max <= 0) 0f else (v / max).toFloat().coerceIn(0f, 1f)
}

/** Pure chart scaling and axis rules (unit tested). */
internal object CcChart {
  private const val HEADROOM = 1.1
  private const val TICK_TARGET = 4.0
  private val NICE = listOf(1.0, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0)
  private val STEPS = listOf(1.0, 2.0, 5.0, 10.0)
  val HOUR_TICKS = listOf(0, 6, 12, 18, 23)
  private const val MS_PER_HOUR = 3_600_000.0
  private const val NOON = 12

  /** The smallest "nice" number ≥ [v] (1, 2, 2.5, 3, 4, 5, 6, 8 × 10ⁿ); 1 for nothing. */
  fun niceMax(v: Double): Double {
    if (v <= 0 || v.isNaN()) return 1.0
    val mag = 10.0.pow(floor(log10(v)))
    return (NICE.firstOrNull { it * mag >= v - 1e-9 } ?: 10.0) * mag
  }

  /** Gridline values below [max] at a 1/2/5 step, at most ~3 of them. */
  fun ticks(max: Double): List<Double> {
    if (max <= 0) return emptyList()
    val raw = max / TICK_TARGET
    val mag = 10.0.pow(floor(log10(raw)))
    val step = (STEPS.firstOrNull { it * mag >= raw - 1e-9 } ?: 10.0) * mag
    val n = ceil(max / step - 1e-9).toInt()
    return (1 until n).map { it * step }
  }

  fun hourLabel(h: Int): String = when {
    h == 0 -> "12a"
    h == NOON -> "12p"
    h > NOON -> "${h - NOON}p"
    else -> "${h}a"
  }

  fun today(series: CcEnergySeries, now: Long): CcChartModel {
    val start = series.starts.firstOrNull() ?: now
    val pos = ((now - start) / MS_PER_HOUR).toFloat()
    val max = niceMax((series.use + series.solar).maxOrNull()?.times(HEADROOM) ?: 0.0)
    val n = series.starts.size
    return CcChartModel(false, series.use, series.solar, max, ticks(max),
        HOUR_TICKS.filter { it < n }.map { it to hourLabel(it) },
        pos.takeIf { it in 0f..n.toFloat() }, floor(pos).toInt())
  }

  fun week(series: CcEnergySeries, zone: TimeZone = TimeZone.getDefault(), locale: Locale = Locale.getDefault()): CcChartModel {
    val max = niceMax(series.use.maxOrNull()?.times(HEADROOM) ?: 0.0)
    val solarShare = series.use.indices.map { i -> minOf(series.solar.getOrElse(i) { 0.0 }, series.use[i]) }
    val fmt = SimpleDateFormat("EEE", locale).apply { timeZone = zone }
    val labels = series.starts.mapIndexed { i, t -> i to if (i == series.starts.lastIndex) "Today" else fmt.format(Date(t)) }
    return CcChartModel(true, series.use, solarShare, max, emptyList(), labels, null, series.starts.lastIndex)
  }

  /** The current hour of [now] in [zone] (used when no series exists yet). */
  fun hourOf(now: Long, zone: TimeZone = TimeZone.getDefault()): Int =
      Calendar.getInstance(zone).apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
}

/** A KPI tile: big value + caption. */
internal data class CcKpi(val value: String, val label: String)

/** KPI tiles and consumer bars (pure). */
internal object CcEnergyKpis {
  private const val PCT = 100

  fun kpis(series: CcEnergySeries, prefs: CcEnergyPrefs?, week: Boolean, currency: String = currencySymbol()): List<CcKpi> {
    val period = if (week) "this week" else "today"
    val fmt = if (week) "%.0f kWh" else "%.1f kWh"
    return listOfNotNull(
        CcKpi(fmt.format(Locale.US, series.solarTotal), "Solar $period").takeIf { prefs == null || prefs.solar.isNotEmpty() },
        CcKpi(fmt.format(Locale.US, series.usedTotal), "Used $period"),
        series.selfPowered?.let { CcKpi("${(it * PCT).roundToInt()}%", "Self-powered") },
        series.cost?.let { CcKpi("$currency${"%.2f".format(Locale.US, it)}", "Grid cost") },
    )
  }

  fun currencySymbol(locale: Locale = Locale.getDefault()): String =
      runCatching { Currency.getInstance(locale).getSymbol(locale) }.getOrNull() ?: "$"

  /** Each consumer's bar as a share of the biggest, 0..1. */
  fun shares(consumers: List<CcConsumer>): List<Float> {
    val top = consumers.maxOfOrNull { it.watts }?.takeIf { it > 0 } ?: return consumers.map { 0f }
    return consumers.map { (it.watts / top).toFloat().coerceIn(0f, 1f) }
  }

  fun consumerIcon(entityId: String): String = when {
    "dish" in entityId -> "dish"
    "dryer" in entityId -> "dryer"
    "wash" in entityId -> "washer"
    "oven" in entityId || "stove" in entityId || "cook" in entityId -> "oven"
    "tv" in entityId || "media" in entityId -> "tv"
    "heat" in entityId || "hvac" in entityId || "pump" in entityId -> "heat"
    "fridge" in entityId || "freezer" in entityId -> "power"
    else -> "bolt"
  }

  /** The EV charge switch, if HA has one ("switch.*charg*"). */
  fun chargeSwitch(entities: Map<String, CcEntity>): CcEntity? =
      entities.values.filter { it.domain == "switch" && "charg" in it.id && !it.unavailable }.minByOrNull { it.id }

  /** The car's state of charge, if a battery sensor names the car. */
  fun carBattery(entities: Map<String, CcEntity>): Int? = entities.values.firstOrNull { e ->
    e.domain == "sensor" && e.deviceClass == "battery" && CAR.any { it in e.id } && "phone" !in e.id
  }?.numeric?.toInt()?.coerceIn(0, PCT)

  private val CAR = listOf("car", "ev_", "vehicle", "tesla")
}
