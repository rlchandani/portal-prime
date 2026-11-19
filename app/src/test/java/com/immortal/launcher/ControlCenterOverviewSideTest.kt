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

class ControlCenterOverviewSideTest {
  private fun e(id: String, state: String = "on", vararg attrs: Pair<String, Any?>) = CcEntity(id, state, mapOf(*attrs))
  private fun named(id: String, name: String, vararg members: String) =
      CcEntity(id, "scening", mapOf("friendly_name" to name, "entity_id" to members.toList()))

  @Test fun sceneTonesFromNameHints() {
    assertEquals(CcSceneTone.NIGHT, CcSideLogic.tone(named("scene.a", "Good night")))
    assertEquals(CcSceneTone.NIGHT, CcSideLogic.tone(named("scene.bedtime_sleep", "Bedtime")))
    assertEquals(CcSceneTone.MOVIE, CcSideLogic.tone(named("scene.b", "Movie time")))
    assertEquals(CcSceneTone.LEAVE, CcSideLogic.tone(named("script.c", "Leaving home")))
    assertEquals(CcSceneTone.MORNING, CcSideLogic.tone(named("scene.d", "Wake up")))
    assertEquals(CcSceneTone.NEUTRAL, CcSideLogic.tone(named("scene.e", "Dinner")))
    // Whole words only: "outdoor" is not "out", "nightingale" is not "night".
    assertEquals(CcSceneTone.NEUTRAL, CcSideLogic.tone(named("scene.f", "Outdoor nightingale")))
    assertEquals("moon", CcSideLogic.icon(CcSceneTone.NIGHT))
    assertEquals("sparkle", CcSideLogic.icon(CcSceneTone.NEUTRAL))
  }

  @Test fun pickPrefersRecognisedScenesAndCapsAtFour() {
    val all = listOf(named("scene.z", "Zebra"), named("scene.a", "Alpha"), named("scene.m", "Good morning"),
        named("scene.n", "Good night"), named("script.l", "Leave"), named("scene.t", "TV"))
    assertEquals(listOf("Good night", "TV", "Leave", "Good morning"), CcSideLogic.pick(all, kids = false).map { it.name })
    val kids = CcSideLogic.pick(all, kids = true).map { it.name }
    assertEquals(listOf("Good night", "TV", "Good morning", "Alpha"), kids)
    assertTrue(CcSideLogic.pick(emptyList(), false).isEmpty())
  }

  @Test fun subtitleDescribesWhatTheSceneTouches() {
    val plan = CcPlan(listOf(CcFloor("g", "G", listOf(
        CcRoom("living", "Living room", CcRect(0, 0, 4, 4), devices = listOf(CcPlaced("light.a", .5f, .5f), CcPlaced("light.b", .5f, .5f))),
        CcRoom("kitchen", "Kitchen", CcRect(4, 0, 4, 4), devices = listOf(CcPlaced("light.c", .5f, .5f))),
        CcRoom("hall", "Hall", CcRect(8, 0, 4, 4), devices = listOf(CcPlaced("light.d", .5f, .5f)))))))
    assertEquals("Living room", CcSideLogic.subtitle(named("scene.a", "A", "light.a", "light.b"), plan))
    assertEquals("Living room & Kitchen", CcSideLogic.subtitle(named("scene.a", "A", "light.a", "light.c"), plan))
    assertEquals("3 rooms", CcSideLogic.subtitle(named("scene.a", "A", "light.a", "light.c", "light.d"), plan))
    assertEquals("Walks the house", CcSideLogic.subtitle(named("scene.a", "A", "light.a", "light.b", "light.c", "light.d", "x.1", "x.2"), plan))
    assertEquals("2 devices", CcSideLogic.subtitle(named("scene.a", "A", "x.1", "x.2"), plan))
    assertEquals("1 device", CcSideLogic.subtitle(named("scene.a", "A", "x.1"), null))
    assertEquals("Runs a script", CcSideLogic.subtitle(named("script.a", "A"), plan))
    assertEquals("Scene", CcSideLogic.subtitle(named("scene.a", "A"), plan))
  }

  @Test fun allClearIsHonest() {
    assertEquals("Doors locked · garage shut", CcSideLogic.allClear(listOf(e("lock.a", "locked"), e("lock.b", "locked"),
        e("cover.g", "closed", "device_class" to "garage"))))
    assertEquals("Door locked", CcSideLogic.allClear(listOf(e("lock.a", "locked"))))
    assertEquals("Garage shut", CcSideLogic.allClear(listOf(e("cover.g", "closed", "device_class" to "garage"))))
    assertEquals("Nothing needs you right now", CcSideLogic.allClear(listOf(e("lock.a", "unlocked"))))
    assertEquals("Nothing needs you right now", CcSideLogic.allClear(emptyList()))
  }

  @Test fun alertTonesAndIcons() {
    fun alert(sev: CcSeverity, kind: CcKind, id: String) = CcAlert("x", sev, kind, id, "t", "b", 0, CcAlertAction.VIEW)
    assertEquals(CcTone.RED, CcSideLogic.tone(alert(CcSeverity.CRITICAL, CcKind.LEAK, "binary_sensor.l")))
    assertEquals(CcTone.AMBER, CcSideLogic.tone(alert(CcSeverity.WARNING, CcKind.GARAGE, "cover.g")))
    assertEquals(CcTone.NEUTRAL, CcSideLogic.tone(alert(CcSeverity.INFO, CcKind.BATTERY, "sensor.b")))
    assertEquals("garage", CcSideLogic.icon(alert(CcSeverity.WARNING, CcKind.GARAGE, "cover.g")))
    assertEquals("dish", CcSideLogic.icon(alert(CcSeverity.INFO, CcKind.APPLIANCE, "switch.dishwasher")))
  }

  @Test fun captionsPerLens() {
    val room = CcRoom("k", "Kitchen", CcRect(0, 0, 4, 4))
    val v = CcRoomView(room, 2, 3, emptyList(), emptyList(), 70.6, 46, false, 0, 450.0, null)
    assertEquals("71° · 2 lights on", CcOverviewText.caption(v, CcLens.LIVE))
    assertEquals("71° · 46%", CcOverviewText.caption(v, CcLens.CLIMATE))
    assertEquals("", CcOverviewText.caption(v.copy(temperature = null, humidity = null), CcLens.CLIMATE))
    val out = v.copy(room = room.copy(outdoor = true))
    assertEquals("71°", CcOverviewText.caption(out, CcLens.LIVE))
  }

  @Test fun heatRampHitsTheMockStops() {
    assertEquals(0x5AA9FF, CcOverviewText.heatRgb(0f))
    assertEquals(0xF08A4B, CcOverviewText.heatRgb(1f))
    assertEquals(0xF08A4B, CcOverviewText.heatRgb(2f))
    assertEquals(0x61D0A6, CcOverviewText.heatRgb(0.44f))
    val mid = CcOverviewText.heatRgb(0.22f)
    assertTrue(mid != 0x5AA9FF && mid != 0x61D0A6)
  }

  @Test fun washMixesLightColours() {
    assertEquals(CcOverviewText.WARM_WASH, CcOverviewText.mixRgb(emptyList()))
    assertEquals(0x7F007F, CcOverviewText.mixRgb(listOf(0xFF0000, 0x0000FF)))
    assertEquals(0x808080, CcOverviewText.lerpRgb(0x000000, 0xFFFFFF, 0.5f) + 0x010101)
  }

  @Test fun freshnessPill() {
    val now = 100 * 60_000L
    assertEquals("Live from Home Assistant", CcOverviewText.freshness(CcConnection(CcPhase.LIVE), now))
    assertEquals("Updated 6 min ago", CcOverviewText.freshness(CcConnection(CcPhase.STALE, lastLiveAt = now - 6 * 60_000L), now))
    assertEquals("Updated just now", CcOverviewText.freshness(CcConnection(CcPhase.STALE, lastLiveAt = now - 1_000L), now))
    assertEquals("Reconnecting", CcOverviewText.freshness(CcConnection(CcPhase.STALE), now))
    assertEquals("Last known · 7:31 pm", CcOverviewText.freshness(CcConnection(CcPhase.UNREACHABLE, lastLiveAt = 1L), now) { "7:31 pm" })
    assertEquals("No live data yet", CcOverviewText.freshness(CcConnection(CcPhase.OFFLINE), now))
    assertTrue(CcOverviewText.staleLine(CcConnection(CcPhase.STALE, lastLiveAt = now - 6 * 60_000L), now).endsWith("last update 6 min ago"))
    assertEquals("Live updates paused — reconnecting", CcOverviewText.staleLine(CcConnection(CcPhase.STALE), now))
  }

  @Test fun loadingChecklistFollowsTheEngine() {
    fun steps(step: String) = CcOverviewText.loading(CcConnection(CcPhase.CONNECTING, step), null, 0).map { it.step }
    val d = CcOverviewText.Step.DONE; val n = CcOverviewText.Step.NOW; val l = CcOverviewText.Step.LATER
    assertEquals(listOf(n, l, l, l), steps("Connecting"))
    assertEquals(listOf(d, n, l, l), steps("Loading rooms"))
    assertEquals(listOf(d, d, n, l), steps("Loading devices"))
    assertEquals(listOf(d, d, d, n), steps("Waiting for the first update"))
    val reg = CcRegistry(floors = listOf(CcHaFloor("g", "G", 0), CcHaFloor("u", "U", 1)), areas = List(15) { CcHaArea("a$it", "A", "g") },
        entities = setOf("light.a", "light.b"))
    val rows = CcOverviewText.loading(CcConnection(CcPhase.CONNECTING, "Loading devices"), reg, 64).map { it.text }
    assertEquals("Floors & areas · 2 floors, 15 areas", rows[1])
    assertEquals("Devices · 2 entities", rows[2])
    assertEquals("Live states · 64 entities", rows[3])
  }

  @Test fun climateLineAndRangeAndFloorNames() {
    assertNull(CcOverviewText.climateLine(emptyList()))
    val c = CcEntity("climate.d", "heat", mapOf("friendly_name" to "Downstairs", "hvac_action" to "heating"))
    assertEquals("Downstairs heating", CcOverviewText.climateLine(listOf(c)))
    assertEquals("Up idle", CcOverviewText.climateLine(listOf(CcEntity("climate.u", "heat", mapOf("friendly_name" to "Up", "hvac_action" to "idle")))))
    assertEquals("61°" to "75°", CcOverviewText.heatRange(true))
    assertEquals("16°" to "24°", CcOverviewText.heatRange(false))
    assertEquals("Ground", CcOverviewText.shortFloor("Ground floor"))
    assertEquals("Upstairs", CcOverviewText.shortFloor("Upstairs"))
  }
}
