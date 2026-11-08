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

class OtaWireTest {
  private val available = OtaManifest("com.immortal.launcher.debug", 224, "1.229", "ab".repeat(32), 1_000,
      "https://github.com/x", 24, "Wireless updates")
  private val base = OtaState(installedVersionCode = 223, installedVersionName = "1.228")

  @Test fun decodesActions() {
    assertEquals(OtaRequest(OtaAction.CHECK, force = false), OtaWire.decode(JSONObject("""{"action":"check"}""")))
    assertEquals(OtaRequest(OtaAction.INSTALL, force = true),
        OtaWire.decode(JSONObject("""{"action":"install","force":true}""")))
    assertNull(OtaWire.decode(JSONObject("""{"action":"reboot"}""")))
    assertNull(OtaWire.decode(JSONObject("{}")))
  }

  @Test fun stateJsonCarriesVersionsAndResult() {
    val json = OtaWire.toJson(base.copy(phase = OtaPhase.AVAILABLE, available = available,
        lastCheckAtMs = 1_700_000_000_000, waitingFor = OtaWait.INSTALL_WINDOW))
    assertEquals(223L, json.getJSONObject("installed").getLong("versionCode"))
    assertEquals("1.228", json.getJSONObject("installed").getString("versionName"))
    assertEquals("available", json.getString("status"))
    assertEquals("1.229", json.getJSONObject("available").getString("versionName"))
    assertEquals(224L, json.getJSONObject("available").getLong("versionCode"))
    assertEquals("Wireless updates", json.getJSONObject("available").getString("notes"))
    assertEquals(1_700_000_000_000, json.getLong("lastCheckAt"))
    assertEquals("install_window", json.getString("waitingFor"))
    assertTrue(json.isNull("error"))
    assertFalse(json.getBoolean("devMode"))
  }

  @Test fun idleStateJsonHasNulls() {
    val json = OtaWire.toJson(base.copy(phase = OtaPhase.FAILED, error = OtaError.NETWORK))
    assertEquals("failed", json.getString("status"))
    assertTrue(json.isNull("available"))
    assertTrue(json.isNull("lastCheckAt"))
    assertEquals("network", json.getJSONObject("error").getString("code"))
    assertTrue(json.getJSONObject("error").getString("message").isNotBlank())
  }

  @Test fun settingsRowCopy() {
    val upToDate = OtaCopy.row(base.copy(phase = OtaPhase.UP_TO_DATE, lastCheckAtMs = 1), "3:12 PM")
    assertEquals("Hearthpane 1.228", upToDate.title)
    assertEquals("Up to date · Checked 3:12 PM", upToDate.subtitle)
    assertEquals("Check for updates", upToDate.action)
    assertTrue(upToDate.enabled)

    val pending = OtaCopy.row(base.copy(phase = OtaPhase.AVAILABLE, available = available), null)
    assertEquals("Hearthpane 1.229 available · Wireless updates", pending.subtitle)
    assertEquals("Update now", pending.action)

    val checking = OtaCopy.row(base.copy(phase = OtaPhase.CHECKING), null)
    assertFalse(checking.enabled)

    val failed = OtaCopy.row(base.copy(phase = OtaPhase.FAILED, error = OtaError.SIGNATURE), "9:00 AM")
    assertEquals("${OtaError.SIGNATURE.message} · Checked 9:00 AM", failed.subtitle)

    val dev = OtaCopy.row(base.copy(devMode = true), null)
    assertFalse(dev.enabled)
    assertEquals(OtaError.DEV_MODE.message, dev.subtitle)
  }

  @Test fun userFacingCopySaysHearthpane() {
    OtaError.values().forEach { assertFalse(it.name, it.message.contains("Immortal")) }
  }
}
