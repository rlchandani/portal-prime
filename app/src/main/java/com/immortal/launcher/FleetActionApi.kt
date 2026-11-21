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
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * `POST /action`: operator actions and test hooks, each an allow-list of its own (actions, ops,
 * screens, orientations, plans). Pure: the effects come in through [FleetActions].
 */
internal class FleetActionApi(private val actions: FleetActions) {

  fun handle(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val body = parseJson(req.bodyText()) ?: return json(400, err("bad_json"))
    return when (body.optString("action")) {
      "reaffirm" -> {
        actions.reaffirm()
        json(200, ok().put("action", "reaffirm"))
      }
      "identify" -> {
        actions.identify()
        json(200, ok().put("action", "identify"))
      }
      "settings_sync" -> settingsSync(body)
      "ha_adopt" -> haAdopt(body)
      "ha_peers" -> json(200, ok().put("action", "ha_peers").put("peers", actions.haPeers()))
      // Opens the Home Control Center (optionally on a screen: map, cameras, tesla, alerts,
      // security, edit, settings) — for remote checks and on-device measurement.
      "open_control_center" -> {
        val opened = actions.openControlCenter(body.optString("screen", "map"))
        json(200, JSONObject().put("ok", opened).put("action", "open_control_center"))
      }
      "phone_test_session" -> phoneTestSession(body)
      "cc_debug" -> ccDebug(body)
      "screensaver_test_source" -> screensaverTestSource(body)
      "digital_clock_preview" -> digitalClockPreview(body)
      // Who's there? test switches (operator only, in memory): {"ignoreQuietHours":bool, "glance":true, "benchmark":true}.
      "whos_there_debug" -> json(200, JSONObject().put("ok", true).put("action", "whos_there_debug")
          .put("result", actions.whosThereDebug(body)))
      "open_screen" -> openScreen(body)
      // Rotation test hook (operator only): {"request":"portrait"|"landscape"|"reverse_landscape"|"auto"|"forget"}.
      "orientation" -> {
        val request = body.optString("request").takeIf { it in ORIENTATIONS }
        json(200, JSONObject().put("ok", true).put("action", "orientation")
            .put("result", actions.orientation(request)))
      }
      // Needs the signature/privileged REBOOT permission we don't hold — report honestly.
      "reboot" -> json(200, JSONObject().put("ok", false).put("action", "reboot").put("result", "not_permitted"))
      else -> json(400, err("unknown_action"))
    }
  }

  /**
   * Household settings sync: status, the one-time join choice, and pause/resume for testing
   * catch-up (a paused Portal neither sends nor accepts sync traffic).
   */
  private fun settingsSync(body: JSONObject): FleetHttpServer.Response {
    val op = body.optString("op", "status")
    if (op !in SYNC_OPS) return json(400, err("bad_op"))
    if (op != "status" && op != "values") actions.settingsSync(op)
    val out = ok().put("action", "settings_sync").put("sync", actions.settingsSyncStatus())
    if (body.optString("op") == "values") out.put("values", actions.settingsSyncValues())
    return json(200, out)
  }

  /**
   * Use another household Portal's Home Assistant connection: {"from":"<device id or Portal name>"}
   * (+ "replace":true to swap out a different saved connection). The connection is validated with
   * Home Assistant before it is saved. The reply carries the outcome and the peer's name only —
   * never the address or token.
   */
  private fun haAdopt(body: JSONObject): FleetHttpServer.Response {
    val from = (body.opt("from") as? String)?.trim()?.takeIf { it.length in 1..MAX_PEER_REF }
        ?: return json(400, err("bad_from"))
    val outcome = actions.haAdopt(from, replace = body.optBoolean("replace", false))
    return json(200, JSONObject().put("ok", outcome.result.ok).put("action", "ha_adopt")
        .put("result", outcome.result.wire).put("from", outcome.peerName ?: JSONObject.NULL)
        .put("validated", outcome.validated))
  }

  /**
   * Operator verification of phone routes: {"op":"mint"} returns a paired-phone session marked as a
   * test ({"householdGrant":true} also gives it the post-pairing household-key window, as a PIN
   * pairing would); {"op":"age","days":N} moves test sessions' last use N days back (debug clock
   * for expiry); {"op":"pin"} issues a live pairing code, as "Show pairing code" would, so the
   * real /remote/pair path and its rate limit can be checked without the screen (the session it
   * mints is a test session); {"op":"revoke_id","id":"…"} removes one paired phone; {"op":"revoke"}
   * removes every test session (real pairings stay). The fleet token already has full control of
   * this Portal (it can mint sessions directly), so none of this grants anything new.
   */
  private fun phoneTestSession(body: JSONObject): FleetHttpServer.Response = when (body.optString("op")) {
    "mint" -> json(200, ok().put("action", "phone_test_session").put("remoteEnabled", actions.remoteEnabled())
        .put("token", actions.mintTestSession(householdGrant = body.optBoolean("householdGrant"))))
    "pin" -> json(200, ok().put("action", "phone_test_session").put("pin", actions.newTestPin()))
    // Removes one paired phone by its record id (the 16-hex id Settings lists), test or real.
    "revoke_id" -> {
      val id = body.optString("id")
      if (!id.matches(SESSION_ID)) json(400, err("bad_id"))
      else json(200, ok().put("action", "phone_test_session").put("removed", actions.revokeSession(id)))
    }
    "age" -> {
      val days = body.optLong("days", -1L)
      if (days !in 0..MAX_TEST_AGE_DAYS) json(400, err("bad_days"))
      else json(200, ok().put("action", "phone_test_session")
          .put("aged", actions.ageTestSessions(TimeUnit.DAYS.toMillis(days))))
    }
    "revoke" -> json(200, ok().put("action", "phone_test_session").put("revoked", actions.revokeTestSessions()))
    else -> json(400, err("bad_op"))
  }

  /** Control Center test switches (operator only): {"dryRun":true|false, "testPlan":"load"|"clear", "haUnreachable":true|false, "showTest":kind}. */
  private fun ccDebug(body: JSONObject): FleetHttpServer.Response {
    val dry = if (body.has("dryRun")) body.optBoolean("dryRun") else null
    val plan = body.optString("testPlan").takeIf { it in CC_TEST_PLANS }
    val alert = body.optString("testAlert").takeIf { it.matches(CC_TEST_ALERT) }
    val unreachable = if (body.has("haUnreachable")) body.optBoolean("haUnreachable") else null
    // {"fakeState":{"slug":"leak1","state":"on","deviceClass":"moisture","changedMinAgo":0}}: dry run only.
    val fake = if (body.has("fakeState")) CcFakeState.parse(body.optJSONObject("fakeState"))
        ?: return json(400, err("bad_fakeState")) else null
    // {"showTest":"takeover"|"doorbell"}: a silent held overlay (dry run only), for the accessibility-unbind checks.
    if (body.has("showTest")) {
      val kind = body.optString("showTest").takeIf { it in CcOverlayTest.KINDS } ?: return json(400, err("bad_showTest"))
      return json(200, JSONObject().put("ok", true).put("action", "cc_debug").put("result", actions.ccShowTest(kind)))
    }
    return json(200, JSONObject().put("ok", true).put("action", "cc_debug")
        .put("result", actions.ccDebug(dry, plan, alert, unreachable, fake)))
  }

  /**
   * Verification only: swap every photo source for generated scenes (expires on its own), or
   * switch back. {"enabled":true|false, "minutes":1..240, "night":false|true, "mode":"<mode>",
   * "intervalSec":5..600, "face":"<photo face id>"}. Any frame already on
   * screen is retired first so household photos are never on screen while the switch is on;
   * enabling then opens a fresh frame on the generated scenes. Release builds report unavailable.
   */
  private fun screensaverTestSource(body: JSONObject): FleetHttpServer.Response {
    val enable = body.optBoolean("enabled", false)
    val minutes = body.optInt("minutes", DEFAULT_TEST_SOURCE_MINUTES)
    if (enable && minutes !in 1..ScreensaverTestSource.MAX_MINUTES) return json(400, err("bad_minutes"))
    // "night":true also forces the dark-room state (night colours) without the light sensor.
    val night = body.optBoolean("night", false)
    // Performance A/B: "mode" (+ "intervalSec", default 30) pins one screensaver mode at a fixed
    // beat for the same span instead of the household's mode and interval.
    val pinMode = body.optString("mode", "")
    val pin = if (enable && pinMode.isNotEmpty()) {
      ScreensaverTestSource.Pin.of(pinMode, body.optInt("intervalSec", DEFAULT_PIN_INTERVAL_SEC),
          body.optString("face", "").takeIf { it.isNotEmpty() })
          ?: return json(400, err("bad_pin"))
    } else null
    val applied = if (enable) actions.enableTestSource(minutes, night = night, pin = pin) else actions.disableTestSource()
    if (applied) actions.reopenFrame(enable)
    return json(200, JSONObject().put("ok", applied).put("action", "screensaver_test_source")
        .put("digitalClockFace", actions.clockLook().face)
        .put("available", actions.testSourceAvailable())
        .put("active", actions.testSourceActive())
        .put("night", actions.testSourceNight())
        .put("pin", actions.testSourcePin() ?: JSONObject.NULL)
        .put("until", actions.testSourceUntil()))
  }

  /**
   * Verification only (test-source builds): open the digital clock preview, optionally on a
   * face {"face":"classic|bold|neon|led|flip|analog"}. The reply carries the exact previous
   * look; send it back as {"restore":{...},"open":false} afterwards.
   */
  private fun digitalClockPreview(body: JSONObject): FleetHttpServer.Response {
    if (!actions.testSourceAvailable()) return json(400, err("unavailable"))
    val face = body.optString("face").takeIf { actions.isClockFace(it) }
    val prior = actions.clockLook()
    val before = JSONObject().put("style", prior.style).put("font", prior.font)
        .put("color", prior.color).put("glow", prior.glow)
    body.optJSONObject("restore")?.let { r ->
      // Write back the exact previous look (it may be a custom combination, not a preset).
      r.optString("style").takeIf { it.isNotEmpty() }?.let(actions::setClockStyle)
      r.optString("font").takeIf { it.isNotEmpty() }?.let(actions::setClockFont)
      r.optString("color").takeIf { it.isNotEmpty() }?.let(actions::setClockColor)
      r.optString("glow").takeIf { it.isNotEmpty() }?.let(actions::setClockGlow)
    }
    if (face != null) actions.setClockFace(face)
    if (body.optBoolean("open", true)) actions.openClockPreview()
    return json(200, ok().put("action", "digital_clock_preview").put("previousFace", before)
        .put("face", actions.clockLook().face))
  }

  /** Layout test hook (operator only): opens one of [SCREENS] for captures. */
  private fun openScreen(body: JSONObject): FleetHttpServer.Response {
    val screen = body.optString("screen")
    // Optional Settings page for captures, e.g. {"screen":"settings","page":"people"}.
    val page = body.optString("page").takeIf { screen == SETTINGS_SCREEN && actions.isSettingsPage(it) }
    val opened = screen in SCREENS && actions.openScreen(screen, page)
    return json(200, JSONObject().put("ok", opened).put("action", "open_screen"))
  }

  companion object {
    /** Upper bound for the `phone_test_session` debug clock (a little past the 1-year mark). */
    const val MAX_TEST_AGE_DAYS = 400L
    const val DEFAULT_TEST_SOURCE_MINUTES = 30
    const val DEFAULT_PIN_INTERVAL_SEC = 30
    const val SETTINGS_SCREEN = "settings"
    /** A device id (≤ 64) or a Portal name (≤ 40 after cleaning) for `ha_adopt`. */
    const val MAX_PEER_REF = 64
    val SYNC_OPS = setOf("status", "values", "pause", "resume", "adopt", "keep", "rejoin")
    val ORIENTATIONS = setOf("portrait", "landscape", "reverse_landscape", "auto", "forget")
    val CC_TEST_PLANS = setOf("load", "clear")
    val CC_TEST_ALERT = Regex("[a-z_]{1,32}")
    val SESSION_ID = Regex("[0-9a-f]{16}")
    /** The internal screens `open_screen` may open (the adapter maps each to its Activity). */
    val SCREENS = setOf("calls", SETTINGS_SCREEN, "help", "household_list", "ha_dashboard", "lamp",
        "guest_card", "store", "web_app", "whos_there_enrol")
  }
}
