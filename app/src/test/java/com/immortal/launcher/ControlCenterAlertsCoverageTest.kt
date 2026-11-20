/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [CcAlerts] (what raises an alert, in what words) and [CcBannerPolicy] (who sees and hears it). */
class ControlCenterAlertsCoverageTest {
  private val min = 60_000L
  private val now = 1_000 * min
  private fun e(id: String, state: String, vararg attrs: Pair<String, Any?>, changed: Long = now - 30 * min) =
      CcEntity(id, state, mapOf(*attrs), lastChanged = changed)
  private fun alerts(vararg es: CcEntity, rooms: (String) -> String? = { null }, done: Map<String, Long> = emptyMap()) =
      CcAlerts.active(es.associateBy { it.id }, now, 10, rooms, done)
  private fun one(entity: CcEntity, rooms: (String) -> String? = { null }) = alerts(entity, rooms = rooms).single()

  @Test fun hazardsAreCriticalWithTheirOwnWords() {
    assertEquals("Gas detected · Kitchen", one(e("binary_sensor.g", "on", "device_class" to "gas")) { "Kitchen" }.title)
    assertEquals("Carbon monoxide", one(e("binary_sensor.c", "on", "device_class" to "carbon_monoxide")).title)
    assertEquals("Smoke alarm", one(e("binary_sensor.s", "on", "device_class" to "safety")).title)
    val leak = one(e("binary_sensor.leak", "on", "device_class" to "moisture")) { "Utility" }
    assertEquals("Water leak · Utility", leak.title)
    assertEquals(CcAlertAction.SHUT_WATER, leak.action)
    assertTrue(alerts(e("binary_sensor.s", "off", "device_class" to "smoke")).isEmpty())
    assertTrue(alerts(e("binary_sensor.s", "unavailable", "device_class" to "smoke")).isEmpty())
  }

  @Test fun openingsWarnOnlyAfterTheWaitAndWithAKnownTime() {
    val door = e("binary_sensor.back", "on", "device_class" to "door", "friendly_name" to "Back door", changed = now - 75 * min)
    val a = one(door)
    assertEquals("Back door is open", a.title)
    assertEquals("Open 1 h 15 min", a.body)
    assertEquals(CcAlertAction.REMIND, a.action)
    assertEquals("Hall window is open", one(e("binary_sensor.w", "on", "device_class" to "window")) { "Hall" }.title)
    assertEquals("Study window is open", one(e("binary_sensor.w", "on", "device_class" to "window", "friendly_name" to "Study")).title)
    assertTrue(alerts(door.copy(lastChanged = now - 9 * min)).isEmpty())
    assertTrue("no time, no 'open for' warning", alerts(door.copy(lastChanged = 0)).isEmpty())
  }

  @Test fun lowBatteriesFromSensorsAttributesOrBinarySensors() {
    val s = one(e("sensor.door_battery", "12", "device_class" to "battery", "friendly_name" to "Door battery")) { "Hall" }
    assertEquals("Door battery 12%", s.title)
    assertEquals("Replace it in the next few weeks · Hall", s.body)
    assertEquals(CcSeverity.INFO, s.severity)
    assertTrue(alerts(e("sensor.door_battery", "16", "device_class" to "battery")).isEmpty())
    assertEquals("Lock battery 5%", one(e("lock.front", "locked", "battery_level" to 5, "friendly_name" to "Lock")).title)
    assertEquals("Motion battery low", one(e("binary_sensor.m_batt", "on", "device_class" to "battery", "friendly_name" to "Motion Battery")).title)
    assertTrue(alerts(e("binary_sensor.m_batt", "off", "device_class" to "battery")).isEmpty())
    assertTrue(alerts(e("sensor.door_battery", "n/a", "device_class" to "battery")).isEmpty())
  }

  @Test fun appliancesDoneAndOrdering() {
    val washer = e("switch.washing_machine", "off")
    val dryer = e("switch.dryer", "off")
    val dish = e("switch.dishwasher", "off")
    val oven = e("switch.oven", "off", "friendly_name" to "Oven")
    val out = alerts(washer, dryer, dish, oven, e("binary_sensor.leak", "on", "device_class" to "moisture"),
        done = mapOf(washer.id to 3L, dryer.id to 2L, dish.id to 1L, oven.id to 4L, "switch.gone" to 5L), rooms = { if (it == dish.id) "Kitchen" else null })
    assertEquals(CcSeverity.CRITICAL, out.first().severity)
    assertEquals(listOf("Oven is done", "Washer is done", "Dryer is done", "Dishwasher is done"), out.drop(1).map { it.title })
    assertEquals("Kitchen", out.last().body)
    assertEquals("Finished", out[1].body)
  }

  @Test fun finishingNeedsARunningStateThenADoneState() {
    val after = e("sensor.washer_state", "Complete")
    assertTrue(CcAlerts.finished(e("sensor.washer_state", "Spin"), after))
    assertFalse(CcAlerts.finished(null, after))
    assertFalse(CcAlerts.finished(e("sensor.washer_state", "off"), after))
    assertFalse(CcAlerts.finished(e("sensor.temp", "on"), e("sensor.temp", "off")))
    assertEquals("just now", CcAlerts.duration(0))
    assertEquals("5 min", CcAlerts.duration(5))
  }

  // --- banners -----------------------------------------------------------------------------

  private val full = ControlCenterConfig.Settings()
  private fun alert(sev: CcSeverity, kind: CcKind, cls: String?) = CcAlert("x", sev, kind, "e.x", "T", "B", 0L, CcAlertAction.DISMISS, cls)

  @Test fun criticalsTakeOverEverywhereAndOnlyLifeSafetySoundsAtNight() {
    val smoke = alert(CcSeverity.CRITICAL, CcKind.SMOKE, "smoke")
    val gas = alert(CcSeverity.CRITICAL, CcKind.SMOKE, "gas")
    val leakNoClass = alert(CcSeverity.CRITICAL, CcKind.LEAK, null)
    val kids = full.copy(portalMode = ControlCenterConfig.MODE_KIDS)
    assertEquals(CcBannerPolicy.Decision(CcBannerPolicy.Show.TAKEOVER, true), CcBannerPolicy.decide(gas, kids, quietHours = false))
    assertEquals(CcBannerPolicy.Decision(CcBannerPolicy.Show.TAKEOVER, true), CcBannerPolicy.decide(smoke, full, quietHours = true))
    assertEquals(CcBannerPolicy.Decision(CcBannerPolicy.Show.TAKEOVER, false), CcBannerPolicy.decide(gas, full, quietHours = true))
    assertTrue(CcBannerPolicy.decide(leakNoClass, full, quietHours = true).sound)
    assertFalse(CcBannerPolicy.decide(smoke, full.copy(criticalSoundAtNight = false), quietHours = true).sound)
    assertFalse(CcBannerPolicy.soundsAtNight(alert(CcSeverity.WARNING, CcKind.LEAK, "moisture")))
    assertFalse(CcBannerPolicy.soundsAtNight(alert(CcSeverity.CRITICAL, CcKind.SMOKE, null)))
  }

  @Test fun warningsAreSilentBannersOnSharedFullPortalsOnly() {
    val door = alert(CcSeverity.WARNING, CcKind.DOOR, null)
    val none = CcBannerPolicy.Decision(CcBannerPolicy.Show.NONE, false)
    assertEquals(CcBannerPolicy.Decision(CcBannerPolicy.Show.BANNER, false), CcBannerPolicy.decide(door, full, quietHours = true))
    assertEquals(none, CcBannerPolicy.decide(door, full.copy(bannersWarnings = false), false))
    assertEquals(none, CcBannerPolicy.decide(door, full.copy(sharedRoom = false), false))
    assertEquals(none, CcBannerPolicy.decide(door, full.copy(portalMode = ControlCenterConfig.MODE_BANNERS), false))
    assertEquals(none, CcBannerPolicy.decide(alert(CcSeverity.WARNING, CcKind.GARAGE, null), full.copy(portalMode = ControlCenterConfig.MODE_KIDS), false))
  }

  @Test fun infoBannersAreForFinishedAppliancesInDaytime() {
    val done = alert(CcSeverity.INFO, CcKind.APPLIANCE, null)
    val none = CcBannerPolicy.Decision(CcBannerPolicy.Show.NONE, false)
    assertEquals(CcBannerPolicy.Show.BANNER, CcBannerPolicy.decide(done, full, false).show)
    assertEquals(none, CcBannerPolicy.decide(done, full, true))
    assertEquals(none, CcBannerPolicy.decide(done, full.copy(bannersInfo = false), false))
    assertEquals(none, CcBannerPolicy.decide(done, full.copy(portalMode = ControlCenterConfig.MODE_KIDS), false))
    assertEquals(none, CcBannerPolicy.decide(alert(CcSeverity.INFO, CcKind.BATTERY, null), full, false))
  }
}
