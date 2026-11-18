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
  fun label(dryRun: Boolean, testPlan: Boolean): String? = listOfNotNull(
      "Dry run".takeIf { dryRun }, "Test plan".takeIf { testPlan }).joinToString(" · ").ifBlank { null }
}

// --- engine side (engine thread) ----------------------------------------------------------

/** Dry run: log, then behave as if HA had echoed the optimistic state at once. */
internal fun CcEngine.dryRunCall(call: CcCall, onResult: ((Boolean, String?) -> Unit)?) {
  Log.i(CcEngine.TAG, "dry-run ${call.domain}.${call.service} data=${call.data.keys.sorted()}")
  val id = call.entityId
  val raw = id?.let { live.raw[it] }
  if (raw != null && call.optimistic != null) live = live.update(mapOf(id to call.optimistic.applyTo(raw)), clock())
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
  ControlCenterBanners.offer(app, alert, ui.settings, appVisible = visible > 0, dryRun = true)
  return "testAlert=$deviceClass logged (dry run)"
}

/** Fleet `cc_debug`: switch dry run, load/clear the local-only test plan. Returns a short status. */
internal fun CcEngine.debug(dry: Boolean?, plan: String?): String {
  dry?.let { dryRun = it; CcDebugStore.setDryRun(app, it) }
  when (plan) {
    "load" -> ui.registry?.let { reg ->
      val p = CcTestPlan.build(reg, live.raw, clock())
      CcDebugStore.saveTestPlan(app, p)
      testPlan = p
      ui = ui.copy(plan = p, planSource = CcPlanSource.THIS_PORTAL)
    } ?: return "no registry yet (open the Control Center once, or wait for Home Assistant)"
    "clear" -> { CcDebugStore.clearTestPlan(app); testPlan = null; ui = ui.copy(plan = CcPlanStore.load(app) ?: CcPlan.EMPTY) }
  }
  ui = ui.copy(debug = CcTestPlan.label(dryRun, testPlan != null))
  publish()
  ensure()
  val rooms = testPlan?.floors?.sumOf { it.rooms.size } ?: 0
  return "dryRun=$dryRun testPlan=${testPlan != null} floors=${testPlan?.floors?.size ?: 0} rooms=$rooms"
}
