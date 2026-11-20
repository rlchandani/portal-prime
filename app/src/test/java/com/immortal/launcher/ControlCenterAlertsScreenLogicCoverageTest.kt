/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** [CcAlertRules]: which button an alert gets, what it acts on, and when a dismissal ends. */
class ControlCenterAlertsScreenLogicCoverageTest {
  private val utc: TimeZone = TimeZone.getTimeZone("UTC")
  private val hour = 60 * 60_000L
  private val noon = 12 * hour
  private fun e(id: String, state: String, vararg attrs: Pair<String, Any?>) = CcEntity(id, state, mapOf(*attrs))
  private fun alert(entity: String, action: CcAlertAction, kind: CcKind = CcKind.SENSOR, sev: CcSeverity = CcSeverity.WARNING, since: Long = 0L) =
      CcAlert("a:$entity", sev, kind, entity, "Title", "Body", since, action)

  @Test fun dismissalsEndPerAction() {
    assertEquals(noon + CcAlertRules.SNOOZE_MS, CcAlertRules.until(CcAlertAction.SNOOZE, noon, utc))
    assertEquals(Long.MAX_VALUE, CcAlertRules.until(CcAlertAction.DISMISS, noon, utc))
    assertEquals(22 * hour, CcAlertRules.until(CcAlertAction.REMIND, noon, utc))
    for (a in listOf(CcAlertAction.CLOSE, CcAlertAction.SHUT_WATER, CcAlertAction.VIEW)) assertNull(CcAlertRules.until(a, noon, utc))
  }

  @Test fun remindFallsBackToAnHourWhenTenPmIsTooClose() {
    val late = 21 * hour + 30 * 60_000L
    assertEquals(late + CcAlertRules.REMIND_FALLBACK_MS, CcAlertRules.remindAt(late, utc))
    assertEquals("Remind in 1 h", CcAlertRules.label(CcAlertAction.REMIND, late, utc))
    assertEquals("Remind 10 pm", CcAlertRules.label(CcAlertAction.REMIND, noon, utc))
    assertEquals(22 * hour, CcAlertRules.remindAt(21 * hour, utc))
  }

  @Test fun labelsForEveryAction() {
    assertEquals(listOf("Close", "Shut off water", "View", "Dismiss", "Snooze"),
        listOf(CcAlertAction.CLOSE, CcAlertAction.SHUT_WATER, CcAlertAction.VIEW, CcAlertAction.DISMISS, CcAlertAction.SNOOZE)
            .map { CcAlertRules.label(it, noon, utc) })
  }

  @Test fun theValveForALeakIsInTheSameRoomElseARealValveFirst() {
    val leak = alert("binary_sensor.leak", CcAlertAction.SHUT_WATER, CcKind.LEAK)
    val sw = e("switch.water_main", "on")
    val valve = e("valve.main", "open")
    val dead = e("valve.dead", "unavailable")
    val rooms = mapOf("binary_sensor.leak" to "utility", sw.id to "utility")
    assertEquals(sw, CcAlertRules.valveFor(leak, listOf(valve, sw, dead), rooms::get))
    assertEquals(valve, CcAlertRules.valveFor(leak, listOf(sw, valve), { null }))
    assertEquals(sw, CcAlertRules.valveFor(leak, listOf(sw), { null }))
    assertNull(CcAlertRules.valveFor(leak, listOf(dead, e("light.l", "on")), { null }))
    assertEquals(CcCalls.power(sw, false), CcAlertRules.shutCall(sw))
  }

  @Test fun theGarageToCloseIsTheAlertsCoverElseTheHousesFirstGarage() {
    val contact = alert("binary_sensor.garage_contact", CcAlertAction.CLOSE, CcKind.GARAGE)
    val b = e("cover.garage_b", "open", "device_class" to "garage")
    val a = e("cover.garage_a", "open", "device_class" to "garage")
    val blind = e("cover.blind", "open")
    val ents = listOf(b, a, blind).associateBy { it.id }
    assertEquals(a, CcAlertRules.garageFor(contact, ents))
    assertEquals(b, CcAlertRules.garageFor(alert(b.id, CcAlertAction.CLOSE, CcKind.GARAGE), ents))
    assertNull(CcAlertRules.garageFor(contact, mapOf(blind.id to blind)))
  }

  @Test fun cardsOnlyActWhenActiveAndTheHouseCanBeControlled() {
    val g = e("cover.garage", "open", "device_class" to "garage")
    val a = alert(g.id, CcAlertAction.CLOSE, CcKind.GARAGE, since = 5L)
    val ents = mapOf(g.id to g)
    val item = CcAlertRules.item(a, true, ents, { null }, canControl = false, now = noon, time = { "T$it" }, zone = utc)
    assertEquals(CcAlertOp.Act(g, CcCalls.coverClose(g)), item.op)
    assertFalse("acting needs a live connection", item.enabled)
    assertEquals("Body · T5", item.line)
    assertEquals("Close", item.label)
    assertFalse(item.danger)
    val closed = CcAlertRules.item(a, true, mapOf(g.id to g.copy(state = "closed")), { null }, true, noon, { "" }, utc)
    assertNull(closed.op)
    assertNull(closed.label)
    assertTrue(closed.enabled)
    val resolved = CcAlertRules.item(a.copy(since = 0L), false, ents, { null }, true, noon, { "x" }, utc)
    assertNull(resolved.op)
    assertEquals("Body", resolved.line)
  }

  @Test fun viewOpensTheRoomAndDismissKindsCarryTheirEnd() {
    val v = CcAlertRules.item(alert("binary_sensor.d", CcAlertAction.VIEW), true, emptyMap(), { "hall" }, true, noon, { "" }, utc)
    assertEquals(CcAlertOp.OpenRoom("hall"), v.op)
    assertNull(CcAlertRules.item(alert("binary_sensor.d", CcAlertAction.VIEW), true, emptyMap(), { null }, true, noon, { "" }, utc).op)
    val s = CcAlertRules.item(alert("sensor.b", CcAlertAction.SNOOZE, sev = CcSeverity.CRITICAL), true, emptyMap(), { null }, false, noon, { "" }, utc)
    assertEquals(CcAlertOp.Dismiss(noon + CcAlertRules.SNOOZE_MS), s.op)
    assertTrue("dismissing needs no connection", s.enabled)
    assertTrue(s.danger)
    val leakOff = CcAlertRules.item(alert("binary_sensor.leak", CcAlertAction.SHUT_WATER, CcKind.LEAK), true,
        mapOf("valve.main" to e("valve.main", "closed")), { null }, true, noon, { "" }, utc)
    assertNull("a closed valve has nothing to shut", leakOff.op)
  }

  @Test fun tonesAndIcons() {
    assertEquals(CcTone.RED, CcAlertRules.tone(CcSeverity.CRITICAL))
    assertEquals(CcTone.AMBER, CcAlertRules.tone(CcSeverity.WARNING))
    assertEquals(CcTone.NEUTRAL, CcAlertRules.tone(CcSeverity.INFO))
    fun icon(id: String, kind: CcKind) = CcAlertRules.icon(alert(id, CcAlertAction.DISMISS, kind), null)
    assertEquals("dish", icon("switch.dishwasher", CcKind.APPLIANCE))
    assertEquals("dryer", icon("switch.dryer", CcKind.APPLIANCE))
    assertEquals("oven", icon("sensor.stove_state", CcKind.APPLIANCE))
    assertEquals("oven", icon("sensor.cooker", CcKind.APPLIANCE))
    assertEquals("washer", icon("sensor.washing_machine", CcKind.APPLIANCE))
    assertEquals("warn", icon("binary_sensor.smoke", CcKind.SMOKE))
    assertEquals(CcIcons.forKind(CcKind.LOCK), CcAlertRules.icon(alert("x.y", CcAlertAction.DISMISS, CcKind.SENSOR), e("lock.l", "unlocked")))
  }

  @Test fun resolvedListKeepsNewestFirstAndCaps() {
    val a = alert("a.a", CcAlertAction.DISMISS)
    val b = alert("b.b", CcAlertAction.DISMISS)
    val r = CcAlertRules.resolve(listOf(CcResolvedAlert(b, 1L)), listOf(a, b), active = emptyList(), now = 5L)
    assertEquals(listOf(a.id, b.id), r.map { it.alert.id })
    val many = (1..40).map { CcResolvedAlert(alert("s.$it", CcAlertAction.DISMISS), it.toLong()) }
    assertEquals(CcAlertRules.RESOLVED_MAX, CcAlertRules.resolve(many, listOf(a), emptyList(), 99L).size)
    assertEquals(emptyList<CcResolvedAlert>(), CcAlertRules.resolve(emptyList(), listOf(a), listOf(a), 1L))
  }

  @Test fun filterListsActiveResolvedOrBoth() {
    val a = alert("a.a", CcAlertAction.DISMISS)
    val b = alert("b.b", CcAlertAction.DISMISS)
    val resolved = listOf(CcResolvedAlert(b, 1L), CcResolvedAlert(a, 2L))
    assertEquals(listOf(a to true), CcAlertRules.list(CcAlertFilter.ACTIVE, listOf(a), resolved))
    assertEquals(listOf(b to false), CcAlertRules.list(CcAlertFilter.RESOLVED, listOf(a), resolved))
    assertEquals(listOf(a to true, b to false), CcAlertRules.list(CcAlertFilter.ALL, listOf(a), resolved))
  }
}
