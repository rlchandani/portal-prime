/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import com.immortal.launcher.settings.SettingsRegistry
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

/**
 * The phone-remote HTTP surface (`/remote/…`), delegated to from [FleetRoutes]. Served
 * by the same always-on, LAN-guarded fleet agent, but with its **own** auth: the public
 * setup page and PIN exchange need no token, while the input endpoints require a paired
 * session token (or the fleet bearer token, so the laptop CLI can drive it too). See
 * [RemotePairing] for the pairing model and [RemoteInput] for what input we can inject.
 *
 * Phase 1 is the Tier-A soft remote: global-action nav buttons + an app-launcher grid.
 * Keyboard, focus nav, gesture touchpad and presets extend these same routes later.
 */
class RemoteRoutes(private val context: Context) {

  private val companion by lazy { RemoteCompanionRoutes(context) }

  // One provider-owned read cache. Static requests never reopen assets after the first read.
  private val remoteAssets = mutableMapOf<String, ByteArray>()

  fun handle(req: FleetHttpServer.Request): FleetHttpServer.Response =
      when (req.path) {
        "/remote/companion", "/remote/layout", "/remote/snapshot", "/remote/catalog",
        "/remote/operation", "/remote/operations", "/remote/jarvis", "/remote/scenes",
        "/remote/scene", "/remote/photos", "/remote/photo", "/remote/photo-upload" -> companion.handle(req)
        // Public (LAN-guarded only): the page itself carries no secrets, and pairing
        // is gated by the PIN shown on the Portal's screen.
        "/remote/ui" -> requireMethod("GET", req) { html(RemoteHtml.PAGE) }
        "/remote/assets/control-center.css", "/remote/assets/control-center.js",
        "/remote/assets/core.mjs" -> requireMethod("GET", req) { staticAsset(req.path) }
        // PWA install surface: the manifest + app icon let the remote be added to the phone's
        // home screen as a standalone app, whose storage isn't evicted like a browser tab's —
        // the durable home for the synced roster. Public (no secrets), like the page itself.
        "/remote/manifest.webmanifest" -> requireMethod("GET", req) { manifest() }
        "/remote/app-icon" -> requireMethod("GET", req) { appIcon(req) }
        "/remote/pair" -> requireMethod("POST", req) { pair(req) }
        // App icons aren't sensitive; serving them unauthenticated keeps the token out
        // of <img> URLs. Still behind the server's LAN-only peer guard.
        "/remote/icon" -> requireMethod("GET", req) { icon(req) }
        // Album art isn't sensitive either — served unauthenticated like icons so the cover
        // can be an <img src> without leaking the token. LAN-guarded like everything else.
        "/remote/art" -> requireMethod("GET", req) { art(req) }
        // Authenticated: anything that reads the app list or drives input.
        "/remote/apps" -> authed(req) { apps() }
        "/remote/nowplaying" -> authed(req) { requireMethod("GET", req) { nowplaying() } }
        "/remote/media" -> authed(req) { media(req) }
        "/remote/volume" -> authed(req) { requireMethod("POST", req) { volume(req) } }
        "/remote/key" -> authed(req) { key(req) }
        "/remote/launch" -> authed(req) { launch(req) }
        "/remote/text" -> authed(req) { text(req) }
        "/remote/cursor" -> authed(req) { cursor(req) }
        "/remote/tap" -> authed(req) { tap() }
        "/remote/swipe" -> authed(req) { swipe(req) }
        "/remote/scroll" -> authed(req) { scroll(req) }
        "/remote/presets" -> authed(req) { presets(req) }
        "/remote/preset" -> authed(req) { runPreset(req) }
        "/remote/devices" -> authed(req) { devices() }
        "/remote/roster" -> authed(req) { roster(req) }
        "/remote/sources" -> authed(req) { sources(req) }
        "/remote/immich/albums" -> authed(req) { immichAlbums(req) }
        "/remote/settings" -> authed(req) { settings(req) }
        else -> json(404, err("not_found"))
      }

  // --- endpoints --------------------------------------------------------------

  private fun pair(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val body = parseJson(req.bodyText()) ?: return json(400, err("bad_json"))
    val token = RemotePairing.redeem(context, body.optString("pin")) ?: return json(401, err("bad_pin"))
    return json(200, ok().put("token", token).put("name", FleetConfig.name(context)))
  }

  private fun apps(): FleetHttpServer.Response =
      json(200, ok().put("apps", RemoteApps.listJson(context)))

  private fun icon(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val pkg = req.queryParam("pkg") ?: return json(400, err("pkg_required"))
    val png = RemoteApps.iconPng(context, pkg) ?: return json(404, err("no_icon"))
    return FleetHttpServer.Response.stream(200, "image/png", png.size.toLong()) { it.write(png) }
  }

  /**
   * The PWA web app manifest. Installing the remote to the home screen makes it a standalone app
   * and — crucially for "stay connected between sessions" — gives its storage the durable lifetime
   * an installed app gets, instead of the ~7-day eviction a plain browser tab faces. Served as
   * `application/manifest+json`; icons point at [appIcon] (Immortal's own launcher icon).
   */
  private fun manifest(): FleetHttpServer.Response {
    val obj =
        JSONObject()
            .put("name", "Immortal Remote")
            .put("short_name", "Immortal")
            .put("description", "Control your Immortal Portal from your phone.")
            .put("start_url", "/remote/ui")
            .put("scope", "/remote/")
            .put("display", "standalone")
            .put("orientation", "portrait")
            .put("background_color", "#0e0e10")
            .put("theme_color", "#0e0e10")
            .put(
                "icons",
                JSONArray()
                    .put(iconEntry(192, "any"))
                    .put(iconEntry(512, "any"))
                    .put(iconEntry(512, "maskable")))
    val bytes = obj.toString().toByteArray(Charsets.UTF_8)
    return FleetHttpServer.Response.stream(
        200, "application/manifest+json; charset=utf-8", bytes.size.toLong()) {
          it.write(bytes)
        }
  }

  private fun iconEntry(size: Int, purpose: String) =
      JSONObject()
          .put("src", "/remote/app-icon?size=$size")
          .put("sizes", "${size}x${size}")
          .put("type", "image/png")
          .put("purpose", purpose)

  /**
   * Immortal's own launcher icon as PNG, for the manifest icons and the iOS apple-touch-icon.
   * Public like [icon] (an app icon isn't a secret), and rendered at the requested (clamped) size
   * from our adaptive launcher icon — so the installed remote wears the same face as the app.
   */
  private fun appIcon(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val size = (req.queryParam("size")?.toIntOrNull() ?: 192).coerceIn(48, 1024)
    val png = RemoteApps.iconPng(context, context.packageName, size) ?: return json(404, err("no_icon"))
    return FleetHttpServer.Response.stream(200, "image/png", png.size.toLong()) { it.write(png) }
  }

  /** The current album art as PNG (in-memory bitmap, or a resolved metadata URI). */
  private fun art(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val snapshot = NowPlayingHub.current ?: return json(404, err("no_art"))
    val requestedVersion = req.queryParam("v")
    if (requestedVersion != null && requestedVersion != RemoteMedia.artVersion(snapshot).toString())
      return json(409, err("stale_art"))
    // Resolve the same immutable snapshot whose version was checked, even if the track changes
    // while a URI cover is loading. Old clients without v retain the existing relay behavior.
    val png = RemoteMedia.artPng(context, snapshot) ?: return json(404, err("no_art"))
    return FleetHttpServer.Response.stream(200, "image/png", png.size.toLong()) { it.write(png) }
  }

  /** Transport for the active media session: `{"action":"playpause|next|prev|seek","positionMs":…}`. */
  private fun media(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val body = parseJson(req.bodyText()) ?: return json(400, err("bad_json"))
    val action = body.optString("action")
    if (action !in setOf("playpause", "next", "prev", "previous", "seek"))
        return json(400, err("unknown_action"))
    val dispatched = RemoteMedia.command(action, body.optLong("positionMs", 0L))
    // 409 when nothing is playing — the action was understood but there's no session to drive.
    return json(if (dispatched) 200 else 409, JSONObject().put("ok", dispatched).put("action", action))
  }

  /** Current media capabilities and a fresh music-volume snapshot, even when nothing plays. */
  private fun nowplaying(): FleetHttpServer.Response {
    return runCatching {
      val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
          ?: return json(503, err("audio_unavailable"))
      json(200, ok().put("np", RemoteMedia.stateJson(NowPlayingHub.current, RemoteMedia.volumeState(audio))))
    }.getOrElse { json(500, err("volume_failed")) }
  }

  /** Music volume: `{"dir":"up|down|mute|set","level":…}`. Set takes a stream index,
   *  validated before dispatch; Android owns any normal mute behavior of setStreamVolume. */
  private fun volume(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val body = parseJson(req.bodyText()) ?: return json(400, err("bad_json"))
    val dir = body.optString("dir")
    if (dir !in setOf("up", "down", "mute", "set")) return json(400, err("unknown_dir"))
    return runCatching {
      val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
          ?: return json(503, err("audio_unavailable"))
      val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
      val level = if (dir == "set") {
        validatedVolumeLevel(body.opt("level"), max) ?: return json(400, err("invalid_level"))
      } else null
      if (audio.isVolumeFixed || max <= 0) return json(409, err("volume_fixed"))
      if (level != null) {
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, AudioManager.FLAG_SHOW_UI)
      } else {
        val adjust = when (dir) {
          "up" -> AudioManager.ADJUST_RAISE
          "down" -> AudioManager.ADJUST_LOWER
          else -> AudioManager.ADJUST_TOGGLE_MUTE
        }
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, adjust, AudioManager.FLAG_SHOW_UI)
      }
      json(200, ok().put("dir", dir))
    }.getOrElse { json(500, err("volume_failed")) }
  }

  private fun key(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val body = parseJson(req.bodyText()) ?: return json(400, err("bad_json"))
    val action = body.optString("action")
    // The paired Home button owns Immortal's home, even when the stock launcher is default.
    if (action == "home") {
      val opened = context.openApprovedHomeTab(ApprovedLauncherTab.HOME)
      return json(if (opened) 200 else 500, JSONObject().put("ok", opened).put("action", "home").put("dispatched", opened))
    }
    // Apps shares Home's retained page, including its navigation out of the photo frame.
    if (action == "apps") return openAppsTab()
    // "screensaver" starts the photo frame (same as the home-screen screensaver button).
    if (action == "screensaver") return openScreensaver()
    if (RemoteInput.globalActionCode(action) == null) return json(400, err("unknown_action"))
    if (!RemoteInput.available()) return json(503, err("no_accessibility"))
    val dispatched = RemoteInput.globalAction(action)
    return json(200, ok().put("action", action).put("dispatched", dispatched))
  }

  /** Select Apps in the retained Home host, just like the paired Home button. */
  private fun openAppsTab(): FleetHttpServer.Response {
    val ok = startAppsTab()
    return json(if (ok) 200 else 500, JSONObject().put("ok", ok).put("action", "apps"))
  }

  private fun startAppsTab(): Boolean = context.openApprovedHomeTab(ApprovedLauncherTab.APPS)

  /** Start the photo-frame screensaver ([PhotoFramePreviewActivity]) — the same thing the
   *  home-screen screensaver button does. Same-app start, so the non-exported activity is fine. */
  private fun openScreensaver(): FleetHttpServer.Response {
    val ok =
        runCatching {
              context.startActivity(
                  Intent(context, PhotoFramePreviewActivity::class.java)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
              true
            }
            .getOrDefault(false)
    return json(if (ok) 200 else 500, JSONObject().put("ok", ok).put("action", "screensaver"))
  }

  private fun launch(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val body = parseJson(req.bodyText()) ?: return json(400, err("bad_json"))
    val pkg = body.optString("packageName").ifBlank { null } ?: return json(400, err("packageName_required"))
    val launched = RemoteApps.launch(context, pkg)
    return json(if (launched) 200 else 404, JSONObject().put("ok", launched).put("packageName", pkg))
  }

  /** Edit the focused field: `{"mode":"set|append|backspace|clear","text":"…"}`. */
  private fun text(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val body = parseJson(req.bodyText()) ?: return json(400, err("bad_json"))
    if (!RemoteInput.available()) return json(503, err("no_accessibility"))
    val mode = body.optString("mode").ifBlank { "set" }
    val applied = RemoteInput.typeText(body.optString("text"), mode)
    // applied=false usually means no editable field has focus — report it so the UI can hint.
    return json(200, ok().put("applied", applied).put("mode", mode))
  }

  /** Move the on-TV pointer by a relative delta: `{"dx":12.0,"dy":-4.0}` (screen px). */
  private fun cursor(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val body = parseJson(req.bodyText()) ?: return json(400, err("bad_json"))
    if (!RemoteInput.gesturesAvailable()) return json(503, err("no_gestures"))
    RemoteInput.cursorMove(body.optDouble("dx", 0.0).toFloat(), body.optDouble("dy", 0.0).toFloat())
    return json(200, ok())
  }

  /** Tap at the current pointer position (synthesized touch). */
  private fun tap(): FleetHttpServer.Response {
    if (!RemoteInput.gesturesAvailable()) return json(503, err("no_gestures"))
    return json(200, ok().put("dispatched", RemoteInput.tap()))
  }

  /** Scroll/swipe from the pointer by a relative delta: `{"dx":0.0,"dy":-300.0}`. */
  private fun swipe(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val body = parseJson(req.bodyText()) ?: return json(400, err("bad_json"))
    if (!RemoteInput.gesturesAvailable()) return json(503, err("no_gestures"))
    return json(
        200,
        ok()
            .put(
                "dispatched",
                RemoteInput.swipe(
                    body.optDouble("dx", 0.0).toFloat(), body.optDouble("dy", 0.0).toFloat())))
  }

  /** List (GET) or replace (POST `{"presets":[…]}`) the user's saved preset macros. */
  private fun presets(req: FleetHttpServer.Request): FleetHttpServer.Response =
      when (req.method) {
        "GET" -> json(200, ok().put("presets", RemotePresets.listJson(context)))
        "POST" -> {
          val body = parseJson(req.bodyText())
          val arr = body?.optJSONArray("presets")
          if (arr == null) json(400, err("presets_required"))
          else {
            RemotePresets.save(context, arr)
            json(200, ok().put("presets", RemotePresets.listJson(context)))
          }
        }
        else -> json(405, err("method_not_allowed"))
      }

  /**
   * Screensaver photo-source + calendar setup — the unified "set up from your phone" surface (it
   * replaced the old standalone LAN form). GET returns the current source fields (pre-fill) and
   * calendar; POST applies a new source and/or calendar feed through the same fleet config path,
   * then reaffirms screensaver ownership so it takes effect.
   */
  private fun sources(req: FleetHttpServer.Request): FleetHttpServer.Response {
    return when (req.method) {
        "GET" -> {
          val s = ScreensaverConfig.load(context)
          json(200, ok().put("sources", FleetScreensaver.sourcesJson(s)).put("calendar", FleetCalendar.toJson(s)))
        }
        "POST" -> {
          val body = parseJson(req.bodyText())
          if (body == null) json(400, err("bad_json"))
          else {
            val prepared = RemoteCompanionSources.prepare(body, ScreensaverConfig.load(context))
            if (prepared.error != null) return json(400, err(prepared.error))
            val applied = FleetScreensaver.apply(context, prepared.body!!).toMutableList()
            // Optional calendar feed in the same save: {"calendarUrl":"…"} → FleetCalendar {"url"}.
            if (body.has("calendarUrl")) {
              applied += FleetCalendar.apply(context, JSONObject().put("url", body.optString("calendarUrl")))
            }
            SettingsGuard.afterScreensaverApply(context, applied)
            val s = ScreensaverConfig.load(context)
            json(
                200,
                ok()
                    .put("applied", JSONArray(applied))
                    .put("sources", FleetScreensaver.sourcesJson(s))
                    .put("calendar", FleetCalendar.toJson(s)))
          }
        }
        else -> json(405, err("method_not_allowed"))
      }

  }

  /**
   * List an Immich server's albums for the Setup form's album picker: POST `{"url":"…","key":"…"}`
   * — the form's live field values, so the picker works before the connection is saved; blank
   * fields fall back to the stored connection. Runs on the fleet server's pool thread, so the
   * blocking fetch is fine here. 502 when the server can't be reached or rejects the key, so the
   * form can hint at the problem instead of showing an empty list.
   */
  private fun immichAlbums(req: FleetHttpServer.Request): FleetHttpServer.Response {
    if (req.method != "POST") return json(405, err("method_not_allowed"))
    val body = parseJson(req.bodyText()) ?: return json(400, err("bad_json"))
    val stored = ScreensaverConfig.load(context)
    val url = body.optString("url").ifBlank { stored.immichUrl ?: "" }
    val candidate = JSONObject().put("immichUrl", url)
    body.optString("key").takeIf { it.isNotBlank() }?.let { candidate.put("immichKey", it) }
    val prepared = RemoteCompanionSources.prepare(candidate, stored)
    if (prepared.error != null) return json(400, err("immich_credentials_required"))
    val key = prepared.body!!.getString("immichKey")
    val albums = ImmichSource.listAlbums(url, key) ?: return json(502, err("immich_unreachable"))
    return json(200, ok().put("albums", FleetScreensaver.albumsJson(albums)))
  }

  /**
   * Generic settings surface driven by the declarative [SettingsRegistry]. GET returns every
   * registered domain's schema — controls, current values, constraints, declarative visibility —
   * so the remote renders it without hardcoding field lists. POST applies a batch to one domain:
   * `{"domain":"screensaver","values":{"intervalSec":45,"shuffle":true}}`, routed through the same
   * registry the on-device UI and fleet endpoints use, firing that domain's side effects (e.g. the
   * screensaver reaffirm + overnight reschedule) once. Photo-source credentials stay on
   * [sources] — they're read-only here — so this surface is the display + calendar controls.
   */
  private fun settings(req: FleetHttpServer.Request): FleetHttpServer.Response =
      when (req.method) {
        "GET" -> json(200, ok().put("settings", SettingsRegistry.schemaJson(context)))
        "POST" -> {
          val body = parseJson(req.bodyText())
          val domainId = body?.optString("domain")?.ifBlank { null }
          when {
            body == null -> json(400, err("bad_json"))
            domainId == null -> json(400, err("domain_required"))
            else -> {
              val values = body.optJSONObject("values") ?: JSONObject()
              val applied = SettingsRegistry.apply(context, domainId, values)
              if (applied == null) json(404, err("unknown_domain"))
              else
                  json(
                      200,
                      ok()
                          .put("applied", JSONArray(applied.toList()))
                          .put("domain", SettingsRegistry.domain(domainId)!!.schemaJson(context)))
            }
          }
        }
        else -> json(405, err("method_not_allowed"))
      }

  /**
   * The phone's device roster, backed up here so any paired Portal's page can rehydrate the whole
   * fleet — pair one device and the rest come back, instead of re-pairing all of them after a
   * browser wipes its storage. GET returns the stored roster; POST replaces it with the phone's
   * current `[{name, base, token}]`. Auth-gated like every input route: the on-screen PIN that
   * mints a session is the same gate that releases the roster, so only someone who paired in
   * person can read it. See [RemoteRoster] for the storage + sanitisation.
   */
  private fun roster(req: FleetHttpServer.Request): FleetHttpServer.Response =
      when (req.method) {
        "GET" -> json(200, ok().put("roster", RemoteRoster.load(context)))
        "POST" -> {
          val body = parseJson(req.bodyText())
          val arr = body?.optJSONArray("roster")
          if (arr == null) json(400, err("roster_required"))
          else {
            RemoteRoster.save(context, arr.toString())
            json(200, ok().put("roster", RemoteRoster.load(context)))
          }
        }
        else -> json(405, err("method_not_allowed"))
      }

  /** Peers discovered on the LAN via mDNS, plus this device's own name, for the device switcher. */
  private fun devices(): FleetHttpServer.Response =
      json(
          200,
          ok().put("self", FleetConfig.name(context)).put("devices", RemoteDiscovery.peersJson()))

  /** Run a saved preset by id: `{"id":"…"}`. Runs async (steps may include waits). */
  private fun runPreset(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val body = parseJson(req.bodyText()) ?: return json(400, err("bad_json"))
    val id = body.optString("id").ifBlank { null } ?: return json(400, err("id_required"))
    val preset = RemotePresets.find(context, id) ?: return json(404, err("unknown_preset"))
    val steps = preset.optJSONArray("steps") ?: JSONArray()
    thread(name = "remote-preset") { runSteps(steps) }
    return json(200, ok().put("running", id).put("steps", steps.length()))
  }

  /** Execute a preset's steps in order. Each maps to an action the remote already performs. */
  private fun runSteps(steps: JSONArray) {
    for (i in 0 until steps.length()) {
      val s = steps.optJSONObject(i) ?: continue
      when (s.optString("type")) {
        "launch" -> RemoteApps.launch(context, s.optString("packageName"))
        "key" ->
            // Legacy "recents" steps share the Apps button's retained Home page.
            if (s.optString("action") == "apps" || s.optString("action") == "recents") startAppsTab()
            else RemoteInput.globalAction(s.optString("action"))
        "text" -> RemoteInput.typeText(s.optString("text"), s.optString("mode").ifBlank { "set" })
        "wait" -> runCatching { Thread.sleep(clampWaitMs(s.optLong("ms", 300))) }
        "config" -> applyConfig(s.optString("target").ifBlank { "screensaver" }, s.optJSONObject("body"))
      }
    }
  }

  /**
   * The remote×fleet bridge: a preset step can push device config, reusing the very same
   * fleet subsystems the laptop tool drives ([FleetScreensaver] / [FleetCalendar]) — so a
   * one-tap preset can both drive input and reconfigure the Portal (e.g. set the screensaver
   * source then go Home). Mirrors the `/screensaver` route's post-apply reaffirm/reschedule.
   */
  private fun applyConfig(target: String, body: JSONObject?) {
    val b = body ?: return
    when (target) {
      "screensaver" -> {
        val applied = FleetScreensaver.apply(context, b)
        SettingsGuard.afterScreensaverApply(context, applied)
      }
      "calendar" -> FleetCalendar.apply(context, b)
    }
  }

  /** Page-scroll the foreground content: `{"dir":"up"|"down"}` (one big center swipe). */
  private fun scroll(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val body = parseJson(req.bodyText()) ?: return json(400, err("bad_json"))
    if (!RemoteInput.gesturesAvailable()) return json(503, err("no_gestures"))
    return json(200, ok().put("dispatched", RemoteInput.scrollPage(body.optString("dir") != "up")))
  }

  // --- auth + helpers ---------------------------------------------------------

  /** Run [block] only for a request bearing a valid session or the fleet token. */
  private inline fun authed(
      req: FleetHttpServer.Request,
      block: () -> FleetHttpServer.Response,
  ): FleetHttpServer.Response = if (authorized(req)) block() else json(401, err("unauthorized"))

  private fun authorized(req: FleetHttpServer.Request): Boolean {
    val token = bearer(req.header("authorization")) ?: return false
    return RemotePairing.isValidSession(context, token) ||
        FleetRoutes.constantTimeEquals(token, FleetConfig.token(context))
  }

  private inline fun requireMethod(
      method: String,
      req: FleetHttpServer.Request,
      block: () -> FleetHttpServer.Response,
  ): FleetHttpServer.Response = if (req.method == method) block() else json(405, err("method_not_allowed"))

  private fun staticAsset(path: String): FleetHttpServer.Response {
    val asset = staticAssetName(path) ?: return json(404, err("not_found"))
    val bytes = synchronized(remoteAssets) {
      remoteAssets[asset] ?: runCatching { context.assets.open("remote/$asset").use { it.readBytes() } }
          .getOrNull()?.also { remoteAssets[asset] = it }
    } ?: return json(404, err("not_found"))
    val type = if (asset.endsWith(".css")) "text/css; charset=utf-8" else "text/javascript; charset=utf-8"
    return FleetHttpServer.Response.stream(200, type, bytes.size.toLong(),
        headers = mapOf("Cache-Control" to "no-cache", "X-Content-Type-Options" to "nosniff")) { it.write(bytes) }
  }

  private fun html(page: String): FleetHttpServer.Response {
    val bytes = page.toByteArray(Charsets.UTF_8)
    return FleetHttpServer.Response.stream(200, "text/html; charset=utf-8", bytes.size.toLong()) {
      it.write(bytes)
    }
  }

  private fun ok() = JSONObject().put("ok", true)

  private fun err(code: String) = JSONObject().put("ok", false).put("error", code)

  private fun parseJson(s: String): JSONObject? =
      runCatching { JSONObject(if (s.isBlank()) "{}" else s) }.getOrNull()

  private fun json(status: Int, obj: JSONObject) = FleetHttpServer.Response(status, obj.toString())

  internal companion object {
    /** Exact allowlist: no user-controlled path reaches AssetManager. */
    internal fun staticAssetName(path: String): String? = when (path) {
      "/remote/assets/control-center.css" -> "control-center.css"
      "/remote/assets/control-center.js" -> "control-center.js"
      "/remote/assets/core.mjs" -> "core.mjs"
      else -> null
    }

    /** Extract the token from an `Authorization: Bearer <token>` header. Pure. */
    internal fun bearer(header: String?): String? {
      val h = header?.trim() ?: return null
      if (!h.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) return null
      return h.substring(7).trim().ifBlank { null }
    }

    /** Accept only JSON integer indices in the stream's range, without coercion or overflow. */
    internal fun validatedVolumeLevel(value: Any?, max: Int): Int? {
      val level = when (value) {
        is Int -> value.toLong()
        is Long -> value
        else -> return null
      }
      return if (level >= 0L && level <= max.toLong()) level.toInt() else null
    }

    /** Clamp a preset "wait" step to a sane bound (0..10s) so a typo can't hang the runner. Pure. */
    internal fun clampWaitMs(ms: Long): Long = ms.coerceIn(0L, 10_000L)
  }
}
