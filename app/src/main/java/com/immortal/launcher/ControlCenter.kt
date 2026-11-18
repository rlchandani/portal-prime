/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Home Control Center runtime facade. Screens call [setVisible] from onStart/onStop (live state
 * only while someone looks; banners keep a small alert subscription when enabled), read [state]
 * and act through [send] and friends. Nothing is ever queued: while Home Assistant isn't live an
 * action is refused with the reason as a toast and the controls render disabled.
 */
internal object ControlCenter {
  @Volatile private var engine: CcEngine? = null
  private val main by lazy { Handler(Looper.getMainLooper()) }

  private fun engine(context: Context): CcEngine =
      engine ?: synchronized(this) { engine ?: CcEngine(context.applicationContext).also { engine = it } }

  fun state(context: Context): StateFlow<CcUiState> = engine(context).flow

  /** App start: keep the background alert subscription if this Portal shows banners. */
  fun install(context: Context) { val e = engine(context); e.post { e.ensure() } }

  fun setVisible(context: Context, visible: Boolean) {
    val e = engine(context)
    e.post { e.visible = (e.visible + if (visible) 1 else -1).coerceAtLeast(0); e.ensure() }
  }

  /** HA address/token or Control Center settings changed. */
  fun restart(context: Context) {
    val e = engine(context)
    e.post { e.ui = e.ui.copy(settings = ControlCenterConfig.load(e.app)); e.restart() }
  }

  fun reloadSettings(context: Context) {
    val e = engine(context)
    e.post { e.ui = e.ui.copy(settings = ControlCenterConfig.load(e.app)); e.publish(); e.ensure() }
  }

  fun retryNow(context: Context) { val e = engine(context); e.post { e.attempt = 0; e.restart() } }

  /**
   * Sends [call] now, or refuses (returns false) when HA isn't live or [CcPolicy.refusal] says no.
   * Every call passes this one gate: [confirmed] is true only from the household's answer to a
   * modal or the PIN pad ([CcUi.confirmed], the alarm paths); sliders and dials send unconfirmed.
   */
  fun send(context: Context, call: CcCall, undoable: Boolean = true, onResult: ((Boolean, String?) -> Unit)? = null,
           confirmed: Boolean = false): Boolean {
    val e = engine(context)
    val s = e.flow.value
    if (refused(context, s, call, confirmed)) return false
    val reason = s.connection.disabledReason
    if (reason != null) { e.post { e.toast(reason, error = true) }; return false }
    e.post { e.dispatch(call, undoable, onResult) }
    return true
  }

  private fun refused(context: Context, s: CcUiState, call: CcCall, confirmed: Boolean): Boolean {
    val why = ccSendRefusal(s, call, confirmed) { ControlCenterConfig.scenesSeen(context) } ?: return false
    Log.w(TAG, "refused ${call.domain}.${call.service}: $why")
    return true
  }

  fun undo(context: Context) { val e = engine(context); e.post { e.undoLast() } }

  fun dismissToast(context: Context, id: Long) { val e = engine(context); e.post { if (e.ui.toast?.id == id) { e.ui = e.ui.copy(toast = null); e.publish() } } }

  /** Runs a scene/script in HA and walks the house on the plan while it happens. */
  fun runScene(context: Context, scene: CcEntity, confirmed: Boolean = false): Boolean {
    val e = engine(context)
    if (refused(context, e.flow.value, CcCalls.run(scene), confirmed)) return false
    val reason = e.flow.value.connection.disabledReason
    if (reason != null) { e.post { e.toast(reason, error = true) }; return false }
    e.post { e.startWalk(scene) }
    ControlCenterConfig.markSceneSeen(context, scene.id)
    return true
  }

  fun clearSceneRun(context: Context) { val e = engine(context); e.post { e.ui = e.ui.copy(sceneRun = null); e.publish() } }

  /**
   * Arm/disarm; [onResult] on the main thread (false + HA's message for a wrong code). Called only
   * from the PIN pad and the arm check, which are the household's confirmation.
   */
  fun alarm(context: Context, panel: CcEntity, mode: CcAlarmMode, code: String?, onResult: (Boolean, String?) -> Unit) {
    val delivered: (Boolean, String?) -> Unit = { ok, msg -> main.post { onResult(ok, msg) } }
    if (!send(context, CcCalls.alarm(panel, mode, code), undoable = false, onResult = delivered, confirmed = true)) delivered(false, null)
  }

  /** Fleet `cc_debug` (operator only): dry run, local test plan, HA-unreachable switch. Blocks briefly. */
  fun debug(context: Context, dryRun: Boolean?, testPlan: String?, testAlert: String? = null, haUnreachable: Boolean? = null): String {
    val e = engine(context)
    return runCatching { e.exec.submit<String> { testAlert?.let { e.testAlert(it) } ?: e.debug(dryRun, testPlan, haUnreachable) }.get(DEBUG_WAIT_S, java.util.concurrent.TimeUnit.SECONDS) }
        .getOrElse { "timeout" }
  }

  fun savePlan(context: Context, plan: CcPlan) { val e = engine(context); e.post { e.savePlan(plan) } }

  fun dismissAlert(context: Context, alert: CcAlert, untilMs: Long) {
    val e = engine(context)
    e.post {
      if (alert.kind == CcKind.APPLIANCE) e.live = e.live.dismissDone(alert.entityId)
      e.ui = e.ui.copy(dismissed = e.ui.dismissed + (alert.id to untilMs)); e.publish()
    }
  }

  /** Loads today (hourly) and this week (daily) from the recorder, once the energy prefs are known. */
  fun loadEnergy(context: Context) { val e = engine(context); e.post { e.requestEnergy() } }

  internal fun isoUtc(ms: Long): String =
      SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(ms)

  /** Local-midnight bucket starts: 24 hours of today, or the last 7 days. */
  internal fun buckets(now: Long, zone: TimeZone, week: Boolean): List<Long> {
    val c = Calendar.getInstance(zone).apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    if (!week) return (0 until HOURS).map { h -> (c.clone() as Calendar).apply { add(Calendar.HOUR_OF_DAY, h) }.timeInMillis }
    c.add(Calendar.DAY_OF_YEAR, -(DAYS - 1))
    return (0 until DAYS).map { d -> (c.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, d) }.timeInMillis }
  }

  private const val DEBUG_WAIT_S = 5L
  private const val HOURS = 24
  private const val DAYS = 7
  const val TAG = CcEngine.TAG
}

// --- actions, on the engine thread ----------------------------------------------------------

internal fun CcEngine.toast(text: String, undo: Boolean = false, error: Boolean = false) {
  ui = ui.copy(toast = CcToast(++toastSeq, text, undo, error)); publish()
}

internal fun CcEngine.dispatch(call: CcCall, undoable: Boolean, onResult: ((Boolean, String?) -> Unit)?) {
  val ws = socket; val p = protocol
  if (ws == null || p == null || !ui.connection.canControl) { toast(ui.connection.disabledReason ?: "Not connected", error = true); onResult?.invoke(false, null); return }
  if (dryRun) { dryRunCall(call, onResult); return }
  val key = "c${++callSeq}"
  val before = call.entityId?.let { live.raw[it] }
  calls[key] = CcCallInfo(call, before, undoable, onResult)
  live = live.send(call, clock())
  ws.send(p.call(call, key).text)
  publish()
  exec.schedule({ post { expirePending() } }, CcLive.ECHO_MS + EXPIRE_SLACK_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
}

private const val EXPIRE_SLACK_MS = 50L

internal fun CcEngine.expirePending() {
  val (next, silent) = live.expire(clock())
  live = next
  if (silent.isNotEmpty()) toast("${live.raw[silent.first()]?.name ?: "A device"} didn't respond", error = true) else publish()
}

internal fun CcEngine.callResult(r: CcProtocol.Out.Result) {
  val info = calls.remove(r.req.key) ?: return
  val id = info.call.entityId
  if (r.ok) {
    if (id != null) live = live.acknowledged(id)
    val undo = if (info.undoable) info.before?.let { CcCalls.undo(it, info.call) } else null
    lastUndo = undo
    if (undo != null && info.call.label.isNotBlank()) toast(info.call.label, undo = true) else publish()
  } else {
    if (id != null) live = live.failed(id)
    Log.i(CcEngine.TAG, "control center call ${info.call.domain}.${info.call.service} refused")
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
    testPlan = stamped; CcDebugStore.saveTestPlan(app, stamped)
    ui = ui.copy(plan = stamped, planSource = CcPlanSource.THIS_PORTAL)
    toast("Test plan saved on this Portal only")
    publish(); ensure(); return
  }
  CcPlanStore.save(app, stamped)
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

internal fun CcEngine.requestEnergy() {
  val prefs = ui.energyPrefs ?: return
  val ws = socket ?: return
  val p = protocol ?: return
  if (!prefs.configured) return
  val zone = TimeZone.getDefault()
  val now = clock()
  val today = ControlCenter.buckets(now, zone, week = false)
  val week = ControlCenter.buckets(now, zone, week = true)
  ws.send(p.statistics("today", prefs.all, ControlCenter.isoUtc(today.first()), ControlCenter.isoUtc(now), "hour").text)
  ws.send(p.statistics("week", prefs.all, ControlCenter.isoUtc(week.first()), ControlCenter.isoUtc(now), "day").text)
}

internal fun CcEngine.statsResult(r: CcProtocol.Out.Result) {
  val prefs = ui.energyPrefs ?: return
  if (!r.ok) return
  val stats = CcEnergy.stats(r.result)
  val zone = TimeZone.getDefault()
  val week = r.req.key == "week"
  val series = CcEnergy.series(prefs, stats, ControlCenter.buckets(clock(), zone, week))
  ui = if (week) ui.copy(energyWeek = series) else ui.copy(energyToday = series)
  publish()
}

/** [CcPolicy.refusal] for [call] against the current state (pure; what [ControlCenter.send] checks). */
internal fun ccSendRefusal(s: CcUiState, call: CcCall, confirmed: Boolean, scenesSeen: () -> Set<String>): String? =
    CcPolicy.refusal(call.entityId?.let(s::entity), call, s.settings, confirmed, scenesSeen)
