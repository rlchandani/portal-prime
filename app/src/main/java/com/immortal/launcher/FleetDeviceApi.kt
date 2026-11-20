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
import org.json.JSONArray
import org.json.JSONObject

/** The catalog snapshot used by /info, /apps, /install and /update (refreshed by the adapter). */
internal class FleetCatalog {
  @Volatile var apps: List<CatalogApp> = emptyList()
}

/**
 * Device routes: `/info`, `/dev`, `/config`, `/calendar`, `/screensaver`, `/diag` and `/logcat`.
 * Pure: everything Android comes in through [FleetDevice], [FleetSettings] and [FleetPackages].
 */
internal class FleetDeviceApi(
    private val device: FleetDevice,
    private val settings: FleetSettings,
    private val packages: FleetPackages,
    private val catalog: FleetCatalog,
) {
  fun info(): FleetHttpServer.Response {
    val (code, vname) = device.appVersion()
    val m = packages.installPlan()
    val p = device.presence()
    val o =
        ok()
            .put("name", device.name())
            .put("model", device.model())
            .put("device", device.device())
            .put("apiLevel", device.apiLevel())
            .put("app", JSONObject().put("versionCode", code).put("versionName", vname))
            .put("ip", device.lanIp() ?: "")
            .put("port", device.port())
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
                    .put("autoConfirm", device.installConfirmEnabled()))
            .put("memoryHealth", device.memoryHealth(fresh = false))
            .put("canWriteSecureSettings", device.canWriteSecureSettings())
            .put("devMode", device.devMode())
            .put("capabilities", capabilities())
    // Installed catalog apps + a CHEAP (no-network) update hint from the catalog pin.
    // The authoritative F-Droid check is POST /update {"dryRun":true}, kept off the
    // hot dashboard-poll path so /info stays fast across a whole wall of devices.
    o.put("apps", installedApps(catalog.apps))
    return json(200, o)
  }

  private fun capabilities(): JSONObject =
      JSONObject().put("files", true).put("diag", true).put("calendar", true).put("screensaver", true)
          .put("dev", true).put("logcat", device.canReadLogs()).put("performance", true).put("backup", true)

  private fun installedApps(entries: List<CatalogApp>): JSONArray {
    val versions = packages.installedVersions(entries.mapTo(HashSet()) { it.packageName })
    val arr = JSONArray()
    for (app in entries) {
      val installed = (if (versions != null) versions.versionCode(app.packageName)
          else packages.installedVersionCode(app.packageName)) ?: continue
      val a = JSONObject().put("packageName", app.packageName).put("name", app.name).put("installed", installed)
      app.versionCode?.let {
        a.put("catalogVersion", it)
        a.put("updateAvailable", it > installed)
      }
      arr.put(a)
    }
    return arr
  }

  /**
   * Read (GET) or toggle (POST `{"enabled":bool}`) developer mode. When on, the
   * device's official self-updater is paused so a locally-pushed build isn't
   * overwritten (see [DevMode] / `fleetctl dev`).
   */
  fun dev(req: FleetHttpServer.Request): FleetHttpServer.Response =
      when (req.method) {
        "GET" -> json(200, ok().put("dev", devStatus()))
        "POST" -> {
          val body = parseJson(req.bodyText())
          if (body == null) json(400, err("bad_json"))
          else {
            if (body.has("enabled")) device.setDevMode(body.optBoolean("enabled"))
            json(200, ok().put("dev", devStatus()))
          }
        }
        else -> FleetReply.methodNotAllowed()
      }

  private fun devStatus(): JSONObject {
    val (code, name) = device.appVersion()
    return DevMode.statusJson(device.devMode(), code, name)
  }

  fun config(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val body = parseJson(req.bodyText()) ?: return json(400, err("bad_json"))
    if (body.has("name")) settings.setName(body.getString("name"))
    body.optJSONObject("set")?.let { set ->
      for (k in set.keys()) settings.setConfigValue(k, set.get(k).toString())
    }
    val cfg = JSONObject()
    settings.configValues().forEach { (k, v) -> cfg.put(k, v) }
    return json(200, ok().put("name", device.name()).put("config", cfg))
  }

  /**
   * Read (GET) or push (POST) the screensaver calendar's feed link + display range,
   * so a fleet can be configured over WiFi without wireless ADB. Pushes apply live —
   * the running photo frame reloads the calendar on its refresh loop — so no reboot
   * or re-dream is needed.
   */
  fun calendar(req: FleetHttpServer.Request): FleetHttpServer.Response =
      readOrApply(req, "calendar", settings::calendarJson, settings::applyCalendar)

  /**
   * Read (GET) or push (POST) the photo-frame screensaver config — source, fit,
   * interval, shuffle, videos, now-playing, presence/power, and idle/overnight
   * screen-off. Lets a fleet be configured over WiFi without wireless ADB. Display
   * changes take effect on the next screensaver cycle (as in the in-app settings);
   * `enabled` and overnight changes apply immediately — the adapter reaffirms screensaver
   * ownership and reschedules the overnight window after a push.
   */
  fun screensaver(req: FleetHttpServer.Request): FleetHttpServer.Response =
      readOrApply(req, "screensaver", settings::screensaverJson, settings::applyScreensaver)

  private fun readOrApply(
      req: FleetHttpServer.Request,
      key: String,
      read: () -> JSONObject,
      apply: (JSONObject) -> List<String>,
  ): FleetHttpServer.Response =
      when (req.method) {
        "GET" -> json(200, ok().put(key, read()))
        "POST" -> {
          val body = parseJson(req.bodyText())
          if (body == null) json(400, err("bad_json"))
          else {
            val applied = apply(body)
            json(200, ok().put("applied", JSONArray(applied)).put(key, read()))
          }
        }
        else -> FleetReply.methodNotAllowed()
      }

  fun diag(): FleetHttpServer.Response =
      json(200, device.diagSnapshot().put("memoryHealth", device.memoryHealth(fresh = true)))

  fun logcat(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val lines = req.queryParam("lines")?.toIntOrNull() ?: DEFAULT_LOG_LINES
    val bytes = device.logcat(lines).toByteArray(Charsets.UTF_8)
    return FleetHttpServer.Response.stream(200, "text/plain; charset=utf-8", bytes.size.toLong()) {
      it.write(bytes)
    }
  }

  companion object {
    const val DEFAULT_LOG_LINES = 500
  }
}
