/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import org.json.JSONObject

/**
 * An in-memory Portal for the pure fleet route classes: every [FleetDevice], [FleetSettings],
 * [FleetPackages], [FleetActions] and [FleetPerf] call is recorded in [calls] and answers from a
 * settable field. No Context, no Android.
 */
internal class FleetTestHost : FleetDevice, FleetSettings, FleetPackages, FleetActions, FleetPerf {
  val calls = ArrayList<String>()
  private fun note(call: String) { synchronized(calls) { calls += call } }
  fun called(call: String) = synchronized(calls) { calls.count { it == call } }

  // --- FleetDevice
  var deviceToken = "t0k3n-0123456789abcdef"
  var deviceName = "Kitchen"
  var devModeOn = false
  var version = 277L to "1.283"
  var ip: String? = "10.0.0.2"
  var presence = PresenceState(Presence.PRESENT, ScreenState.INTERACTIVE, confident = true, sinceMs = 0L)
  var canReadLogs = true
  var logText = "line1\nline2"
  override fun token() = deviceToken.also { note("token") }
  override fun name() = deviceName
  override fun port() = 8723
  override fun model() = "Portal+"
  override fun device() = "aloha"
  override fun apiLevel() = 29
  override fun appVersion() = version
  override fun lanIp() = ip
  override fun presence() = presence
  override fun devMode() = devModeOn
  override fun setDevMode(on: Boolean) { note("setDevMode:$on"); devModeOn = on }
  override fun canWriteSecureSettings() = true
  override fun installConfirmEnabled() = false
  override fun canReadLogs() = canReadLogs
  override fun memoryHealth(fresh: Boolean) = JSONObject().put("fresh", fresh)
  override fun diagSnapshot() = JSONObject().put("ok", true).put("uptimeSec", 5)
  override fun logcat(lines: Int): String { note("logcat:$lines"); return logText }

  // --- FleetSettings
  val config = LinkedHashMap<String, String>()
  var calendar = JSONObject().put("enabled", false)
  var screensaver = JSONObject().put("fit", "fill")
  override fun setName(name: String) { note("setName:$name"); deviceName = name }
  override fun setConfigValue(key: String, value: String) { config[key] = value }
  override fun configValues(): Map<String, String> = config
  override fun calendarJson() = JSONObject(calendar.toString())
  override fun applyCalendar(body: JSONObject): List<String> { calendar.put("enabled", true); return body.keys().asSequence().toList() }
  override fun screensaverJson() = JSONObject(screensaver.toString())
  override fun applyScreensaver(body: JSONObject): List<String> {
    note("applyScreensaver")
    body.keys().forEach { screensaver.put(it, body.get(it)) }
    return body.keys().asSequence().toList()
  }

  // --- FleetPackages
  var plan = InstallLifecycle.Plan("silent", daemonAvailable = true, legacy = false, dialogFixed = true, paused = false)
  var versions: Map<String, Long>? = emptyMap()
  val fallbackVersions = HashMap<String, Long>()
  var archivePackage: String? = null
  /** The store's status messages for a daemon install (null: the store never answers). */
  var storeMessages: List<String>? = listOf("Downloading…", "Installed ✓")
  var storeInstallGate: (() -> Unit)? = null
  var daemonResult = true
  var dialogOutcome: HeadlessInstallOutcome = HeadlessInstallOutcome.Installed
  var downloadError: Throwable? = null
  var cacheDir: File = File(System.getProperty("java.io.tmpdir"))
  /** The update check's answer (null: it never completes). */
  var updates: Map<String, Long>? = emptyMap()
  override fun installPlan() = plan
  override fun installedVersions(packageNames: Set<String>) =
      versions?.let { InstalledPackageVersionSnapshot(it, packageNames, 0L) }
  override fun installedVersionCode(packageName: String) = fallbackVersions[packageName]
  override fun isInstalled(packageName: String) = packageName in fallbackVersions
  override fun invalidateInstalledVersions() = note("invalidate")
  override fun archivePackageName(path: String) = archivePackage
  override fun startStoreInstall(app: CatalogApp, status: (String) -> Unit) {
    note("storeInstall:${app.packageName}")
    storeInstallGate?.invoke()
    storeMessages?.forEach(status)
  }
  override fun daemonInstall(apk: File, packageName: String): Boolean { note("daemonInstall:${apk.name}:$packageName"); return daemonResult }
  override fun dialogInstall(apk: File, packageName: String): HeadlessInstallOutcome { note("dialogInstall:${apk.name}:$packageName"); return dialogOutcome }
  override fun download(app: CatalogApp, dest: File) {
    note("download:${app.packageName}:${app.apkUrl}")
    downloadError?.let { throw it }
    dest.writeText("apk")
  }
  override fun downloadFile(packageName: String) = File(cacheDir, "$packageName.fleet.apk")
  override fun startUpdateCheck(apps: List<CatalogApp>, done: (Map<String, Long>) -> Unit): () -> Unit {
    note("updateCheck:${apps.size}")
    updates?.let(done)
    return { note("updateCheckCancelled") }
  }

  // --- FleetActions
  var remoteEnabled = true
  var testSourceAvailable = true
  var testSourceApplied = true
  var clock = FleetClockLook("classic", "light", "white", "soft", "classic")
  var openResult = true
  override fun reaffirm() = note("reaffirm")
  override fun identify() = note("identify")
  override fun settingsSync(op: String) = note("sync:$op")
  override fun settingsSyncStatus() = JSONObject().put("phase", "JOINED")
  override fun settingsSyncValues() = JSONObject().put("screensaver.fit", "fill")
  override fun openControlCenter(screen: String): Boolean { note("controlCenter:$screen"); return openResult }
  override fun remoteEnabled() = remoteEnabled
  override fun mintTestSession(householdGrant: Boolean): String { note("mint:$householdGrant"); return "session-token" }
  override fun newTestPin() = "123456"
  override fun revokeSession(id: String): Boolean { note("revokeSession:$id"); return true }
  override fun ageTestSessions(ms: Long): Int { note("age:$ms"); return 2 }
  override fun revokeTestSessions() = 3
  override fun ccDebug(dryRun: Boolean?, testPlan: String?, testAlert: String?, haUnreachable: Boolean?, fake: CcFakeState?): String {
    note("cc:$dryRun:$testPlan:$testAlert:$haUnreachable:${fake?.entityId}")
    return "dry=$dryRun"
  }
  override fun testSourceAvailable() = testSourceAvailable
  override fun enableTestSource(minutes: Int, night: Boolean): Boolean { note("enableTestSource:$minutes:$night"); return testSourceApplied }
  override fun disableTestSource(): Boolean { note("disableTestSource"); return testSourceApplied }
  override fun reopenFrame(enabled: Boolean) = note("reopenFrame:$enabled")
  override fun testSourceActive() = true
  override fun testSourceNight() = false
  override fun testSourceUntil() = 42L
  override fun clockLook() = clock
  override fun isClockFace(id: String) = id in setOf("classic", "bold", "neon")
  override fun setClockStyle(style: String) { note("style:$style"); clock = clock.copy(style = style) }
  override fun setClockFont(font: String) = note("font:$font")
  override fun setClockColor(color: String) = note("color:$color")
  override fun setClockGlow(glow: String) = note("glow:$glow")
  override fun setClockFace(face: String) { note("face:$face"); clock = clock.copy(face = face) }
  override fun openClockPreview() = note("openClockPreview")
  override fun whosThereDebug(body: JSONObject) = JSONObject().put("echo", body.optBoolean("glance"))
  override fun isSettingsPage(page: String) = page in setOf("people", "display")
  override fun openScreen(screen: String, page: String?): Boolean { note("openScreen:$screen:$page"); return openResult }
  override fun orientation(request: String?): JSONObject { note("orientation:$request"); return JSONObject().put("request", request ?: JSONObject.NULL) }

  // --- FleetPerf
  var homeDiagnostics = true
  var startResult: Boolean? = true
  var stopResult = true
  var pending = false
  override fun snapshot() = JSONObject().put("capture", "full")
  override fun stateSnapshot() = JSONObject().put("capture", "state")
  override fun readinessSnapshot() = JSONObject().put("capture", "readiness")
  override fun start(command: FramePerformanceRequest): Boolean? {
    note("perfStart:${command.durationSec}")
    if (pending) throw FramePerformanceCommandPending()
    return startResult
  }
  override fun stop(): Boolean {
    note("perfStop")
    if (pending) throw FramePerformanceCommandPending()
    return stopResult
  }
  override fun homeDiagnosticsEnabled() = homeDiagnostics
  override fun recentApps() = JSONObject().put("diag", "recent")
  override fun webAppStartup() = JSONObject().put("diag", "webapp")
  override fun toolsDiscovery() = JSONObject().put("diag", "tools")
  override fun remoteInput() = JSONObject().put("diag", "input")
  override fun filmstripRender() = FleetHttpServer.Response(200, "{\"ok\":true,\"render\":1}")
}

/** A filesystem for the `/fs` routes: the real one, with free space and failures settable. */
internal class FleetTestFiles(var usableBytes: Long = 4L shl 30) : FleetFiles by JvmFleetFiles {
  var writeError: Throwable? = null
  val listed = ArrayList<String>()
  override fun list(path: String): JSONObject { listed += path; return JvmFleetFiles.list(path) }
  override fun usableSpaceFor(file: File) = usableBytes
  override fun write(file: File, body: (OutputStream) -> Long): Long {
    writeError?.let { throw it }
    return JvmFleetFiles.write(file, body)
  }
}

/** Builds requests and reads responses for the fleet route tests. */
internal object FleetReq {
  fun of(
      method: String,
      path: String,
      body: String = "",
      query: String = "",
      auth: String? = null,
      contentLength: Int? = null,
  ): FleetHttpServer.Request {
    val bytes = body.toByteArray(Charsets.UTF_8)
    val headers = if (auth == null) emptyMap() else mapOf("authorization" to auth)
    return FleetHttpServer.Request(method, path, query, headers, ByteArrayInputStream(bytes), contentLength ?: bytes.size)
  }

  fun get(path: String, query: String = "") = of("GET", path, query = query)
  fun post(path: String, body: String = "") = of("POST", path, body)
  fun post(path: String, body: JSONObject) = of("POST", path, body.toString())

  fun text(resp: FleetHttpServer.Response): String = resp.use {
    val out = ByteArrayOutputStream()
    it.jsonBytes?.let(out::write) ?: it.streamWriter?.invoke(out)
    out.toString("UTF-8")
  }

  fun json(resp: FleetHttpServer.Response): JSONObject = JSONObject(text(resp))

  /** Status plus the error code (or "ok"), the shape most route assertions need. */
  fun outcome(resp: FleetHttpServer.Response): String {
    val body = json(resp)
    return "${resp.status} " + (body.optString("error").ifEmpty { if (body.optBoolean("ok")) "ok" else "not_ok" })
  }

  fun catalogApp(pkg: String, versionCode: Long? = null, apkUrl: String? = null) = CatalogApp(
      name = "App $pkg", packageName = pkg, source = if (apkUrl != null) "url" else "fdroid", fdroidId = pkg,
      apkUrl = apkUrl, versionCode = versionCode, description = "", category = "Tools")
}
