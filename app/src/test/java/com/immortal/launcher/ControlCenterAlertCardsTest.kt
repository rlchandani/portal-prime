/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** The Alerts screen's per-snapshot slice ([CcUiState.alertCards]) and the cards derived from it. */
class ControlCenterAlertCardsTest {
  private val utc = TimeZone.getTimeZone("UTC")
  private val live = CcConnection(CcPhase.LIVE)
  private fun at(d: Int, h: Int, m: Int = 0): Long = Calendar.getInstance(utc).apply { clear(); set(2026, Calendar.NOVEMBER, d, h, m) }.timeInMillis
  private val now = at(21, 12, 30)
  private fun e(id: String, state: String, vararg attrs: Pair<String, Any?>, changed: Long = 0L) = CcEntity(id, state, mapOf(*attrs), changed)
  private val leak = e("binary_sensor.sink_leak", "on", "device_class" to "moisture", "friendly_name" to "Sink", changed = now - 60_000L)
  private val lamp = e("light.lamp", "on")
  private fun state(vararg es: CcEntity, version: Long = 1L, at: Long = now) =
      CcUiState(entities = es.associateBy { it.id }, connection = live, now = at, entitiesVersion = version)
  private fun alert(id: String, title: String = "Office door is open", since: Long = at(7, 14, 33)) =
      CcAlert(id, CcSeverity.WARNING, CcKind.DOOR, "binary_sensor.office", title, "Open 13 days", since, CcAlertAction.REMIND)

  @Test fun anUpdateThatChangesNoCardKeepsTheSliceEqual() {
    val before = state(leak, lamp).alertCards
    val after = state(leak, lamp.copy(state = "off"), version = 2L, at = now + 20_000L).alertCards
    assertEquals(1, before.active.size)
    assertEquals(before, after)
  }

  @Test fun aChangeACardShowsChangesTheSlice() {
    val before = state(leak).alertCards
    val valve = e("valve.main", "open", "friendly_name" to "Main water")
    assertNotEquals(before, state(leak, valve, version = 2L).alertCards)
    assertNotEquals(before, state(version = 2L).alertCards)
  }

  @Test fun theSliceIsTheScreensOwnCardDerivation() {
    val s = state(leak)
    val expected = s.alerts().map { CcAlertRules.item(it, true, s.entities, { null }, s.connection.canControl, s.now, ::ccTime) }
    assertEquals(expected, s.alertCards.active)
    assertEquals(CcAlertRules.dayStart(now), s.alertCards.dayStart)
  }

  @Test fun dayStartIsLocalMidnight() {
    assertEquals(at(21, 0), CcAlertRules.dayStart(now, utc))
    assertEquals(at(21, 0), CcAlertRules.dayStart(at(21, 0), utc))
  }

  @Test fun aResolvedCardKeepsItsIconAndStampsAgainstToday() {
    val old = alert("o")
    val r = CcAlertRules.resolvedItem(CcResolvedAlert(old, now, icon = "door"), at(21, 0), { "2:33 pm" }, utc, { "Sat 7 Nov" })
    assertEquals("door", r.icon)
    assertEquals("Open 13 days · Sat 7 Nov 2:33 pm", r.line)
    assertNull(r.label)
    assertNull(r.op)
    val today = alert("t", since = at(21, 9, 48))
    assertEquals("Open 13 days · 2:33 pm", CcAlertRules.resolvedItem(CcResolvedAlert(today, now), at(21, 0), { "2:33 pm" }, utc).line)
  }

  /** Same card as the old derivation of an inactive alert (no entity known). */
  @Test fun aResolvedCardMatchesTheInactiveItem() {
    val a = alert("a")
    val legacy = CcAlertRules.item(a, false, emptyMap(), { null }, true, now, { "x" }, utc, day = { "d" })
    assertEquals(legacy, CcAlertRules.resolvedItem(CcResolvedAlert(a, now), CcAlertRules.dayStart(now, utc), { "x" }, utc, { "d" }))
  }

  @Test fun resolvingRecordsTheIconTheCardHad() {
    val a = alert("a")
    val r = CcAlertRules.resolve(emptyList(), listOf(a), emptyList(), now) { if (it.id == "a") "door" else null }
    assertEquals(listOf(CcResolvedAlert(a, now, "door")), r)
  }

  /** "…, 1 of 2" numbering spans the active and resolved cards the filter shows. */
  @Test fun repeatedButtonsAreNumberedAcrossTheShownCards() {
    val cards = CcAlertCards(listOf(CcAlertRules.item(alert("a"), true, emptyMap(), { null }, true, now, { "x" }, utc),
        CcAlertRules.item(alert("b"), true, emptyMap(), { null }, true, now, { "x" }, utc)), CcAlertRules.dayStart(now, utc))
    val shown = CcAlertRules.shown(CcAlertFilter.ACTIVE, cards, emptyList(), { "x" }, utc)
    assertEquals(listOf("Office door is open · Remind 10 pm, 1 of 2", "Office door is open · Remind 10 pm, 2 of 2"), shown.map { it.spoken })
    val withResolved = CcAlertRules.shown(CcAlertFilter.ALL, cards, listOf(CcResolvedAlert(alert("c"), now)), { "x" }, utc)
    assertEquals(listOf("a", "b", "c"), withResolved.map { it.alert.id })
    assertEquals(null, withResolved[2].spoken)
  }
}
