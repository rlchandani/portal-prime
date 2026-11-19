/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** The car view: asleep / offline / stale, charging, openings, tyres, the charger and the week. */
class ControlCenterTeslaCarTest {
  private val now = 1_794_943_503_000L
  private val hour = 3_600_000L
  private val ids = CcTeslaIds(name = "Stargate", model = "Model Y", car = mapOf(
      CcCarRole.BATTERY to "sensor.b", CcCarRole.RANGE to "sensor.r", CcCarRole.CHARGING to "sensor.cs",
      CcCarRole.CHARGER_POWER to "sensor.p", CcCarRole.ENERGY_ADDED to "sensor.added", CcCarRole.TIME_TO_FULL to "sensor.ttf",
      CcCarRole.CHARGE_LIMIT to "number.limit", CcCarRole.CABLE to "binary_sensor.cable", CcCarRole.STATUS to "binary_sensor.status",
      CcCarRole.LOCATION to "device_tracker.loc", CcCarRole.LOCK to "lock.car", CcCarRole.FRUNK to "cover.frunk",
      CcCarRole.WINDOW_RL to "binary_sensor.wrl", CcCarRole.TYRE_FL to "sensor.tfl", CcCarRole.TYRE_WARN_FL to "binary_sensor.wfl",
      CcCarRole.UPDATE to "update.car", CcCarRole.INSIDE_TEMP to "sensor.in", CcCarRole.CLIMATE to "climate.car",
      CcCarRole.CHARGE_SWITCH to "switch.charge", CcCarRole.TRUNK to "cover.trunk", CcCarRole.CHARGE_PORT to "cover.port",
      CcCarRole.FLASH to "button.flash"))

  private fun e(id: String, state: String, attrs: Map<String, Any?> = emptyMap(), changed: Long = now - hour) = id to CcEntity(id, state, attrs, changed)

  private val parked = mapOf(
      e("sensor.b", "72", mapOf("unit_of_measurement" to "%")), e("sensor.r", "217.9", mapOf("unit_of_measurement" to "mi")),
      e("sensor.cs", "disconnected"), e("binary_sensor.cable", "off"), e("binary_sensor.status", "on"),
      e("device_tracker.loc", "home", mapOf("latitude" to 1.0)), e("lock.car", "locked"), e("cover.frunk", "closed"),
      e("binary_sensor.wrl", "off", mapOf("device_class" to "window")), e("sensor.tfl", "42.79", mapOf("unit_of_measurement" to "psi")),
      e("binary_sensor.wfl", "off"), e("number.limit", "80", mapOf("min" to 50, "max" to 100)),
      e("update.car", "off", mapOf("installed_version" to "2026.38.5", "latest_version" to "2026.38.5")),
      e("sensor.in", "68.7"), e("climate.car", "off"), e("switch.charge", "off"), e("cover.trunk", "closed"), e("cover.port", "closed"),
      e("button.flash", "unknown"),
  )

  private fun view(entities: Map<String, CcEntity>, phase: CcPhase = CcPhase.LIVE) = CcTeslaCar.view(ids, entities, phase)!!

  @Test fun parkedAtHome_readsEverything() {
    val v = view(parked)
    assertEquals(CcCarLife.AWAKE, v.life)
    assertEquals(72, v.batteryPct); assertEquals("218 mi", v.range)
    assertEquals(CcChargeState.UNPLUGGED, v.charge); assertFalse(v.charging)
    assertEquals("Home", v.place); assertEquals(true, v.home)
    assertEquals(80, v.limitPct); assertEquals(50, v.limitMin)
    assertEquals(listOf(43, null, null, null), v.tyres.map { it.pressure })
    assertEquals("Parked", CcTeslaText.headline(v, now))
    assertEquals("Up to date · 2026.38.5", CcTeslaText.software(v))
    assertTrue(v.openingsKnown); assertTrue(v.open.isEmpty())
    assertNull(CcTeslaCar.view(CcTeslaIds.NONE, parked, CcPhase.LIVE))
  }

  @Test fun asleep_saysWhenItLastReported() {
    val v = view(parked + e("binary_sensor.status", "off", changed = now - 2 * hour))
    assertEquals(CcCarLife.ASLEEP, v.life)
    assertEquals("Car is asleep · updated 2 h ago", CcTeslaText.headline(v, now))
    // A TeslaMate-style state sensor says so directly.
    assertEquals(CcCarLife.ASLEEP, CcTeslaCar.life(null, CcEntity("sensor.s", "asleep"), CcEntity("sensor.b", "1"), CcPhase.LIVE))
    assertEquals(CcCarLife.OFFLINE, CcTeslaCar.life(null, CcEntity("sensor.s", "offline"), CcEntity("sensor.b", "1"), CcPhase.LIVE))
    // Unavailable status says nothing about sleep.
    assertEquals(CcCarLife.AWAKE, CcTeslaCar.life(CcEntity("binary_sensor.s", "unavailable"), null, CcEntity("sensor.b", "1"), CcPhase.LIVE))
  }

  @Test fun offlineAndStale() {
    val gone = view(parked + e("sensor.b", "unavailable"))
    assertEquals(CcCarLife.OFFLINE, gone.life)
    assertNull(gone.batteryPct)
    assertEquals("Car is offline · Tesla isn't reaching it", CcTeslaText.headline(gone, now))
    val stale = view(parked, CcPhase.STALE)
    assertEquals(CcCarLife.STALE, stale.life)
    assertTrue(CcTeslaText.headline(stale, now).startsWith("Last known"))
  }

  @Test fun charging_powerAddedAndTimeToFull() {
    val full = "2026-11-17T20:45:03+00:00"
    val v = view(parked + e("sensor.cs", "charging") + e("binary_sensor.cable", "on") + e("sensor.p", "7.2") +
        e("sensor.added", "12.4") + e("sensor.ttf", full, mapOf("device_class" to "timestamp")))
    assertTrue(v.charging); assertEquals(true, v.pluggedIn)
    assertEquals("Charging · 7.2 kW", CcTeslaText.headline(v, now))
    assertEquals("1 h 20 min", CcTeslaText.toFull(v.fullAt, now))
    assertEquals(12.4, v.energyAddedKwh!!, 0.001)
    // Minutes-style integrations count from the value's last change.
    val mins = view(parked + e("sensor.ttf", "45", mapOf("unit_of_measurement" to "min"), changed = now))
    assertEquals("45 min", CcTeslaText.toFull(mins.fullAt, now))
    val hours = view(parked + e("sensor.ttf", "1.5", mapOf("unit_of_measurement" to "h"), changed = now))
    assertEquals("1 h 30 min", CcTeslaText.toFull(hours.fullAt, now))
    assertNull(CcTeslaText.toFull(now - 1, now))
  }

  @Test fun chargeStates() {
    assertEquals(CcChargeState.PLUGGED, CcTeslaCar.charge("disconnected", true))
    assertEquals(CcChargeState.COMPLETE, CcTeslaCar.charge("Complete", true))
    assertEquals(CcChargeState.UNKNOWN, CcTeslaCar.charge(null, null))
    assertEquals(CcChargeState.STOPPED, CcTeslaCar.charge("stopped", true))
    val complete = view(parked + e("sensor.cs", "complete"))
    assertEquals("Charge complete · 80% limit", CcTeslaText.headline(complete, now))
  }

  @Test fun openings_tyreWarning_update_andStatusRows() {
    val v = view(parked + e("cover.frunk", "open") + e("binary_sensor.wrl", "on", mapOf("device_class" to "window")) +
        e("binary_sensor.wfl", "on") + e("update.car", "on", mapOf("installed_version" to "a", "latest_version" to "2026.40.1")))
    assertEquals(listOf("Frunk", "Rear left window"), v.open)
    assertEquals("Frunk and 1 more open", CcTeslaText.openings(v.open))
    assertEquals("Update ready · 2026.40.1", CcTeslaText.software(v))
    val rows = CcTeslaStatus.rows(v)
    assertEquals(CcTone.AMBER, rows.first { it.label.startsWith("Tyres") }.tone)
    assertEquals("43 · — · — · — psi", rows.first { it.label.startsWith("Tyres") }.value)
    assertEquals("Locked", rows.first { it.label == "Doors" }.value)
    assertEquals("69° inside", rows.first { it.icon == "therm" }.value)
    val blind = view(mapOf(e("sensor.b", "50")))
    assertFalse(blind.openingsKnown)
    assertFalse(CcTeslaStatus.rows(blind).any { it.icon == "door" })
  }

  @Test fun noCoordinatesEverReachTheView() {
    val v = view(parked + e("device_tracker.loc", "not_home", mapOf("latitude" to 47.1, "longitude" to -122.1)))
    assertEquals("Away", v.place)
    assertEquals("Parked · away", CcTeslaText.headline(v, now))
    assertFalse(v.toString().contains("47.1"))
  }

  @Test fun ago_andFormatting() {
    assertEquals("just now", CcTeslaText.ago(now, now))
    assertEquals("12 min ago", CcTeslaText.ago(now - 12 * 60_000L, now))
    assertEquals("3 d ago", CcTeslaText.ago(now - 72 * hour, now))
    assertEquals("a while ago", CcTeslaText.ago(0, now))
    assertEquals("17", CcTeslaText.kwh(17.4)); assertEquals("4.2", CcTeslaText.kwh(4.2))
    assertEquals("$1.75", CcTeslaText.money(1.75))
    assertEquals("All closed", CcTeslaText.openings(emptyList()))
    assertEquals("Tu", CcTeslaText.day(now, TimeZone.getTimeZone("UTC")))
  }

  @Test fun mini_andSpeech() {
    val m = CcTeslaText.mini(ids, view(parked), now)
    assertEquals("Stargate", m.label); assertEquals(72, m.batteryPct); assertFalse(m.charging)
    assertEquals("Stargate, battery 72 percent, 218 mi, Parked. Opens Stargate", CcTeslaText.speech(m))
  }

  @Test fun charger_viewAndWeek() {
    val cids = CcTeslaIds(charger = mapOf(CcChargerRole.POWER to "sensor.wp", CcChargerRole.SESSION to "sensor.ws",
        CcChargerRole.STATUS to "sensor.wst"), chargerSource = "tesla_wall_connector", price = "sensor.electricity_price")
    val ents = mapOf(e("sensor.wp", "7200", mapOf("unit_of_measurement" to "W")), e("sensor.ws", "5300", mapOf("unit_of_measurement" to "Wh")),
        e("sensor.wst", "charging_reduced"), e("sensor.electricity_price", "0.12", mapOf("unit_of_measurement" to "USD/kWh")))
    val ch = CcTeslaCharger.view(cids, ents)!!
    assertEquals(7.2, ch.powerKw!!, 0.001); assertEquals(5.3, ch.sessionKwh!!, 0.001)
    assertEquals("Charging", ch.status); assertTrue(ch.delivering); assertEquals("Wall Connector", ch.source)
    assertNull(CcTeslaCharger.view(CcTeslaIds.NONE, ents))
    assertEquals(0.12, CcTeslaCharger.price(cids, ents)!!, 0.0001)
    assertNull(CcTeslaCharger.price(cids, ents + e("sensor.electricity_price", "0.12", mapOf("unit_of_measurement" to "kWh"))))
    assertEquals("Not connected", CcTeslaCharger.status("not_connected"))
    assertEquals("Plugged in", CcTeslaCharger.status("waiting_car"))
    assertEquals("Not charging", CcTeslaCharger.status("Not Charging"))

    val stats = CcTeslaCharger.stats(JSONObject("""{"sensor.life":[{"start":1000,"change":22.4},{"start":2000,"change":null},
      {"start":"1970-01-01T00:00:03Z","change":-5.0}]}"""))
    val week = CcTeslaCharger.week("sensor.life", stats, listOf(1000L, 2000L, 3000L), 0.10)
    assertEquals(listOf(22.4, 0.0, 0.0), week.kwh)
    assertEquals(22.4, week.total, 0.001); assertEquals(0.0, week.today, 0.001)
    assertEquals(2.24, week.weekCost!!, 0.001)
    assertNull(CcTeslaCharger.week("x", emptyMap(), listOf(1L), null).todayCost)
    assertTrue(CcTeslaCharger.stats("nope").isEmpty())
    assertNotNull(stats["sensor.life"])
  }
}
