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

/** `/info`, `/dev`, `/config`, `/calendar`, `/screensaver`, `/diag` and `/logcat` on a fake Portal. */
class FleetDeviceApiTest {
  private val host = FleetTestHost()
  private val catalog = FleetCatalog()
  private val api = FleetDeviceApi(host, host, host, catalog)

  @Test fun infoReportsIdentityPresenceInstallModeAndCapabilities() {
    host.plan = InstallLifecycle.Plan("dialog", daemonAvailable = false, legacy = true, dialogFixed = true, paused = false)
    val o = FleetReq.json(api.info())
    assertTrue(o.getBoolean("ok"))
    assertEquals("Kitchen", o.getString("name"))
    assertEquals("Portal+", o.getString("model"))
    assertEquals("aloha", o.getString("device"))
    assertEquals(29, o.getInt("apiLevel"))
    assertEquals(277L, o.getJSONObject("app").getLong("versionCode"))
    assertEquals("1.283", o.getJSONObject("app").getString("versionName"))
    assertEquals("10.0.0.2", o.getString("ip"))
    assertEquals(8723, o.getInt("port"))
    val presence = o.getJSONObject("presence")
    assertEquals("PRESENT", presence.getString("presence"))
    assertEquals("INTERACTIVE", presence.getString("screen"))
    assertTrue(presence.getBoolean("confident"))
    val install = o.getJSONObject("install")
    assertEquals("dialog", install.getString("mode"))
    assertFalse(install.getBoolean("daemonAvailable"))
    assertTrue(install.getBoolean("legacyInstaller"))
    assertTrue(install.getBoolean("dialogFixed"))
    assertFalse(install.getBoolean("paused"))
    assertFalse(install.getBoolean("autoConfirm"))
    // The dashboard poll reuses the last sample; only /diag samples fresh.
    assertFalse(o.getJSONObject("memoryHealth").getBoolean("fresh"))
    assertTrue(o.getBoolean("canWriteSecureSettings"))
    assertFalse(o.getBoolean("devMode"))
    val caps = o.getJSONObject("capabilities")
    for (k in listOf("files", "diag", "calendar", "screensaver", "dev", "performance", "backup", "logcat")) assertTrue(k, caps.getBoolean(k))
    assertEquals(0, o.getJSONArray("apps").length())
  }

  @Test fun infoWithoutAnIpOrLogsSaysSo() {
    host.ip = null
    host.canReadLogs = false
    val o = FleetReq.json(api.info())
    assertEquals("", o.getString("ip"))
    assertFalse(o.getJSONObject("capabilities").getBoolean("logcat"))
  }

  @Test fun infoListsOnlyInstalledCatalogAppsWithACheapUpdateHint() {
    catalog.apps = listOf(FleetReq.catalogApp("a.pinned", versionCode = 5), FleetReq.catalogApp("b.current", versionCode = 3),
        FleetReq.catalogApp("c.unpinned"), FleetReq.catalogApp("d.missing", versionCode = 9))
    host.versions = mapOf("a.pinned" to 4L, "b.current" to 3L, "c.unpinned" to 1L)
    val apps = FleetReq.json(api.info()).getJSONArray("apps")
    assertEquals(3, apps.length())
    val a = apps.getJSONObject(0)
    assertEquals("a.pinned", a.getString("packageName"))
    assertEquals("App a.pinned", a.getString("name"))
    assertEquals(4L, a.getLong("installed"))
    assertEquals(5L, a.getLong("catalogVersion"))
    assertTrue(a.getBoolean("updateAvailable"))
    assertFalse(apps.getJSONObject(1).getBoolean("updateAvailable"))
    val c = apps.getJSONObject(2)
    assertFalse(c.has("catalogVersion"))
    assertFalse(c.has("updateAvailable"))
  }

  @Test fun infoFallsBackToPerPackageLookupsWithoutASnapshot() {
    catalog.apps = listOf(FleetReq.catalogApp("a"), FleetReq.catalogApp("b"))
    host.versions = null
    host.fallbackVersions["b"] = 7L
    val apps = FleetReq.json(api.info()).getJSONArray("apps")
    assertEquals(1, apps.length())
    assertEquals(7L, apps.getJSONObject(0).getLong("installed"))
  }

  @Test fun devReadsAndTogglesOnlyWhenAsked() {
    val read = FleetReq.json(api.dev(FleetReq.get("/dev")))
    assertEquals(DevMode.statusJson(false, 277L, "1.283").toString(), read.getJSONObject("dev").toString())
    val on = FleetReq.json(api.dev(FleetReq.post("/dev", JSONObject().put("enabled", true))))
    assertTrue(on.getJSONObject("dev").getBoolean("enabled"))
    // A body without "enabled" leaves it alone; a non-boolean reads as false (optBoolean).
    api.dev(FleetReq.post("/dev", "{}"))
    assertTrue(host.devModeOn)
    api.dev(FleetReq.post("/dev", JSONObject().put("enabled", "yes")))
    assertFalse(host.devModeOn)
    assertEquals(listOf("setDevMode:true", "setDevMode:false"), host.calls.filter { it.startsWith("setDevMode") })
    assertEquals("400 bad_json", FleetReq.outcome(api.dev(FleetReq.post("/dev", "{"))))
    assertEquals("405 method_not_allowed", FleetReq.outcome(api.dev(FleetReq.of("PUT", "/dev"))))
  }

  @Test fun configSetsTheNameAndStringifiesFreeFormValues() {
    val o = FleetReq.json(api.config(FleetReq.post("/config",
        JSONObject().put("name", "Den").put("set", JSONObject().put("wall", "north").put("n", 3).put("b", true)))))
    assertEquals("Den", o.getString("name"))
    val cfg = o.getJSONObject("config")
    assertEquals("north", cfg.getString("wall"))
    assertEquals("3", cfg.getString("n"))
    assertEquals("true", cfg.getString("b"))
    // Without a name or set it only reads back.
    val read = FleetReq.json(api.config(FleetReq.post("/config", "")))
    assertEquals("Den", read.getString("name"))
    assertEquals(1, host.called("setName:Den"))
    assertEquals("400 bad_json", FleetReq.outcome(api.config(FleetReq.post("/config", "[]"))))
    // A non-object "set" is ignored.
    assertEquals(200, api.config(FleetReq.post("/config", "{\"set\":5}")).status)
  }

  @Test fun calendarReadsAndAppliesThenReadsBack() {
    assertFalse(FleetReq.json(api.calendar(FleetReq.get("/calendar"))).getJSONObject("calendar").getBoolean("enabled"))
    val o = FleetReq.json(api.calendar(FleetReq.post("/calendar", JSONObject().put("url", "https://x/cal.ics"))))
    assertEquals("[\"url\"]", o.getJSONArray("applied").toString())
    assertTrue(o.getJSONObject("calendar").getBoolean("enabled"))
    assertEquals("400 bad_json", FleetReq.outcome(api.calendar(FleetReq.post("/calendar", "nope"))))
    assertEquals("405 method_not_allowed", FleetReq.outcome(api.calendar(FleetReq.of("DELETE", "/calendar"))))
  }

  @Test fun screensaverAppliesThroughTheHostAndReadsBack() {
    assertEquals("fill", FleetReq.json(api.screensaver(FleetReq.get("/screensaver"))).getJSONObject("screensaver").getString("fit"))
    val o = FleetReq.json(api.screensaver(FleetReq.post("/screensaver", JSONObject().put("fit", "fit"))))
    assertEquals("[\"fit\"]", o.getJSONArray("applied").toString())
    assertEquals("fit", o.getJSONObject("screensaver").getString("fit"))
    assertEquals(1, host.called("applyScreensaver"))
    assertEquals("400 bad_json", FleetReq.outcome(api.screensaver(FleetReq.post("/screensaver", "nope"))))
    assertEquals("405 method_not_allowed", FleetReq.outcome(api.screensaver(FleetReq.of("PUT", "/screensaver"))))
    assertEquals(1, host.called("applyScreensaver"))
  }

  @Test fun diagAddsAFreshMemorySample() {
    val o = FleetReq.json(api.diag())
    assertEquals(5, o.getInt("uptimeSec"))
    assertTrue(o.getJSONObject("memoryHealth").getBoolean("fresh"))
  }

  @Test fun logcatStreamsPlainTextWithItsLength() {
    val resp = api.logcat(FleetReq.get("/logcat", "lines=20"))
    assertEquals(200, resp.status)
    assertEquals("text/plain; charset=utf-8", resp.contentType)
    assertEquals(host.logText.length.toLong(), resp.streamLength)
    assertEquals(host.logText, FleetReq.text(resp))
    api.logcat(FleetReq.get("/logcat"))
    api.logcat(FleetReq.get("/logcat", "lines=lots"))
    assertEquals(listOf("logcat:20", "logcat:500", "logcat:500"), host.calls.filter { it.startsWith("logcat") })
  }

  @Test fun logcatLengthCountsUtf8Bytes() {
    host.logText = "naïve ✓"
    val resp = api.logcat(FleetReq.get("/logcat"))
    assertEquals(host.logText.toByteArray(Charsets.UTF_8).size.toLong(), resp.streamLength)
  }
}
