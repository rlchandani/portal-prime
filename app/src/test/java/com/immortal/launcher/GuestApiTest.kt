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

class GuestApiTest {
  private class FakePort : GuestPort {
    var values = JSONObject().put("ssid", "").put("security", "wpa")
    var password = ""
    var storageBroken = false
    var state = GuestModeState.OFF
    var started: GuestSchedule? = null
    var now = 1_000L
    override fun card() = JSONObject(values.toString())
    override fun applyCard(values: JSONObject) { values.keys().forEach { this.values.put(it, values.get(it)) } }
    override fun hasPassword() = password.isNotEmpty()
    override fun setPassword(value: String) = !storageBroken && true.also { password = value }
    override fun mode() = state
    override fun start(schedule: GuestSchedule) { started = schedule; state = GuestModeState(true, schedule.endMs(now, java.time.ZoneOffset.UTC)) }
    override fun end() { state = GuestModeState.OFF }
    override fun nowMs() = now
  }

  private val port = FakePort()
  private val api = GuestApi(port)
  private fun post(body: String) = api.handle("POST", JSONObject(body))

  @Test fun getNeverReturnsThePassword() {
    port.values.put("ssid", "Home")
    port.password = "hunter2-secret"
    val (status, json) = api.handle("GET", JSONObject())
    assertEquals(200, status)
    assertFalse(json.toString().contains("hunter2"))
    assertTrue(json.getBoolean("hasPassword"))
    assertTrue(json.getBoolean("ready"))
    assertFalse(json.getJSONObject("mode").getBoolean("active"))
  }

  @Test fun saveSetsCardAndPasswordButReadBackHidesIt() {
    val (status, json) = post("""{"action":"save","card":{"ssid":"Guest Net","doorCode":"4821"},"password":"pa;ss word"}""")
    assertEquals(200, status)
    assertEquals("pa;ss word", port.password)
    assertEquals("Guest Net", json.getJSONObject("card").getString("ssid"))
    assertFalse(json.toString().contains("pa;ss"))
  }

  @Test fun passwordCanBeCleared() {
    port.password = "abcdefgh"
    assertEquals(200, post("""{"action":"save","clearPassword":true}""").first)
    assertEquals("", port.password)
  }

  @Test fun invalidInputIsRejectedWithoutWriting() {
    assertEquals("unknown_field", post("""{"action":"save","card":{"password":"x"}}""").second.getString("error"))
    assertEquals("invalid_ssid", post("""{"action":"save","card":{"ssid":"a\nb"}}""").second.getString("error"))
    assertEquals("invalid_security", post("""{"action":"save","card":{"security":"wpa9"}}""").second.getString("error"))
    assertEquals("invalid_hidePassword", post("""{"action":"save","card":{"hidePassword":"yes"}}""").second.getString("error"))
    assertEquals("invalid_password", post("""{"action":"save","password":""}""").second.getString("error"))
    assertEquals("invalid_password", post("""{"action":"save","password":12345678}""").second.getString("error"))
    assertEquals("conflicting_password", post("""{"action":"save","password":"abc","clearPassword":true}""").second.getString("error"))
    assertEquals("nothing_to_save", post("""{"action":"save"}""").second.getString("error"))
    assertEquals("", port.values.getString("ssid"))
    assertEquals("", port.password)
  }

  @Test fun brokenSecretStorageIsReported() {
    port.storageBroken = true
    assertEquals(503, post("""{"action":"save","password":"abcdefgh"}""").first)
  }

  @Test fun startHonoursSchedules() {
    assertEquals(200, post("""{"action":"start","days":2}""").first)
    assertEquals(GuestSchedule.ForDays(2), port.started)
    post("""{"action":"start","untilHour":11,"untilMinute":0}""")
    assertEquals(GuestSchedule.UntilTomorrow(11, 0), port.started)
    val (_, json) = post("""{"action":"start"}""")
    assertEquals(GuestSchedule.UntilEnded, port.started)
    assertTrue(json.getJSONObject("mode").getBoolean("active"))
    assertTrue(json.getJSONObject("mode").isNull("until"))
  }

  @Test fun badSchedulesAreRejected() {
    listOf("""{"action":"start","days":0}""", """{"action":"start","days":"2"}""", """{"action":"start","days":1.5}""",
        """{"action":"start","untilHour":11}""", """{"action":"start","days":1,"untilHour":11,"untilMinute":0}""")
        .forEach { assertEquals(it, 400, post(it).first) }
    assertNull(port.started)
  }

  @Test fun endTurnsItOff() {
    port.state = GuestModeState(true, null)
    val (_, json) = post("""{"action":"end"}""")
    assertFalse(json.getJSONObject("mode").getBoolean("active"))
  }

  @Test fun expiredModeReadsAsOff() {
    port.state = GuestModeState(true, 500L)
    assertFalse(api.handle("GET", JSONObject()).second.getJSONObject("mode").getBoolean("active"))
  }

  @Test fun unknownActionsAndMethods() {
    assertEquals("missing_action", post("{}").second.getString("error"))
    assertEquals("unknown_action", post("""{"action":"nuke"}""").second.getString("error"))
    assertEquals(405, api.handle("DELETE", JSONObject()).first)
  }

  @Test fun openNetworkIsReadyWithoutPassword() {
    port.values.put("ssid", "Cafe").put("security", "none")
    assertTrue(api.handle("GET", JSONObject()).second.getBoolean("ready"))
  }
}
