/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

/** [CcTeslaText], [CcTeslaStatus] and [CcTeslaIdle]: the car's wording for every state the view can hold. */
class ControlCenterTeslaTextCoverageTest {
  private val base: CcCarView = run {
    val ids = CcTeslaIds(car = mapOf(CcCarRole.BATTERY to "sensor.y_battery"))
    CcTeslaCar.view(ids, mapOf("sensor.y_battery" to CcEntity("sensor.y_battery", "50")), CcPhase.LIVE)!!
  }
  private val min = 60_000L

  @Test fun headlinesForEveryLifeState() {
    assertEquals("Last known · reconnecting to Home Assistant", CcTeslaText.headline(base.copy(life = CcCarLife.STALE), 0))
    assertEquals("Car is offline · Tesla isn't reaching it", CcTeslaText.headline(base.copy(life = CcCarLife.OFFLINE), 0))
    assertEquals("Car is asleep · updated a while ago", CcTeslaText.headline(base.copy(life = CcCarLife.ASLEEP, updatedAt = 0), 0))
    assertEquals("Parked", CcTeslaText.headline(base.copy(life = CcCarLife.AWAKE, charge = CcChargeState.UNKNOWN), 0))
  }

  @Test fun chargeLinesForEveryChargeState() {
    fun c(s: CcChargeState, f: (CcCarView) -> CcCarView = { it }) = CcTeslaText.charge(f(base.copy(charge = s)))
    assertEquals("Charging", c(CcChargeState.CHARGING) { it.copy(powerKw = null) })
    assertEquals("Charging · 7.2 kW", c(CcChargeState.CHARGING) { it.copy(powerKw = 7.2) })
    assertEquals("Starting to charge", c(CcChargeState.STARTING))
    assertEquals("Plugged in · charging paused", c(CcChargeState.STOPPED))
    assertEquals("Charge complete", c(CcChargeState.COMPLETE) { it.copy(limitPct = null) })
    assertEquals("Charge complete · 80% limit", c(CcChargeState.COMPLETE) { it.copy(limitPct = 80) })
    assertEquals("Plugged in", c(CcChargeState.PLUGGED))
    assertEquals("Plugged in · charger has no power", c(CcChargeState.NO_POWER))
    assertEquals("Parked · away", c(CcChargeState.UNPLUGGED) { it.copy(home = false) })
    assertEquals("Parked", c(CcChargeState.UNPLUGGED) { it.copy(home = null) })
  }

  @Test fun agesAndDurations() {
    val now = 10 * 24 * 60 * min
    assertEquals("just now", CcTeslaText.ago(now + 5_000, now))
    assertEquals("12 min ago", CcTeslaText.ago(now - 12 * min, now))
    assertEquals("2 h ago", CcTeslaText.ago(now - 125 * min, now))
    assertEquals("3 d ago", CcTeslaText.ago(now - 3 * 24 * 60 * min, now))
    assertNull(CcTeslaText.toFull(null, now))
    assertNull(CcTeslaText.toFull(now + 30_000, now))
    assertEquals("1 h 20 min", CcTeslaText.toFull(now + 80 * min, now))
    assertEquals("2 h", CcTeslaText.duration(120))
    assertEquals("45 min", CcTeslaText.duration(45))
  }

  @Test fun numbersOpeningsAndSoftware() {
    assertEquals("12", CcTeslaText.kwh(12.4))
    assertEquals("$1.50", CcTeslaText.money(1.5))
    assertEquals("All closed", CcTeslaText.openings(emptyList()))
    assertEquals("Frunk open", CcTeslaText.openings(listOf("Frunk")))
    assertEquals("Frunk and 2 more open", CcTeslaText.openings(listOf("Frunk", "Trunk", "Port")))
    assertEquals("Update ready · 2026.8", CcTeslaText.software(base.copy(updateTo = "2026.8")))
    assertEquals("Update ready", CcTeslaText.software(base.copy(updateTo = " ")))
    assertEquals("Up to date · 2026.2", CcTeslaText.software(base.copy(updateTo = null, installed = "2026.2")))
    assertNull(CcTeslaText.software(base.copy(updateTo = null, installed = null)))
    assertEquals("Th", CcTeslaText.day(0L, TimeZone.getTimeZone("UTC")))
  }

  @Test fun miniCardAndSpeech() {
    val ids = CcTeslaIds(name = "Stargate")
    val m = CcTeslaText.mini(ids, base.copy(life = CcCarLife.AWAKE, charge = CcChargeState.CHARGING, powerKw = null, range = "200 mi"), 0)
    assertEquals(true, m.charging)
    assertEquals("Stargate, battery 50 percent, 200 mi, Charging. Opens Stargate", CcTeslaText.speech(m))
    val asleep = CcTeslaText.mini(ids, base.copy(life = CcCarLife.ASLEEP, updatedAt = 0, charge = CcChargeState.CHARGING, batteryPct = null, range = null), 0)
    assertEquals(false, asleep.charging)
    assertEquals("Stargate, Car is asleep · updated a while ago. Opens Stargate", CcTeslaText.speech(asleep))
  }

  @Test fun statusRowsOnlyForWhatTheCarReports() {
    val bare = base.copy(locked = null, insideTemp = null, climateOn = null, sentry = null, tyres = emptyList(), openingsKnown = false,
        place = null, updateTo = null, installed = null, odometer = null)
    assertEquals(emptyList<CcStatusRow>(), CcTeslaStatus.rows(bare))
    val rich = bare.copy(locked = false, climateOn = true, sentry = true, tyres = listOf(CcTyre(42, false), CcTyre(null, true)),
        tyreUnit = "psi", openingsKnown = true, open = listOf("Frunk"), place = "Away", home = false, updateTo = "x", odometer = "1,000 mi")
    val rows = CcTeslaStatus.rows(rich).associateBy { it.label }
    assertEquals(CcStatusRow("unlock", "Doors", "Unlocked", CcTone.AMBER), rows["Doors"])
    assertEquals(CcStatusRow("therm", "Climate", "On"), rows["Climate"])
    assertEquals(CcStatusRow("eye", "Sentry mode", "On", CcTone.GREEN), rows["Sentry mode"])
    assertEquals(CcStatusRow("warn", "Tyres · check pressure", "42 · — psi", CcTone.AMBER), rows["Tyres · check pressure"])
    assertEquals(CcTone.AMBER, rows.getValue("Doors, windows, frunk, trunk").tone)
    assertEquals(CcTone.NEUTRAL, rows.getValue("Location").tone)
    assertEquals(CcTone.AMBER, rows.getValue("Software").tone)
    assertEquals("1,000 mi", rows.getValue("Odometer").value)
    val cabin = CcTeslaStatus.rows(bare.copy(insideTemp = "20°", outsideTemp = null, climateOn = false)).single()
    assertEquals(CcStatusRow("therm", "Climate off", "20° inside", CcTone.NEUTRAL), cabin)
    val warm = CcTeslaStatus.rows(bare.copy(insideTemp = "20°", outsideTemp = "5°", climateOn = true)).single()
    assertEquals(CcStatusRow("therm", "Climate on", "20° inside · 5° out", CcTone.GREEN), warm)
    val okTyres = CcTeslaStatus.rows(bare.copy(tyres = listOf(CcTyre(40, false)), tyreUnit = "bar")).single()
    assertEquals(CcStatusRow("check", "Tyres", "40 bar", CcTone.NEUTRAL), okTyres)
    assertEquals(CcTone.GREEN, CcTeslaStatus.rows(bare.copy(locked = true)).single().tone)
  }

  @Test fun idleTitlesAndTheHomeChargeEstimate() {
    fun idle(s: CcChargeState, f: (CcCarView) -> CcCarView = { it }, charger: CcChargerView? = null, model: String? = null) =
        CcTeslaIdle.of(f(base.copy(charge = s)), charger, model)
    assertEquals("Charge complete", idle(CcChargeState.COMPLETE).title)
    assertEquals("Plugged in · paused", idle(CcChargeState.STOPPED).title)
    assertEquals("Plugged in · not charging", idle(CcChargeState.PLUGGED).title)
    assertEquals("Plugged in · no power", idle(CcChargeState.NO_POWER).title)
    assertEquals("Not plugged in", idle(CcChargeState.UNPLUGGED).title)
    assertNull(idle(CcChargeState.PLUGGED, { it.copy(batteryPct = null) }).detail)
    assertNull(idle(CcChargeState.PLUGGED, { it.copy(limitPct = null) }).detail)
    assertEquals("At your 50% limit", idle(CcChargeState.PLUGGED, { it.copy(limitPct = 50) }).detail)
    assertNull("no amps, no estimate", idle(CcChargeState.PLUGGED, { it.copy(limitPct = 80, chargeAmps = null) }).detail)
    // 48 A at the nominal 240 V is 11.52 kW; the wording rounds it and uses the pack size by model.
    val kw = CcTeslaIdle.rateKw(48, null)!!
    assertEquals(11.52, kw, 1e-9)
    val mins = CcTeslaIdle.minutesTo(50, 80, 75.0, kw)
    val estimate = idle(CcChargeState.PLUGGED, { it.copy(limitPct = 80, chargeAmps = 48) })
    assertEquals("About ${CcTeslaText.duration(mins)} to 80% at 12 kW", estimate.detail)
    assertEquals("the Mini's tile drops the rate", "About ${CcTeslaText.duration(mins)} to 80%", estimate.shortDetail)
    assertEquals("At your 50% limit", idle(CcChargeState.PLUGGED, { it.copy(limitPct = 50) }).shortDetail)
    assertNull(idle(CcChargeState.PLUGGED, { it.copy(batteryPct = null) }).shortDetail)
    val grid = CcChargerView("Wall Connector", null, null, null, null, null, gridVolts = 200.0)
    assertEquals(9.6, CcTeslaIdle.rateKw(48, grid.gridVolts)!!, 1e-9)
    val onGrid = CcTeslaIdle.minutesTo(50, 80, 75.0, 9.6)
    assertEquals("About ${CcTeslaText.duration(onGrid)} to 80% at 10 kW",
        idle(CcChargeState.PLUGGED, { it.copy(limitPct = 80, chargeAmps = 48) }, charger = grid).detail)
    val sx = CcTeslaIdle.minutesTo(50, 80, 95.0, kw)
    assertEquals("About ${CcTeslaText.duration(sx)} to 80% at 12 kW",
        idle(CcChargeState.PLUGGED, { it.copy(limitPct = 80, chargeAmps = 48) }, model = "Model X").detail)
    val cyber = CcTeslaIdle.minutesTo(50, 80, 123.0, kw)
    assertEquals("About ${CcTeslaText.duration(cyber)} to 80% at 12 kW",
        idle(CcChargeState.PLUGGED, { it.copy(limitPct = 80, chargeAmps = 48) }, model = "Cybertruck").detail)
    val y = CcTeslaIdle.minutesTo(50, 80, 75.0, kw)
    assertEquals("About ${CcTeslaText.duration(y)} to 80% at 12 kW",
        idle(CcChargeState.PLUGGED, { it.copy(limitPct = 80, chargeAmps = 48) }, model = "Model Y").detail)
  }
}
