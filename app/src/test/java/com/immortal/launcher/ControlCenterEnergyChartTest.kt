/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class ControlCenterEnergyChartTest {
  private fun now(home: Double? = 2300.0, solar: Double? = 300.0, grid: Double? = 400.0, battery: Double? = 1600.0,
                  pct: Int? = 68, charger: Double? = null) = CcEnergyNow(home, solar, grid, battery, pct, charger, emptyList())

  @Test fun nodes_onlyWhatTheHouseMeasures() {
    assertEquals(listOf(CcEnergyNode.SOLAR, CcEnergyNode.GRID, CcEnergyNode.HOME, CcEnergyNode.BATTERY), CcEnergyFlow.nodes(now()))
    assertEquals(listOf(CcEnergyNode.GRID, CcEnergyNode.HOME), CcEnergyFlow.nodes(now(solar = null, battery = null, pct = null)))
    assertEquals(listOf(CcEnergyNode.HOME, CcEnergyNode.CHARGER),
        CcEnergyFlow.nodes(CcEnergyNow(null, null, null, null, null, 0.0, emptyList())))
    assertTrue(CcEnergyFlow.nodes(CcEnergyNow(null, null, null, null, null, null, emptyList())).isEmpty())
  }

  @Test fun lines_pointTheWayPowerFlows() {
    val lines = CcEnergyFlow.lines(now(grid = -1200.0, battery = -500.0, charger = 7200.0)).associateBy { it.node }
    assertEquals(CcEnergyNode.SOLAR, lines.getValue(CcEnergyNode.SOLAR).from)
    assertEquals(CcEnergyNode.HOME, lines.getValue(CcEnergyNode.GRID).from)          // exporting
    assertEquals(CcEnergyNode.GRID, lines.getValue(CcEnergyNode.GRID).to)
    assertEquals(1.2, lines.getValue(CcEnergyNode.GRID).kw, 1e-9)
    assertEquals(CcEnergyNode.BATTERY, lines.getValue(CcEnergyNode.BATTERY).to)    // charging
    assertEquals(CcEnergyNode.CHARGER, lines.getValue(CcEnergyNode.CHARGER).to)
    val importing = CcEnergyFlow.lines(now()).associateBy { it.node }
    assertEquals(CcEnergyNode.GRID, importing.getValue(CcEnergyNode.GRID).from)
    assertEquals(CcEnergyNode.BATTERY, importing.getValue(CcEnergyNode.BATTERY).from)
  }

  @Test fun line_widthSpeedAndIdle() {
    val idle = CcEnergyLine(CcEnergyNode.GRID, CcEnergyNode.GRID, CcEnergyNode.HOME, 0.01)
    assertTrue(!idle.active)
    val big = CcEnergyLine(CcEnergyNode.GRID, CcEnergyNode.GRID, CcEnergyNode.HOME, 7.0)
    assertEquals(20f, big.width, 1e-4f)
    assertEquals(700, big.periodMs)
    assertEquals(1896, idle.periodMs)
  }

  @Test fun valuesAndLabels() {
    assertEquals("0.4 kW", CcEnergyFlow.value(CcEnergyNode.GRID, -400.0))
    assertEquals("idle", CcEnergyFlow.value(CcEnergyNode.CHARGER, 10.0))
    assertEquals("—", CcEnergyFlow.value(CcEnergyNode.SOLAR, null))
    assertEquals("Grid · importing", CcEnergyFlow.label(CcEnergyNode.GRID, now()))
    assertEquals("Grid · exporting", CcEnergyFlow.label(CcEnergyNode.GRID, now(grid = -900.0)))
    assertEquals("Grid · idle", CcEnergyFlow.label(CcEnergyNode.GRID, now(grid = 0.0)))
    assertEquals("Battery 68%", CcEnergyFlow.label(CcEnergyNode.BATTERY, now()))
    assertEquals("Battery · charging", CcEnergyFlow.label(CcEnergyNode.BATTERY, now(battery = -900.0, pct = null)))
  }

  @Test fun status_saysWhatCarriesTheHouse() {
    assertEquals("Battery covering 70%", CcEnergyFlow.status(now())?.text)
    assertEquals("Solar covering 87%", CcEnergyFlow.status(now(solar = 2000.0, battery = null))?.text)
    assertEquals("Exporting 0.5 kW", CcEnergyFlow.status(now(grid = -500.0))?.text)
    assertEquals("All from the grid", CcEnergyFlow.status(now(solar = 0.0, battery = 0.0))?.text)
    assertNull(CcEnergyFlow.status(now(home = null)))
    assertNull(CcEnergyFlow.status(now(solar = 0.0, battery = 0.0, grid = 0.0)))
  }

  @Test fun niceMaxAndTicks() {
    assertEquals(4.0, CcChart.niceMax(3.85), 1e-9)
    assertEquals(1.0, CcChart.niceMax(0.0), 1e-9)
    assertEquals(30.0, CcChart.niceMax(28.6), 1e-9)
    assertEquals(0.25, CcChart.niceMax(0.22), 1e-9)
    assertEquals(listOf(1.0, 2.0, 3.0), CcChart.ticks(4.0))
    assertEquals(listOf(10.0, 20.0), CcChart.ticks(30.0))
    assertTrue(CcChart.ticks(0.0).isEmpty())
  }

  @Test fun hourLabels() {
    assertEquals(listOf("12a", "6a", "12p", "6p", "11p"), CcChart.HOUR_TICKS.map(CcChart::hourLabel))
  }

  @Test fun today_scalesAndPlacesNow() {
    val starts = (0 until 24).map { it * 3_600_000L }
    val use = List(24) { 1.0 }.toMutableList().also { it[18] = 2.6 }
    val solar = List(24) { 0.0 }.toMutableList().also { it[12] = 3.5 }
    val m = CcChart.today(CcEnergySeries(starts, use, solar, use, null), 19 * 3_600_000L + 42 * 60_000L)
    assertEquals(4.0, m.max, 1e-9)
    assertEquals(19.7f, m.now!!, 1e-4f)
    assertEquals(19, m.lastPast)
    assertEquals(5, m.labels.size)
    assertEquals(0.875f, m.y(3.5), 1e-6f)
    assertEquals(1f, m.y(99.0), 1e-6f)
  }

  @Test fun week_labelsDaysAndCapsSolarAtUse() {
    val zone = TimeZone.getTimeZone("UTC")
    val day = 86_400_000L
    val base = 1_794_355_200_000L // Wed 2026-11-11 00:00 UTC
    val starts = (0 until 7).map { base + it * day }
    val m = CcChart.week(CcEnergySeries(starts, listOf(21.0, 23.0, 19.0, 26.0, 25.0, 22.0, 18.0),
        listOf(15.0, 9.0, 17.0, 18.0, 6.0, 14.0, 18.6), emptyList(), 14.2), zone, Locale.US)
    assertEquals("Wed", m.labels.first().second)
    assertEquals("Today", m.labels.last().second)
    assertEquals(18.0, m.solar.last(), 1e-9)
    assertEquals(30.0, m.max, 1e-9)
    assertNull(m.now)
  }

  @Test fun kpis_hideWhatIsMissing() {
    val s = CcEnergySeries(listOf(0L), listOf(17.9), listOf(18.6), listOf(5.2), 1.84)
    val k = CcEnergyKpis.kpis(s, CcEnergyPrefs(gridIn = listOf("g"), solar = listOf("s")), week = false, currency = "$")
    assertEquals(listOf("18.6 kWh", "17.9 kWh", "71%", "$1.84"), k.map { it.value })
    assertEquals("Solar today", k.first().label)
    val noSolar = CcEnergyKpis.kpis(s.copy(cost = null), CcEnergyPrefs(gridIn = listOf("g")), week = true, currency = "$")
    assertEquals(listOf("Used this week", "Self-powered"), noSolar.map { it.label })
    assertEquals("18 kWh", noSolar.first().value)
  }

  @Test fun consumers_sharesIconsAndEv() {
    val shares = CcEnergyKpis.shares(listOf(CcConsumer("a", "A", 1200.0), CcConsumer("b", "B", 300.0)))
    assertEquals(listOf(1f, 0.25f), shares)
    assertEquals(listOf(0f), CcEnergyKpis.shares(listOf(CcConsumer("z", "Z", 0.0))))
    assertEquals("dryer", CcEnergyKpis.consumerIcon("sensor.dryer_power"))
    assertEquals("dish", CcEnergyKpis.consumerIcon("sensor.dishwasher_power"))
    assertEquals("bolt", CcEnergyKpis.consumerIcon("sensor.plug_3_power"))
    val ents = listOf(CcEntity("switch.ev_charging", "off"), CcEntity("switch.kitchen", "on"),
        CcEntity("sensor.car_battery", "64", mapOf("device_class" to "battery")),
        CcEntity("sensor.phone_car_battery", "12", mapOf("device_class" to "battery"))).associateBy { it.id }
    assertEquals("switch.ev_charging", CcEnergyKpis.chargeSwitch(ents)?.id)
    assertEquals(64, CcEnergyKpis.carBattery(ents))
    assertNull(CcEnergyKpis.chargeSwitch(emptyMap()))
  }
}
