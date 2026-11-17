/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

internal object DoorbellFeature {
  fun install(context: Context) = DoorbellAlerts.install(context)
}

/**
 * Doorbell runtime. A Portal that watches Home Assistant ([DoorbellListener]) turns a state change
 * into a [DoorbellAlert], shows it and tells the household (`doorbell_alert`, [DoorbellWire]);
 * every other Portal shows the same alert from that message. Any number of Portals may watch the
 * same Home Assistant: the shared HA context id makes their alerts identical, and [DoorbellGate]
 * shows each one once and applies the per-camera cooldown. Recent alerts are served to the phone
 * at `/remote/doorbell` ([DoorbellRoute]).
 */
internal object DoorbellAlerts {
  private const val TAG = "HearthpaneDoorbell"
  const val ROUTE = "/remote/doorbell"
  const val RECENT_CAPACITY = 10
  private const val MS_PER_SECOND = 1_000L

  /** Injectable for tests. */
  @Volatile internal var clock: () -> Long = System::currentTimeMillis

  private val lock = Any()
  private var gate = DoorbellGate()
  private val mutableRecent = MutableStateFlow<List<DoorbellAlert>>(emptyList())
  /** Alerts shown here, newest first (in memory; a restart forgets them). */
  val recent: StateFlow<List<DoorbellAlert>> = mutableRecent.asStateFlow()

  fun install(context: Context) {
    HouseholdBus.register(DoorbellWire.ALERT) { ctx, message -> onHousehold(ctx, message) }
    RemoteFeatureRoutes.register(ROUTE, DoorbellRoute)
    DoorbellListener.restart(context)
  }

  /** From [DoorbellListener] (its socket thread). */
  fun onChange(context: Context, entityId: String, old: JSONObject?, new: JSONObject?) {
    val settings = DoorbellConfig.load(context)
    val now = clock()
    val alert = DoorbellTriggers.fromChange(entityId, old, new, settings, DinnerCall.selfName(context), now) ?: return
    if (!admit(alert, now, settings)) return
    Log.i(TAG, "doorbell ${alert.kind.wire} seen here")
    HouseholdBus.send(context, DoorbellWire.ALERT, DoorbellWire.encode(alert, now))
    DoorbellPresenter.show(context, alert, settings)
  }

  /** From the household bus (its listener thread). */
  private fun onHousehold(context: Context, message: HouseholdMessage) {
    val now = clock()
    val alert = DoorbellWire.decode(message.body, now) ?: return
    val settings = DoorbellConfig.load(context)
    if (!admit(alert, now, settings)) return
    Log.i(TAG, "doorbell ${alert.kind.wire} from another room")
    DoorbellPresenter.show(context, alert, settings)
  }

  private fun admit(alert: DoorbellAlert, now: Long, settings: DoorbellConfig.Settings): Boolean = synchronized(lock) {
    val result = gate.admit(alert, now, settings.cooldownSec * MS_PER_SECOND)
    gate = result.gate
    if (result.show) mutableRecent.value = (listOf(alert) + mutableRecent.value).take(RECENT_CAPACITY)
    result.show
  }
}
