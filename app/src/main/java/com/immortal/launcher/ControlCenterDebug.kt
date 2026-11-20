/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Operator-only test switches for the Control Center, set through the authenticated Fleet agent
 * (`/action {"action":"cc_debug", …}`), never from the Portal UI:
 *  - **dry run**: service calls are logged (domain.service + data keys only) and simulated
 *    locally instead of being sent to Home Assistant;
 *  - **HA unreachable** (in memory only, cleared on restart): the socket targets a dead port, so the
 *    stale and unreachable states can be checked without touching the saved connection;
 *  - **test plan**: a plan built from the real HA floors/areas (the editor's suggested rooms +
 *    auto-placed devices) kept in a separate local file. While it is active the plan is never
 *    written to Home Assistant (`frontend/set_user_data` is not sent) and HA's own plan is ignored.
 * Both show a pill in the header so a capture can't be mistaken for the real home.
 */
internal object CcDebugStore {
  private const val PREFS = "hearthpane_control_center_debug"
  private const val KEY_DRY = "dry_run"
  private const val FILE = "control_center_test_plan.json"

  fun dryRun(c: Context): Boolean = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DRY, false)

  fun setDryRun(c: Context, on: Boolean) =
      c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DRY, on).apply()

  fun testPlan(c: Context): CcPlan? = runCatching {
    File(c.filesDir, FILE).takeIf { it.exists() }?.readText()?.let(CcPlanJson::decodeString)
  }.getOrNull()

  fun saveTestPlan(c: Context, plan: CcPlan): Boolean = runCatching {
    File(c.filesDir, FILE).writeText(CcPlanJson.encode(plan).toString())
  }.onFailure { Log.w(CcEngine.TAG, "test plan not saved (${it.javaClass.simpleName})") }.isSuccess

  fun clearTestPlan(c: Context) { runCatching { File(c.filesDir, FILE).delete() } }
}

/** Builds the test plan from the registry (pure, unit tested): suggested rooms + auto-placed devices per floor. */
internal object CcTestPlan {
  fun build(reg: CcRegistry, live: Map<String, CcEntity>, now: Long): CcPlan {
    val floors = CcPlanEdit.floorsFor(reg).mapNotNull { f ->
      val rooms = CcPlanEdit.suggestRooms(CcPlanEdit.areasFor(reg, f.id))
      if (rooms.isEmpty()) null else CcPlanEdit.autoPlace(CcFloor(f.id, f.name, rooms), reg, live)
    }
    return CcPlan(floors, now)
  }

  /** "Dry run · Test plan" for the header pill, or null in normal use. */
  fun label(dryRun: Boolean, testPlan: Boolean, unreachable: Boolean = false): String? = listOfNotNull(
      "Dry run".takeIf { dryRun }, "Test plan".takeIf { testPlan }, "HA unreachable".takeIf { unreachable })
      .joinToString(" · ").ifBlank { null }

  /** The socket URL with its port swapped for the discard port (nothing listens), or null if unparseable. */
  fun unreachableUrl(url: String): String? = runCatching {
    val u = java.net.URI(url)
    if (u.scheme == null || u.host == null) return null
    java.net.URI(u.scheme, u.userInfo, u.host, DEAD_PORT, u.path, u.query, null).toString()
  }.getOrNull()

  private const val DEAD_PORT = 9
}

// --- engine side (engine thread) ----------------------------------------------------------

/**
 * What HA would end up echoing for [opt]: a transition settles (no device will report "open" later
 * in a dry run, so "opening" would stick), and a state change restarts `last_changed`.
 */
internal fun dryRunEcho(raw: CcEntity, opt: CcOptimistic, now: Long): CcEntity {
  val applied = opt.applyTo(raw)
  val next = DRY_RUN_SETTLED[applied.state]?.let { applied.copy(state = it) } ?: applied
  return if (next.state == raw.state) next else next.copy(lastChanged = now, userId = null)
}

/**
 * The state a dry run shows after [call]. Alarm calls carry no optimistic state on purpose (a real
 * panel must confirm), so the dry run supplies the panel's resulting mode to keep the flow testable.
 */
internal fun dryRunOptimistic(call: CcCall): CcOptimistic? =
    call.optimistic ?: DRY_RUN_ALARM[call.service]?.takeIf { call.domain == "alarm_control_panel" }?.let { CcOptimistic(state = it) }

private val DRY_RUN_ALARM = mapOf(
    "alarm_disarm" to "disarmed", "alarm_arm_home" to "armed_home", "alarm_arm_away" to "armed_away",
    "alarm_arm_night" to "armed_night", "alarm_arm_vacation" to "armed_vacation")

private val DRY_RUN_SETTLED = mapOf("opening" to "open", "closing" to "closed")

/** Dry run: log, then behave as if HA had echoed the optimistic state at once. */
internal fun CcEngine.dryRunCall(call: CcCall, onResult: ((Boolean, String?) -> Unit)?) {
  log.i("dry-run ${call.domain}.${call.service} data=${call.data.keys.sorted()}")
  val id = call.entityId
  val raw = id?.let { live.raw[it] }
  val opt = dryRunOptimistic(call)
  if (raw != null && opt != null) live = live.update(mapOf(id to dryRunEcho(raw, opt, clock())), clock())
  toast("Dry run: ${call.label.ifBlank { "${call.domain}.${call.service}" }} (not sent)")
  onResult?.invoke(true, null)
}

/**
 * Fleet `cc_debug` `testAlert`: runs the banner policy for a synthetic critical alert of HA device class
 * [deviceClass] with dry run forced on — the decision is logged, nothing is shown or played.
 */
internal fun CcEngine.testAlert(deviceClass: String): String {
  val kind = if (deviceClass == "moisture") CcKind.LEAK else CcKind.SMOKE
  val alert = CcAlert("test:$deviceClass:${clock()}", CcSeverity.CRITICAL, kind, "binary_sensor.test", "Test alert", "", clock(),
      CcAlertAction.VIEW, deviceClass)
  host.offerBanner(alert, ui.settings, appVisible = visible > 0, dryRun = true)
  return "testAlert=$deviceClass logged (dry run)"
}

/**
 * Fleet `cc_debug` `fakeState`: a made-up state change for [CcFakeState.entityId], run through the
 * normal live update → alerts → background banner path. Dry run only (refused otherwise), so the
 * policy's decisions are logged and nothing is shown, played or relayed. The fake entity stays in
 * the live view until the next reconnect; send `"state":"off"` (or closed) to end its alert.
 */
internal fun CcEngine.fakeState(fake: CcFakeState): String {
  if (!dryRun) return "fakeState refused: turn dry run on first"
  if (ui.connection.phase != CcPhase.LIVE) return "fakeState refused: not live (${ui.connection.phase.name.lowercase()})"
  val now = clock()
  val entity = CcEntity(fake.entityId, fake.state, mapOf("device_class" to fake.deviceClass, "friendly_name" to fake.name),
      lastChanged = now - fake.changedMinAgo * CcFakeState.MS_PER_MIN)
  live = live.update(mapOf(fake.entityId to entity), now)
  publish(alertsToo = true)
  return "fakeState ${fake.entityId}=${fake.state} class=${fake.deviceClass} changedMinAgo=${fake.changedMinAgo} (dry run)"
}

/** A validated `cc_debug` `fakeState` request. Ids are forced into a `fake_` namespace so a real entity is never overwritten. */
internal data class CcFakeState(val entityId: String, val state: String, val deviceClass: String, val name: String, val changedMinAgo: Long) {
  companion object {
    const val MS_PER_MIN = 60_000L
    private const val MAX_MIN_AGO = 24 * 60L
    private val SLUG = Regex("[a-z0-9_]{1,40}")
    private val STATES = setOf("on", "off", "open", "closed")
    private val DOMAINS = setOf("binary_sensor", "cover")

    fun parse(o: org.json.JSONObject?): CcFakeState? {
      if (o == null) return null
      val domain = o.optString("domain", "binary_sensor").takeIf { it in DOMAINS } ?: return null
      val slug = o.optString("slug").takeIf { it.matches(SLUG) } ?: return null
      val state = o.optString("state").takeIf { it in STATES } ?: return null
      val cls = o.optString("deviceClass").takeIf { it.matches(SLUG) } ?: return null
      val minAgo = o.optLong("changedMinAgo", 0L).takeIf { it in 0..MAX_MIN_AGO } ?: return null
      val name = o.optString("name").take(40).ifBlank { "Fake $cls" }
      return CcFakeState("$domain.fake_$slug", state, cls, name, minAgo)
    }
  }
}

/** Fleet `cc_debug`: switch dry run, load/clear the local-only test plan. Returns a short status. */
internal fun CcEngine.debug(dry: Boolean?, plan: String?, unreachable: Boolean? = null): String {
  dry?.let { dryRun = it; host.setDryRun(it) }
  when (plan) {
    "load" -> ui.registry?.let { reg ->
      val p = CcTestPlan.build(reg, live.raw, clock())
      host.saveTestPlan(p)
      testPlan = p
      ui = ui.copy(plan = p, planSource = CcPlanSource.THIS_PORTAL)
    } ?: return "no registry yet (open the Control Center once, or wait for Home Assistant)"
    "clear" -> { host.clearTestPlan(); testPlan = null; ui = ui.copy(plan = host.loadPlan() ?: CcPlan.EMPTY) }
  }
  val reconnect = unreachable != null && unreachable != haUnreachable
  unreachable?.let { haUnreachable = it }
  ui = ui.copy(debug = CcTestPlan.label(dryRun, testPlan != null, haUnreachable))
  publish()
  if (reconnect) restart() else ensure()
  val rooms = testPlan?.floors?.sumOf { it.rooms.size } ?: 0
  return "dryRun=$dryRun testPlan=${testPlan != null} floors=${testPlan?.floors?.size ?: 0} rooms=$rooms haUnreachable=$haUnreachable"
}

/**
 * Fleet `cc_debug` `showTest`: a silent test of a held critical overlay ([OverlayHold]) — a leak
 * takeover or a camera-less doorbell card — so the accessibility-unbind and app-update paths can be
 * checked on a Portal. Dry run only. Never a sound, a relay, the alert gate or the alert history;
 * the doorbell card has no camera, so nothing private is drawn.
 */
internal object CcOverlayTest {
  const val TAKEOVER = "takeover"
  const val DOORBELL = "doorbell"
  val KINDS = setOf(TAKEOVER, DOORBELL)

  fun show(context: Context, kind: String): String {
    val app = context.applicationContext
    if (!CcDebugStore.dryRun(app)) return "showTest refused: turn dry run on first"
    val stamp = System.currentTimeMillis()
    when (kind) {
      TAKEOVER -> ControlCenterBanners.offerTest(app, CcAlert("test:overlay:$stamp", CcSeverity.CRITICAL, CcKind.LEAK,
          "binary_sensor.test", "Test alert", "Operator test · not a real leak", stamp, CcAlertAction.VIEW, "moisture"))
      DOORBELL -> android.os.Handler(android.os.Looper.getMainLooper()).post {
        DoorbellCardOverlay.show(app, DoorbellCardOverlay.Card("test:doorbell:$stamp", "Test doorbell",
            "Operator test · not a real visitor", "Silent test", quiet = true, camera = ""))
      }
      else -> return "showTest refused: unknown kind"
    }
    Log.i("HearthpaneOverlayHold", "showTest $kind (silent)")
    return "showTest=$kind shown (silent, dry run)"
  }
}
