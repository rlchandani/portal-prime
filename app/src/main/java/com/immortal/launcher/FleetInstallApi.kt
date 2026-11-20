/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.FleetReply.err
import com.immortal.launcher.FleetReply.json
import com.immortal.launcher.FleetReply.ok
import com.immortal.launcher.FleetReply.parseJson
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/**
 * `/apps`, `/install` and `/update`: a pure CONSUMER of the store's install paths, adding no
 * install/catalog logic of its own, so the App Store and the fleet always install apps the exact
 * same way. Pure: packages, installs and the update check come in through [FleetPackages], files
 * through [FleetFiles].
 *
 * Headless install uses the silent shell-daemon path when it's available, otherwise a
 * PackageInstaller dialog that [InstallConfirmService] auto-confirms, so a rebooted Portal (daemon
 * gone) can still install unattended. Only a truly paused Gen-1 (no daemon AND no working dialog)
 * is refused. One install per package at a time.
 */
internal class FleetInstallApi(
    private val device: FleetDevice,
    private val packages: FleetPackages,
    private val files: FleetFiles,
    private val catalog: FleetCatalog,
    private val storeInstallTimeoutMs: Long = STORE_INSTALL_TIMEOUT_MS,
    private val updateCheckTimeoutMs: Long = UPDATE_CHECK_TIMEOUT_MS,
) {
  private val inFlight = ConcurrentHashMap.newKeySet<String>()

  fun apps(): FleetHttpServer.Response {
    val entries = catalog.apps
    if (entries.isEmpty()) return json(503, err("catalog_not_ready"))
    val versions = packages.installedVersions(entries.mapTo(HashSet()) { it.packageName })
    val arr = JSONArray()
    for (app in entries) {
      val a =
          JSONObject()
              .put("name", app.name)
              .put("packageName", app.packageName)
              .put("source", app.source)
              .put("category", app.category)
              .put("installed", if (versions != null) versions.versionCode(app.packageName) != null
                  else packages.isInstalled(app.packageName))
      app.versionCode?.let { a.put("versionCode", it) }
      arr.put(a)
    }
    return json(200, ok().put("apps", arr))
  }

  fun install(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val body = parseJson(req.bodyText()) ?: return json(400, err("bad_json"))
    // Local-build install: a device-side APK path (pushed via /fs/write), installed
    // directly — no catalog lookup, no versionCode gate. This is the dev-iteration
    // path (e.g. `fleetctl dev update`). The package is derived from the APK if not
    // given. NOTE: an in-place update is OS signature-checked, so a local build must
    // be signed with the same key as the installed one (see the dev-mode docs).
    //
    // Gated behind dev mode: installing an arbitrary device-side APK sidesteps the
    // catalog/versionCode gate, so it's only allowed when the operator has explicitly
    // opted in via /dev. Outside dev mode the token only installs known catalog apps.
    val localPath = body.optString("path").ifBlank { null }
    if (localPath != null) {
      if (!device.devMode()) return json(403, err("dev_mode_required"))
      return installFile(localPath, body.optString("packageName").ifBlank { null })
    }

    val pkg = body.optString("packageName").ifBlank { null } ?: return json(400, err("packageName_required"))
    val apkUrl = body.optString("apkUrl").ifBlank { null }
    // A request-supplied URL (and every redirect hop) must be on our release hosts, F-Droid, or a
    // host the catalog itself uses: the Portal is not a fetch proxy for arbitrary LAN/web hosts.
    val apkHosts = ApkUrlPolicy.allowedHosts(catalog.apps)
    if (apkUrl != null && !ApkUrlPolicy.isAllowed(apkUrl, apkHosts)) return json(400, err("apk_url_not_allowed"))
    val app =
        if (apkUrl != null) syntheticUrlApp(pkg, apkUrl, apkHosts)
        else
            catalog.apps.firstOrNull { it.packageName == pkg }
                ?: return if (catalog.apps.isEmpty()) json(503, err("catalog_not_ready"))
                else json(404, err("unknown_package"))
    return installReply(pkg, doInstall(app))
  }

  fun update(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val body = parseJson(req.bodyText()) ?: return json(400, err("bad_json"))
    val dryRun = body.optBoolean("dryRun", false)
    val all = body.optBoolean("all", false)
    val packageName = body.optString("packageName").ifBlank { null }
    if (!dryRun && !all && packageName == null) return json(400, err("specify_all_or_packageName"))
    val entries = catalog.apps
    if (entries.isEmpty()) return json(503, err("catalog_not_ready"))
    val updates = findUpdatesBlocking(entries)
        ?: return json(503, err("update_check_unconfirmed"))
    if (dryRun) {
      val arr = JSONArray()
      updates.forEach { (p, v) -> arr.put(JSONObject().put("packageName", p).put("latest", v)) }
      return json(200, ok().put("updates", arr))
    }
    val targets =
        when {
          all -> updates.keys.toList()
          packageName != null && updates.containsKey(packageName) -> listOf(packageName)
          else -> emptyList()
        }
    return json(200, updateResult(targets) { p ->
      val app = entries.firstOrNull { it.packageName == p }
      if (app == null) "catalog_entry_missing" else doInstall(app)
    })
  }

  // --- install plumbing -------------------------------------------------------

  private fun installReply(pkg: String, result: String): FleetHttpServer.Response =
      json(if (result == BUSY) 409 else 200,
          JSONObject()
              .put("ok", result == "installed")
              .put("packageName", pkg)
              .put("result", result)
              .put("mode", packages.installPlan().mode))

  private fun doInstall(app: CatalogApp): String =
      exclusiveInstall(app.packageName) { mode -> if (mode == SILENT) storeInstall(app) else downloadAndInstall(app) }

  /**
   * Install a local device-path APK directly (the dev-iteration path). Mirrors
   * [doInstall] but skips any download — the file is already on the device (pushed
   * via /fs/write). The package labels the daemon queue entry / install session.
   */
  private fun installFile(path: String, pkgArg: String?): FleetHttpServer.Response {
    val apk = File(path)
    if (!files.isReadableFile(apk)) return json(404, err("file_not_found"))
    val pkg = pkgArg ?: packages.archivePackageName(path) ?: return json(400, err("packageName_required"))
    val result = exclusiveInstall(pkg) { mode ->
      if (mode == SILENT) {
        if (packages.daemonInstall(apk, pkg)) "installed" else "failed"
      } else fleetResult(packages.dialogInstall(apk, pkg))
    }
    return installReply(pkg, result)
  }

  /** Refuse when paused or when [pkg] is already installing; publish only after invalidating. */
  private inline fun exclusiveInstall(pkg: String, install: (mode: String) -> String): String {
    val m = packages.installPlan()
    if (m.paused) return "paused"
    if (!inFlight.add(pkg)) return BUSY
    return try {
      install(m.mode)
    } finally {
      // Publish completion only after invalidating any pre-install read snapshot.
      packages.invalidateInstalledVersions()
      inFlight.remove(pkg)
    }
  }

  /** Silent path: hand off to the store's shell-daemon install, latch its status. */
  private fun storeInstall(app: CatalogApp): String {
    val latch = CountDownLatch(1)
    val result = AtomicReference<Boolean?>(null)
    packages.startStoreInstall(app) { msg ->
      terminalResultAndInvalidate(msg, packages::invalidateInstalledVersions)?.let {
        result.set(it)
        latch.countDown()
      }
    }
    val done = latch.await(storeInstallTimeoutMs, TimeUnit.MILLISECONDS)
    return when {
      !done -> "timeout"
      result.get() == true -> "installed"
      else -> "failed"
    }
  }

  /** No daemon: download then PackageInstaller, with the installer dialog
   *  auto-confirmed by [InstallConfirmService] (needs SYSTEM_ALERT_WINDOW). */
  private fun downloadAndInstall(app: CatalogApp): String {
    val apk = packages.downloadFile(app.packageName)
    return try {
      packages.download(app, apk)
      fleetResult(packages.dialogInstall(apk, app.packageName))
    } catch (t: Throwable) {
      "failed"
    } finally {
      runCatching { apk.delete() }
    }
  }

  private fun findUpdatesBlocking(apps: List<CatalogApp>): Map<String, Long>? {
    val latch = CountDownLatch(1)
    val ref = AtomicReference<Map<String, Long>>(emptyMap())
    val cancel = packages.startUpdateCheck(apps) {
      ref.set(it)
      latch.countDown()
    }
    return try {
      completedUpdates(latch.await(updateCheckTimeoutMs, TimeUnit.MILLISECONDS), ref.get())
    } finally {
      cancel()
    }
  }

  private fun syntheticUrlApp(pkg: String, url: String, hosts: Set<String>) =
      CatalogApp(
          name = pkg,
          packageName = pkg,
          source = "url",
          fdroidId = null,
          apkUrl = url,
          versionCode = null,
          description = "",
          category = "",
          downloadHosts = hosts)

  companion object {
    const val BUSY = "busy"
    private const val SILENT = "silent"
    const val STORE_INSTALL_TIMEOUT_MS = 190_000L
    const val UPDATE_CHECK_TIMEOUT_MS = 30_000L

    /** Derive the install-mode label reported in /info and gating /install. */
    fun modeFor(daemonAvailable: Boolean, paused: Boolean): String =
        InstallLifecycle.modeFor(daemonAvailable, paused)

    /** Session-verified result; "confirm_failed" means the system dialog was never accepted. */
    fun fleetResult(outcome: HeadlessInstallOutcome): String =
        when {
          outcome.ok -> "installed"
          outcome.confirmationFailed -> "confirm_failed"
          else -> "failed"
        }

    /** Preserve each selected package's terminal result in the bulk wire response. */
    fun updateResult(targets: List<String>, install: (String) -> String): JSONObject {
      val results = JSONArray()
      var installedCount = 0
      targets.forEach { pkg ->
        val result = install(pkg)
        if (result == "installed") installedCount++
        results.put(JSONObject().put("packageName", pkg).put("result", result))
      }
      return JSONObject()
          .put("ok", installedCount == targets.size)
          .put("count", targets.size)
          .put("installedCount", installedCount)
          .put("failedCount", targets.size - installedCount)
          .put("updated", results)
    }

    /** The HTTP waiter must distinguish completed discovery from an expired wait. */
    fun completedUpdates(completed: Boolean, updates: Map<String, Long>): Map<String, Long>? =
        if (completed) updates else null

    /** A downloader/installer may publish after its HTTP waiter has already timed out. */
    fun terminalResultAndInvalidate(
        message: String,
        invalidate: () -> Unit = InstalledPackageVersions::invalidate,
    ): Boolean? {
      val terminal = terminalResult(message) ?: return null
      invalidate()
      return terminal
    }

    /** Map a store status string to a terminal result, or null for progress updates. */
    fun terminalResult(msg: String): Boolean? = InstallLifecycle.terminalResult(msg)
  }
}
