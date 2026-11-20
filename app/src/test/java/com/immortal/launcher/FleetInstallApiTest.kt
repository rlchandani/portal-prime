/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.pm.PackageInstaller
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `/apps`, `/install` and `/update`: validation, the dev-mode gate, URL policy and the install paths. */
class FleetInstallApiTest {
  private val dir: File = Files.createTempDirectory("fleet-install").toFile()
  private val host = FleetTestHost().apply { cacheDir = dir }
  private val catalog = FleetCatalog()
  private val api = FleetInstallApi(host, host, FleetTestFiles(), catalog, storeInstallTimeoutMs = 2_000, updateCheckTimeoutMs = 50)

  @After fun cleanUp() { dir.deleteRecursively() }

  private fun install(body: JSONObject) = api.install(FleetReq.post("/install", body))
  private fun update(body: JSONObject) = api.update(FleetReq.post("/update", body))
  private val dialogPlan = InstallLifecycle.Plan("dialog", daemonAvailable = false, legacy = false, dialogFixed = true, paused = false)

  @Test fun appsNeedsTheCatalogAndReportsInstalledState() {
    assertEquals("503 catalog_not_ready", FleetReq.outcome(api.apps()))
    catalog.apps = listOf(FleetReq.catalogApp("a", versionCode = 3), FleetReq.catalogApp("b"))
    host.versions = mapOf("a" to 1L)
    val apps = FleetReq.json(api.apps()).getJSONArray("apps")
    val a = apps.getJSONObject(0)
    assertEquals("App a", a.getString("name"))
    assertEquals("a", a.getString("packageName"))
    assertEquals("fdroid", a.getString("source"))
    assertEquals("Tools", a.getString("category"))
    assertTrue(a.getBoolean("installed"))
    assertEquals(3L, a.getLong("versionCode"))
    assertFalse(apps.getJSONObject(1).getBoolean("installed"))
    assertFalse(apps.getJSONObject(1).has("versionCode"))
    // Without a snapshot it asks per package.
    host.versions = null
    host.fallbackVersions["b"] = 1L
    val fallback = FleetReq.json(api.apps()).getJSONArray("apps")
    assertFalse(fallback.getJSONObject(0).getBoolean("installed"))
    assertTrue(fallback.getJSONObject(1).getBoolean("installed"))
  }

  @Test fun installValidatesItsBody() {
    assertEquals("400 bad_json", FleetReq.outcome(api.install(FleetReq.post("/install", "nope"))))
    assertEquals("400 packageName_required", FleetReq.outcome(api.install(FleetReq.post("/install", ""))))
    assertEquals("400 packageName_required", FleetReq.outcome(install(JSONObject().put("packageName", " "))))
    assertEquals("503 catalog_not_ready", FleetReq.outcome(install(JSONObject().put("packageName", "a"))))
    catalog.apps = listOf(FleetReq.catalogApp("a"))
    assertEquals("404 unknown_package", FleetReq.outcome(install(JSONObject().put("packageName", "zz"))))
    assertTrue(host.calls.none { it.startsWith("storeInstall") || it.startsWith("download") })
  }

  @Test fun aRequestUrlMustBeOnAnAllowedHttpsHost() {
    for (url in listOf("http://f-droid.org/a.apk", "https://evil.example/a.apk", "https://192.168.1.10/a.apk",
        "https://f-droid.org:8443/a.apk", "file:///sdcard/a.apk", "ftp://f-droid.org/a.apk", "not a url", "https://user@f-droid.org/a.apk", "/relative/a.apk")) {
      assertEquals(url, "400 apk_url_not_allowed", FleetReq.outcome(install(JSONObject().put("packageName", "a").put("apkUrl", url))))
    }
    assertTrue(host.calls.none { it.startsWith("download") || it.startsWith("storeInstall") })
  }

  @Test fun aCatalogHostIsAllowedForARequestUrl() {
    catalog.apps = listOf(FleetReq.catalogApp("c", apkUrl = "https://apps.example.org/c.apk"))
    host.plan = dialogPlan
    val o = FleetReq.json(install(JSONObject().put("packageName", "x.y").put("apkUrl", "https://apps.example.org/x.apk")))
    assertEquals("installed", o.getString("result"))
    assertEquals(1, host.called("download:x.y:https://apps.example.org/x.apk"))
  }

  @Test fun aRequestUrlInstallsTheNamedPackageViaTheStore() {
    val o = FleetReq.json(install(JSONObject().put("packageName", "x.y").put("apkUrl", "https://f-droid.org/repo/x.apk")))
    assertTrue(o.getBoolean("ok"))
    assertEquals("x.y", o.getString("packageName"))
    assertEquals("installed", o.getString("result"))
    assertEquals("silent", o.getString("mode"))
    assertEquals(1, host.called("storeInstall:x.y"))
  }

  @Test fun theDeviceApkPathNeedsDevMode() {
    val apk = File(dir, "local.apk").apply { writeText("apk") }
    assertEquals("403 dev_mode_required", FleetReq.outcome(install(JSONObject().put("path", apk.path))))
    host.devModeOn = true
    assertEquals("404 file_not_found", FleetReq.outcome(install(JSONObject().put("path", File(dir, "none.apk").path))))
    assertEquals("404 file_not_found", FleetReq.outcome(install(JSONObject().put("path", dir.path))))
    // No package given and none in the archive.
    assertEquals("400 packageName_required", FleetReq.outcome(install(JSONObject().put("path", apk.path))))
    assertTrue(host.calls.none { it.startsWith("daemonInstall") || it.startsWith("dialogInstall") })
  }

  @Test fun theDeviceApkInstallsSilentlyOrByDialog() {
    val apk = File(dir, "local.apk").apply { writeText("apk") }
    host.devModeOn = true
    host.archivePackage = "from.archive"
    val silent = FleetReq.json(install(JSONObject().put("path", apk.path)))
    assertEquals("from.archive", silent.getString("packageName"))
    assertEquals("installed", silent.getString("result"))
    assertEquals(1, host.called("daemonInstall:local.apk:from.archive"))
    host.daemonResult = false
    assertEquals("failed", FleetReq.json(install(JSONObject().put("path", apk.path).put("packageName", "given"))).getString("result"))
    assertEquals(1, host.called("daemonInstall:local.apk:given"))
    host.plan = dialogPlan
    host.dialogOutcome = HeadlessInstallOutcome.ConfirmTimeout(1000, 1, tapped = false)
    val dialog = FleetReq.json(install(JSONObject().put("path", apk.path)))
    assertEquals("confirm_failed", dialog.getString("result"))
    assertEquals("dialog", dialog.getString("mode"))
    // The pushed APK stays (the operator owns it).
    assertTrue(apk.isFile)
    assertEquals(3, host.called("invalidate"))
  }

  @Test fun aPausedPortalRefusesEveryInstall() {
    host.plan = InstallLifecycle.Plan("paused", daemonAvailable = false, legacy = true, dialogFixed = false, paused = true)
    catalog.apps = listOf(FleetReq.catalogApp("a"))
    val o = FleetReq.json(install(JSONObject().put("packageName", "a")))
    assertFalse(o.getBoolean("ok"))
    assertEquals("paused", o.getString("result"))
    host.devModeOn = true
    val apk = File(dir, "p.apk").apply { writeText("apk") }
    assertEquals("paused", FleetReq.json(install(JSONObject().put("path", apk.path).put("packageName", "p"))).getString("result"))
    assertTrue(host.calls.none { it.startsWith("storeInstall") || it.startsWith("daemonInstall") || it == "invalidate" })
  }

  @Test fun theStoreInstallLatchesTheFirstTerminalStatus() {
    catalog.apps = listOf(FleetReq.catalogApp("a"))
    host.storeMessages = listOf("Downloading…", "Installing…", "Install failed")
    val o = FleetReq.json(install(JSONObject().put("packageName", "a")))
    assertEquals("failed", o.getString("result"))
    // The terminal status invalidates the version snapshot, and so does the end of the install.
    assertEquals(2, host.called("invalidate"))
    host.storeMessages = listOf("Paused — connect to your computer to add apps")
    assertEquals("failed", FleetReq.json(install(JSONObject().put("packageName", "a"))).getString("result"))
  }

  @Test fun aStoreInstallThatNeverFinishesTimesOut() {
    val quick = FleetInstallApi(host, host, FleetTestFiles(), catalog, storeInstallTimeoutMs = 20)
    catalog.apps = listOf(FleetReq.catalogApp("a"))
    host.storeMessages = listOf("Downloading…")
    assertEquals("timeout", FleetReq.json(quick.install(FleetReq.post("/install", JSONObject().put("packageName", "a")))).getString("result"))
    host.storeMessages = null
    assertEquals("timeout", FleetReq.json(quick.install(FleetReq.post("/install", JSONObject().put("packageName", "a")))).getString("result"))
  }

  @Test fun oneInstallPerPackageAtATime() {
    catalog.apps = listOf(FleetReq.catalogApp("a"), FleetReq.catalogApp("b"))
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    host.storeInstallGate = { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
    var first: FleetHttpServer.Response? = null
    val t = Thread { first = install(JSONObject().put("packageName", "a")) }.apply { start() }
    assertTrue(entered.await(5, TimeUnit.SECONDS))
    host.storeInstallGate = null
    val busy = install(JSONObject().put("packageName", "a"))
    assertEquals(409, busy.status)
    val o = FleetReq.json(busy)
    assertFalse(o.getBoolean("ok"))
    assertEquals("busy", o.getString("result"))
    // Another package isn't blocked.
    assertEquals(200, install(JSONObject().put("packageName", "b")).status)
    release.countDown()
    t.join(5_000)
    assertEquals(200, first!!.status)
    // And the package is free again afterwards.
    assertEquals(200, install(JSONObject().put("packageName", "a")).status)
  }

  @Test fun theDialogPathDownloadsInstallsAndAlwaysDeletesTheDownload() {
    catalog.apps = listOf(FleetReq.catalogApp("a"))
    host.plan = dialogPlan
    val apk = File(dir, "a.fleet.apk")
    assertEquals("installed", FleetReq.json(install(JSONObject().put("packageName", "a"))).getString("result"))
    assertFalse(apk.exists())
    host.dialogOutcome = HeadlessInstallOutcome.Failed(PackageInstaller.STATUS_FAILURE_ABORTED, "aborted")
    assertEquals("confirm_failed", FleetReq.json(install(JSONObject().put("packageName", "a"))).getString("result"))
    host.dialogOutcome = HeadlessInstallOutcome.Failed(PackageInstaller.STATUS_FAILURE_INVALID, "bad apk")
    assertEquals("failed", FleetReq.json(install(JSONObject().put("packageName", "a"))).getString("result"))
    host.downloadError = IOException("network")
    assertEquals("failed", FleetReq.json(install(JSONObject().put("packageName", "a"))).getString("result"))
    assertFalse(apk.exists())
    assertEquals(3, host.calls.count { it.startsWith("dialogInstall:a.fleet.apk") })
  }

  @Test fun fleetResultMapsEveryOutcome() {
    assertEquals("installed", FleetInstallApi.fleetResult(HeadlessInstallOutcome.Installed))
    assertEquals("confirm_failed", FleetInstallApi.fleetResult(HeadlessInstallOutcome.ConfirmTimeout(1, 0, false)))
    assertEquals("failed", FleetInstallApi.fleetResult(HeadlessInstallOutcome.Error("staging")))
    assertEquals("failed", FleetInstallApi.fleetResult(HeadlessInstallOutcome.Failed(PackageInstaller.STATUS_FAILURE, null)))
  }

  @Test fun updateValidatesAndNeedsTheCatalog() {
    assertEquals("400 bad_json", FleetReq.outcome(api.update(FleetReq.post("/update", "nope"))))
    assertEquals("400 specify_all_or_packageName", FleetReq.outcome(update(JSONObject())))
    assertEquals("400 specify_all_or_packageName", FleetReq.outcome(update(JSONObject().put("packageName", ""))))
    assertEquals("503 catalog_not_ready", FleetReq.outcome(update(JSONObject().put("all", true))))
    assertEquals("503 catalog_not_ready", FleetReq.outcome(update(JSONObject().put("packageName", "a"))))
    assertTrue(host.calls.none { it.startsWith("updateCheck") })
  }

  @Test fun anUnfinishedUpdateCheckIsReportedAndCancelled() {
    catalog.apps = listOf(FleetReq.catalogApp("a"))
    host.updates = null
    assertEquals("503 update_check_unconfirmed", FleetReq.outcome(update(JSONObject().put("dryRun", true))))
    assertEquals(1, host.called("updateCheckCancelled"))
  }

  @Test fun aDryRunListsUpdatesWithoutInstalling() {
    catalog.apps = listOf(FleetReq.catalogApp("a"), FleetReq.catalogApp("b"))
    host.updates = mapOf("a" to 9L)
    val o = FleetReq.json(update(JSONObject().put("dryRun", true).put("all", true)))
    assertEquals(1, o.getJSONArray("updates").length())
    assertEquals("a", o.getJSONArray("updates").getJSONObject(0).getString("packageName"))
    assertEquals(9L, o.getJSONArray("updates").getJSONObject(0).getLong("latest"))
    assertEquals(1, host.called("updateCheck:2"))
    assertEquals(1, host.called("updateCheckCancelled"))
    assertTrue(host.calls.none { it.startsWith("storeInstall") })
  }

  @Test fun updateInstallsAllOrOnePackageWithUpdates() {
    catalog.apps = listOf(FleetReq.catalogApp("a"), FleetReq.catalogApp("b"))
    host.updates = mapOf("a" to 9L, "b" to 4L)
    val all = FleetReq.json(update(JSONObject().put("all", true)))
    assertTrue(all.getBoolean("ok"))
    assertEquals(2, all.getInt("count"))
    assertEquals(2, all.getInt("installedCount"))
    val one = FleetReq.json(update(JSONObject().put("packageName", "b")))
    assertEquals(1, one.getInt("count"))
    assertEquals("b", one.getJSONArray("updated").getJSONObject(0).getString("packageName"))
    // A package without an update is a successful no-op.
    host.updates = mapOf("a" to 9L)
    val none = FleetReq.json(update(JSONObject().put("packageName", "b")))
    assertTrue(none.getBoolean("ok"))
    assertEquals(0, none.getInt("count"))
  }

  @Test fun anUpdateForAPackageTheCatalogDroppedIsReported() {
    catalog.apps = listOf(FleetReq.catalogApp("a"))
    host.updates = mapOf("a" to 2L, "gone" to 3L)
    val o = FleetReq.json(update(JSONObject().put("all", true)))
    assertFalse(o.getBoolean("ok"))
    assertEquals(1, o.getInt("failedCount"))
    val results = (0 until 2).map { o.getJSONArray("updated").getJSONObject(it) }.associate { it.getString("packageName") to it.getString("result") }
    assertEquals(mapOf("a" to "installed", "gone" to "catalog_entry_missing"), results)
  }

  @Test fun theDefaultTimeoutsAreTheDocumentedOnes() {
    assertEquals(190_000L, FleetInstallApi.STORE_INSTALL_TIMEOUT_MS)
    assertEquals(30_000L, FleetInstallApi.UPDATE_CHECK_TIMEOUT_MS)
    assertEquals("busy", FleetInstallApi.BUSY)
  }
}
