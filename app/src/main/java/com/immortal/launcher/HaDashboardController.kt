/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the dashboard's network side: the 30 s house poll, service calls with optimistic state and
 * revert-on-failure, sensor history. State is two immutable values ([state], [session]) replaced
 * on the main thread; all decisions live in the pure [HaDashboardState] / [HaDashboardSession].
 *
 * Honours Control Center's operator dry run ([CcDebugStore.dryRun], fleet `cc_debug`): with it on,
 * nothing is sent to Home Assistant and every call reports success.
 */
internal class HaDashboardController(context: Context, private val scope: CoroutineScope) {

  private val app = context.applicationContext

  var state by mutableStateOf(if (HaConfig.isConfigured(app)) HaDashboardState.Loading else HaDashboardState.NotConfigured)
    private set
  var session by mutableStateOf(HaDashboardSession())
    private set

  private var serial = 0L
  private var settleJob: Job? = null

  /** Fetch now and then every [POLL_MS] until the caller's scope stops (screen left). */
  suspend fun poll() {
    if (!HaConfig.isConfigured(app)) { state = HaDashboardState.NotConfigured; return }
    if (state == HaDashboardState.NotConfigured) state = HaDashboardState.Loading
    while (true) {
      refresh()
      delay(POLL_MS)
    }
  }

  suspend fun refresh() {
    val result = withContext(Dispatchers.IO) { HaDashboardApi.fetchHouse(app) }
    if (result !is HaResult.Ok) Log.w(TAG, "house fetch: ${result.javaClass.simpleName}")
    state = HaDashboardState.after(state, result, System.currentTimeMillis())
    if (result is HaResult.Ok) session = session.fetched()
    loadHistory()
  }

  /** "Try again" / "Check again" on a full-screen state or the offline banner. */
  fun retry() {
    if (state.house == null) state = HaDashboardState.Loading
    scope.launch { refresh() }
  }

  fun select(roomId: String) {
    session = session.select(roomId)
    loadHistory()
  }

  fun run(action: HaDashboardSession.Action) {
    val id = ++serial
    if (state is HaDashboardState.Offline) { show(session.refusedOffline(action, id), id); return }
    session = session.begin(action)
    scope.launch {
      val ok = withContext(Dispatchers.IO) { perform(action) }
      if (ok) show(session.succeeded(action, id), id) else show(session.failed(action, id), id)
      if (ok) settleSoon()
    }
  }

  /** Repeat the toast's failed action. */
  fun retryToast() {
    val action = session.toast?.retry ?: return
    session = session.dismissToast(session.toast?.serial ?: return)
    run(action)
  }

  private fun show(next: HaDashboardSession, id: Long) {
    session = next
    if (next.toast?.serial != id) return
    scope.launch { delay(TOAST_MS); session = session.dismissToast(id) }
  }

  /** HA reports a new state a moment after accepting a call: refetch once it has. */
  private fun settleSoon() {
    settleJob?.cancel()
    settleJob = scope.launch { delay(SETTLE_MS); refresh() }
  }

  private fun perform(action: HaDashboardSession.Action): Boolean {
    if (CcDebugStore.dryRun(app)) return true.also { Log.i(TAG, "dry run: ${action.javaClass.simpleName} not sent") }
    return when (action) {
      is HaDashboardSession.Action.Tap -> HaEntityLook.tapService(action.detail).let {
        HaClient.callService(app, it.domain, it.service, action.detail.entityId)
      }
      is HaDashboardSession.Action.Dim -> HaDashboardApi.setBrightness(app, action.detail.entityId, action.pct)
      is HaDashboardSession.Action.Temp -> HaDashboardApi.setTemperature(app, action.detail.entityId, action.target)
      is HaDashboardSession.Action.AllOff ->
        action.details.map { HaClient.callService(app, it.domain, "turn_off", it.entityId) }.all { it }
      is HaDashboardSession.Action.Scene -> HaClient.activate(app, action.id)
    }
  }

  private fun loadHistory() {
    val house = state.house ?: return
    val room = session.room(house) ?: return
    room.details.filter { it.domain == "sensor" && it.entityId !in session.history }.forEach { d ->
      // Mark as requested so a poll doesn't refetch; an empty series just draws no sparkline.
      session = session.withHistory(d.entityId, emptyList())
      scope.launch {
        val values = withContext(Dispatchers.IO) { HaDashboardApi.fetchHistory(app, d.entityId) }
        if (values.isNotEmpty()) session = session.withHistory(d.entityId, values)
      }
    }
  }

  companion object {
    private const val TAG = "HaDashboard"
    private const val POLL_MS = 30_000L
    private const val TOAST_MS = 3_200L
    private const val SETTLE_MS = 1_500L
  }
}
