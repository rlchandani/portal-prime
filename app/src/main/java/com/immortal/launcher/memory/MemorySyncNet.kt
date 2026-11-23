/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.immortal.launcher.BoundedRead
import com.immortal.launcher.ChoresFeature
import com.immortal.launcher.FleetConfig
import com.immortal.launcher.FleetHttpServer
import com.immortal.launcher.HouseholdBus
import com.immortal.launcher.HouseholdKey
import com.immortal.launcher.HouseholdListSync
import com.immortal.launcher.HouseholdMessage
import com.immortal.launcher.PeerRequestGate
import com.immortal.launcher.RemoteCompanionStore
import com.immortal.launcher.RemoteFeatureRoutes
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * What Jarvis knows, synced between this household's Portals (plan §3.8, slice 5), over the reminders transport:
 * - after a profile is written, a signed `memory_rev` bus signal ([MemorySync.signalBody]) names this Portal's scopes
 *   by keyed ids, with a revision each, held back [SIGNAL_DELAY_MS] so a Forget's Undo can land first;
 * - a Portal holding one of those scopes at another revision posts its copies, sealed, to [PATH]; the reply is the
 *   merge, sealed back, and both sides keep it ([MemoryRuntime.mergeIn]).
 * A Portal only sends and takes the household's profile and those of adults enrolled on it ([JarvisMemory.syncScopes]):
 * never a guest's, a kid's or a teen's, and nobody's it doesn't know. Nothing at all with the switch off or without
 * a household key. Logs carry counts only.
 */
internal object MemorySyncNet {
  const val PATH = "/remote/memorypeer"
  private const val TAG = "HearthpaneMemorySync"
  private const val CONNECT_MS = 3_000
  private const val READ_MS = 8_000
  const val SIGNAL_DELAY_MS = 6_000L
  private val RETRY_MS = longArrayOf(5_000L, 30_000L, 120_000L)

  private val net = Executors.newSingleThreadExecutor { r -> Thread(r, "hearthpane-memory-net").apply { isDaemon = true } }
  private val main by lazy { Handler(Looper.getMainLooper()) }
  private val random = SecureRandom()
  private val replay = HouseholdMessage.ReplayGuard()
  @Volatile private var installed = false
  @Volatile private var retryStep = 0
  @Volatile private var pendingSignal: Runnable? = null

  /** Registers the bus signal (once). Sync itself only runs while the switch is on. */
  fun install(context: Context) {
    if (installed) return
    installed = true
    HouseholdBus.register(MemorySync.SIGNAL_TYPE) { ctx, message -> onSignal(ctx, message) }
  }

  /** Announces this Portal's revisions soon (coalesced): after a write, and once at start-up for catch-up. */
  fun signalSoon(context: Context) {
    val app = context.applicationContext
    val task = Runnable { net.execute { runCatching { signal(app) } } }
    pendingSignal?.let(main::removeCallbacks)
    pendingSignal = task
    main.postDelayed(task, SIGNAL_DELAY_MS)
  }

  private fun signal(context: Context) {
    if (!JarvisMemory.enabled(context)) return
    val key = HouseholdKey.get(context) ?: return
    val host = HouseholdListSync.lanAddress() ?: return
    val profiles = JarvisMemory.syncProfiles(context)
    HouseholdBus.send(context, MemorySync.SIGNAL_TYPE,
        MemorySync.signalBody(key, host, FleetConfig.port(context), profiles))
    Log.i(TAG, "announced ${profiles.size} scopes")
  }

  /** Bus handler (listener thread; the bus already verified the signature and freshness). */
  private fun onSignal(context: Context, message: HouseholdMessage) {
    val app = context.applicationContext
    net.execute {
      runCatching {
        if (!JarvisMemory.enabled(app)) return@runCatching
        val signal = MemorySync.parseSignal(message.body) ?: return@runCatching
        val key = HouseholdKey.get(app) ?: return@runCatching
        val scopes = MemorySync.differing(key, signal, JarvisMemory.syncProfiles(app))
        if (scopes.isEmpty()) return@runCatching
        retryStep = 0
        exchangeWithRetry(app, signal, scopes.toSet())
      }.onFailure { Log.i(TAG, "signal not handled (${it.javaClass.simpleName})") }
    }
  }

  private fun exchangeWithRetry(context: Context, signal: MemorySync.Signal, scopes: Set<MemoryScope>) {
    val ok = runCatching { exchange(context, signal, scopes) }
        .onFailure { Log.i(TAG, "exchange failed (${it.javaClass.simpleName})") }.getOrDefault(false)
    if (ok) {
      retryStep = 0
      return
    }
    val step = retryStep
    if (step >= RETRY_MS.size) return
    retryStep = step + 1
    main.postDelayed({ net.execute { exchangeWithRetry(context, signal, scopes) } }, RETRY_MS[step])
  }

  private fun exchange(context: Context, signal: MemorySync.Signal, scopes: Set<MemoryScope>): Boolean {
    if (!JarvisMemory.enabled(context)) return true
    val key = HouseholdKey.get(context) ?: return false
    val mine = JarvisMemory.syncProfiles(context).filter { it.scope in scopes }
    if (mine.isEmpty()) return true
    val nonce = ByteArray(12).also(random::nextBytes).let(HouseholdKey::toHex)
    val body = MemorySync.sealedBody(key, nonce, MemorySync.REQUEST, mine)
    val header = MemorySync.auth.requestHeader(key, RemoteCompanionStore.id(context), nonce,
        System.currentTimeMillis(), body) ?: return false
    val conn = URL("http", signal.host, signal.port, PATH).openConnection() as HttpURLConnection
    try {
      conn.connectTimeout = CONNECT_MS
      conn.readTimeout = READ_MS
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.instanceFollowRedirects = false
      conn.setRequestProperty("Content-Type", "application/json")
      conn.setRequestProperty(HEADER, header)
      val bytes = body.toByteArray(Charsets.UTF_8)
      conn.setFixedLengthStreamingMode(bytes.size)
      conn.outputStream.use { it.write(bytes) }
      if (conn.responseCode == 409) return true // the peer has learning off: nothing to do
      if (conn.responseCode != 200) return false
      val text = BoundedRead.reply(conn, MemorySync.MAX_BODY_CHARS) ?: return false
      val envelope = JSONObject(text)
      val payload = envelope.optString("payload")
      if (!MemorySync.auth.verifyResponse(key, nonce, payload, envelope.optString("sig"))) return false
      val theirs = MemorySync.openBody(key, nonce, MemorySync.RESPONSE, payload, scopes) ?: return false
      val runtime = JarvisMemory.runtime(context)
      theirs.forEach(runtime::mergeIn)
      Log.i(TAG, "exchanged ${mine.size} scopes, took ${theirs.size}")
      return true
    } finally {
      conn.disconnect()
    }
  }

  /** `POST /remote/memorypeer`: a peer's sealed copies in, the merge sealed back. */
  fun handle(context: Context, req: FleetHttpServer.Request): FleetHttpServer.Response {
    if (req.method != "POST") return RemoteFeatureRoutes.json(405, RemoteFeatureRoutes.error("method_not_allowed"))
    if (req.contentLength !in 1..MemorySync.MAX_BODY_CHARS) {
      return RemoteFeatureRoutes.json(413, RemoteFeatureRoutes.error("body_too_large"))
    }
    val key = HouseholdKey.get(context) ?: return RemoteFeatureRoutes.json(503, RemoteFeatureRoutes.error("no_household_key"))
    // Header first: an unsigned or stale request never gets its body read.
    if (!PeerRequestGate.admits(req.header(HEADER), MemorySync.REQUEST_TYPE, key, System.currentTimeMillis())) {
      return RemoteFeatureRoutes.json(401, RemoteFeatureRoutes.error("unauthorized"))
    }
    if (!JarvisMemory.enabled(context)) return RemoteFeatureRoutes.json(409, RemoteFeatureRoutes.error("learning_off"))
    val text = try {
      req.bodyText(MemorySync.MAX_BODY_CHARS)
    } catch (e: Exception) {
      return FleetHttpServer.requestBodyError(e) ?: RemoteFeatureRoutes.json(400, RemoteFeatureRoutes.error("bad_body"))
    }
    val caller = MemorySync.auth.verifyRequest(req.header(HEADER), text, key, System.currentTimeMillis(), replay,
        ChoresFeature.selfId(context)) ?: return RemoteFeatureRoutes.json(401, RemoteFeatureRoutes.error("unauthorized"))
    val accepted = JarvisMemory.syncScopes(context).toSet()
    val theirs = MemorySync.openBody(key, caller.nonce, MemorySync.REQUEST, text, accepted)
        ?: return RemoteFeatureRoutes.json(400, RemoteFeatureRoutes.error("bad_state"))
    val runtime = JarvisMemory.runtime(context)
    val merged = theirs.map(runtime::mergeIn)
    Log.i(TAG, "peer sent ${theirs.size} scopes")
    val payload = MemorySync.sealedBody(key, caller.nonce, MemorySync.RESPONSE, merged)
    return RemoteFeatureRoutes.json(200, JSONObject().put("payload", payload)
        .put("sig", MemorySync.auth.signResponse(key, caller.nonce, payload)))
  }

  private const val HEADER = com.immortal.launcher.HouseholdListPeerAuth.HEADER
}
