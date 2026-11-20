/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.TimeZone

/* Actions, on the engine thread: service calls and their answers, undo, scenes, the plan, charging. */

internal fun CcEngine.toast(text: String, undo: Boolean = false, error: Boolean = false) {
  ui = ui.copy(toast = CcToast(++toastSeq, text, undo, error)); publish()
}

internal fun CcEngine.dispatch(call: CcCall, undoable: Boolean, onResult: ((Boolean, String?) -> Unit)?) {
  val ws = socket; val p = protocol
  if (ws == null || p == null || !ui.connection.canControl) { toast(ui.connection.disabledReason ?: "Not connected", error = true); onResult?.invoke(false, null); return }
  if (dryRun) { dryRunCall(call, onResult); return }
  val key = "c${++callSeq}"
  val before = call.entityId?.let { live.raw[it] }
  calls = calls + (key to CcCallInfo(call, before, undoable, onResult))
  live = live.send(call, clock())
  ws.send(p.call(call, key).text)
  publish()
  sched.schedule(CcLive.ECHO_MS + EXPIRE_SLACK_MS) { post { expirePending() } }
}

private const val EXPIRE_SLACK_MS = 50L

internal fun CcEngine.expirePending() {
  val (next, silent) = live.expire(clock())
  live = next
  if (silent.isNotEmpty()) toast("${live.raw[silent.first()]?.name ?: "A device"} didn't respond", error = true) else publish()
}

internal fun CcEngine.callResult(r: CcProtocol.Out.Result) {
  val info = calls[r.req.key] ?: return
  calls = calls - r.req.key
  val id = info.call.entityId
  if (r.ok) {
    if (id != null) live = live.acknowledged(id)
    val undo = if (info.undoable) info.before?.let { CcCalls.undo(it, info.call) } else null
    lastUndo = undo
    if (undo != null && info.call.label.isNotBlank()) toast(info.call.label, undo = true) else publish()
  } else {
    if (id != null) live = live.failed(id)
    log.i("control center call ${info.call.domain}.${info.call.service} refused")
    if (info.call.service != "create") toast("Couldn't do that: ${r.error ?: "Home Assistant refused"}", error = true)
  }
  info.onResult?.invoke(r.ok, r.error)
}

internal fun CcEngine.undoLast() {
  val u = lastUndo ?: return
  lastUndo = null
  dispatch(u, undoable = false, onResult = null)
}

internal fun CcEngine.startWalk(scene: CcEntity) {
  val members = scene.members
  val rooms = CcSceneRun.rooms(ui.plan, members)
  val open = ui.entities.values.filter { (CcKind.of(it) == CcKind.WINDOW || CcKind.of(it) == CcKind.DOOR) && it.isOpen }.map { it.name }
  ui = ui.copy(sceneRun = CcSceneRun(scene.id, scene.name, rooms, clock(), CcSceneRun.duration(rooms.size), open, members.isNotEmpty()))
  // Snapshot the members first (scene.create) so the whole scene can be undone; run once HA has it.
  val run = CcCalls.run(scene)
  val snap = CcCalls.snapshot(members)
  if (snap == null) { dispatch(run, undoable = false, onResult = null); return }
  dispatch(snap, undoable = false) { ok, _ ->
    post {
      dispatch(run, undoable = false) { ran, _ -> if (ran && ok) post { lastUndo = CcCalls.undoScene() } }
    }
  }
}

internal fun CcEngine.savePlan(plan: CcPlan) {
  val stamped = plan.copy(updatedAt = clock())
  if (testPlan != null) {
    // Test plan: local only, never Home Assistant.
    testPlan = stamped; host.saveTestPlan(stamped)
    ui = ui.copy(plan = stamped, planSource = CcPlanSource.THIS_PORTAL)
    toast("Test plan saved on this Portal only")
    publish(); ensure(); return
  }
  host.savePlan(stamped)
  ui = ui.copy(plan = stamped, planSource = CcPlanSource.THIS_PORTAL)
  if (!sendPlanToHa(stamped)) toast("Saved on this Portal. It will copy to Home Assistant when connected.")
  publish()
  ensure()
}

internal fun CcEngine.planSaved(ok: Boolean) {
  if (ok) ui = ui.copy(planSource = CcPlanSource.HOME_ASSISTANT)
  else toast("Saved on this Portal only — Home Assistant didn't accept the plan", error = true)
  publish()
}

internal fun CcEngine.requestChargeWeek() {
  val stat = ui.registry?.tesla?.weekStat ?: return
  val ws = socket ?: return
  val p = protocol ?: return
  val now = clock()
  val week = ControlCenter.weekBuckets(now, TimeZone.getDefault())
  ws.send(p.statistics(CHARGE_WEEK, listOf(stat), ControlCenter.isoUtc(week.first()), ControlCenter.isoUtc(now), "day").text)
}

internal fun CcEngine.chargeStatsResult(r: CcProtocol.Out.Result) {
  val ids = ui.registry?.tesla ?: return
  val stat = ids.weekStat ?: return
  if (!r.ok || r.req.key != CHARGE_WEEK) return
  val starts = ControlCenter.weekBuckets(clock(), TimeZone.getDefault())
  ui = ui.copy(chargeWeek = CcTeslaCharger.week(stat, CcTeslaCharger.stats(r.result), starts, CcTeslaCharger.price(ids, ui.entities)))
  publish()
}

private const val CHARGE_WEEK = "charge_week"

/** [CcPolicy.refusal] for [call] against the current state (pure; what [ControlCenter.send] checks). */
internal fun ccSendRefusal(s: CcUiState, call: CcCall, confirmed: Boolean, scenesSeen: () -> Set<String>): String? =
    CcPolicy.refusal(call.entityId?.let(s::entity), call, s.settings, confirmed, scenesSeen)

/** The phone lease ran out unless renewed meanwhile; then back to whatever this Portal needs. */
internal fun CcEngine.scheduleRemoteExpiry() {
  val wait = (remoteUntil - clock()).coerceAtLeast(0L) + CcRemoteWatch.EXPIRY_SLACK_MS
  remoteExpiry = sched.schedule(wait) {
    post {
      if (remoteUntil > clock()) scheduleRemoteExpiry() else { remoteExpiry = null; ensure() }
    }
  }
}
