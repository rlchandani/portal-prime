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
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Keeps this Portal's chores in step with the other Portals, like the household list: no polling.
 * A change here (tap, phone, peer merge) broadcasts a tiny signed `chores_rev` signal; a Portal
 * whose replica differs POSTs its own replica to the sender's `/remote/chorespeer` and merges the
 * signed reply, so both sides end up with the union. A failed exchange retries with backoff.
 */
internal object ChoreSync {
  private const val TAG = "HearthpaneChoresSync"
  private const val MAX_RESPONSE = 1024 * 1024
  private const val CONNECT_MS = 3_000
  private const val READ_MS = 5_000
  private val RETRY_MS = longArrayOf(5_000L, 30_000L, 120_000L)

  private val net = Executors.newSingleThreadExecutor { r -> Thread(r, "hearthpane-chores-net").apply { isDaemon = true } }
  private val main by lazy { Handler(Looper.getMainLooper()) }
  private val random = SecureRandom()
  @Volatile private var retryStep = 0
  @Volatile private var pending: Runnable? = null

  /** Broadcasts "my chores are now <digest>; exchange with me at host:port". */
  fun signal(context: Context) {
    val app = context.applicationContext
    net.execute {
      val host = HouseholdListSync.lanAddress() ?: return@execute
      val digest = ChoreCodec.digest(ChoreStore.current(app))
      HouseholdBus.send(app, ChorePeerAuth.SIGNAL_TYPE,
          ChorePeerAuth.signalBody(HouseholdListPeerAuth.Signal(digest, host, FleetConfig.port(app))))
    }
  }

  /** Bus handler for [ChorePeerAuth.SIGNAL_TYPE] (listener thread). */
  fun onSignal(context: Context, message: HouseholdMessage) {
    val signal = ChorePeerAuth.parseSignal(message.body) ?: return
    if (signal.digest == ChoreCodec.digest(ChoreStore.current(context))) return
    retryStep = 0
    exchangeLater(context.applicationContext, signal)
  }

  private fun exchangeLater(context: Context, signal: HouseholdListPeerAuth.Signal) {
    net.execute {
      val ok = runCatching { exchange(context, signal) }
          .onFailure { Log.i(TAG, "chores exchange failed: ${it.javaClass.simpleName}") }
          .getOrDefault(false)
      if (ok) clearRetry() else scheduleRetry(context, signal)
    }
  }

  private fun exchange(context: Context, signal: HouseholdListPeerAuth.Signal): Boolean {
    val key = HouseholdKey.get(context) ?: return false
    val self = RemoteCompanionStore.id(context)
    val body = JSONObject().put("name", HouseholdListSync.selfName(context))
        .put("state", ChoreCodec.encodeState(ChoreStore.current(context))).toString()
    val nonce = ByteArray(12).also(random::nextBytes).let(HouseholdKey::toHex)
    val header = ChorePeerAuth.requestHeader(key, self, nonce, System.currentTimeMillis(), body) ?: return false
    val conn = URL("http", signal.host, signal.port, ChoresPeerRoute.PATH).openConnection() as HttpURLConnection
    try {
      conn.connectTimeout = CONNECT_MS
      conn.readTimeout = READ_MS
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.instanceFollowRedirects = false
      conn.setRequestProperty("Content-Type", "application/json")
      conn.setRequestProperty(ChorePeerAuth.HEADER, header)
      val bytes = body.toByteArray(Charsets.UTF_8)
      conn.setFixedLengthStreamingMode(bytes.size)
      conn.outputStream.use { it.write(bytes) }
      if (conn.responseCode != 200) return false
      val text = BoundedRead.reply(conn, MAX_RESPONSE) ?: return false
      val envelope = JSONObject(text)
      val payload = envelope.optString("payload")
      if (!ChorePeerAuth.verifyResponse(key, nonce, payload, envelope.optString("sig"))) return false
      val theirs = ChoreCodec.decodeState(JSONObject(payload).optJSONObject("state")) ?: return false
      ChoreStore.update(context, ChoreStore.Source.PEER) { s -> ChoreMerge.merge(s, theirs) to Unit }
      return true
    } finally {
      conn.disconnect()
    }
  }

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
