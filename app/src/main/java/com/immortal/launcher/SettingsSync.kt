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
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.immortal.launcher.settings.SettingsDomain
import com.immortal.launcher.settings.SettingsSyncCatalog
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Household settings sync: keeps every [com.immortal.launcher.settings.SettingScope.HOUSEHOLD]
 * setting the same on all Portals that share the household key. A change here goes out at once as
 * a signed `setting_changed`; every Portal also gossips a digest of its versions, and a peer whose
 * digest differs exchanges full state over the signed `/remote/settingspeer` route — so a Portal
 * that was off catches up. Received values are schema-checked and written through the registry, so
 * they take effect live exactly as if set on this Portal.
 *
 * The facade: [SettingsSyncCore] holds the rules (on one worker thread); this object wires it to
 * the app's Context, the household bus, the main thread and the network callback.
 */
internal object SettingsSync {
  private const val TAG = "HearthpaneSettingsSync"
  const val GOSSIP_MS = SettingsSyncCore.GOSSIP_MS

  /**
   * The ledger thread and the peer-POST thread. Seams (like [clock]) so tests can run both on one
   * queue they drain themselves; read when the core is built and when a bus message arrives.
   */
  @Volatile internal var worker: ExecutorService =
      Executors.newSingleThreadExecutor { r -> Thread(r, "hearthpane-settings-sync").apply { isDaemon = true } }
  @Volatile internal var net: ExecutorService =
      Executors.newSingleThreadExecutor { r -> Thread(r, "hearthpane-settings-net").apply { isDaemon = true } }
  private val main by lazy { Handler(Looper.getMainLooper()) }

  @Volatile private var installed = false
  @Volatile private var core: SettingsSyncCore? = null
  private val pausedFlag = AtomicBoolean(false)

  /**
   * Wall clock (epoch ms) for everything the sync stamps: peer sightings, record and sync times,
   * the peer route's signatures. A seam so tests can replace it with a deterministic clock.
   */
  @Volatile internal var clock: () -> Long = System::currentTimeMillis
  private val _status = MutableStateFlow(SettingsSyncStatus())
  val status: StateFlow<SettingsSyncStatus> = _status.asStateFlow()

  val paused: Boolean get() = pausedFlag.get()

  private fun core(context: Context): SettingsSyncCore = core ?: synchronized(this) {
    core ?: context.applicationContext.let { c ->
      SettingsSyncCore(CatalogSyncSettings(c), AndroidSyncHost(c), AndroidSyncBus(c), { host, port, body -> SettingsSyncNet.post(c, host, port, body) },
          HandlerSyncTimers { main }, AndroidSyncLog(TAG), { clock() }, worker, net, _status, pausedFlag)
    }.also { core = it }
  }

  fun install(context: Context) {
    if (installed) return
    installed = true
    val sync = core(context)
    sync.bind()
    val householdDomains = SettingsSyncCatalog.household.entries.values.map { it.domain.id }.toSet()
    SettingsDomain.valuesApplied = { _, domainId, _ -> if (domainId in householdDomains) noteLocalChange() }
    HouseholdBus.register(SettingsSyncWire.CHANGED_TYPE) { _, m -> worker.execute(HouseholdBus.contained(m.type) { sync.onChanged(m) }) }
    HouseholdBus.register(SettingsSyncWire.SIGNAL_TYPE) { _, m -> worker.execute(HouseholdBus.contained(m.type) { sync.onSignal(m) }) }
    sync.start()
    watchNetwork(context.applicationContext, sync)
    sync.startGossip()
  }

  /** A household setting may have changed here (registry write, picker screen): look shortly. */
  fun noteLocalChange() {
    if (!installed) return
    core?.noteLocalChange()
  }

  /** The one-time join choice from Settings: adopt the household's settings, or keep this Portal's. */
  fun resolveJoin(keepMine: Boolean) { core?.resolveJoin(keepMine) }

  /** "Later" on the Home card: hide the card; the choice stays in About › Sync. */
  fun dismissAdoptCard() { core?.dismissAdoptCard() }

  /** Debug/fleet hook: stop sending and receiving (simulates this Portal being offline). */
  fun setPaused(on: Boolean) { core?.setPaused(on) ?: worker.execute { pausedFlag.set(on) } }

  /** Debug/fleet hook: forget the ledger and rejoin as if new (settings themselves are untouched). */
  fun rejoin() { core?.rejoin() }

  fun statusJson(): JSONObject = core?.statusJson() ?: SettingsSyncCore.statusJson(_status.value, SyncLedger(), null, emptyMap())

  /** This Portal's household values (no secrets or device-local keys exist in the catalog). */
  fun valuesJson(): JSONObject = core?.valuesJson() ?: JSONObject()

  /** The signed peer route's half of an exchange ([SettingsSyncNet.handle]): our payload, or null. */
  fun handlePeer(context: Context, from: String, body: String): String? = core(context).handlePeer(from, body)

  private fun watchNetwork(context: Context, sync: SettingsSyncCore) {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
    runCatching {
      cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = sync.networkAvailable()
      })
    }.onFailure { Log.w(TAG, "network callback unavailable; catch-up relies on gossip", it) }
  }
}
