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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Background alerts: routing, consolidation, the takeover/open gate, and validation of relayed `cc_alert` messages. */
class ControlCenterBackgroundAlertsCoverageTest {
  private fun alert(id: String, sev: CcSeverity, kind: CcKind, cls: String? = null, entity: String = "e.$id", title: String = "T", since: Long = 0L) =
      CcAlert(id, sev, kind, entity, title, "B", since, CcAlertAction.DISMISS, cls)

  @Test fun routesByWhatTheAlertIs() {
    assertEquals(CcBackgroundPolicy.Route.TAKEOVER, CcBackgroundPolicy.route(alert("a", CcSeverity.CRITICAL, CcKind.LEAK)))
    assertEquals(CcBackgroundPolicy.Route.TAKEOVER, CcBackgroundPolicy.route(alert("a", CcSeverity.CRITICAL, CcKind.SMOKE, "gas")))
    assertEquals(CcBackgroundPolicy.Route.NONE, CcBackgroundPolicy.route(alert("a", CcSeverity.CRITICAL, CcKind.SMOKE, "safety")))
    assertEquals(CcBackgroundPolicy.Route.OPEN_WARNING, CcBackgroundPolicy.route(alert("a", CcSeverity.WARNING, CcKind.WINDOW)))
    assertEquals(CcBackgroundPolicy.Route.NONE, CcBackgroundPolicy.route(alert("a", CcSeverity.WARNING, CcKind.LOCK)))
    assertEquals(CcBackgroundPolicy.Route.INFO, CcBackgroundPolicy.route(alert("a", CcSeverity.INFO, CcKind.APPLIANCE)))
    assertEquals(CcBackgroundPolicy.Route.NONE, CcBackgroundPolicy.route(alert("a", CcSeverity.INFO, CcKind.BATTERY)))
    assertTrue(CcBackgroundPolicy.subscribes("gate"))
    assertTrue(CcBackgroundPolicy.subscribes("smoke"))
    assertFalse(CcBackgroundPolicy.subscribes("motion"))
    assertFalse(CcBackgroundPolicy.subscribes(null))
  }

  @Test fun planSkipsSeenAlertsAndNeverReplaysInfoAtStart() {
    val leak = alert("leak", CcSeverity.CRITICAL, CcKind.LEAK)
    val door = alert("door", CcSeverity.WARNING, CcKind.DOOR)
    val done = alert("done", CcSeverity.INFO, CcKind.APPLIANCE)
    val start = CcBackgroundPolicy.plan(listOf(leak, door, done), null)
    assertEquals(listOf(leak), start.takeovers)
    assertEquals(listOf(door), start.openFresh)
    assertTrue(start.info.isEmpty())
    val later = CcBackgroundPolicy.plan(listOf(leak, door, done), setOf("leak", "door"))
    assertTrue(later.takeovers.isEmpty())
    assertTrue(later.openFresh.isEmpty())
    assertEquals(listOf(door), later.openActive)
    assertEquals(listOf(done), later.info)
  }

  @Test fun consolidationWordsForOneManyWindowsMixedAndGarages() {
    assertNull(CcBackgroundPolicy.consolidate(emptyList(), 0L))
    val d = alert("d", CcSeverity.WARNING, CcKind.DOOR, title = "Back door is open", since = 2L)
    assertEquals(d, CcBackgroundPolicy.consolidate(listOf(d), 0L))
    val w1 = alert("w1", CcSeverity.WARNING, CcKind.WINDOW, title = "Hall window is open", since = 3L)
    val w2 = alert("w2", CcSeverity.WARNING, CcKind.WINDOW, title = "Den window is open", since = 1L)
    val windows = CcBackgroundPolicy.consolidate(listOf(w1, w2), 9L)!!
    assertEquals("2 windows open", windows.title)
    assertEquals("Den window and Hall window", windows.body)
    assertEquals(w2.entityId, windows.entityId)
    assertEquals(CcKind.DOOR, windows.kind)
    val mixed = CcBackgroundPolicy.consolidate(listOf(d, w1, w2), 9L)!!
    assertEquals("3 doors and windows open", mixed.title)
    assertEquals("Den window, Back door and 1 more", mixed.body)
    assertEquals("open:3@9", mixed.id)
    val g1 = alert("g1", CcSeverity.WARNING, CcKind.GARAGE, title = "Garage door is open")
    val g2 = alert("g2", CcSeverity.WARNING, CcKind.GARAGE, title = "Garage door is open")
    val garages = CcBackgroundPolicy.consolidate(listOf(g1, g2), 9L)!!
    assertEquals("2 doors open", garages.title)
    assertEquals(CcKind.GARAGE, garages.kind)
    assertEquals(CcAlertAction.REMIND, garages.action)
  }

  // --- the gate -----------------------------------------------------------------------------

  @Test fun takeoversShowOncePerIdAndCoolDownPerEntity() {
    val now = 10_000_000L
    val first = alert("leak@1", CcSeverity.CRITICAL, CcKind.LEAK, entity = "binary_sensor.leak")
    val r1 = CcAlertGate().admitTakeover(first, now)
    assertEquals(CcAlertGate.Reason.SHOWN, r1.reason)
    assertEquals(CcAlertGate.Reason.DUPLICATE, r1.gate.admitTakeover(first, now + 1).reason)
    val flap = first.copy(id = "leak@2")
    val r2 = r1.gate.admitTakeover(flap, now + 60_000L)
    assertEquals(CcAlertGate.Reason.COOLDOWN, r2.reason)
    assertFalse(r2.show)
    val again = first.copy(id = "leak@3")
    assertTrue(r2.gate.admitTakeover(again, now + CcBackgroundPolicy.CRITICAL_COOLDOWN_MS).show)
    // A clock set backwards does not hold the cooldown forever.
    assertTrue(r1.gate.admitTakeover(first.copy(id = "leak@4"), now - 1).show)
  }

  @Test fun seenIdsExpireAndAreCapped() {
    val now = 100_000_000L
    val old = CcAlertGate(seen = mapOf("x@1" to now - CcAlertGate.SEEN_TTL_MS), takeoverAt = mapOf("e.x" to now - CcAlertGate.SEEN_TTL_MS))
    val r = old.admitTakeover(alert("x@1", CcSeverity.CRITICAL, CcKind.LEAK, entity = "e.x"), now)
    assertEquals("an expired id is new again", CcAlertGate.Reason.SHOWN, r.reason)
    val many = CcAlertGate(seen = (1..CcAlertGate.MAX_SEEN + 10).associate { "id$it" to now - it })
    val after = many.admitTakeover(alert("new", CcSeverity.CRITICAL, CcKind.LEAK), now).gate
    assertEquals(CcAlertGate.MAX_SEEN + 1, after.seen.size)
    assertTrue("the newest are kept", "id1" in after.seen)
    assertFalse("the oldest are dropped", "id${CcAlertGate.MAX_SEEN + 10}" in after.seen)
  }

  @Test fun theOpenBannerShowsOncePerInterval() {
    val r1 = CcAlertGate().admitOpen(5_000L)
    assertTrue(r1.show)
    assertEquals(CcAlertGate.Reason.COOLDOWN, r1.gate.admitOpen(5_000L + CcBackgroundPolicy.OPEN_BANNER_INTERVAL_MS - 1).reason)
    assertTrue(r1.gate.admitOpen(5_000L + CcBackgroundPolicy.OPEN_BANNER_INTERVAL_MS).show)
    assertTrue("clock set backwards", r1.gate.admitOpen(4_000L).show)
  }

  // --- the household wire -----------------------------------------------------------------

  private fun msg(vararg kv: Pair<String, Any?>): JSONObject = JSONObject().apply { kv.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }
  private val good = arrayOf<Pair<String, Any?>>("id" to "binary_sensor.leak@1", "kind" to "LEAK", "entity" to "binary_sensor.leak",
      "title" to "Water leak", "body" to "Sink is wet", "since" to 7L, "cls" to "moisture")

  @Test fun wireRoundTripsALeakAndASmokeAlarm() {
    val leak = CcAlert("binary_sensor.leak@1", CcSeverity.CRITICAL, CcKind.LEAK, "binary_sensor.leak", "Water leak", "Sink", 7L,
        CcAlertAction.SHUT_WATER, null)
    assertEquals(leak, CcAlertWire.decode(CcAlertWire.encode(leak)))
    val smoke = CcAlert("binary_sensor.s@2", CcSeverity.CRITICAL, CcKind.SMOKE, "binary_sensor.s", "Smoke alarm", "Hall", 8L,
        CcAlertAction.VIEW, "smoke")
    assertEquals(smoke, CcAlertWire.decode(CcAlertWire.encode(smoke)))
    val long = leak.copy(title = "t".repeat(500), body = "b".repeat(500))
    val decoded = CcAlertWire.decode(CcAlertWire.encode(long))!!
    assertEquals(160, decoded.title.length)
    assertEquals(160, decoded.body.length)
  }

  @Test fun wireRejectsAnythingThatIsntAHouseholdEmergency() {
    assertNull(CcAlertWire.decode(null))
    assertTrue(CcAlertWire.decode(msg(*good)) != null)
    fun without(key: String, value: Any?) = msg(*good.map { if (it.first == key) key to value else it }.toTypedArray())
    assertNull(CcAlertWire.decode(without("id", "")))
    assertNull(CcAlertWire.decode(without("id", "x".repeat(201))))
    assertNull(CcAlertWire.decode(without("kind", "DOOR")))
    assertNull(CcAlertWire.decode(without("kind", "nonsense")))
    assertNull(CcAlertWire.decode(without("entity", " ")))
    assertNull(CcAlertWire.decode(without("entity", "e".repeat(201))))
    assertNull(CcAlertWire.decode(without("cls", "door")))
    assertNull("smoke needs a class", CcAlertWire.decode(without("kind", "SMOKE").also { it.put("cls", JSONObject.NULL) }))
    assertNull(CcAlertWire.decode(without("title", "  ")))
    val noSince = CcAlertWire.decode(without("since", "soon"))!!
    assertEquals(0L, noSince.since)
    assertEquals(CcSeverity.CRITICAL, noSince.severity)
  }
}
