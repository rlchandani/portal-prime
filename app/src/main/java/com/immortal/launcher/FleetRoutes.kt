/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.io.EOFException
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Fleet Agent's HTTP API — the only place that touches app subsystems
 * ([StoreCatalog], [InstallDaemon], [PresenceHub], [FleetConfig], [SettingsGuard]).
 * Kept separate from the socket plumbing ([FleetHttpServer]) so the routing and
 * JSON shaping are JVM-unit-testable.
 *
 * Every request must carry `Authorization: Bearer <token>` (the per-device token
 * from [FleetConfig]); anything else gets 401 before any work happens. This is a
 * pure CONSUMER of existing APIs — it adds no install/catalog logic of its own,
 * so the App Store and the fleet always install apps the exact same way.
 */
class FleetRoutes(private val context: Context) {

  @Volatile private var catalog: List<CatalogApp> = emptyList()
  private val inFlight = ConcurrentHashMap.newKeySet<String>()

  /** The phone-remote surface (`/remote/…`), which does its own pairing/session auth. */
  private val remote = RemoteRoutes(context)

  /** Sealed settings backups (`/backup/…`): enrolment, export and import. */
  private val backup = FleetBackupRoutes(context)

  /** Load (and later refresh) the catalog snapshot used by /info, /install, /update. */
  fun refreshCatalog() {
    StoreCatalog.loadCatalog(context) { catalog = it }
  }

  fun handle(req: FleetHttpServer.Request): FleetHttpServer.Response {
    // The phone remote authenticates by paired session (or fleet token), not by the
    // bearer gate below — so it's routed first, before the token check.
    if (req.path.startsWith("/remote/")) return remote.handle(req)
    if (!authorized(req)) return resp(401, err("unauthorized"))
    return when (req.path) {
      "/info" -> requireMethod("GET", req) { info() }
      "/apps" -> requireMethod("GET", req) { apps() }
      "/install" -> requireMethod("POST", req) { install(req) }
      "/update" -> requireMethod("POST", req) { update(req) }
      OtaRoutes.FLEET_PATH -> OtaRoutes.fleet(context, req)
      "/dev" -> dev(req)
      "/config" -> requireMethod("POST", req) { config(req) }
      "/calendar" -> calendar(req)
      "/screensaver" -> screensaver(req)
      "/action" -> requireMethod("POST", req) { action(req) }
      "/fs/list" -> requireMethod("GET", req) {
        val path = req.queryParam("path") ?: "/sdcard"
        // The app's private data can't be listed either (names and sizes are metadata too).
        if (FleetSecretPaths.isPrivate(java.io.File(path))) resp(403, err("protected_path")) else resp(200, FleetFs.list(path))
      }
      in FleetBackupRoutes.PATHS -> backup.handle(req)
      "/fs/read" -> requireMethod("GET", req) { fsRead(req) }
      "/fs/write" -> requireMethod("POST", req) { fsWrite(req) }
      "/logcat" -> requireMethod("GET", req) { logcat(req) }
      "/diag" -> requireMethod("GET", req) {
        resp(200, FleetDiag.snapshot(context).put("memoryHealth", memoryHealth(fresh = true)))
      }
      "/perf" -> performance(req)
      "/perf/home-ui" -> HomeUiDiagnostics.handle(context, req)
      "/perf/app-badges" -> AppBadgeProbe.handle(context, req)
      "/perf/store-work" -> StoreLoadingDiagnostics.handle(req)
      "/perf/recent-apps" -> requireMethod("GET", req) {
        if (!BuildConfig.PORTAL_HOME_UI_DIAGNOSTICS) resp(403, err("diagnostics_disabled"))
        else FleetHttpServer.Response(200, RecentAppPreparationDiagnostics.snapshot().toString())
      }
      "/perf/webapp-startup" -> requireMethod("GET", req) {
        if (!BuildConfig.PORTAL_HOME_UI_DIAGNOSTICS) resp(403, err("diagnostics_disabled"))
        else FleetHttpServer.Response(200, WebAppOpeningDiagnostics.snapshot().toString())
      }
      "/perf/tools-discovery" -> requireMethod("GET", req) {
        if (!BuildConfig.PORTAL_HOME_UI_DIAGNOSTICS) resp(403, err("diagnostics_disabled"))
        else FleetHttpServer.Response(200, ToolsDiscoveryDiagnostics.snapshot().toString())
      }
      "/perf/remote-input" -> requireMethod("GET", req) {
        if (!BuildConfig.PORTAL_HOME_UI_DIAGNOSTICS) resp(403, err("diagnostics_disabled"))
        else FleetHttpServer.Response(200, RemoteInput.gestureDiagnostics().toString())
      }
      "/perf/settings-ui" -> SettingsUiDiagnostics.handle(context, req)
      "/perf/webapp" -> WebAppProbe.handle(context, req)
      "/perf/source-preview" -> SourcePreviewProbe.handle(context, req)
      "/perf/photo-upload" -> PhotoUploadProbe.handle(context, req)
      "/perf/calling" -> CallPerfProbe.handle(context, req)
      "/perf/photo-thumbnail" -> PhotoThumbnailProbe.handle(context, req)
      "/perf/callmsg" -> CallMessageProbe.handle(context, req)
      "/perf/source-cache" -> SourceCacheProbe.handle(context, req)
      "/perf/source-photo-preview" -> SourcePhotoPreviewProbe.handle(context, req)
      "/perf/bridge-write" -> BridgeWriteProbe.handle(context, req)
      "/perf/filmstrip-render" -> requireMethod("GET", req) { FilmstripScreenshot.render(context) }
      "/perf/state" -> requireMethod("GET", req) {
        resp(200, if (req.queryParam("readiness") == "1") FramePerformanceCapture.readinessSnapshot()
            else FramePerformanceCapture.stateSnapshot())
      }
      else -> resp(404, err("not_found"))
    }
  }

  // --- endpoints --------------------------------------------------------------

  /** Ephemeral operator diagnostics; no preferences, content capture or scheduled monitoring. */
  private fun performance(req: FleetHttpServer.Request): FleetHttpServer.Response = when (req.method) {
    "GET" -> resp(200, FramePerformanceCapture.snapshot())
    "POST" -> {
      val body = parseJson(req.bodyText())
      val command = body?.let { FramePerformanceRequest.parse(it) }
      if (command == null) resp(400, err("invalid_capture_command"))
      else if (command.action == "start" && !DevMode.isEnabled(context)) resp(403, err("dev_mode_required"))
      else {
        try {
          val applied = if (command.action == "start") {
            FilmstripScreenshot.withPerformanceStart { FramePerformanceCapture.start(command) }
          } else FramePerformanceCapture.stop()
          if (applied == null) resp(409, err("filmstrip_busy"))
          else if (applied) resp(200, FramePerformanceCapture.snapshot())
          else resp(503, err("capture_command_not_applied"))
        } catch (_: FramePerformanceCommandPending) {
          // A begun command cannot be cancelled safely. Report uncertainty instead of inviting a retry.
          resp(202, ok().put("result", "completion_unconfirmed").put("checkStateBeforeRetry", true))
        }
      }
    }
    else -> resp(405, err("method_not_allowed"))
  }

  private fun info(): FleetHttpServer.Response {
    val (code, vname) = appVersion()
    val m = installMode()
    val p = PresenceHub.current
    val o =
        ok()
            .put("name", FleetConfig.name(context))
            .put("model", Build.MODEL ?: "")
            .put("device", Build.DEVICE ?: "")
            .put("apiLevel", Build.VERSION.SDK_INT)
            .put("app", JSONObject().put("versionCode", code).put("versionName", vname))
            .put("ip", currentIp() ?: "")
            .put("port", FleetConfig.port(context))
            .put(
                "presence",
                JSONObject()
                    .put("presence", p.presence.name)
                    .put("screen", p.screen.name)
                    .put("confident", p.confident))
            .put(
                "install",
                JSONObject()
                    .put("mode", m.mode)
                    .put("daemonAvailable", m.daemonAvailable)
                    .put("legacyInstaller", m.legacy)
                    .put("dialogFixed", m.dialogFixed)
                    .put("paused", m.paused)
                    // dialog-mode is still unattended when auto-confirm is on
                    .put("autoConfirm", SettingsGuard.isInstallConfirmEnabled(context)))
            .put("memoryHealth", memoryHealth(fresh = false))
            .put("canWriteSecureSettings", SettingsGuard.canWriteSecureSettings(context))
            .put("devMode", DevMode.isEnabled(context))
            .put(
                "capabilities",
                JSONObject().put("files", true).put("diag", true).put("calendar", true).put("screensaver", true).put("dev", true).put("logcat", canReadLogs()).put("performance", true).put("backup", true))
    // Installed catalog apps + a CHEAP (no-network) update hint from the catalog pin.
    // The authoritative F-Droid check is POST /update {"dryRun":true}, kept off the
    // hot dashboard-poll path so /info stays fast across a whole wall of devices.
    val entries = catalog
    val versions = InstalledPackageVersions.snapshot(context, entries.mapTo(HashSet()) { it.packageName })
    val arr = JSONArray()
    for (app in entries) {
      val installed = (if (versions != null) versions.versionCode(app.packageName)
          else StoreCatalog.installedVersionCode(context, app.packageName)) ?: continue
      val a = JSONObject().put("packageName", app.packageName).put("name", app.name).put("installed", installed)
      app.versionCode?.let {
        a.put("catalogVersion", it)
        a.put("updateAvailable", it > installed)
      }
      arr.put(a)
    }
    o.put("apps", arr)
    return resp(200, o)
  }

  /** `/diag` samples now; `/info` (the dashboard poll) reuses the 15-minute sample. Never fails the call. */
  private fun memoryHealth(fresh: Boolean): JSONObject = runCatching {
    MemoryHealth.toJson(if (fresh) MemoryHealth.sampleNow() else MemoryHealth.latestOrSample())
  }.getOrElse {
    Log.w("ImmortalFleet", "memory health unavailable", it)
    MemoryHealth.toJson(null)
  }

  private fun apps(): FleetHttpServer.Response {
    val entries = catalog
    if (entries.isEmpty()) return resp(503, err("catalog_not_ready"))
    val versions = InstalledPackageVersions.snapshot(context, entries.mapTo(HashSet()) { it.packageName })
    val arr = JSONArray()
    for (app in entries) {
      val a =
          JSONObject()
              .put("name", app.name)
              .put("packageName", app.packageName)
              .put("source", app.source)
              .put("category", app.category)
              .put("installed", if (versions != null) versions.versionCode(app.packageName) != null
                  else StoreCatalog.isInstalled(context, app.packageName))
      app.versionCode?.let { a.put("versionCode", it) }
      arr.put(a)
    }
    return resp(200, ok().put("apps", arr))
  }

  private fun install(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val body = parseJson(req.bodyText()) ?: return resp(400, err("bad_json"))
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
      if (!DevMode.isEnabled(context)) return resp(403, err("dev_mode_required"))
      return installFile(localPath, body.optString("packageName").ifBlank { null })
    }

    val pkg = body.optString("packageName").ifBlank { null } ?: return resp(400, err("packageName_required"))
    val apkUrl = body.optString("apkUrl").ifBlank { null }
    // A request-supplied URL (and every redirect hop) must be on our release hosts, F-Droid, or a
    // host the catalog itself uses: the Portal is not a fetch proxy for arbitrary LAN/web hosts.
    val apkHosts = ApkUrlPolicy.allowedHosts(catalog)
    if (apkUrl != null && !ApkUrlPolicy.isAllowed(apkUrl, apkHosts)) return resp(400, err("apk_url_not_allowed"))
    val app =
        if (apkUrl != null) syntheticUrlApp(pkg, apkUrl, apkHosts)
        else
            catalog.firstOrNull { it.packageName == pkg }
                ?: return if (catalog.isEmpty()) resp(503, err("catalog_not_ready"))
                else resp(404, err("unknown_package"))
    val result = doInstall(app)
    val status = if (result == BUSY) 409 else 200
    return resp(
        status,
        JSONObject()
            .put("ok", result == "installed")
            .put("packageName", pkg)
            .put("result", result)
            .put("mode", installMode().mode))
  }

  private fun update(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val body = parseJson(req.bodyText()) ?: return resp(400, err("bad_json"))
    val dryRun = body.optBoolean("dryRun", false)
    val all = body.optBoolean("all", false)
    val packageName = body.optString("packageName").ifBlank { null }
    if (!dryRun && !all && packageName == null) return resp(400, err("specify_all_or_packageName"))
    val entries = catalog
    if (entries.isEmpty()) return resp(503, err("catalog_not_ready"))
    val updates = findUpdatesBlocking(entries)
        ?: return resp(503, err("update_check_unconfirmed"))
    if (dryRun) {
      val arr = JSONArray()
      updates.forEach { (p, v) -> arr.put(JSONObject().put("packageName", p).put("latest", v)) }
      return resp(200, ok().put("updates", arr))
    }
    val targets =
        when {
          all -> updates.keys.toList()
          packageName != null && updates.containsKey(packageName) -> listOf(packageName)
          else -> emptyList()
        }
    return resp(200, updateResult(targets) { p ->
      val app = entries.firstOrNull { it.packageName == p }
      if (app == null) "catalog_entry_missing" else doInstall(app)
    })
  }

  /**
   * Read (GET) or toggle (POST `{"enabled":bool}`) developer mode. When on, the
   * device's official self-updater is paused so a locally-pushed build isn't
   * overwritten (see [DevMode] / `fleetctl dev`).
   */
  private fun dev(req: FleetHttpServer.Request): FleetHttpServer.Response =
      when (req.method) {
        "GET" -> resp(200, ok().put("dev", devStatus()))
        "POST" -> {
          val body = parseJson(req.bodyText())
          if (body == null) resp(400, err("bad_json"))
          else {
            if (body.has("enabled")) DevMode.setEnabled(context, body.optBoolean("enabled"))
            resp(200, ok().put("dev", devStatus()))
          }
        }
        else -> resp(405, err("method_not_allowed"))
      }

  private fun devStatus(): JSONObject {
    val (code, name) = appVersion()
    return DevMode.statusJson(DevMode.isEnabled(context), code, name)
  }

  private fun config(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val body = parseJson(req.bodyText()) ?: return resp(400, err("bad_json"))
    if (body.has("name")) FleetConfig.setName(context, body.getString("name"))
    body.optJSONObject("set")?.let { set ->
      for (k in set.keys()) FleetConfig.setValue(context, k, set.get(k).toString())
    }
    val cfg = JSONObject()
    FleetConfig.allValues(context).forEach { (k, v) -> cfg.put(k, v) }
    return resp(200, ok().put("name", FleetConfig.name(context)).put("config", cfg))
  }

  /**
   * Read (GET) or push (POST) the screensaver calendar's feed link + display range,
   * so a fleet can be configured over WiFi without wireless ADB. Pushes apply live —
   * the running photo frame reloads the calendar on its refresh loop — so no reboot
   * or re-dream is needed.
   */
  private fun calendar(req: FleetHttpServer.Request): FleetHttpServer.Response =
      when (req.method) {
        "GET" -> resp(200, ok().put("calendar", FleetCalendar.toJson(ScreensaverConfig.load(context))))
        "POST" -> {
          val body = parseJson(req.bodyText())
          if (body == null) resp(400, err("bad_json"))
          else {
            val applied = FleetCalendar.apply(context, body)
            resp(
                200,
                ok()
                    .put("applied", JSONArray(applied))
                    .put("calendar", FleetCalendar.toJson(ScreensaverConfig.load(context))))
          }
        }
        else -> resp(405, err("method_not_allowed"))
      }

  /**
   * Read (GET) or push (POST) the photo-frame screensaver config — source, fit,
   * interval, shuffle, videos, now-playing, presence/power, and idle/overnight
   * screen-off. Lets a fleet be configured over WiFi without wireless ADB. Display
   * changes take effect on the next screensaver cycle (as in the in-app settings);
   * `enabled` and overnight changes apply immediately — we reaffirm screensaver
   * ownership and reschedule the overnight window after a push.
   */
  private fun screensaver(req: FleetHttpServer.Request): FleetHttpServer.Response =
      when (req.method) {
        "GET" ->
            resp(200, ok().put("screensaver", FleetScreensaver.toJson(ScreensaverConfig.load(context))))
        "POST" -> {
          val body = parseJson(req.bodyText())
          if (body == null) resp(400, err("bad_json"))
          else {
            val applied = FleetScreensaver.apply(context, body)
            SettingsGuard.afterScreensaverApply(context, applied)
            resp(
                200,
                ok()
                    .put("applied", JSONArray(applied))
                    .put("screensaver", FleetScreensaver.toJson(ScreensaverConfig.load(context))))
          }
        }
        else -> resp(405, err("method_not_allowed"))
      }

  private fun action(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val body = parseJson(req.bodyText()) ?: return resp(400, err("bad_json"))
    return when (body.optString("action")) {
      "reaffirm" -> {
        SettingsGuard.reaffirmAdb(context)
        SettingsGuard.reaffirmScreensaver(context)
        resp(200, ok().put("action", "reaffirm"))
      }
      "identify" -> {
        identify()
        resp(200, ok().put("action", "identify"))
      }
      // Household settings sync: status, the one-time join choice, and pause/resume for testing
      // catch-up (a paused Portal neither sends nor accepts sync traffic).
      "settings_sync" -> {
        when (body.optString("op", "status")) {
          "status", "values" -> Unit
          "pause" -> SettingsSync.setPaused(true)
          "resume" -> SettingsSync.setPaused(false)
          "adopt" -> SettingsSync.resolveJoin(keepMine = false)
          "keep" -> SettingsSync.resolveJoin(keepMine = true)
          "rejoin" -> SettingsSync.rejoin()
          else -> return resp(400, err("bad_op"))
        }
        val out = ok().put("action", "settings_sync").put("sync", SettingsSync.statusJson())
        if (body.optString("op") == "values") out.put("values", SettingsSync.valuesJson())
        resp(200, out)
      }
      // Opens the Home Control Center (optionally on a screen: map, cameras, tesla, alerts,
      // security, edit, settings) — for remote checks and on-device measurement.
      "open_control_center" -> {
        val intent = ControlCenterActivity.intent(context).putExtra(ControlCenterActivity.EXTRA_SCREEN, body.optString("screen", "map"))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val opened = runCatching { context.startActivity(intent) }.isSuccess
        resp(200, JSONObject().put("ok", opened).put("action", "open_control_center"))
      }
      // Operator verification of phone routes: {"op":"mint"} returns a paired-phone session marked as a
      // test ({"householdGrant":true} also gives it the post-pairing household-key window, as a PIN
      // pairing would); {"op":"age","days":N} moves test sessions' last use N days back (debug clock
      // for expiry); {"op":"pin"} issues a live pairing code, as "Show pairing code" would, so the
      // real /remote/pair path and its rate limit can be checked without the screen (the session it
      // mints is a test session); {"op":"revoke_id","id":"…"} removes one paired phone; {"op":"revoke"}
      // removes every test session (real pairings stay). The fleet token already has full control of
      // this Portal (it can mint sessions directly), so none of this grants anything new.
      "phone_test_session" -> when (body.optString("op")) {
        "mint" -> resp(200, ok().put("action", "phone_test_session").put("remoteEnabled", RemotePairing.isEnabled(context))
            .put("token", RemotePairing.mintTestSession(context, householdGrant = body.optBoolean("householdGrant"))))
        "pin" -> resp(200, ok().put("action", "phone_test_session").put("pin", RemotePairing.newPin(forTest = true)))
        // Removes one paired phone by its record id (the 16-hex id Settings lists), test or real.
        "revoke_id" -> {
          val id = body.optString("id")
          if (!id.matches(Regex("[0-9a-f]{16}"))) resp(400, err("bad_id"))
          else resp(200, ok().put("action", "phone_test_session").put("removed", RemotePairing.revokeSession(context, id)))
        }
        "age" -> {
          val days = body.optLong("days", -1L)
          if (days !in 0..MAX_TEST_AGE_DAYS) resp(400, err("bad_days"))
          else resp(200, ok().put("action", "phone_test_session")
              .put("aged", RemotePairing.ageTestSessions(context, TimeUnit.DAYS.toMillis(days))))
        }
        "revoke" -> resp(200, ok().put("action", "phone_test_session").put("revoked", RemotePairing.revokeTestSessions(context)))
        else -> resp(400, err("bad_op"))
      }
      // Control Center test switches (operator only): {"dryRun":true|false, "testPlan":"load"|"clear", "haUnreachable":true|false}.
      "cc_debug" -> {
        val dry = if (body.has("dryRun")) body.optBoolean("dryRun") else null
        val plan = body.optString("testPlan").takeIf { it == "load" || it == "clear" }
        val alert = body.optString("testAlert").takeIf { it.matches(Regex("[a-z_]{1,32}")) }
        val unreachable = if (body.has("haUnreachable")) body.optBoolean("haUnreachable") else null
        // {"fakeState":{"slug":"leak1","state":"on","deviceClass":"moisture","changedMinAgo":0}}: dry run only.
        val fake = if (body.has("fakeState")) CcFakeState.parse(body.optJSONObject("fakeState"))
            ?: return resp(400, err("bad_fakeState")) else null
        resp(200, JSONObject().put("ok", true).put("action", "cc_debug")
            .put("result", ControlCenter.debug(context, dry, plan, alert, unreachable, fake)))
      }
      // Verification only: swap every photo source for generated scenes (expires on its own), or
      // switch back. {"enabled":true|false, "minutes":1..240, "night":false|true}. Any frame already on screen is
      // retired first so household photos are never on screen while the switch is on; enabling
      // then opens a fresh frame on the generated scenes. Release builds report unavailable.
      "screensaver_test_source" -> {
        val enable = body.optBoolean("enabled", false)
        val minutes = body.optInt("minutes", 30)
        if (enable && minutes !in 1..ScreensaverTestSource.MAX_MINUTES)
          return resp(400, err("bad_minutes"))
        // "night":true also forces the dark-room state (night colours) without the light sensor.
        val night = body.optBoolean("night", false)
        val applied = if (enable) ScreensaverTestSource.enable(context, minutes, night = night)
            else ScreensaverTestSource.disable(context)
        if (applied) {
          val main = android.os.Handler(android.os.Looper.getMainLooper())
          main.post { runCatching { ImmortalDreamNavigation.dismissForNavigation() } }
          // The singleTask frame must finish before a fresh one is created on the new source.
          if (enable) main.postDelayed({
            runCatching {
              context.startActivity(android.content.Intent(context, PhotoFramePreviewActivity::class.java)
                  .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { android.util.Log.w("FleetRoutes", "test frame launch failed", it) }
          }, 800L)
        }
        resp(200, JSONObject().put("ok", applied).put("action", "screensaver_test_source")
            .put("digitalClockFace", DigitalClockConfig.faceOf(DigitalClockConfig.load(context)))
            .put("available", ScreensaverTestSource.available())
            .put("active", ScreensaverTestSource.active(context))
            .put("night", ScreensaverTestSource.forcedNight(context))
            .put("until", ScreensaverTestSource.until(context)))
      }
      // Verification only (test-source builds): open the digital clock preview, optionally on a
      // face {"face":"classic|bold|neon|led|flip|analog"}. The reply carries the exact previous
      // look; send it back as {"restore":{...},"open":false} afterwards.
      "digital_clock_preview" -> {
        if (!ScreensaverTestSource.available()) return resp(400, err("unavailable"))
        val face = body.optString("face").takeIf { id -> DigitalClockConfig.FACES.any { it.id == id } }
        val prior = DigitalClockConfig.load(context)
        val before = JSONObject().put("style", prior.style).put("font", prior.font)
            .put("color", prior.color).put("glow", prior.glow)
        body.optJSONObject("restore")?.let { r ->
          // Write back the exact previous look (it may be a custom combination, not a preset).
          r.optString("style").takeIf { it.isNotEmpty() }?.let { DigitalClockConfig.setStyle(context, it) }
          r.optString("font").takeIf { it.isNotEmpty() }?.let { DigitalClockConfig.setFont(context, it) }
          r.optString("color").takeIf { it.isNotEmpty() }?.let { DigitalClockConfig.setColor(context, it) }
          r.optString("glow").takeIf { it.isNotEmpty() }?.let { DigitalClockConfig.setGlow(context, it) }
        }
        if (face != null) DigitalClockConfig.setFace(context, face)
        if (body.optBoolean("open", true)) android.os.Handler(android.os.Looper.getMainLooper()).post {
          runCatching {
            context.startActivity(android.content.Intent(context, DigitalClockPreviewActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP))
          }
        }
        resp(200, ok().put("action", "digital_clock_preview").put("previousFace", before)
            .put("face", DigitalClockConfig.faceOf(DigitalClockConfig.load(context))))
      }
      // Who's there? test switches (operator only, in memory): {"ignoreQuietHours":bool, "glance":true, "benchmark":true}.
      "whos_there_debug" -> resp(200, JSONObject().put("ok", true).put("action", "whos_there_debug")
          .put("result", WhosThereDebug.apply(context, body)))
      // Layout test hook (operator only): opens one of a fixed set of internal screens for captures.
      "open_screen" -> {
        val target = when (body.optString("screen")) {
          "calls" -> CallsActivity::class.java
          "settings" -> ImmortalSettingsActivity::class.java
          "help" -> HelpActivity::class.java
          "household_list" -> HouseholdListActivity::class.java
          "ha_dashboard" -> HaDashboardActivity::class.java
          "lamp" -> LampActivity::class.java
          "guest_card" -> GuestCardActivity::class.java
          "store" -> StoreActivity::class.java
          "web_app" -> WebAppActivity::class.java
          "whos_there_enrol" -> WhosThereEnrolActivity::class.java
          else -> null
        }
        val opened = target != null && runCatching {
          val intent = android.content.Intent(context, target).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
          // Optional Settings page for captures, e.g. {"screen":"settings","page":"people"}.
          body.optString("page").takeIf { target == ImmortalSettingsActivity::class.java && SettingsTree.contains(it) }
              ?.let { intent.putExtra(APPROVED_SETTINGS_PAGE, it) }
          context.startActivity(intent)
        }.isSuccess
        resp(200, JSONObject().put("ok", opened).put("action", "open_screen"))
      }
      // Rotation test hook (operator only): {"request":"portrait"|"landscape"|"reverse_landscape"|"auto"|"forget"}.
      "orientation" -> {
        val request = body.optString("request").takeIf {
          it in setOf("portrait", "landscape", "reverse_landscape", "auto", "forget") }
        resp(200, JSONObject().put("ok", true).put("action", "orientation")
            .put("result", RotationFollower.operator(context, request)))
      }
      // Needs the signature/privileged REBOOT permission we don't hold — report honestly.
      "reboot" -> resp(200, JSONObject().put("ok", false).put("action", "reboot").put("result", "not_permitted"))
      else -> resp(400, err("unknown_action"))
    }
  }

  // --- install plumbing -------------------------------------------------------

  /**
   * Headless install. Uses the silent shell-daemon path when it's available,
   * otherwise falls back to a PackageInstaller dialog that [InstallConfirmService]
   * auto-confirms — so a rebooted Portal (daemon gone) can still install unattended.
   * Only a truly paused Gen-1 (no daemon AND no working dialog) is refused.
   */
  private fun doInstall(app: CatalogApp): String {
    val m = installMode()
    if (m.paused) return "paused"
    if (!inFlight.add(app.packageName)) return BUSY
    return try {
      if (m.mode == "silent") silentInstall(app) else dialogInstall(app)
    } finally {
      // Publish completion only after invalidating any pre-install read snapshot.
      InstalledPackageVersions.invalidate()
      inFlight.remove(app.packageName)
    }
  }

  /**
   * Install a local device-path APK directly (the dev-iteration path). Mirrors
   * [doInstall] but skips any download — the file is already on the device (pushed
   * via /fs/write). [pkg] labels the daemon queue entry / install session.
   */
  private fun installFile(path: String, pkgArg: String?): FleetHttpServer.Response {
    val apk = File(path)
    if (!apk.isFile || !apk.canRead()) return resp(404, err("file_not_found"))
    val pkg =
        pkgArg
            ?: runCatching { context.packageManager.getPackageArchiveInfo(path, 0)?.packageName }
                .getOrNull()
            ?: return resp(400, err("packageName_required"))
    val result = doInstallFile(apk, pkg)
    val status = if (result == BUSY) 409 else 200
    return resp(
        status,
        JSONObject()
            .put("ok", result == "installed")
            .put("packageName", pkg)
            .put("result", result)
            .put("mode", installMode().mode))
  }

  private fun doInstallFile(apk: File, pkg: String): String {
    val m = installMode()
    if (m.paused) return "paused"
    if (!inFlight.add(pkg)) return BUSY
    return try {
      if (m.mode == "silent") {
        if (InstallDaemon.install(context, apk, pkg)) "installed" else "failed"
      } else HeadlessInstaller.installForResult(context, apk, pkg).fleetResult()
    } finally {
      // Publish completion only after invalidating any pre-install read snapshot.
      InstalledPackageVersions.invalidate()
      inFlight.remove(pkg)
    }
  }

  /** Silent path: hand off to the store's shell-daemon install, latch its status. */
  private fun silentInstall(app: CatalogApp): String {
    val latch = CountDownLatch(1)
    val result = AtomicReference<Boolean?>(null)
    StoreCatalog.install(context, app) { _, msg ->
      terminalResultAndInvalidate(msg)?.let {
        result.set(it)
        latch.countDown()
      }
    }
    val done = latch.await(190, TimeUnit.SECONDS)
    return when {
      !done -> "timeout"
      result.get() == true -> "installed"
      else -> "failed"
    }
  }

  /** No daemon: download then PackageInstaller, with the installer dialog
   *  auto-confirmed by [InstallConfirmService] (needs SYSTEM_ALERT_WINDOW). */
  private fun dialogInstall(app: CatalogApp): String {
    val apk = File(context.cacheDir, "${app.packageName}.fleet.apk")
    return try {
      StoreCatalog.download(StoreCatalog.resolveApkUrl(app), apk, app.downloadHosts)
      HeadlessInstaller.installForResult(context, apk, app.packageName).fleetResult()
    } catch (t: Throwable) {
      "failed"
    } finally {
      runCatching { apk.delete() }
    }
  }

  private fun findUpdatesBlocking(apps: List<CatalogApp>, timeoutMs: Long = 30_000): Map<String, Long>? {
    val latch = CountDownLatch(1)
    val ref = AtomicReference<Map<String, Long>>(emptyMap())
    val cancel = StoreCatalog.findUpdates(context, apps) {
      ref.set(it)
      latch.countDown()
    }
    return try {
      completedUpdates(latch.await(timeoutMs, TimeUnit.MILLISECONDS), ref.get())
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

  // --- files + logs -----------------------------------------------------------

  private fun fsRead(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val path = req.queryParam("path") ?: return resp(400, err("path_required"))
    val f = File(path)
    // Refuses the app's private data (prefs, keys, tokens, sessions) and /proc side doors.
    if (FleetSecretPaths.isPrivate(f)) return resp(403, err("protected_path"))
    if (!f.isFile || !f.canRead()) return resp(404, err("not_a_readable_file"))
    return FleetFileRead.response(f)
  }

  private fun fsWrite(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val path = req.queryParam("path") ?: return resp(400, err("path_required"))
    val f = File(path)
    // Private data comes back only through the sealed import (/backup/import), never raw.
    if (FleetSecretPaths.isPrivate(f)) return resp(403, err("protected_path"))
    // Checked before reading a byte: a size cap, and enough free space left after the write.
    FsWritePolicy.refusal(req.contentLength.toLong(), usableSpaceFor(f))?.let {
      return resp(if (it == "body_too_large") 413 else 507, err(it)
          .put("maxBytes", FsWritePolicy.MAX_BYTES).put("minFreeAfterBytes", FsWritePolicy.MIN_FREE_AFTER_BYTES))
    }
    return try {
      val written = FleetUploadWriter.write(f, req::streamBodyTo)
      resp(200, ok().put("path", f.absolutePath).put("bytes", written))
    } catch (t: Throwable) {
      resp(if (t is EOFException) 400 else 500, err("write_failed").put("detail", t.message ?: ""))
    }
  }

  /** Free bytes on the volume that will hold [f] (its nearest existing ancestor). */
  private fun usableSpaceFor(f: File): Long {
    var dir: File? = f.absoluteFile.parentFile
    while (dir != null && !dir.exists()) dir = dir.parentFile
    return dir?.usableSpace ?: 0L
  }

  private fun logcat(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val lines = req.queryParam("lines")?.toIntOrNull() ?: 500
    val bytes = FleetDiag.logcat(lines).toByteArray(Charsets.UTF_8)
    return FleetHttpServer.Response.stream(200, "text/plain; charset=utf-8", bytes.size.toLong()) {
      it.write(bytes)
    }
  }

  private fun canReadLogs(): Boolean =
      context.checkSelfPermission(android.Manifest.permission.READ_LOGS) ==
          android.content.pm.PackageManager.PERMISSION_GRANTED

  // --- misc helpers -----------------------------------------------------------

  private fun identify() {
    Handler(Looper.getMainLooper()).post {
      runCatching {
        Toast.makeText(context, "Hearthpane Fleet: ${FleetConfig.name(context)}", Toast.LENGTH_LONG).show()
      }
    }
  }

  private fun appVersion(): Pair<Long, String> =
      runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            val code =
                if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode
                else @Suppress("DEPRECATION") pi.versionCode.toLong()
            code to (pi.versionName ?: "")
          }
          .getOrDefault(0L to "")

  /** Session-verified result; "confirm_failed" means the system dialog was never accepted. */
  private fun HeadlessInstallOutcome.fleetResult(): String =
      when {
        ok -> "installed"
        confirmationFailed -> "confirm_failed"
        else -> "failed"
      }

  private fun installMode(): InstallLifecycle.Plan = InstallDaemon.installPlan(context)

  /** Current LAN IPv4, preferring the wlan interface (computed fresh — DHCP-safe). */
  private fun currentIp(): String? =
      runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback }
                .sortedByDescending { it.name.startsWith("wlan") }
                .flatMap { Collections.list(it.inetAddresses) }
                .firstOrNull {
                  it is Inet4Address &&
                      !it.isLoopbackAddress &&
                      (it.isSiteLocalAddress || it.isLinkLocalAddress)
                }
                ?.hostAddress
          }
          .getOrNull()

  // --- auth + response shaping ------------------------------------------------

  private fun authorized(req: FleetHttpServer.Request): Boolean {
    val got = req.header("authorization")?.removePrefix("Bearer ")?.trim() ?: return false
    return constantTimeEquals(got, FleetConfig.token(context))
  }

  private inline fun requireMethod(
      method: String,
      req: FleetHttpServer.Request,
      block: () -> FleetHttpServer.Response,
  ): FleetHttpServer.Response = if (req.method == method) block() else resp(405, err("method_not_allowed"))

  private fun ok(): JSONObject = JSONObject().put("ok", true)

  private fun err(code: String): JSONObject = JSONObject().put("ok", false).put("error", code)

  private fun parseJson(s: String): JSONObject? =
      runCatching { JSONObject(if (s.isBlank()) "{}" else s) }.getOrNull()

  private fun resp(status: Int, obj: JSONObject) = FleetHttpServer.Response(status, obj.toString())

  internal companion object {
    const val BUSY = "busy"
    /** Upper bound for the `phone_test_session` debug clock (a little past the 1-year mark). */
    const val MAX_TEST_AGE_DAYS = 400L

    /** Derive the install-mode label reported in /info and gating /install. Pure. */
    internal fun modeFor(daemonAvailable: Boolean, paused: Boolean): String =
        InstallLifecycle.modeFor(daemonAvailable, paused)

    /** Preserve each selected package's terminal result in the bulk wire response. */
    internal fun updateResult(targets: List<String>, install: (String) -> String): JSONObject {
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
    internal fun completedUpdates(completed: Boolean, updates: Map<String, Long>): Map<String, Long>? =
        if (completed) updates else null

    /** A downloader/installer may publish after its HTTP waiter has already timed out. */
    internal fun terminalResultAndInvalidate(
        message: String,
        invalidate: () -> Unit = InstalledPackageVersions::invalidate,
    ): Boolean? {
      val terminal = terminalResult(message) ?: return null
      invalidate()
      return terminal
    }

    /** Map a store status string to a terminal result, or null for progress updates. Pure. */
    internal fun terminalResult(msg: String): Boolean? = InstallLifecycle.terminalResult(msg)

    /** Length-checked constant-time token compare. Pure. */
    internal fun constantTimeEquals(a: String, b: String): Boolean {
      if (a.length != b.length) return false
      var r = 0
      for (i in a.indices) r = r or (a[i].code xor b[i].code)
      return r == 0
    }
  }
}
