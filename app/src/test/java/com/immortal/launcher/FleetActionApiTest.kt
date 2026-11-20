/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `POST /action`: each action and each allow-list it applies (ops, screens, orientations, plans). */
class FleetActionApiTest {
  private val host = FleetTestHost()
  private val api = FleetActionApi(host)

  private fun act(body: JSONObject) = api.handle(FleetReq.post("/action", body))
  private fun act(action: String, vararg kv: Pair<String, Any>) =
      act(JSONObject().put("action", action).also { o -> kv.forEach { (k, v) -> o.put(k, v) } })

  @Test fun onlyNamedActionsRun() {
    assertEquals("400 bad_json", FleetReq.outcome(api.handle(FleetReq.post("/action", "nope"))))
    for (name in listOf("", "shell", "Reboot", "REAFFIRM", "install", " reaffirm", "open_screen ")) {
      assertEquals(name, "400 unknown_action", FleetReq.outcome(act(JSONObject().put("action", name))))
    }
    assertEquals("400 unknown_action", FleetReq.outcome(act(JSONObject())))
    assertEquals("400 unknown_action", FleetReq.outcome(act(JSONObject().put("action", 5))))
    assertTrue(host.calls.isEmpty())
  }

  @Test fun reaffirmIdentifyAndReboot() {
    for (name in listOf("reaffirm", "identify")) {
      val o = FleetReq.json(act(name))
      assertTrue(o.getBoolean("ok"))
      assertEquals(name, o.getString("action"))
      assertEquals(2, o.length())
      assertEquals(1, host.called(name))
    }
    val reboot = FleetReq.json(act("reboot"))
    assertFalse(reboot.getBoolean("ok"))
    assertEquals("not_permitted", reboot.getString("result"))
  }

  @Test fun settingsSyncRunsOnlyAllowedOps() {
    for (op in listOf("pause", "resume", "adopt", "keep", "rejoin")) {
      val o = FleetReq.json(act("settings_sync", "op" to op))
      assertEquals("settings_sync", o.getString("action"))
      assertEquals("JOINED", o.getJSONObject("sync").getString("phase"))
      assertFalse(o.has("values"))
    }
    assertEquals(listOf("sync:pause", "sync:resume", "sync:adopt", "sync:keep", "sync:rejoin"), host.calls)
    // status (the default) and values only read.
    assertEquals(200, act("settings_sync").status)
    assertEquals(200, act("settings_sync", "op" to "status").status)
    assertEquals("fill", FleetReq.json(act("settings_sync", "op" to "values")).getJSONObject("values").getString("screensaver.fit"))
    assertEquals(5, host.calls.size)
    for (op in listOf("wipe", "", "PAUSE", "status ")) {
      assertEquals(op, "400 bad_op", FleetReq.outcome(act("settings_sync", "op" to op)))
    }
    assertEquals(5, host.calls.size)
  }

  @Test fun controlCenterOpensOnTheMapByDefault() {
    val o = FleetReq.json(act("open_control_center"))
    assertTrue(o.getBoolean("ok"))
    assertEquals("open_control_center", o.getString("action"))
    act("open_control_center", "screen" to "cameras")
    assertEquals(listOf("controlCenter:map", "controlCenter:cameras"), host.calls)
    host.openResult = false
    assertFalse(FleetReq.json(act("open_control_center")).getBoolean("ok"))
  }

  @Test fun phoneTestSessionOps() {
    val mint = FleetReq.json(act("phone_test_session", "op" to "mint", "householdGrant" to true))
    assertEquals("session-token", mint.getString("token"))
    assertTrue(mint.getBoolean("remoteEnabled"))
    act("phone_test_session", "op" to "mint")
    assertEquals(listOf("mint:true", "mint:false"), host.calls)
    assertEquals("123456", FleetReq.json(act("phone_test_session", "op" to "pin")).getString("pin"))
    assertEquals(3, FleetReq.json(act("phone_test_session", "op" to "revoke")).getInt("revoked"))
    assertTrue(FleetReq.json(act("phone_test_session", "op" to "revoke_id", "id" to "0123456789abcdef")).getBoolean("removed"))
    assertEquals(1, host.called("revokeSession:0123456789abcdef"))
    for (op in listOf("", "grant", "MINT")) assertEquals("400 bad_op", FleetReq.outcome(act("phone_test_session", "op" to op)))
    assertEquals("400 bad_op", FleetReq.outcome(act("phone_test_session")))
  }

  @Test fun revokeIdTakesOnly16LowerCaseHex() {
    for (id in listOf("", "0123456789abcde", "0123456789abcdef0", "0123456789ABCDEF", "0123456789abcdeg",
        "../../../../etc/x", "0123456789abcde\n")) {
      assertEquals(id, "400 bad_id", FleetReq.outcome(act("phone_test_session", "op" to "revoke_id", "id" to id)))
    }
    assertEquals("400 bad_id", FleetReq.outcome(act("phone_test_session", "op" to "revoke_id")))
    assertTrue(host.calls.none { it.startsWith("revokeSession") })
  }

  @Test fun ageTakes0To400Days() {
    for (days in listOf(-1L, 401L, Long.MAX_VALUE, Long.MIN_VALUE)) {
      assertEquals("$days", "400 bad_days", FleetReq.outcome(act("phone_test_session", "op" to "age", "days" to days)))
    }
    assertEquals("400 bad_days", FleetReq.outcome(act("phone_test_session", "op" to "age")))
    assertEquals("400 bad_days", FleetReq.outcome(act("phone_test_session", "op" to "age", "days" to "soon")))
    assertEquals(2, FleetReq.json(act("phone_test_session", "op" to "age", "days" to 0)).getInt("aged"))
    act("phone_test_session", "op" to "age", "days" to 400)
    assertEquals(listOf("age:0", "age:${TimeUnit.DAYS.toMillis(400)}"), host.calls)
    assertEquals(400L, FleetActionApi.MAX_TEST_AGE_DAYS)
  }

  @Test fun ccDebugPassesOnlyAllowedSwitches() {
    act("cc_debug")
    act("cc_debug", "dryRun" to true, "testPlan" to "load", "testAlert" to "leak_kitchen", "haUnreachable" to false)
    act("cc_debug", "dryRun" to false, "testPlan" to "clear")
    // Outside the allow-lists they read as "not given".
    act("cc_debug", "testPlan" to "wipe", "testAlert" to "Leak")
    act("cc_debug", "testAlert" to "a".repeat(33))
    act("cc_debug", "testAlert" to "a".repeat(32))
    assertEquals(listOf("cc:null:null:null:null:null", "cc:true:load:leak_kitchen:false:null", "cc:false:clear:null:null:null",
        "cc:null:null:null:null:null", "cc:null:null:null:null:null", "cc:null:null:${"a".repeat(32)}:null:null"), host.calls)
    val o = FleetReq.json(act("cc_debug", "dryRun" to true))
    assertTrue(o.getBoolean("ok"))
    assertEquals("dry=true", o.getString("result"))
  }

  @Test fun ccDebugFakeStateMustParse() {
    assertEquals("400 bad_fakeState", FleetReq.outcome(act("cc_debug", "fakeState" to "leak")))
    assertEquals("400 bad_fakeState", FleetReq.outcome(act("cc_debug", "fakeState" to JSONObject())))
    val fake = JSONObject().put("slug", "leak1").put("state", "on").put("deviceClass", "moisture").put("changedMinAgo", 0)
    assertEquals(200, act("cc_debug", "dryRun" to true, "fakeState" to fake).status)
    val parsed = requireNotNull(CcFakeState.parse(fake))
    assertEquals(1, host.called("cc:true:null:null:null:${parsed.entityId}"))
  }

  @Test fun screensaverTestSourceMinutesAre1To240WhenEnabling() {
    for (m in listOf(0, -5, 241, 100_000)) {
      assertEquals("$m", "400 bad_minutes", FleetReq.outcome(act("screensaver_test_source", "enabled" to true, "minutes" to m)))
    }
    assertTrue(host.calls.isEmpty())
    act("screensaver_test_source", "enabled" to true, "minutes" to 1)
    act("screensaver_test_source", "enabled" to true, "minutes" to 240, "night" to true)
    act("screensaver_test_source", "enabled" to true)
    // Disabling ignores minutes.
    act("screensaver_test_source", "enabled" to false, "minutes" to 0)
    assertEquals(listOf("enableTestSource:1:false", "reopenFrame:true", "enableTestSource:240:true", "reopenFrame:true",
        "enableTestSource:30:false", "reopenFrame:true", "disableTestSource", "reopenFrame:false"), host.calls)
  }

  @Test fun anUnappliedTestSourceLeavesTheFrameAlone() {
    host.testSourceApplied = false
    host.testSourceAvailable = false
    val o = FleetReq.json(act("screensaver_test_source", "enabled" to true))
    assertFalse(o.getBoolean("ok"))
    assertFalse(o.getBoolean("available"))
    assertTrue(o.getBoolean("active"))
    assertFalse(o.getBoolean("night"))
    assertEquals(42L, o.getLong("until"))
    assertEquals("classic", o.getString("digitalClockFace"))
    assertTrue(host.calls.none { it.startsWith("reopenFrame") })
  }

  @Test fun digitalClockPreviewNeedsATestSourceBuild() {
    host.testSourceAvailable = false
    assertEquals("400 unavailable", FleetReq.outcome(act("digital_clock_preview", "face" to "neon")))
    assertTrue(host.calls.isEmpty())
  }

  @Test fun digitalClockPreviewSetsOnlyKnownFacesAndRestoresExactly() {
    val o = FleetReq.json(act("digital_clock_preview", "face" to "neon"))
    assertEquals("neon", o.getString("face"))
    assertEquals("classic", o.getJSONObject("previousFace").getString("style"))
    assertEquals("light", o.getJSONObject("previousFace").getString("font"))
    assertEquals(listOf("face:neon", "openClockPreview"), host.calls)
    host.calls.clear()
    act("digital_clock_preview", "face" to "../evil", "open" to false)
    assertTrue(host.calls.isEmpty())
    // Restore writes back only the given, non-empty parts.
    act("digital_clock_preview", "open" to false, "restore" to JSONObject().put("style", "bold").put("font", "").put("glow", "none"))
    assertEquals(listOf("style:bold", "glow:none"), host.calls)
    host.calls.clear()
    act("digital_clock_preview", "open" to false, "restore" to JSONObject().put("color", "red").put("font", "mono"))
    assertEquals(listOf("font:mono", "color:red"), host.calls)
  }

  @Test fun whosThereDebugEchoesTheHost() {
    val o = FleetReq.json(act("whos_there_debug", "glance" to true))
    assertTrue(o.getBoolean("ok"))
    assertTrue(o.getJSONObject("result").getBoolean("echo"))
  }

  @Test fun openScreenOpensOnlyListedScreens() {
    assertEquals(10, FleetActionApi.SCREENS.size)
    for (screen in FleetActionApi.SCREENS) assertTrue(screen, FleetReq.json(act("open_screen", "screen" to screen)).getBoolean("ok"))
    assertEquals(FleetActionApi.SCREENS.map { "openScreen:$it:null" }, host.calls)
    host.calls.clear()
    for (screen in listOf("", "com.evil.Activity", "Settings", "calls ", "../store")) {
      assertFalse(screen, FleetReq.json(act("open_screen", "screen" to screen)).getBoolean("ok"))
    }
    assertFalse(FleetReq.json(act("open_screen")).getBoolean("ok"))
    assertTrue(host.calls.isEmpty())
    host.openResult = false
    assertFalse(FleetReq.json(act("open_screen", "screen" to "help")).getBoolean("ok"))
  }

  @Test fun aSettingsPageIsPassedOnlyForSettingsAndOnlyIfItExists() {
    act("open_screen", "screen" to "settings", "page" to "people")
    act("open_screen", "screen" to "settings", "page" to "nope")
    act("open_screen", "screen" to "help", "page" to "people")
    assertEquals(listOf("openScreen:settings:people", "openScreen:settings:null", "openScreen:help:null"), host.calls)
  }

  @Test fun orientationPassesOnlyKnownRequests() {
    for (r in FleetActionApi.ORIENTATIONS) {
      val o = FleetReq.json(act("orientation", "request" to r))
      assertTrue(o.getBoolean("ok"))
      assertEquals(r, o.getJSONObject("result").getString("request"))
    }
    for (r in listOf("sideways", "", "PORTRAIT")) act("orientation", "request" to r)
    act("orientation")
    assertEquals(FleetActionApi.ORIENTATIONS.map { "orientation:$it" } + List(4) { "orientation:null" }, host.calls)
    assertEquals(setOf("portrait", "landscape", "reverse_landscape", "auto", "forget"), FleetActionApi.ORIENTATIONS)
  }
}
