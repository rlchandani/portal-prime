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
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlCenterProtocolTest {
  private fun sent(o: CcProtocol.Out) = JSONObject((o as CcProtocol.Out.Send).text)

  @Test fun auth_sendsTokenOnlyInAuthMessage() {
    val p = CcProtocol("secret-token")
    val out = p.onMessage("""{"type":"auth_required"}""")
    val auth = sent(out.single())
    assertEquals("auth", auth.getString("type"))
    assertEquals("secret-token", auth.getString("access_token"))
    assertEquals(CcProtocol.Out.AuthOk, p.onMessage("""{"type":"auth_ok"}""").single())
    assertEquals(CcProtocol.Out.AuthFailed, p.onMessage("""{"type":"auth_invalid"}""").single())
    p.registry().forEach { assertFalse(it.text.contains("secret-token")) }
  }

  @Test fun results_routeByRequestKind() {
    val p = CcProtocol("t")
    val reqs = p.registry()
    assertEquals(listOf("config/floor_registry/list", "config/area_registry/list", "config/device_registry/list", "config/entity_registry/list"),
        reqs.map { JSONObject(it.text).getString("type") })
    val id = JSONObject(reqs[1].text).getInt("id")
    val r = p.onMessage("""{"id":$id,"type":"result","success":true,"result":[]}""").single() as CcProtocol.Out.Result
    assertEquals(CcProtocol.Kind.AREAS, r.req.kind)
    assertTrue(r.ok)
    assertTrue(p.onMessage("""{"id":$id,"type":"result","success":true}""").isEmpty())
  }

  @Test fun subscribe_entitiesThenEventsAreEntities() {
    val p = CcProtocol("t")
    val sub = p.subscribe(setOf("light.b", "light.a"))
    val msg = JSONObject(sub.text)
    assertEquals("subscribe_entities", msg.getString("type"))
    assertEquals("light.a", msg.getJSONArray("entity_ids").getString(0))
    val id = msg.getInt("id")
    assertEquals(CcProtocol.Out.Subscribed, p.onMessage("""{"id":$id,"type":"result","success":true}""").single())
    val ev = p.onMessage("""{"id":$id,"type":"event","event":{"a":{"light.a":{"s":"on","a":{}}}}}""").single()
    assertTrue(ev is CcProtocol.Out.Entities)
  }

  @Test fun subscribeRefused_fallsBackToGetStatesAndStateChanged() {
    val p = CcProtocol("t")
    val id = JSONObject(p.subscribe(setOf("light.a")).text).getInt("id")
    val fb = p.onMessage("""{"id":$id,"type":"result","success":false,"error":{"code":"unknown_command","message":"x"}}""")
    assertEquals(listOf("get_states", "subscribe_events"), fb.map { sent(it).getString("type") })
    val statesId = sent(fb[0]).getInt("id")
    val eventsId = sent(fb[1]).getInt("id")
    val snap = p.onMessage("""{"id":$statesId,"type":"result","success":true,"result":[
      {"entity_id":"light.a","state":"on","attributes":{}},{"entity_id":"light.other","state":"on","attributes":{}}]}""").single()
    assertEquals(listOf("light.a"), (snap as CcProtocol.Out.Snapshot).entities.map { it.id })
    val ignored = p.onMessage("""{"id":$eventsId,"type":"event","event":{"data":{"entity_id":"light.other","new_state":{"entity_id":"light.other","state":"off"}}}}""")
    assertTrue(ignored.isEmpty())
    val changed = p.onMessage("""{"id":$eventsId,"type":"event","event":{"data":{"entity_id":"light.a","new_state":null}}}""").single()
    assertEquals(CcProtocol.Out.Changed("light.a", null), changed)
  }

  @Test fun call_carriesTargetAndData_andErrorsCarryMessage() {
    val p = CcProtocol("t")
    val call = CcCalls.brightness(CcEntity("light.k", "on"), 40)
    val msg = JSONObject(p.call(call, "c1").text)
    assertEquals("call_service", msg.getString("type"))
    assertEquals("light", msg.getString("domain"))
    assertEquals("turn_on", msg.getString("service"))
    assertEquals(40, msg.getJSONObject("service_data").getInt("brightness_pct"))
    assertEquals("light.k", msg.getJSONObject("target").getString("entity_id"))
    val r = p.onMessage("""{"id":${msg.getInt("id")},"type":"result","success":false,"error":{"message":"Invalid code"}}""").single() as CcProtocol.Out.Result
    assertEquals("c1", r.req.key)
    assertEquals("Invalid code", r.error)
    assertFalse(r.ok)
  }

  @Test fun ping_pongClearsTimer() {
    val p = CcProtocol("t")
    val id = JSONObject(p.ping(1000L).text).getInt("id")
    assertEquals(1000L, p.pingSentAt)
    assertTrue(p.onMessage("""{"id":999,"type":"pong"}""").isEmpty())
    assertEquals(CcProtocol.Out.Pong, p.onMessage("""{"id":$id,"type":"pong"}""").single())
    assertEquals(0L, p.pingSentAt)
  }

  @Test fun resubscribe_unsubscribesOldFirst() {
    val p = CcProtocol("t")
    val first = JSONObject(p.subscribe(setOf("a.b")).text).getInt("id")
    val out = p.resubscribe(null).map { JSONObject(it.text) }
    assertEquals("unsubscribe_events", out[0].getString("type"))
    assertEquals(first, out[0].getInt("subscription"))
    assertFalse(out[1].has("entity_ids"))
  }

  @Test fun planAndStatsRequests() {
    val p = CcProtocol("t")
    assertEquals(CcPlanJson.HA_KEY, JSONObject(p.getPlan().text).getString("key"))
    val set = JSONObject(p.setPlan(JSONObject().put("v", 1)).text)
    assertEquals("frontend/set_user_data", set.getString("type"))
    val st = JSONObject(p.statistics("today", listOf("sensor.e"), "a", "b", "hour").text)
    assertEquals("hour", st.getString("period"))
    assertEquals("change", st.getJSONArray("types").getString(0))
  }

  @Test fun garbageIsIgnored() {
    assertTrue(CcProtocol("t").onMessage("not json").isEmpty())
    assertTrue(CcProtocol("t").onMessage("""{"type":"event","id":5,"event":{}}""").isEmpty())
  }
}
