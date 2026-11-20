/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.CcEngineRig.Companion.diff
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Service calls through the policy gate, dry run, the plan, alerts, streams and charging, on fakes. */
class CcEngineActionsTest {
  private fun live(configure: FakeCcHost.() -> Unit = {}) = CcEngineRig(configure).apply { connectLive() }
  private fun CcEngineRig.e(id: String) = state.entity(id)!!

  @Test fun askingCallsNeedTheHouseholdsConfirmation() {
    val r = live()
    assertFalse(r.engine.send(CcCalls.unlock(r.e("lock.front")), true, null, confirmed = false))
    assertTrue("w refused lock.unlock: needs confirm first" in r.log.lines)
    assertFalse(r.engine.send(CcCalls.valveClose(r.e("valve.main")), true, null, confirmed = false))
    assertTrue(r.engine.send(CcCalls.unlock(r.e("lock.front")), true, null, confirmed = true))
    r.sched.runDue()
    assertEquals("unlock", r.ws.last("call_service").getString("service"))
    assertTrue(r.engine.send(CcCalls.power(r.e("light.kitchen"), false), true, null, confirmed = false))
  }

  @Test fun kidsModeHidesLocksEvenWhenConfirmed() {
    val r = live { settings = settings.copy(portalMode = ControlCenterConfig.MODE_KIDS, kidsHideLocks = true) }
    assertFalse(r.engine.send(CcCalls.unlock(r.e("lock.front")), true, null, confirmed = true))
    assertTrue(r.log.lines.any { it.endsWith("hidden on this Portal") })
  }

  @Test fun aCallWhileNotLiveIsRefusedWithTheReason() {
    val r = CcEngineRig()
    r.engine.setVisible(true); r.sched.runDue()
    assertFalse(r.engine.send(CcCall("light", "turn_on", "light.x"), true, null, false))
    r.sched.runDue()
    assertEquals("Connecting to Home Assistant", r.state.toast?.text)
    assertEquals("Connecting to Home Assistant", r.engine.sendFromPhone(CcCall("light", "turn_on", "light.x")) { _, _ -> })
    val results = ArrayList<Boolean>()
    r.run { dispatch(CcCall("light", "turn_on", "light.x"), false) { ok, _ -> results += ok } }
    assertEquals(listOf(false), results)
  }

  @Test fun anAcknowledgedCallOffersUndoAndARefusedOneSaysWhy() {
    val r = live()
    val results = ArrayList<String>()
    r.engine.send(CcCalls.power(r.e("light.kitchen"), false), true, { ok, err -> results += "$ok/$err" }, false); r.sched.runDue()
    assertEquals(setOf("light.kitchen"), r.state.pending)
    r.reply("call_service")
    assertEquals("Kitchen light off", r.state.toast?.text)
    assertTrue(r.state.toast!!.undo)
    r.engine.undo(); r.sched.runDue()
    assertEquals("turn_on", r.ws.last("call_service").getString("service"))
    r.engine.send(CcCalls.coverOpen(r.e("valve.main")), true, { ok, err -> results += "$ok/$err" }, true); r.sched.runDue()
    r.reply("call_service", ok = false, error = "Jammed")
    assertEquals("Couldn't do that: Jammed", r.state.toast?.text)
    assertEquals(listOf("true/null", "false/Jammed"), results)
    r.engine.dismissToast(r.state.toast!!.id); r.sched.runDue()
    assertNull(r.state.toast)
  }

  @Test fun aCallWithNoEchoSaysTheDeviceDidNotRespond() {
    val r = live()
    r.engine.send(CcCalls.power(r.e("light.kitchen"), false), true, null, false); r.sched.runDue()
    r.advance(CcLive.ECHO_MS + 100)
    assertEquals("Kitchen light didn't respond", r.state.toast?.text)
    assertTrue(r.state.pending.isEmpty())
  }

  @Test fun aPhoneCallIsSentWithoutUndo() {
    val r = live()
    var answer: Boolean? = null
    assertNull(r.engine.sendFromPhone(CcCalls.power(r.e("light.kitchen"), true)) { ok, _ -> answer = ok })
    r.sched.runDue()
    r.reply("call_service")
    assertEquals(true, answer)
    assertFalse(r.state.toast?.undo == true)
  }

  @Test fun dryRunLogsTheCallAndSendsNothing() {
    val r = live { dry = true }
    val sent = r.ws.sent.size
    var ok = false
    r.engine.send(CcCalls.power(r.e("light.kitchen"), false), true, { o, _ -> ok = o }, false); r.sched.runDue()
    assertTrue(ok)
    assertEquals(sent, r.ws.sent.size)
    assertTrue("i dry-run light.turn_off data=[]" in r.log.lines)
    assertEquals("Dry run: Kitchen light off (not sent)", r.state.toast?.text)
    assertEquals("Dry run", r.state.debug)
  }

  @Test fun scenesSnapshotFirstThenRunAndCanBeUndone() {
    val r = CcEngineRig().apply {
      connectLive(entities = JSONObject(CcEngineRig.ENTITIES.toString()).also { e -> e.getJSONObject("a").put("scene.movie",
          JSONObject().put("s", "unknown").put("a", JSONObject().put("friendly_name", "Movie").put("entity_id", JSONArray().put("light.kitchen")))) })
    }
    assertTrue(r.engine.runScene(r.e("scene.movie"), confirmed = true)); r.sched.runDue()
    assertEquals(setOf("scene.movie"), r.host.scenes)
    assertEquals("create", r.ws.last("call_service").getString("service"))
    r.reply("call_service")
    assertEquals("scene.movie", r.ws.last("call_service").getJSONObject("target").getString("entity_id"))
    r.reply("call_service")
    assertEquals("Movie", r.state.sceneRun?.name)
    r.engine.undo(); r.sched.runDue()
    assertEquals("scene.hearthpane_undo", r.ws.last("call_service").getJSONObject("target").getString("entity_id"))
    r.engine.clearSceneRun(); r.sched.runDue()
    assertNull(r.state.sceneRun)
  }

  @Test fun aNewerLocalPlanIsCopiedToHomeAssistantAndSavingStampsIt() {
    val r = CcEngineRig { plan = CcEngineRig.PLAN.copy(updatedAt = 5_000L) }
    r.connectLive()
    assertEquals(CcPlanSource.THIS_PORTAL, r.state.planSource)
    assertEquals(5_000L, r.ws.last("frontend/set_user_data").getJSONObject("value").getLong("updatedAt"))
    r.reply("frontend/set_user_data")
    assertEquals(CcPlanSource.HOME_ASSISTANT, r.state.planSource)
    r.engine.queueSavePlan(CcEngineRig.PLAN); r.sched.runDue()
    assertEquals(r.sched.now, r.host.plan?.updatedAt)
    r.reply("frontend/set_user_data", ok = false, error = "bad")
    assertEquals("Saved on this Portal only — Home Assistant didn't accept the plan", r.state.toast?.text)
  }

  @Test fun aTestPlanOrDryRunNeverWritesTheHomeAssistantPlan() {
    val r = live()
    assertEquals("dryRun=true testPlan=false floors=0 rooms=0 haUnreachable=false", r.engine.debugCommand(true, null, null, null, null, 1_000))
    val writes = { r.ws.types().count { it == "frontend/set_user_data" } }
    r.engine.queueSavePlan(CcEngineRig.PLAN); r.sched.runDue()
    assertEquals(0, writes())
    r.engine.debugCommand(false, "load", null, null, null, 1_000)
    assertNotNull(r.host.test)
    r.engine.queueSavePlan(CcEngineRig.PLAN); r.sched.runDue()
    assertEquals(0, writes())
    assertEquals("Test plan saved on this Portal only", r.state.toast?.text)
    r.engine.debugCommand(null, "clear", null, null, null, 1_000)
    assertNull(r.host.test)
  }

  @Test fun aPlanOverHomeAssistantsCeilingStaysOnThisPortal() {
    val r = live()
    val rooms = (0 until 1_400).map { CcRoom("r$it", "Room $it " + "n".repeat(180), CcRect(it % 40, it / 40, 1, 1), false, emptyList()) }
    r.engine.queueSavePlan(CcPlan(listOf(CcFloor("g", "G", rooms)), 0L)); r.sched.runDue()
    assertEquals(0, r.ws.types().count { it == "frontend/set_user_data" })
    assertEquals(1_400, r.host.plan?.floors?.single()?.rooms?.size)
  }

  @Test fun anOversizedPlanSavedWhileConnectedSaysItIsTooLargeInsteadOfPromisingACopy() {
    val r = live()
    val big = CcPlan(listOf(CcFloor("g", "G", (0 until 1_400).map {
      CcRoom("r$it", "Room $it " + "n".repeat(180), CcRect(it % 40, it / 40, 1, 1), false, emptyList())
    })), 0L)
    r.engine.queueSavePlan(big); r.sched.runDue()
    assertEquals(CcPhase.LIVE, r.state.connection.phase)
    val toast = requireNotNull(r.state.toast)
    val kb = (CcPlanJson.encodedBytes(r.host.plan!!) + 1023) / 1024
    assertEquals("Too large to save in Home Assistant · kept on this Portal ($kb KB, limit 256 KB). " +
        "Simplify the plan (fewer rooms or devices) to share it.", toast.text)
    assertTrue(toast.error)
    assertFalse(toast.text.contains("when connected"))
    assertEquals(0, r.ws.types().count { it == "frontend/set_user_data" })
  }

  @Test fun aPlanThatFitsSavedWhileOfflineStillPromisesTheCopy() {
    val r = CcEngineRig()
    r.engine.queueSavePlan(CcEngineRig.PLAN); r.sched.runDue()
    assertEquals("Saved on this Portal. It will copy to Home Assistant when connected.", r.state.toast?.text)
    assertFalse(r.state.toast!!.error)
  }

  @Test fun alertsReachTheRelayOnceAndFakeStatesNeedDryRunAndLive() {
    val r = live { dry = true }
    r.event("subscribe_entities", diff("binary_sensor.leak", "on"))
    r.advance(CcEngine.FLUSH_MS)
    val takeovers = r.host.alertPlans.flatMap { it.takeovers }
    assertEquals(listOf("binary_sensor.leak"), takeovers.map { it.entityId })
    r.advance(CcEngine.PING_S * 1000)
    assertEquals("seen alerts aren't taken over again", 1, r.host.alertPlans.flatMap { it.takeovers }.size)
    assertTrue(r.engine.debugCommand(null, null, null, null, CcFakeState.parse(JSONObject().put("slug", "x").put("state", "on")
        .put("deviceClass", "smoke")), 1_000).startsWith("fakeState binary_sensor.fake_x=on"))
    assertEquals("testAlert=smoke logged (dry run)", r.engine.debugCommand(null, null, "smoke", null, null, 1_000))
    assertEquals(true, r.host.banners.single().second)
    r.engine.debugCommand(false, null, null, null, null, 1_000)
    assertEquals("fakeState refused: turn dry run on first",
        r.engine.debugCommand(null, null, null, null, CcFakeState("binary_sensor.fake_y", "on", "smoke", "Y", 0), 1_000))
  }

  @Test fun aDismissedAlertStaysHiddenUntilItsTime() {
    val r = live()
    r.event("subscribe_entities", diff("binary_sensor.leak", "on")); r.advance(CcEngine.FLUSH_MS)
    val alert = r.state.alerts().single()
    r.engine.dismissAlert(alert, r.sched.now + 60_000); r.sched.runDue()
    assertTrue(r.state.alerts().isEmpty())
  }

  @Test fun cameraStreamsAnswerWithAnAbsoluteUrlOrNull() {
    val r = CcEngineRig()
    val urls = ArrayList<String?>()
    r.engine.cameraStream("camera.porch") { urls += it }; r.sched.runDue()
    r.connectLive()
    r.engine.cameraStream("camera.porch") { urls += it }; r.sched.runDue()
    r.reply("camera/stream", JSONObject().put("url", "/api/hls/a/master.m3u8"))
    r.engine.cameraStream("camera.porch") { urls += it }; r.sched.runDue()
    r.reply("camera/stream", ok = false, error = "no")
    r.engine.cameraStream("camera.porch") { urls += it }; r.sched.runDue()
    r.fail()
    assertEquals(listOf(null, "http://ha.test:8123/api/hls/a/master.m3u8", null, null), urls)
  }

  @Test fun theChargeWeekAsksForDailyStatisticsOfTheCarsEnergy() {
    val r = CcEngineRig()
    r.engine.setVisible(true); r.sched.runDue()
    r.authenticate()
    val car = { id: String, key: String -> JSONObject().put("entity_id", id).put("device_id", "car").put("platform", "tesla_fleet").put("translation_key", key) }
    r.reply("config/floor_registry/list", JSONArray()); r.reply("config/area_registry/list", JSONArray())
    r.reply("config/device_registry/list", JSONArray().put(JSONObject().put("id", "car").put("name", "Model Y")))
    r.reply("config/entity_registry/list", JSONArray().put(car("sensor.y_battery_level", "charge_state_battery_level"))
        .put(car("sensor.y_energy_added", "charge_state_charge_energy_added")))
    r.reply("frontend/get_user_data", JSONObject().put("value", JSONObject.NULL))
    r.engine.loadChargeWeek(); r.sched.runDue()
    val stats = r.ws.last("recorder/statistics_during_period")
    assertEquals("sensor.y_energy_added", stats.getJSONArray("statistic_ids").getString(0))
    assertEquals("day", stats.getString("period"))
    r.reply("recorder/statistics_during_period", JSONObject().put("sensor.y_energy_added", JSONArray()))
    assertNotNull(r.state.chargeWeek)
  }

  @Test fun debugTimesOutWhenTheEngineThreadDoesNotAnswer() {
    val stuck = object : CcScheduler {
      override fun execute(task: () -> Unit) = Unit
      override fun schedule(delayMs: Long, task: () -> Unit): CcTimer = error("unused")
      override fun repeat(periodMs: Long, task: () -> Unit): CcTimer = error("unused")
      override fun <T> call(timeoutMs: Long, task: () -> T): T = throw java.util.concurrent.TimeoutException()
    }
    val e = CcEngine(FakeCcHost(), FakeCcSockets(), stuck, RecordingCcLog()) { 0L }
    assertEquals("timeout", e.debugCommand(true, null, null, null, null, 1))
  }

  @Test fun aFailingTaskIsLoggedAndTheEngineKeepsGoing() {
    val r = CcEngineRig()
    r.engine.post { error("boom") }; r.sched.runDue()
    assertTrue("w control center task failed (IllegalStateException)" in r.log.lines)
    r.engine.setSignals(CcRoomSignals.NONE); r.sched.runDue()
    r.engine.install(); r.sched.runDue()
    assertEquals(1, r.sockets.opened.size)
  }
}
