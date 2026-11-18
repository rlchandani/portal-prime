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

class ControlCenterAlertsTest {
  private val min = 60_000L
  private val now = 100 * min
  private fun map(vararg e: CcEntity) = e.associateBy { it.id }

  @Test fun severities_andOrdering() {
    val leak = CcEntity("binary_sensor.leak", "on", mapOf("device_class" to "moisture", "friendly_name" to "Laundry leak"), now - min)
    val garage = CcEntity("cover.garage", "open", mapOf("device_class" to "garage"), now - 17 * min)
    val window = CcEntity("binary_sensor.office_window", "on", mapOf("device_class" to "window"), now - 30 * min)
    val battery = CcEntity("sensor.back_door_battery", "12", mapOf("device_class" to "battery", "friendly_name" to "Back door battery"))
    val alerts = CcAlerts.active(map(leak, garage, window, battery), now, 10, rooms = { if (it == window.id) "Office" else null })
    assertEquals(listOf(CcSeverity.CRITICAL, CcSeverity.WARNING, CcSeverity.WARNING, CcSeverity.INFO), alerts.map { it.severity })
    assertEquals(CcAlertAction.SHUT_WATER, alerts[0].action)
    assertEquals("Garage door is open", alerts[1].title)
    assertEquals("Open 17 min", alerts[1].body)
    assertEquals(CcAlertAction.CLOSE, alerts[1].action)
    assertEquals("Office window is open", alerts[2].title)
    assertEquals("Back door battery 12%", alerts[3].title)
  }

  @Test fun openNotLongEnough_andUnavailable_areQuiet() {
    val garage = CcEntity("cover.garage", "open", mapOf("device_class" to "garage"), now - 2 * min)
    val gone = CcEntity("binary_sensor.leak", "unavailable", mapOf("device_class" to "moisture"))
    assertTrue(CcAlerts.active(map(garage, gone), now, 10).isEmpty())
    assertTrue(CcAlerts.active(map(CcEntity("sensor.phone_battery", "80", mapOf("device_class" to "battery"))), now, 10).isEmpty())
  }

  @Test fun smokeGasCo_titles() {
    val co = CcEntity("binary_sensor.co", "on", mapOf("device_class" to "carbon_monoxide"), now)
    val gas = CcEntity("binary_sensor.gas", "on", mapOf("device_class" to "gas"), now)
    val titles = CcAlerts.active(map(co, gas), now, 10).map { it.title }.toSet()
    assertEquals(setOf("Carbon monoxide", "Gas detected"), titles)
  }

  @Test fun applianceDone_fromTransitions() {
    val washer = CcEntity("sensor.washer_status", "running")
    assertTrue(CcAlerts.finished(washer, washer.copy(state = "complete")))
    assertFalse(CcAlerts.finished(washer.copy(state = "off"), washer.copy(state = "complete")))
    assertFalse(CcAlerts.finished(null, washer))
    val alerts = CcAlerts.active(map(washer.copy(state = "complete")), now, 10, { "Laundry" }, mapOf(washer.id to now - min))
    assertEquals("Washer is done", alerts.single().title)
    assertEquals("Laundry", alerts.single().body)
    assertEquals("17 min", CcAlerts.duration(17)); assertEquals("1 h 5 min", CcAlerts.duration(65)); assertEquals("just now", CcAlerts.duration(0))
  }

  @Test fun banners_followDecisions() {
    val full = ControlCenterConfig.Settings()
    val crit = CcAlert("a", CcSeverity.CRITICAL, CcKind.LEAK, "binary_sensor.l", "Leak", "", now, CcAlertAction.SHUT_WATER)
    val warn = crit.copy(severity = CcSeverity.WARNING, kind = CcKind.GARAGE)
    val info = crit.copy(severity = CcSeverity.INFO, kind = CcKind.APPLIANCE)
    val battery = info.copy(kind = CcKind.BATTERY)
    val kidsMode = full.copy(portalMode = ControlCenterConfig.MODE_KIDS)
    val bannersOnly = full.copy(portalMode = ControlCenterConfig.MODE_BANNERS)
    assertEquals(CcBannerPolicy.Decision(CcBannerPolicy.Show.TAKEOVER, true), CcBannerPolicy.decide(crit, full, quietHours = false))
    // crit has no device class but is a LEAK: sounds at night by default (owner's decision).
    assertEquals(CcBannerPolicy.Decision(CcBannerPolicy.Show.TAKEOVER, true), CcBannerPolicy.decide(crit, kidsMode, quietHours = true))
    assertFalse(CcBannerPolicy.decide(crit, full.copy(criticalSoundAtNight = false), quietHours = true).sound)
    assertEquals(CcBannerPolicy.Show.TAKEOVER, CcBannerPolicy.decide(crit, bannersOnly, true).show)
    assertEquals(CcBannerPolicy.Show.BANNER, CcBannerPolicy.decide(warn, full, true).show)
    assertFalse(CcBannerPolicy.decide(warn, full, false).sound)
    assertEquals(CcBannerPolicy.Show.NONE, CcBannerPolicy.decide(warn, kidsMode, false).show)
    assertEquals(CcBannerPolicy.Show.NONE, CcBannerPolicy.decide(warn, full.copy(bannersWarnings = false), false).show)
    assertEquals(CcBannerPolicy.Show.BANNER, CcBannerPolicy.decide(info, full, false).show)
    assertEquals(CcBannerPolicy.Show.NONE, CcBannerPolicy.decide(info, full, true).show)
    assertEquals(CcBannerPolicy.Show.NONE, CcBannerPolicy.decide(battery, full, false).show)
  }

  @Test fun nightSound_onlyLeakSmokeAndCo() {
    val s = ControlCenterConfig.Settings()
    assertTrue(s.criticalSoundAtNight)
    fun crit(cls: String, kind: CcKind) = CcAlert("a", CcSeverity.CRITICAL, kind, "binary_sensor.x", "t", "", now, CcAlertAction.VIEW, cls)
    listOf(crit("moisture", CcKind.LEAK), crit("smoke", CcKind.SMOKE), crit("carbon_monoxide", CcKind.SMOKE)).forEach { a ->
      assertEquals(CcBannerPolicy.Decision(CcBannerPolicy.Show.TAKEOVER, true), CcBannerPolicy.decide(a, s, quietHours = true))
    }
    listOf(crit("gas", CcKind.SMOKE), crit("safety", CcKind.SMOKE)).forEach { a ->
      assertEquals(CcBannerPolicy.Decision(CcBannerPolicy.Show.TAKEOVER, false), CcBannerPolicy.decide(a, s, quietHours = true))
      assertTrue(CcBannerPolicy.decide(a, s, quietHours = false).sound)
    }
    val warn = crit("garage_door", CcKind.GARAGE).copy(severity = CcSeverity.WARNING)
    val info = crit("", CcKind.APPLIANCE).copy(severity = CcSeverity.INFO)
    assertFalse(CcBannerPolicy.decide(warn, s, quietHours = true).sound)
    assertFalse(CcBannerPolicy.decide(warn, s, quietHours = false).sound)
    assertFalse(CcBannerPolicy.decide(info, s, quietHours = false).sound)
    // From live state: CO and leak sensors carry their class into the alert.
    val co = CcEntity("binary_sensor.co", "on", mapOf("device_class" to "carbon_monoxide"), now)
    val leak = CcEntity("binary_sensor.l", "on", mapOf("device_class" to "moisture"), now)
    CcAlerts.active(map(co, leak), now, 10).forEach { assertTrue(CcBannerPolicy.soundsAtNight(it)) }
  }

  @Test fun uiState_alertsHonourDismissAndKids() {
    val garage = CcEntity("cover.garage", "open", mapOf("device_class" to "garage"), now - 20 * min)
    val leak = CcEntity("binary_sensor.leak", "on", mapOf("device_class" to "moisture"), now)
    val st = CcUiState(entities = map(garage, leak), now = now)
    assertEquals(2, st.alerts().size)
    val kids = st.copy(settings = ControlCenterConfig.Settings(portalMode = ControlCenterConfig.MODE_KIDS))
    assertEquals(listOf(CcSeverity.CRITICAL), kids.alerts().map { it.severity })
    val dismissed = st.copy(dismissed = mapOf(st.alerts().last().id to now + min))
    assertEquals(1, dismissed.alerts().size)
    val expired = st.copy(dismissed = mapOf(st.alerts().last().id to now - 1))
    assertEquals(2, expired.alerts().size)
  }
}
