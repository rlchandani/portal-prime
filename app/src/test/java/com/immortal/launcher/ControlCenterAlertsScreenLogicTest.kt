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
import java.util.Calendar
import java.util.TimeZone

class ControlCenterAlertsScreenLogicTest {
  private val utc = TimeZone.getTimeZone("UTC")
  private fun at(h: Int, m: Int = 0): Long = Calendar.getInstance(utc).apply { clear(); set(2026, Calendar.NOVEMBER, 17, h, m) }.timeInMillis
  private fun alert(id: String, action: CcAlertAction, entity: String = "x.y", sev: CcSeverity = CcSeverity.WARNING, kind: CcKind = CcKind.GARAGE) =
      CcAlert(id, sev, kind, entity, "t", "b", 1L, action)

  @Test fun until_perAction() {
    val now = at(19, 42)
    assertEquals(now + CcAlertRules.SNOOZE_MS, CcAlertRules.until(CcAlertAction.SNOOZE, now, utc))
    assertEquals(at(22), CcAlertRules.until(CcAlertAction.REMIND, now, utc))
    assertEquals(Long.MAX_VALUE, CcAlertRules.until(CcAlertAction.DISMISS, now, utc))
    assertNull(CcAlertRules.until(CcAlertAction.CLOSE, now, utc))
    assertNull(CcAlertRules.until(CcAlertAction.SHUT_WATER, now, utc))
  }

  @Test fun remind_fallsBackToAnHourLate() {
    assertEquals(at(21, 30) + CcAlertRules.REMIND_FALLBACK_MS, CcAlertRules.remindAt(at(21, 30), utc))
    assertEquals(at(22), CcAlertRules.remindAt(at(21), utc))
    assertEquals("Remind 10 pm", CcAlertRules.label(CcAlertAction.REMIND, at(18), utc))
    assertEquals("Remind in 1 h", CcAlertRules.label(CcAlertAction.REMIND, at(23), utc))
    assertEquals("Close", CcAlertRules.label(CcAlertAction.CLOSE, 0L, utc))
    assertEquals("Shut off water", CcAlertRules.label(CcAlertAction.SHUT_WATER, 0L, utc))
  }

  @Test fun valveFor_prefersTheSameRoom() {
    val a = alert("leak", CcAlertAction.SHUT_WATER, "binary_sensor.laundry_leak", CcSeverity.CRITICAL, CcKind.LEAK)
    val main = CcEntity("valve.water_main", "open")
    val laundry = CcEntity("switch.laundry_valve", "on")
    val rooms = mapOf("binary_sensor.laundry_leak" to "laundry", "switch.laundry_valve" to "laundry")
    assertEquals(laundry, CcAlertRules.valveFor(a, listOf(main, laundry), rooms::get))
    assertEquals(main, CcAlertRules.valveFor(a, listOf(laundry, main)) { null })
    assertNull(CcAlertRules.valveFor(a, listOf(CcEntity("valve.x", "unavailable"))) { null })
    assertEquals("close_valve", CcAlertRules.shutCall(main).service)
    assertEquals("turn_off", CcAlertRules.shutCall(laundry).service)
  }

  @Test fun garageFor_findsTheCoverBehindAContactSensor() {
    val cover = CcEntity("cover.garage", "open", mapOf("device_class" to "garage"))
    val contact = CcEntity("binary_sensor.garage_contact", "on", mapOf("device_class" to "garage_door"))
    val ents = listOf(cover, contact).associateBy { it.id }
    assertEquals(cover, CcAlertRules.garageFor(alert("g", CcAlertAction.CLOSE, contact.id), ents))
    assertEquals(cover, CcAlertRules.garageFor(alert("g", CcAlertAction.CLOSE, cover.id), ents))
    assertNull(CcAlertRules.garageFor(alert("g", CcAlertAction.CLOSE, contact.id), mapOf(contact.id to contact)))
  }

  @Test fun toneAndIcon() {
    assertEquals(CcTone.RED, CcAlertRules.tone(CcSeverity.CRITICAL))
    assertEquals(CcTone.AMBER, CcAlertRules.tone(CcSeverity.WARNING))
    assertEquals(CcTone.NEUTRAL, CcAlertRules.tone(CcSeverity.INFO))
    assertEquals("dryer", CcAlertRules.icon(alert("d", CcAlertAction.DISMISS, "switch.dryer", kind = CcKind.APPLIANCE), null))
    assertEquals("washer", CcAlertRules.icon(alert("d", CcAlertAction.DISMISS, "switch.machine", kind = CcKind.APPLIANCE), null))
    assertEquals("garage", CcAlertRules.icon(alert("g", CcAlertAction.CLOSE), null))
  }

  @Test fun resolve_tracksWhatWentAway() {
    val a = alert("a", CcAlertAction.CLOSE)
    val b = alert("b", CcAlertAction.SNOOZE)
    val r1 = CcAlertRules.resolve(emptyList(), listOf(a, b), listOf(b), 10L)
    assertEquals(listOf("a"), r1.map { it.alert.id })
    val r2 = CcAlertRules.resolve(r1, listOf(a, b), listOf(b), 20L)
    assertEquals(1, r2.size)
    assertEquals(10L, r2.single().at)
    val many = (0 until 40).map { alert("x$it", CcAlertAction.DISMISS) }
    assertEquals(CcAlertRules.RESOLVED_MAX, CcAlertRules.resolve(emptyList(), many, emptyList(), 1L).size)
  }

  @Test fun list_filters() {
    val a = alert("a", CcAlertAction.CLOSE)
    val b = alert("b", CcAlertAction.SNOOZE)
    val resolved = listOf(CcResolvedAlert(a, 1L), CcResolvedAlert(b, 2L))
    assertEquals(listOf("b" to true), CcAlertRules.list(CcAlertFilter.ACTIVE, listOf(b), resolved).map { it.first.id to it.second })
    assertEquals(listOf("a" to false), CcAlertRules.list(CcAlertFilter.RESOLVED, listOf(b), resolved).map { it.first.id to it.second })
    assertEquals(listOf("b" to true, "a" to false), CcAlertRules.list(CcAlertFilter.ALL, listOf(b), resolved).map { it.first.id to it.second })
  }

  @Test fun item_derivesTheButton() {
    val now = at(19, 42)
    val t: (Long) -> String = { "7:25 pm" }
    val garage = CcEntity("cover.garage", "open", mapOf("device_class" to "garage"))
    val ents = mapOf(garage.id to garage)
    val close = CcAlertRules.item(CcAlert("g", CcSeverity.WARNING, CcKind.GARAGE, garage.id, "Garage door is open", "Open 17 min", 5L,
        CcAlertAction.CLOSE), true, ents, { null }, canControl = false, now = now, time = t, zone = utc)
    assertEquals("Close", close.label)
    assertEquals("Open 17 min · 7:25 pm", close.line)
    assertEquals(false, close.enabled)
    assertEquals("close_cover", (close.op as CcAlertOp.Act).call.service)
    val remind = CcAlertRules.item(alert("w", CcAlertAction.REMIND, kind = CcKind.WINDOW), true, emptyMap(), { null }, true, now, t, utc)
    assertEquals(CcAlertOp.Dismiss(at(22)), remind.op)
    assertEquals("Remind 10 pm", remind.label)
    val view = CcAlertRules.item(alert("s", CcAlertAction.VIEW, "binary_sensor.smoke", CcSeverity.CRITICAL, CcKind.SMOKE), true, emptyMap(),
        { "kitchen" }, true, now, t, utc)
    assertEquals(CcAlertOp.OpenRoom("kitchen"), view.op)
    assertEquals(true, view.danger)
    val noValve = CcAlertRules.item(alert("l", CcAlertAction.SHUT_WATER, kind = CcKind.LEAK), true, emptyMap(), { null }, true, now, t, utc)
    assertNull(noValve.label)
    val resolved = CcAlertRules.item(alert("r", CcAlertAction.SNOOZE), false, emptyMap(), { null }, true, now, t, utc)
    assertNull(resolved.op)
  }
}
