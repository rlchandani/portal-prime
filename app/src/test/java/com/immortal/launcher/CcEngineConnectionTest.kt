/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.CcEngineRig.Companion.T0
import com.immortal.launcher.CcEngineRig.Companion.diff
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The connection state machine on fakes: auth, subscriptions, batching, heartbeat, backoff, generations. */
class CcEngineConnectionTest {
  @Test fun authFlowRequestsRegistryAndPlanThenGoesLiveOnTheFirstEvent() {
    val r = CcEngineRig()
    r.engine.setVisible(true); r.sched.runDue()
    assertEquals("ws://ha.test:8123/api/websocket", r.ws.url)
    assertEquals(CcPhase.CONNECTING, r.state.connection.phase)
    r.ha(JSONObject().put("type", "auth_required"))
    assertEquals("tok", r.ws.last("auth").getString("access_token"))
    r.ha(JSONObject().put("type", "auth_ok"))
    assertEquals(listOf("auth", "config/floor_registry/list", "config/area_registry/list", "config/device_registry/list",
        "config/entity_registry/list", "frontend/get_user_data"), r.ws.types())
    assertEquals("Loading rooms", r.state.connection.step)
    assertEquals(listOf(CcEngine.PING_S * 1000), r.sched.pendingDelays())
    r.handshake()
    val sub = r.ws.last("subscribe_entities").getJSONArray("entity_ids")
    assertTrue((0 until sub.length()).map(sub::getString).containsAll(listOf("light.kitchen", "binary_sensor.leak")))
    r.reply("subscribe_entities")
    assertEquals("Waiting for the first update", r.state.connection.step)
    r.event("subscribe_entities", CcEngineRig.ENTITIES)
    assertEquals("published only after the batch window", CcPhase.CONNECTING, r.state.connection.phase)
    r.advance(CcEngine.FLUSH_MS)
    assertEquals(CcPhase.LIVE, r.state.connection.phase)
    assertEquals(5, r.state.entities.size)
    assertEquals(CcPlanSource.HOME_ASSISTANT, r.state.planSource)
    assertEquals(CcEngineRig.PLAN, r.host.plan)
  }

  @Test fun entityDiffsInOneWindowArePublishedOnce() {
    val r = CcEngineRig().apply { connectLive() }
    val before = r.state.entitiesVersion
    r.event("subscribe_entities", diff("light.kitchen", "off"))
    r.advance(40)
    r.event("subscribe_entities", diff("lock.front", "unlocked"))
    assertEquals(before, r.state.entitiesVersion)
    r.advance(60)
    assertEquals(before + 1, r.state.entitiesVersion)
    assertEquals("off", r.state.entity("light.kitchen")?.state)
    assertEquals("unlocked", r.state.entity("lock.front")?.state)
    r.event("subscribe_entities", JSONObject().put("r", JSONArray().put("camera.porch")))
    r.advance(CcEngine.FLUSH_MS)
    assertNull(r.state.entity("camera.porch"))
  }

  @Test fun refusedSubscribeEntitiesFallsBackToStatesAndEvents() {
    val r = CcEngineRig()
    r.engine.setVisible(true); r.sched.runDue()
    r.handshake()
    r.reply("subscribe_entities", ok = false, error = "Unknown command")
    assertEquals(listOf("get_states", "subscribe_events"), r.ws.types().takeLast(2))
    r.reply("get_states", JSONArray().put(JSONObject().put("entity_id", "light.kitchen").put("state", "on").put("attributes", JSONObject())))
    r.reply("subscribe_events")
    r.advance(CcEngine.FLUSH_MS)
    assertEquals(CcPhase.LIVE, r.state.connection.phase)
    r.event("subscribe_events", JSONObject().put("data", JSONObject().put("entity_id", "light.kitchen")
        .put("new_state", JSONObject().put("entity_id", "light.kitchen").put("state", "off").put("attributes", JSONObject()))))
    r.advance(CcEngine.FLUSH_MS)
    assertEquals("off", r.state.entity("light.kitchen")?.state)
    assertEquals("", r.state.connection.step)
  }

  @Test fun theGetStatesFallbackDoesNotShowWaitingOnceStatesArrived() {
    val r = CcEngineRig()
    r.engine.setVisible(true); r.sched.runDue()
    r.handshake()
    r.reply("subscribe_entities", ok = false, error = "Unknown command")
    r.reply("get_states", JSONArray().put(JSONObject().put("entity_id", "light.kitchen").put("state", "on").put("attributes", JSONObject())))
    r.reply("subscribe_events")
    r.advance(CcEngine.FLUSH_MS)
    assertEquals(CcPhase.LIVE, r.state.connection.phase)
    assertEquals("", r.state.connection.step)
  }

  @Test fun aResubscribeWhileLiveClearsTheStepOnceStateArrives() {
    val r = CcEngineRig().apply { connectLive() }
    r.engine.setVisible(false); r.sched.runDue()
    r.reply("subscribe_entities")
    assertEquals(CcPhase.LIVE, r.state.connection.phase)
    assertEquals("", r.state.connection.step)
    r.event("subscribe_entities", JSONObject().put("a", JSONObject().put("binary_sensor.leak", CcEngineRig.state("on", "Leak", "moisture"))))
    r.advance(CcEngine.FLUSH_MS)
    assertEquals("", r.state.connection.step)
    assertEquals("on", r.state.entity("binary_sensor.leak")?.state)
  }

  @Test fun aStepLeftOverWhileLiveIsClearedByTheNextState() {
    val r = CcEngineRig().apply { connectLive() }
    r.run { setPhase(CcPhase.LIVE, "Waiting for the first update") }
    r.event("subscribe_entities", diff("light.kitchen", "off"))
    r.advance(CcEngine.FLUSH_MS)
    assertEquals("", r.state.connection.step)
  }

  @Test fun disconnectMidCallFailsTheCallAndReconnectsOnANewGeneration() {
    val r = CcEngineRig().apply { connectLive() }
    val results = ArrayList<Pair<Boolean, String?>>()
    assertTrue(r.engine.send(CcCalls.power(r.state.entity("light.kitchen")!!, false), true, { ok, e -> results += ok to e }, false))
    r.sched.runDue()
    val old = r.ws
    assertEquals("call_service", old.types().last())
    r.fail()
    assertTrue(old.cancelled)
    assertEquals(listOf(false to null), results)
    assertEquals(CcPhase.STALE, r.state.connection.phase)
    assertEquals(T0 + 1_100, r.state.connection.retryAt)
    // The dead socket's late answer belongs to an old generation.
    r.ha(JSONObject().put("type", "result").put("id", old.last("call_service").getInt("id")).put("success", true), old)
    assertEquals(1, results.size)
    r.advance(CcBackoff.FIRST_MS)
    assertEquals(2, r.sockets.opened.size)
    r.handshake(); r.reply("subscribe_entities"); r.event("subscribe_entities", CcEngineRig.ENTITIES); r.advance(CcEngine.FLUSH_MS)
    assertEquals(CcPhase.LIVE, r.state.connection.phase)
    assertEquals(0, r.engine.attempt)
  }

  @Test fun tokenRefusedRetriesRarelyAndARestartUsesTheNewToken() {
    val r = CcEngineRig()
    r.engine.setVisible(true); r.sched.runDue()
    r.ha(JSONObject().put("type", "auth_required"))
    r.ha(JSONObject().put("type", "auth_invalid"))
    assertEquals(CcPhase.AUTH_FAILED, r.state.connection.phase)
    assertEquals(listOf(CcBackoff.AUTH_MS), r.sched.pendingDelays())
    assertTrue("w control center token refused" in r.log.lines)
    r.advance(CcBackoff.AUTH_MS - 1)
    assertEquals(1, r.sockets.opened.size)
    r.host.token = "fresh"
    r.engine.restartWithSettings(); r.sched.runDue()
    r.ha(JSONObject().put("type", "auth_required"))
    assertEquals("fresh", r.ws.last("auth").getString("access_token"))
  }

  @Test fun anOversizedMessageIsProcessedAndMalformedFramesAreIgnored() {
    val r = CcEngineRig().apply { connectLive() }
    val big = "x".repeat(3_000_000)
    r.event("subscribe_entities", JSONObject().put("a", JSONObject().put("sensor.big",
        JSONObject().put("s", "on").put("a", JSONObject().put("blob", big)))))
    r.advance(CcEngine.FLUSH_MS)
    assertEquals(big, r.state.entity("sensor.big")?.attrs?.get("blob"))
    val v = r.state.entitiesVersion
    listOf("{not json", "[]", "{\"type\":\"result\",\"id\":999}", "{\"type\":\"mystery\"}").forEach(r::raw)
    r.advance(CcEngine.FLUSH_MS)
    assertEquals(v, r.state.entitiesVersion)
    assertEquals(CcPhase.LIVE, r.state.connection.phase)
  }

  @Test fun heartbeatPingsAndAMissingPongDropsTheSocket() {
    val r = CcEngineRig().apply { connectLive() }
    r.advance(CcEngine.PING_S * 1000)
    val ping = r.ws.last("ping").getInt("id")
    r.ha(JSONObject().put("type", "pong").put("id", ping))
    r.advance(CcEngine.PING_S * 1000)
    assertEquals(2, r.ws.types().count { it == "ping" })
    r.advance(CcEngine.PING_S * 1000)
    assertTrue(r.ws.cancelled)
    assertEquals(CcPhase.STALE, r.state.connection.phase)
    assertTrue("i control center: no pong; reconnecting" in r.log.lines)
  }

  @Test fun backoffGrowsToItsCapAndRetryNowStartsOver() {
    val r = CcEngineRig()
    r.engine.setVisible(true); r.sched.runDue()
    val delays = (0 until 6).map { r.fail(); r.sched.pendingDelays().single().also { r.advance(it) } }
    assertEquals(listOf(1_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), delays)
    assertEquals(CcPhase.CONNECTING, r.state.connection.phase)
    r.engine.retryNow(); r.sched.runDue()
    r.fail()
    assertEquals(listOf(1_000L), r.sched.pendingDelays())
    assertEquals(CcPhase.UNREACHABLE, r.state.connection.phase)
  }

  @Test fun offlineWaitsWithoutOpeningASocket() {
    val r = CcEngineRig { online = false }
    r.engine.setVisible(true); r.sched.runDue()
    assertEquals(CcPhase.OFFLINE, r.state.connection.phase)
    assertTrue(r.sockets.opened.isEmpty())
    r.host.online = true
    r.advance(CcBackoff.FIRST_MS)
    assertEquals(1, r.sockets.opened.size)
  }

  @Test fun notConfiguredOrAnUnusableAddressNeverConnects() {
    val r = CcEngineRig { token = "" }
    r.engine.setVisible(true); r.sched.runDue()
    assertEquals(CcPhase.NOT_CONFIGURED, r.state.connection.phase)
    r.host.token = "tok"; r.host.baseUrl = "not a url"
    r.engine.restartWithSettings(); r.sched.runDue()
    assertTrue(r.sockets.opened.isEmpty())
    r.host.baseUrl = "http://ha.test:8123"
    r.sockets.refuse += "ws://ha.test:8123/api/websocket"
    r.engine.restartWithSettings(); r.sched.runDue()
    assertEquals(CcPhase.NOT_CONFIGURED, r.state.connection.phase)
  }

  @Test fun hiddenScreenDropsToTheBackgroundSetAndClosesWhenNobodyNeedsIt() {
    val r = CcEngineRig().apply { connectLive() }
    r.engine.setVisible(false); r.sched.runDue()
    assertEquals(listOf("unsubscribe_events", "subscribe_entities"), r.ws.types().takeLast(2))
    val ids = r.ws.last("subscribe_entities").getJSONArray("entity_ids")
    assertEquals(listOf("binary_sensor.leak"), (0 until ids.length()).map(ids::getString))
    assertEquals(setOf("binary_sensor.leak"), r.engine.live.raw.keys)
    r.host.settings = r.host.settings.copy(bannersListen = false)
    r.engine.reloadSettings(); r.sched.runDue()
    assertEquals(1000, r.ws.closed)
    assertTrue(r.sched.pendingDelays().isEmpty())
  }

  @Test fun thePhonesLeaseHoldsTheForegroundUntilItLapses() {
    val r = CcEngineRig { settings = settings.copy(bannersListen = false) }
    r.engine.remoteWatch(); r.sched.runDue()
    assertTrue(r.engine.foreground)
    r.handshake(); r.reply("subscribe_entities")
    r.advance(30_000)
    r.engine.remoteWatch(); r.sched.runDue()
    r.advance(CcRemoteWatch.LEASE_MS + CcRemoteWatch.EXPIRY_SLACK_MS * 2)
    assertFalse(r.engine.foreground)
    assertEquals(1000, r.ws.closed)
  }
}

/** Control Center opens with the last-known states of the entities the background subscription doesn't watch. */
class CcEngineLastKnownTest {
  private fun CcEngineRig.hideThenShow() {
    engine.setVisible(false); sched.runDue()
    engine.setVisible(true); sched.runDue()
  }

  @Test fun anOpenDrawsTheLastKnownStatesAtOnceUnconfirmed() {
    val r = CcEngineRig().apply { connectLive() }
    r.engine.setVisible(false); r.sched.runDue()
    assertEquals(setOf("binary_sensor.leak"), r.engine.live.raw.keys)
    assertEquals(setOf("light.kitchen", "lock.front", "camera.porch", "valve.main"), r.engine.lastKnown.keys)
    r.engine.setVisible(true); r.sched.runDue()
    assertEquals("on", r.state.entity("light.kitchen")?.state)
    assertEquals("locked", r.state.entity("lock.front")?.state)
    assertEquals(setOf("light.kitchen", "lock.front", "camera.porch", "valve.main"), r.state.unconfirmed)
    assertTrue("the plan is built before the subscription answers", r.state.planModels.isNotEmpty())
    assertEquals("safety-critical shows as in flight", setOf("lock.front"), r.state.pending)
    assertTrue(r.engine.lastKnown.isEmpty())
  }

  @Test fun theFullAnswerConfirmsInPlaceAndDropsWhatHaNoLongerHas() {
    val r = CcEngineRig().apply { connectLive() }
    r.hideThenShow()
    val versionBefore = r.state.entitiesVersion
    val fresh = JSONObject().put("a", JSONObject()
        .put("light.kitchen", CcEngineRig.state("off", "Kitchen light")).put("lock.front", CcEngineRig.state("locked", "Front lock"))
        .put("binary_sensor.leak", CcEngineRig.state("off", "Leak", "moisture")).put("valve.main", CcEngineRig.state("open", "Water main")))
    r.reply("subscribe_entities")
    r.event("subscribe_entities", fresh)
    r.advance(CcEngine.FLUSH_MS)
    assertTrue(r.state.unconfirmed.isEmpty())
    assertTrue(r.state.pending.isEmpty())
    assertEquals("a stale value updates in place", "off", r.state.entity("light.kitchen")?.state)
    assertNull("gone from HA", r.state.entity("camera.porch"))
    assertTrue(r.state.entitiesVersion > versionBefore)
  }

  @Test fun aDiffDoesNotConfirmALastKnownLock() {
    val r = CcEngineRig().apply { connectLive() }
    r.hideThenShow()
    r.reply("subscribe_entities")
    r.event("subscribe_entities", CcEngineRig.diff("lock.front", "unlocked"))
    r.advance(CcEngine.FLUSH_MS)
    assertTrue("lock.front" in r.state.unconfirmed)
  }

  @Test fun anActionOnALastKnownLockIsRefusedUntilHaConfirmsIt() {
    val r = CcEngineRig().apply { connectLive() }
    r.hideThenShow()
    val lock = r.state.entity("lock.front")!!
    val calls = r.ws.types().count { it == "call_service" }
    assertFalse(r.engine.send(CcCalls.unlock(lock), undoable = false, onResult = null, confirmed = true))
    r.sched.runDue()
    assertEquals(calls, r.ws.types().count { it == "call_service" })
    r.reply("subscribe_entities")
    r.event("subscribe_entities", CcEngineRig.ENTITIES)
    r.advance(CcEngine.FLUSH_MS)
    assertTrue(r.engine.send(CcCalls.unlock(r.state.entity("lock.front")!!), undoable = false, onResult = null, confirmed = true))
    r.sched.runDue()
    assertEquals(calls + 1, r.ws.types().count { it == "call_service" })
  }

  @Test fun theEngineRechecksEvenIfTheScreenRaced() {
    val r = CcEngineRig().apply { connectLive() }
    r.hideThenShow()
    var result: Boolean? = null
    r.run { dispatch(CcCalls.unlock(live.raw.getValue("lock.front")), undoable = false) { ok, _ -> result = ok } }
    assertEquals(false, result)
    assertFalse(r.ws.types().contains("call_service"))
  }

  @Test fun aLastKnownLightCanStillBeSwitched() {
    val r = CcEngineRig().apply { connectLive() }
    r.hideThenShow()
    assertTrue(r.engine.send(CcCalls.power(r.state.entity("light.kitchen")!!, false), undoable = false, onResult = null, confirmed = true))
  }
}
