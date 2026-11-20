/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/*
 * What screens, the phone remote and the Fleet agent ask of the engine ([ControlCenter] forwards
 * each one). Gates run on the caller's thread against the last published state; the work itself
 * is posted to the engine thread.
 */

internal fun CcEngine.install() = post { ensure() }

internal fun CcEngine.setVisible(on: Boolean) = post { visible = (visible + if (on) 1 else -1).coerceAtLeast(0); ensure() }

/** HA address/token or Control Center settings changed. */
internal fun CcEngine.restartWithSettings() = post { ui = ui.copy(settings = host.settings()); restart() }

internal fun CcEngine.setSignals(signals: CcRoomSignals) = post { if (ui.signals != signals) { ui = ui.copy(signals = signals); if (foreground) publish() } }

internal fun CcEngine.reloadSettings() = post { ui = ui.copy(settings = host.settings()); publish(); ensure() }

internal fun CcEngine.retryNow() = post { attempt = 0; restart() }

/** See [ControlCenter.send]. */
internal fun CcEngine.send(call: CcCall, undoable: Boolean, onResult: ((Boolean, String?) -> Unit)?, confirmed: Boolean): Boolean {
  val s = flow.value
  if (refused(s, call, confirmed)) return false
  val reason = s.connection.disabledReason
  if (reason != null) { post { toast(reason, error = true) }; return false }
  post { dispatch(call, undoable, onResult) }
  return true
}

private fun CcEngine.refused(s: CcUiState, call: CcCall, confirmed: Boolean): Boolean {
  val why = ccSendRefusal(s, call, confirmed) { host.scenesSeen() } ?: return false
  log.w("refused ${call.domain}.${call.service}: $why")
  return true
}

/** See [ControlCenter.remoteWatch]. */
internal fun CcEngine.remoteWatch() = post {
  remoteUntil = clock() + CcRemoteWatch.LEASE_MS
  ensure()
  if (remoteExpiry?.isDone != false) scheduleRemoteExpiry()
}

/** See [ControlCenter.sendFromPhone]. */
internal fun CcEngine.sendFromPhone(call: CcCall, onResult: (Boolean, String?) -> Unit): String? {
  flow.value.connection.disabledReason?.let { return it }
  log.i("phone call ${call.domain}.${call.service}")
  post { dispatch(call, undoable = false, onResult = onResult) }
  return null
}

internal fun CcEngine.undo() = post { undoLast() }

internal fun CcEngine.dismissToast(id: Long) = post { if (ui.toast?.id == id) { ui = ui.copy(toast = null); publish() } }

/** See [ControlCenter.runScene]. */
internal fun CcEngine.runScene(scene: CcEntity, confirmed: Boolean): Boolean {
  if (refused(flow.value, CcCalls.run(scene), confirmed)) return false
  val reason = flow.value.connection.disabledReason
  if (reason != null) { post { toast(reason, error = true) }; return false }
  post { startWalk(scene) }
  host.markSceneSeen(scene.id)
  return true
}

internal fun CcEngine.clearSceneRun() = post { ui = ui.copy(sceneRun = null); publish() }

/** Fleet `cc_debug`: runs on the engine thread and waits briefly; "timeout" if it can't. */
internal fun CcEngine.debugCommand(dry: Boolean?, plan: String?, alert: String?, unreachable: Boolean?,
                                   fake: CcFakeState?, waitMs: Long): String =
    runCatching { sched.call(waitMs) { fake?.let { fakeState(it) } ?: alert?.let { testAlert(it) } ?: debug(dry, plan, unreachable) } }
        .getOrElse { "timeout" }

internal fun CcEngine.queueSavePlan(plan: CcPlan) = post { savePlan(plan) }

internal fun CcEngine.dismissAlert(alert: CcAlert, untilMs: Long) = post {
  if (alert.kind == CcKind.APPLIANCE) live = live.dismissDone(alert.entityId)
  ui = ui.copy(dismissed = ui.dismissed + (alert.id to untilMs)); publish()
}

/** A camera's absolute HLS URL from HA (`camera/stream`), or null; [onUrl] runs on the engine thread. */
internal fun CcEngine.cameraStream(entityId: String, onUrl: (String?) -> Unit) =
    post { requestStream(entityId) { rel -> onUrl(rel?.let { CcCameraLogic.streamUrl(host.haBaseUrl(), it) }) } }

internal fun CcEngine.loadChargeWeek() = post { requestChargeWeek() }
