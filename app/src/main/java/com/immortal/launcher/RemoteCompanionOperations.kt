/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledThreadPoolExecutor
import org.json.JSONArray
import org.json.JSONObject

internal object RemoteCompanionOperationPolicy {
  fun validate(body: JSONObject, catalog: List<CatalogApp>, sdk: Int, device: String): RemoteCompanionValidation {
    val valid = when (body.optString("type")) {
      "install", "update" -> body.only("type", "packageName") && catalog.any { it.packageName == body.opt("packageName") && compatible(it, sdk, device) }
      "action" -> if (body.opt("action") == "launch") body.only("type", "action", "packageName") && RemoteLayoutPolicy.packageName(body.opt("packageName"))
          else body.only("type", "action") && body.opt("action") in RemoteScenePolicy.keys + RemoteScenePolicy.media
      "retry", "cancel" -> body.only("type", "id") && RemoteScenePolicy.validId(body.opt("id"))
      else -> false
    }
    return RemoteCompanionValidation(if (valid) emptyList() else listOf("unsupported_operation"))
  }
  fun compatible(app: CatalogApp, sdk: Int, device: String): Boolean = RemoteLayoutPolicy.packageName(app.packageName) && StoreCatalog.isCompatible(app.minSdk, sdk) && (app.devices.isEmpty() || device in app.devices)
}

/** One worker for both scenes and installs; no unbounded work queue or thread per phone request. */
internal class RemoteCompanionOperations(private val context: Context) {
  val history = RemoteCompanionHistory(RemoteCompanionStore.history(context)) { RemoteCompanionStore.update(context, "operations", it) }
  private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "remote-companion-operation").apply { isDaemon = true } }
  private val deadlines = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "remote-companion-deadline").apply { isDaemon = true } }.apply { removeOnCancelPolicy = true }
  @Volatile private var backendBusy = false

  @Synchronized fun start(type: String, target: JSONObject, work: (String) -> JSONArray): JSONObject? {
    if (backendBusy) return null
    reconcileInstalls()
    val op = history.start(type, target) ?: return null
    backendBusy = true
    val deadline = deadlines.schedule({
      history.finish(op.getString("id"), JSONArray().put(result(false, "deadline_exceeded").put("backendMayContinue", true)))
    }, 5, TimeUnit.MINUTES)
    worker.execute {
      try {
        val result = runCatching { work(op.getString("id")) }.getOrElse { JSONArray().put(result(false, "backend_failed")) }
        history.finish(op.getString("id"), result)
      } finally {
        deadline.cancel(false)
        try { history.backendStopped(op.getString("id")) } finally { backendBusy = false }
      }
    }
    return op
  }

  fun action(body: JSONObject): JSONObject {
    val action = body.optString("action")
    val dispatched = when (action) {
      "launch" -> RemoteApps.launch(context, body.getString("packageName"))
      "home" -> open(HomeActivity::class.java)
      "apps" -> open(AppSwitcherActivity::class.java)
      "screensaver" -> open(PhotoFramePreviewActivity::class.java)
      "wake", "sleep" -> {
        if (action == "wake") ScreenControl.wake(context) else ScreenControl.sleep(context)
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isInteractive == (action == "wake")
      }
      in RemoteScenePolicy.media -> RemoteMedia.command(action, 0)
      else -> RemoteInput.globalAction(action)
    }
    return result(dispatched, if (dispatched) "dispatched" else "not_dispatched").put("action", action)
  }
  private fun open(clazz: Class<*>): Boolean = runCatching { context.startActivity(Intent(context, clazz).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)

  fun install(id: String, app: CatalogApp, update: Boolean): JSONObject {
    if (app.packageName == context.packageName || app.packageName == "com.immortal.launcher") return result(false, "self_update_unsupported")
    if (!RemoteCompanionOperationPolicy.compatible(app, Build.VERSION.SDK_INT, deviceKind())) return result(false, "incompatible")
    val plan = InstallDaemon.installPlan(context)
    if (plan.paused) return result(false, "paused")
    val installed = StoreCatalog.installedVersionCode(context, app.packageName)
    if (update) {
      if (installed == null) return result(false, "not_installed")
      val latest = StoreCatalog.latestVersionCode(app) ?: return result(false, "version_unavailable")
      if (latest <= installed) return result(true, "up_to_date")
    }
    val apk = File(context.cacheDir, "${app.packageName}.companion.apk")
    var handedOff = false
    return try {
      StoreCatalog.download(StoreCatalog.resolveApkUrl(app), apk)
      // Catalog metadata alone cannot override the APK's own package or Android compatibility.
      val archive = context.packageManager.getPackageArchiveInfo(apk.path, 0)
      if (archive?.packageName != app.packageName) return result(false, "package_mismatch")
      val version = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else @Suppress("DEPRECATION") archive.versionCode.toLong()
      // Remove stale daemon evidence before persisting the handoff fence. A crash at any
      // later point is conservative: no helper return or process restart clears it.
      if (plan.mode == "silent") {
        val queue = File(context.getExternalFilesDir(null), "installq")
        for (suffix in listOf("done", "failed")) {
          val marker = File(queue, "${app.packageName}.apk.$suffix")
          if (marker.exists() && !marker.delete()) return result(false, "stale_install_evidence")
        }
      }
      history.installPending(id, plan.mode, app.packageName, version, System.currentTimeMillis() / 1000 * 1000)
      handedOff = true
      val succeeded = if (plan.mode == "silent") InstallDaemon.install(context, apk, app.packageName)
          else HeadlessInstaller.install(context, apk, app.packageName)
      if (succeeded) history.reconcileInstall(id, "native_installer_success", true)
      val readback = StoreCatalog.installedVersionCode(context, app.packageName)
      val verified = succeeded && readback != null && readback >= version
      result(verified, if (verified) "installed" else if (succeeded) "installed_readback_mismatch" else "install_failed_or_timed_out")
          .put("backendMayContinue", !succeeded).put("installUncertain", !succeeded)
          .put("packageName", app.packageName).put("installedVersionCode", readback ?: JSONObject.NULL)
    } catch (e: Exception) { result(false, "download_or_install_failed").put("backendMayContinue", handedOff).put("installUncertain", handedOff) }
    finally { apk.delete() }
  }
  /** Native evidence only; absence of a queue file or restarting this app is not evidence. */
  @Synchronized fun reconcileInstalls() {
    if (backendBusy) return
    val entries = history.list()
    for (i in 0 until entries.length()) {
      val entry = entries.getJSONObject(i)
      if (!entry.optBoolean("installUncertain")) continue
      val evidence = entry.optJSONObject("installEvidence") ?: continue // older unknown state stays fenced
      val pkg = evidence.optString("packageName")
      if (!RemoteLayoutPolicy.packageName(pkg)) continue
      val at = evidence.optLong("handedOffAt", Long.MAX_VALUE)
      when (evidence.optString("mode")) {
        "silent" -> {
          val queue = File(context.getExternalFilesDir(null), "installq")
          val done = File(queue, "$pkg.apk.done")
          val failed = File(queue, "$pkg.apk.failed")
          if (done.isFile && done.lastModified() >= at) history.reconcileInstall(entry.getString("id"), "daemon_done", true)
          else if (failed.isFile && failed.lastModified() >= at) history.reconcileInstall(entry.getString("id"), "daemon_failed", false)
        }
        "dialog" -> runCatching {
          // A missing session alone cannot prove success. Require a freshly installed
          // target version and no outstanding app-owned session (including unknown targets).
          if (context.packageManager.packageInstaller.mySessions.isNotEmpty()) return@runCatching
          val installed = context.packageManager.getPackageInfo(pkg, 0)
          val version = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else @Suppress("DEPRECATION") installed.versionCode.toLong()
          if (installed.lastUpdateTime >= at && version >= evidence.getLong("versionCode"))
            history.reconcileInstall(entry.getString("id"), "installed_readback", true)
        }
      }
    }
  }
  fun deviceKind(): String = if (context.packageManager.hasSystemFeature("android.hardware.type.television")) "tv" else "touch"
  companion object {
    fun result(ok: Boolean, code: String): JSONObject = JSONObject().put("ok", ok).put("result", code)
    private val instances = mutableMapOf<String, RemoteCompanionOperations>()
    @Synchronized fun get(c: Context): RemoteCompanionOperations = instances.getOrPut(c.packageName) { RemoteCompanionOperations(c.applicationContext) }
  }
}
