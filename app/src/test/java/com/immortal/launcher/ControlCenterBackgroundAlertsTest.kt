/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.CcBackgroundPolicy.Route
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlCenterBackgroundAlertsTest {
  private val min = 60_000L
  private val now = 1_800_000_000_000L
  private val shared = ControlCenterConfig.Settings()

  private fun sensor(id: String, cls: String, state: String = "on", changedMinAgo: Long = 1, name: String? = null) =
      CcEntity("binary_sensor.$id", state, mapOf("device_class" to cls, "friendly_name" to (name ?: id)), now - changedMinAgo * min)

  private fun alerts(vararg e: CcEntity, warnAfterMin: Int = 10) =
      CcAlerts.active(e.associateBy { it.id }, now, warnAfterMin)

  // --- every class, plan or no plan ---------------------------------------------------------

  @Test fun backgroundSet_usesFetchedClasses_forEveryEntity_noPlanNeeded() {
    val reg = CcRegistry(
        entities = setOf("binary_sensor.kitchen_leak", "binary_sensor.hall_smoke", "binary_sensor.co", "binary_sensor.gas",
            "binary_sensor.pantry_door", "binary_sensor.bath_window", "binary_sensor.hall_motion", "binary_sensor.case_tamper",
            "binary_sensor.boiler_safety", "binary_sensor.ble", "cover.garage_door", "light.k"),
        fetchedClass = mapOf("binary_sensor.kitchen_leak" to "moisture", "binary_sensor.hall_smoke" to "smoke",
            "binary_sensor.co" to "carbon_monoxide", "binary_sensor.gas" to "gas", "binary_sensor.pantry_door" to "door",
            "binary_sensor.bath_window" to "window", "binary_sensor.hall_motion" to "motion",
            "binary_sensor.case_tamper" to "tamper", "binary_sensor.boiler_safety" to "safety", "binary_sensor.ble" to "connectivity"),
        entityClass = mapOf("cover.garage_door" to "garage"))
    assertEquals(setOf("binary_sensor.kitchen_leak", "binary_sensor.hall_smoke", "binary_sensor.co", "binary_sensor.gas",
        "binary_sensor.pantry_door", "binary_sensor.bath_window", "cover.garage_door"), CcInterest.background(reg))
  }

  @Test fun routes_criticalsTakeOver_openWarns_othersNever() {
    val a = alerts(sensor("leak", "moisture"), sensor("smoke", "smoke"), sensor("co", "carbon_monoxide"), sensor("gas", "gas"),
        sensor("safety", "safety"), sensor("door", "door", changedMinAgo = 15), sensor("motion", "motion"), sensor("tamper", "tamper"))
    val byEntity = a.associate { it.entityId.removePrefix("binary_sensor.") to CcBackgroundPolicy.route(it) }
    assertEquals(Route.TAKEOVER, byEntity["leak"])
    assertEquals(Route.TAKEOVER, byEntity["smoke"])
    assertEquals(Route.TAKEOVER, byEntity["co"])
    assertEquals(Route.TAKEOVER, byEntity["gas"])
    assertEquals(Route.NONE, byEntity["safety"])
    assertEquals(Route.OPEN_WARNING, byEntity["door"])
    assertNull("motion raises no alert at all", byEntity["motion"])
    assertNull("tamper raises no alert at all", byEntity["tamper"])
  }

  // --- critical decisions: every Portal, sound in quiet hours except gas ----------------------

  @Test fun criticals_takeOverEveryPortalMode_soundAtNightExceptGas() {
    val modes = ControlCenterConfig.MODES.map { ControlCenterConfig.Settings(portalMode = it, sharedRoom = false) }
    for (cls in listOf("moisture", "smoke", "carbon_monoxide", "gas")) {
      val alert = alerts(sensor("x", cls)).single()
      modes.forEach { s ->
        assertEquals(CcBannerPolicy.Show.TAKEOVER, CcBannerPolicy.decide(alert, s, quietHours = true).show)
        assertTrue("$cls sounds by day", CcBannerPolicy.decide(alert, s, quietHours = false).sound)
        assertEquals("$cls at night", cls != "gas", CcBannerPolicy.decide(alert, s, quietHours = true).sound)
      }
    }
  }

  // --- doors left open ---------------------------------------------------------------------

  @Test fun openWarning_onlyAfterTenMinutes() {
    assertTrue(alerts(sensor("pantry", "door", "on", changedMinAgo = 9)).isEmpty())
    assertEquals(Route.OPEN_WARNING, CcBackgroundPolicy.route(alerts(sensor("pantry", "door", "on", changedMinAgo = 10)).single()))
  }

  @Test fun openWarning_sharedRoomOnly_silentAlways_neverTakeover() {
    val alert = alerts(sensor("pantry", "door", changedMinAgo = 30)).single()
    val day = CcBannerPolicy.decide(alert, shared, quietHours = false)
    val night = CcBannerPolicy.decide(alert, shared, quietHours = true)
    assertEquals(CcBannerPolicy.Show.BANNER, day.show)
    assertEquals(CcBannerPolicy.Show.BANNER, night.show)
    assertFalse(day.sound); assertFalse(night.sound)
    assertEquals(CcBannerPolicy.Show.NONE, CcBannerPolicy.decide(alert, shared.copy(sharedRoom = false), false).show)
  }

  @Test fun startup_doorOpenUnderTenMinutes_isSilent_andLaterMatures() {
    // At install the Pantry door has been open 3 minutes: nothing is active, nothing shows.
    val start = CcBackgroundPolicy.plan(alerts(sensor("pantry", "door", changedMinAgo = 3)), seen = null)
    assertTrue(start.openFresh.isEmpty() && start.takeovers.isEmpty())
    // Ten minutes in, the same opening matures into one fresh warning.
    val later = CcBackgroundPolicy.plan(alerts(sensor("pantry", "door", changedMinAgo = 10)), seen = emptySet())
    assertEquals(1, later.openFresh.size)
  }

  @Test fun startup_doorOpenLong_countsOnce_infoNeverReplays() {
    val active = alerts(sensor("pantry", "door", changedMinAgo = 45))
    val first = CcBackgroundPolicy.plan(active, seen = null)
    assertEquals(1, first.openFresh.size)
    assertTrue(first.info.isEmpty())
    val again = CcBackgroundPolicy.plan(active, seen = active.map { it.id }.toSet())
    assertTrue("the same opening never re-banners", again.openFresh.isEmpty())
  }

  @Test fun consolidate_oneBannerForManyDoors() {
    val open = alerts(sensor("pantry", "door", changedMinAgo = 40, name = "Pantry door"),
        sensor("back", "door", changedMinAgo = 20, name = "Back door"), sensor("side", "door", changedMinAgo = 12, name = "Side door"))
    val banner = CcBackgroundPolicy.consolidate(open, now)!!
    assertEquals("3 doors open", banner.title)
    assertEquals("Pantry door, Back door and 1 more", banner.body)
    assertEquals(CcSeverity.WARNING, banner.severity)
    val mixed = CcBackgroundPolicy.consolidate(open.take(1) + alerts(sensor("bath", "window", changedMinAgo = 15)), now)!!
    assertEquals("2 doors and windows open", mixed.title)
    val single = open.first()
    assertEquals(single, CcBackgroundPolicy.consolidate(listOf(single), now))
    assertNull(CcBackgroundPolicy.consolidate(emptyList(), now))
  }

  @Test fun openBanner_atMostOncePerHour() {
    val g0 = CcAlertGate()
    val first = g0.admitOpen(now)
    assertTrue(first.show)
    val soon = first.gate.admitOpen(now + 59 * min)
    assertFalse(soon.show); assertEquals(CcAlertGate.Reason.COOLDOWN, soon.reason)
    assertTrue(soon.gate.admitOpen(now + 60 * min).show)
    // A saved time from the future (clock went back) never blocks the banner forever.
    assertTrue(CcAlertGate(openBannerAt = now + 5 * min).admitOpen(now).show)
  }

  // --- household dedupe and cooldown --------------------------------------------------------

  @Test fun takeover_dedupedAcrossPortals_byStableId() {
    val leak = alerts(sensor("kitchen_leak", "moisture")).single()
    val own = CcAlertGate().admitTakeover(leak, now)
    assertTrue(own.show)
    val relayed = CcAlertWire.decode(CcAlertWire.encode(leak))!!
    assertEquals(leak.id, relayed.id)
    val dup = own.gate.admitTakeover(relayed, now + 1_000)
    assertFalse(dup.show); assertEquals(CcAlertGate.Reason.DUPLICATE, dup.reason)
  }

  @Test fun takeover_cooldownPerEntity_otherSensorsStillShow() {
    val first = CcAlertGate().admitTakeover(alerts(sensor("leak", "moisture", changedMinAgo = 1)).single(), now)
    val flap = alerts(sensor("leak", "moisture", changedMinAgo = 0)).single()
    val cooled = first.gate.admitTakeover(flap, now + 2 * min)
    assertFalse(cooled.show); assertEquals(CcAlertGate.Reason.COOLDOWN, cooled.reason)
    assertTrue(cooled.gate.admitTakeover(alerts(sensor("smoke", "smoke")).single(), now + 2 * min).show)
    val afterCooldown = alerts(sensor("leak", "moisture", changedMinAgo = -6)).single()
    assertTrue(cooled.gate.admitTakeover(afterCooldown, now + CcBackgroundPolicy.CRITICAL_COOLDOWN_MS + 3 * min).show)
  }

  @Test fun wire_rejectsNonCriticalOrMalformed() {
    val leak = alerts(sensor("leak", "moisture")).single()
    val good = CcAlertWire.encode(leak)
    assertNotNull(CcAlertWire.decode(good))
    assertNull(CcAlertWire.decode(JSONObject(good.toString()).put("kind", "DOOR")))
    assertNull(CcAlertWire.decode(JSONObject(good.toString()).put("kind", "SMOKE").put("cls", "safety")))
    assertNull(CcAlertWire.decode(JSONObject(good.toString()).put("id", "")))
    assertNull(CcAlertWire.decode(JSONObject(good.toString()).put("title", "")))
    assertNull(CcAlertWire.decode(null))
    val gas = CcAlertWire.decode(CcAlertWire.encode(alerts(sensor("gas", "gas")).single()))!!
    assertEquals("gas", gas.deviceClass)
    assertFalse("relayed gas stays silent at night", CcBannerPolicy.decide(gas, shared, quietHours = true).sound)
  }

  // --- the debug hook's validation ----------------------------------------------------------

  @Test fun fakeState_parse_namespacesAndValidates() {
    val f = CcFakeState.parse(JSONObject("""{"slug":"leak1","state":"on","deviceClass":"moisture","changedMinAgo":0}"""))!!
    assertEquals("binary_sensor.fake_leak1", f.entityId)
    assertEquals("cover.fake_gd", CcFakeState.parse(JSONObject("""{"domain":"cover","slug":"gd","state":"open","deviceClass":"garage"}"""))!!.entityId)
    assertNull(CcFakeState.parse(JSONObject("""{"slug":"Bad Slug","state":"on","deviceClass":"moisture"}""")))
    assertNull(CcFakeState.parse(JSONObject("""{"slug":"x","state":"exploded","deviceClass":"moisture"}""")))
    assertNull(CcFakeState.parse(JSONObject("""{"domain":"lock","slug":"x","state":"on","deviceClass":"moisture"}""")))
    assertNull(CcFakeState.parse(JSONObject("""{"slug":"x","state":"on","deviceClass":"door","changedMinAgo":-1}""")))
  }
}
