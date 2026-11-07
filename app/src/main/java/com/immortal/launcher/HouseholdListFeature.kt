/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log

/**
 * Entry point for the shared household list. Call [install] once from `ImmortalApp.onCreate`,
 * after `RemoteFeatureRoutes.registerHouseholdKey()` and `HouseholdBus.start(...)`.
 *
 * Registers `/remote/list` (phone), the `list_rev` bus signal, a default-network callback for
 * offline catch-up (event-driven; no polling), migrates the old To-do list once, and announces
 * this Portal's list so peers that changed while it was off can exchange with it.
 */
internal object HouseholdListFeature {
  private const val TAG = "ImmortalHouseholdList"
  @Volatile private var installed = false

  fun install(context: Context) {
    if (installed) return
    installed = true
    val app = context.applicationContext
    HouseholdListSync.attach(app)
    HouseholdListStore.setListener { ctx, before, after, source -> HouseholdListSync.onLocalChange(ctx, before, after, source) }
    RemoteFeatureRoutes.register(HouseholdListRoute.PATH) { ctx, method, body -> HouseholdListRoute.handle(ctx, method, body) }
    HouseholdBus.register(HouseholdListPeerAuth.SIGNAL_TYPE) { ctx, message -> HouseholdListSync.onSignal(ctx, message) }
    HouseholdListStore.ensureLoaded(app)
    HouseholdListStore.migrateTodosOnce(app)
    watchNetwork(app)
    HouseholdListSync.signal(app)
  }

  private fun watchNetwork(context: Context) {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
    runCatching {
      cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = HouseholdListSync.onNetwork(context, true)
        override fun onLost(network: Network) = HouseholdListSync.onNetwork(context, false)
      })
    }.onFailure { Log.w(TAG, "network callback unavailable; offline catch-up relies on the next change", it) }
  }
}
