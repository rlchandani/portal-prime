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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Keeps every Portal in this home on one [HomeLinkVault], like [ReminderSync]: a stored change
 * broadcasts a signed `home_link_rev` (keyed digest + LAN address); a Portal whose vault differs
 * POSTs its sealed vault to the sender's `/remote/homelinkpeer` and merges the sealed, signed
 * reply. Startup announces, which is the catch-up path after being off. A removal is a tombstone
 * in the vault, so "Remove Lakeview" reaches every Portal and wipes its key there too.
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
    val app = context.applicationContext
    HouseholdBus.register(HomeLinkSyncWire.SIGNAL_TYPE) { ctx, m -> onSignal(ctx, m) }
    net.execute {
      HomeLinkRepository.get(app).onChange = { signal(app) }
      signal(app)
    }
  }

  fun handle(context: Context, req: FleetHttpServer.Request): FleetHttpServer.Response {
    val repo = HomeLinkRepository.get(context)
    return HomeLinkSyncRoute(
        key = { HouseholdKey.get(context) }, selfId = { selfId(context) }, clock = System::currentTimeMillis, replay = replay,
        announcedHost = ::announcedHost, vault = { repo.vault.value }, merge = { repo.merge(it) },
        random = HomeLinkFeature.sharedRandom, log = { Log.i(TAG, it) },
    ).handle(req)
  }

  fun signal(context: Context) {
    val app = context.applicationContext
    net.execute {
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
    val key = HouseholdKey.get(context) ?: return
    if (signal.digest == HomeLinkSyncWire.digest(HomeLinkRepository.get(context).vault.value, key)) return
    retryStep = 0
    exchangeLater(context.applicationContext, signal)
  }

  private fun exchangeLater(context: Context, signal: HouseholdListPeerAuth.Signal) {
    net.execute {
      val ok = runCatching { exchange(context, signal) }
          .onFailure { Log.i(TAG, "home-link exchange failed: ${it.javaClass.simpleName}") }.getOrDefault(false)
      if (ok) clearRetry() else scheduleRetry(context, signal)
    }
  }

  private fun exchange(context: Context, signal: HouseholdListPeerAuth.Signal): Boolean {
    val key = HouseholdKey.get(context) ?: return false
    val self = selfId(context) ?: return false
    val repo = HomeLinkRepository.get(context)
    val nonce = ByteArray(NONCE_BYTES).also(HomeLinkFeature.sharedRandom::nextBytes).let(HouseholdKey::toHex)
    val body = HomeLinkSyncWire.seal(key, self, nonce, HomeLinkSyncWire.REQUEST, repo.vault.value, HomeLinkFeature.sharedRandom)
        ?: return false
    val header = HomeLinkSyncWire.requestHeader(key, self, nonce, System.currentTimeMillis(), body) ?: return false
    val conn = URL("http", signal.host, signal.port, HomeLinkSyncWire.PATH).openConnection() as HttpURLConnection
    try {
      conn.connectTimeout = CONNECT_MS
      conn.readTimeout = READ_MS
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.instanceFollowRedirects = false
      conn.setRequestProperty("Content-Type", "application/json")
      conn.setRequestProperty(HomeLinkSyncWire.HEADER, header)
      val bytes = body.toByteArray(Charsets.UTF_8)
      conn.setFixedLengthStreamingMode(bytes.size)
      conn.outputStream.use { it.write(bytes) }
      if (conn.responseCode != 200) return false
      val envelope = JSONObject(BoundedRead.reply(conn, HomeLinkSyncWire.MAX_BODY * 2) ?: return false)
      val payload = envelope.optString("payload")
      if (!HomeLinkSyncWire.verifyResponse(key, nonce, payload, envelope.optString("sig"))) return false
      val theirs = HomeLinkSyncWire.open(key, self, nonce, HomeLinkSyncWire.RESPONSE, payload) ?: return false
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
