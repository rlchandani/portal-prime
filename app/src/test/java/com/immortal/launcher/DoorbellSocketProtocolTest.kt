/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.DoorbellSocketProtocol.Out
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Auth, subscribe (trigger first, events fallback), event parsing, backoff and URLs. */
class DoorbellSocketProtocolTest {
  private val watched = listOf("event.front_doorbell", "binary_sensor.porch_motion")
  private fun protocol() = DoorbellSocketProtocol("test-token", watched)
  private fun sent(out: List<Out>) = JSONObject((out.single() as Out.Send).text)

  @Test fun authenticatesThenSubscribesToJustTheWatchedEntities() {
    val p = protocol()
    val auth = sent(p.onMessage("""{"type":"auth_required","ha_version":"2026.11"}"""))
    assertEquals("auth", auth.getString("type"))
    assertEquals("test-token", auth.getString("access_token"))
    val sub = sent(p.onMessage("""{"type":"auth_ok"}"""))
    assertEquals("subscribe_trigger", sub.getString("type"))
    assertEquals(DoorbellSocketProtocol.TRIGGER_ID, sub.getInt("id"))
    val trigger = sub.getJSONObject("trigger")
    assertEquals("state", trigger.getString("platform"))
    assertEquals(watched, (0 until 2).map { trigger.getJSONArray("entity_id").getString(it) })
    assertEquals(listOf(Out.Ready), p.onMessage("""{"id":1,"type":"result","success":true}"""))
  }

  @Test fun refusedTriggerFallsBackToStateChangedEvents() {
    val p = protocol()
    val fallback = sent(p.onMessage("""{"id":1,"type":"result","success":false,"error":{"code":"unauthorized"}}"""))
    assertEquals("subscribe_events", fallback.getString("type"))
    assertEquals("state_changed", fallback.getString("event_type"))
    assertEquals(listOf(Out.Ready), p.onMessage("""{"id":2,"type":"result","success":true}"""))
    assertEquals(listOf(Out.Failed), p.onMessage("""{"id":2,"type":"result","success":false}"""))
  }

  @Test fun badTokenIsReported() {
    assertEquals(listOf(Out.AuthFailed), protocol().onMessage("""{"type":"auth_invalid","message":"Invalid access token"}"""))
  }

  @Test fun triggerEventsBecomeChanges() {
    val text = """{"id":1,"type":"event","event":{"variables":{"trigger":{"platform":"state",
      "entity_id":"binary_sensor.porch_motion","from_state":{"state":"off"},"to_state":{"state":"on"}}}}}"""
    val change = protocol().onMessage(text).single() as Out.Change
    assertEquals("binary_sensor.porch_motion", change.entityId)
    assertEquals("off", change.old!!.getString("state"))
    assertEquals("on", change.new!!.getString("state"))
  }

  @Test fun stateChangedEventsAreFilteredToWatchedEntities() {
    fun event(entity: String) = """{"id":2,"type":"event","event":{"event_type":"state_changed","data":{
      "entity_id":"$entity","old_state":{"state":"a"},"new_state":{"state":"b"}}}}"""
    val p = protocol()
    assertEquals("event.front_doorbell", (p.onMessage(event("event.front_doorbell")).single() as Out.Change).entityId)
    assertTrue(p.onMessage(event("sensor.power")).isEmpty())
  }

  @Test fun garbageIsIgnored() {
    assertTrue(protocol().onMessage("not json").isEmpty())
    assertTrue(protocol().onMessage("""{"type":"pong","id":9}""").isEmpty())
  }

  @Test fun backoffDoublesAndCaps() {
    assertEquals(2_000L, DoorbellBackoff.delayMs(0))
    assertEquals(4_000L, DoorbellBackoff.delayMs(1))
    assertEquals(32_000L, DoorbellBackoff.delayMs(4))
    assertEquals(DoorbellBackoff.MAX_MS, DoorbellBackoff.delayMs(5))
    assertEquals(DoorbellBackoff.MAX_MS, DoorbellBackoff.delayMs(500))
    assertTrue(DoorbellBackoff.AUTH_RETRY_MS > DoorbellBackoff.MAX_MS)
  }

  @Test fun urls() {
    assertEquals("ws://ha.local:8123/api/websocket", DoorbellUrls.websocket("http://ha.local:8123/"))
    assertEquals("wss://ha.example/api/websocket", DoorbellUrls.websocket("https://ha.example"))
    assertNull(DoorbellUrls.websocket("ha.local:8123"))
    assertEquals("http://ha/api/camera_proxy_stream/camera.door", DoorbellUrls.stream("http://ha/", "camera.door"))
    assertEquals("http://ha/api/camera_proxy/camera.door", DoorbellUrls.snapshot("http://ha", "camera.door"))
  }
}
