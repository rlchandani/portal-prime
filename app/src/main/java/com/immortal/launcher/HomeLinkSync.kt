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
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Keeps every Portal in this home on one [HomeLinkVault], like [ReminderSync]: a stored change
 * broadcasts a signed `home_link_rev` (keyed digest + LAN address); a Portal whose vault differs
 * **pulls** the sender's vault over `/remote/homelinkpeer` ([HomeLinkSyncWire] v2: signed by its
 * Keystore device key, answered sealed to a one-time ECDH key) and merges it; the sender pulls
 * back when the puller's digest differs. Startup announces, which is the catch-up path after
 * being off. A removal is a tombstone in the vault, so "Remove Lakeview" reaches every Portal.
 *
 * Two gates ([HomeLinkGate]): the build switch, and per Portal the pinned device keys — a Portal
 * that pinned nobody neither asks nor answers, so the household key alone (phones hold it) gets
 * nothing.
 */
internal object HomeLinkSync {
  private const val TAG = "HearthpaneHomeLinkSync"
  private const val NONCE_BYTES = 12
  private const val CONNECT_MS = 3_000
  private const val READ_MS = 5_000
  private val RETRY_MS = longArrayOf(5_000L, 30_000L, 120_000L)
  private val net = Executors.newSingleThreadExecutor { r -> Thread(r, "hearthpane-homelink-net").apply { isDaemon = true } }
  private val main by lazy { Handler(Looper.getMainLooper()) }
  private val replay = HouseholdMessage.ReplayGuard()
  @Volatile private var retryStep = 0
  @Volatile private var pending: Runnable? = null

  /** Wires the bus signal, then (off the main thread: it reads the Keystore) the store's change listener. */
  fun install(context: Context) {
    if (!HomeLinkGate.VAULT_SYNC) return
    val app = context.applicationContext
    HouseholdBus.register(HomeLinkSyncWire.SIGNAL_TYPE) { ctx, m -> onSignal(ctx, m) }
    net.execute {
      HomeLinkRepository.get(app).onChange = { signal(app) }
      signal(app)
    }
  }

  fun handle(context: Context, req: FleetHttpServer.Request): FleetHttpServer.Response {
    if (!HomeLinkGate.VAULT_SYNC) return RemoteFeatureRoutes.json(404, RemoteFeatureRoutes.error("not_found"))
    val repo = HomeLinkRepository.get(context)
    val app = context.applicationContext
    return HomeLinkSyncRoute(
        key = { HouseholdKey.get(context) }, selfId = { selfId(context) }, device = PortalDeviceKey::get,
        roster = { PortalRosterStore.get(app).roster }, clock = System::currentTimeMillis, replay = replay,
        announcedHost = ::announcedHost, vault = { repo.vault.value }, pullBack = { from -> pullBack(app, from) },
        random = HomeLinkFeature.sharedRandom, log = { Log.i(TAG, it) },
    ).handle(req)
  }

  fun signal(context: Context) {
    if (!HomeLinkGate.VAULT_SYNC) return
    val app = context.applicationContext
    net.execute {
      if (!HomeLinkGate.syncs(PortalRosterStore.get(app).roster)) return@execute
      val key = HouseholdKey.get(app) ?: return@execute
      val host = HouseholdListSync.lanAddress() ?: return@execute
      val digest = HomeLinkSyncWire.digest(HomeLinkRepository.get(app).vault.value, key)
      HouseholdBus.send(app, HomeLinkSyncWire.SIGNAL_TYPE,
          HouseholdListPeerAuth.signalBody(HouseholdListPeerAuth.Signal(digest, host, FleetConfig.port(app))))
    }
  }

  /** Bus handler (listener thread; the bus already verified the household signature). */
  private fun onSignal(context: Context, message: HouseholdMessage) {
    val signal = HouseholdListPeerAuth.parseSignal(message.body) ?: return
    if (!HomeLinkSyncPolicy.trusts(message.from, signal.host, announcedHost(message.from))) return
    if (!HomeLinkGate.syncs(PortalRosterStore.get(context).roster)) return
    val key = HouseholdKey.get(context) ?: return
    if (signal.digest == HomeLinkSyncWire.digest(HomeLinkRepository.get(context).vault.value, key)) return
    retryStep = 0
    exchangeLater(context.applicationContext, signal)
  }

  /** The caller of our route has a different vault: pull theirs once (never pulled back again). */
  private fun pullBack(context: Context, from: String) {
    val peer = CallingStore.peers.value.firstOrNull { it.id == from } ?: return
    val host = peer.host ?: return
    val port = peer.port.takeIf { it > 0 } ?: FleetConfig.port(context)
    net.execute {
      runCatching { exchange(context, host, port, back = true) }
          .onFailure { Log.i(TAG, "home-link pull-back failed: ${it.javaClass.simpleName}") }
    }
  }

  private fun exchangeLater(context: Context, signal: HouseholdListPeerAuth.Signal) {
    net.execute {
      val ok = runCatching { exchange(context, signal.host, signal.port, back = false) }
          .onFailure { Log.i(TAG, "home-link exchange failed: ${it.javaClass.simpleName}") }.getOrDefault(false)
      if (ok) clearRetry() else scheduleRetry(context, signal)
    }
  }

  /** One pull from the Portal at [host]: true once its answer, signed by a pinned device key, is merged. */
  private fun exchange(context: Context, host: String, port: Int, back: Boolean): Boolean {
    val key = HouseholdKey.get(context) ?: return false
    val self = selfId(context) ?: return false
    val device = PortalDeviceKey.get() ?: return false
    val roster = PortalRosterStore.get(context).roster
    if (!HomeLinkGate.syncs(roster)) return false
    val repo = HomeLinkRepository.get(context)
    val nonce = ByteArray(NONCE_BYTES).also(HomeLinkFeature.sharedRandom::nextBytes).let(HouseholdKey::toHex)
    val now = System.currentTimeMillis()
    val asking = HomeLinkSyncWire.ask(device, nonce, now, HomeLinkSyncWire.digest(repo.vault.value, key), back,
        HomeLinkFeature.sharedRandom)
    val header = HomeLinkSyncWire.requestHeader(key, self, nonce, now, asking.body) ?: return false
    val conn = URL("http", host, port, HomeLinkSyncWire.PATH).openConnection() as HttpURLConnection
    try {
      conn.connectTimeout = CONNECT_MS
      conn.readTimeout = READ_MS
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.instanceFollowRedirects = false
      conn.setRequestProperty("Content-Type", "application/json")
      conn.setRequestProperty(HomeLinkSyncWire.HEADER, header)
      val bytes = asking.body.toByteArray(Charsets.UTF_8)
      conn.setFixedLengthStreamingMode(bytes.size)
      conn.outputStream.use { it.write(bytes) }
      if (conn.responseCode != 200) return false
      val answer = BoundedRead.reply(conn, HomeLinkSyncWire.MAX_BODY * 2) ?: return false
      val theirs = HomeLinkSyncWire.receive(asking, answer, roster, device.kid) ?: return false
      repo.merge(theirs)
      return true
    } finally {
      conn.disconnect()
    }
  }

  private fun announcedHost(id: String): String? = CallingStore.peers.value.firstOrNull { it.id == id }?.host

  private fun selfId(context: Context): String? = runCatching { RemoteCompanionStore.id(context) }.getOrNull()

  private fun clearRetry() {
    retryStep = 0
    pending?.let(main::removeCallbacks)
    pending = null
  }

  private fun scheduleRetry(context: Context, signal: HouseholdListPeerAuth.Signal) {
    val step = retryStep
    if (step >= RETRY_MS.size) return
    retryStep = step + 1
    val retry = Runnable { exchangeLater(context, signal) }
    pending?.let(main::removeCallbacks)
    pending = retry
    main.postDelayed(retry, RETRY_MS[step])
  }
}

/** Whom we sync the vault with. Pure. */
internal object HomeLinkSyncPolicy {
  /** Only another Portal (never a phone) signalling from the address it announced in its hello. */
  fun trusts(from: String, signalHost: String, announced: String?): Boolean =
      !CallPhoneId.isPhone(from) && DinnerWire.isDeviceId(from) && announced != null && announced == signalHost
}
