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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Keeps this Portal's list in step with the other Portals. No polling: work happens only when
 * something changes here, a peer's signed [HouseholdListPeerAuth.Signal] arrives, the network comes
 * back, or a failed exchange's one-shot retry timer (bounded backoff) fires.
 *
 * Bus handlers run on the bus thread; they only hand the work to this feature's single network
 * thread (an exchange is blocking HTTP and must not run on the main thread). UI state is published
 * through [state] and collected by Compose on the main thread.
 */
internal object HouseholdListSync {
  private const val TAG = "ImmortalHouseholdSync"
  private const val MAX_RESPONSE = 1024 * 1024
  private const val CATCH_UP_SETTLE_MS = 4_000L
  private const val MAX_RETRIES = 10

  private val net = Executors.newSingleThreadExecutor { r -> Thread(r, "immortal-household-list-net").apply { isDaemon = true } }
  private val main by lazy { Handler(Looper.getMainLooper()) }
  private val random = SecureRandom()
  private val flow = MutableStateFlow(HouseholdListSyncState())
  @Volatile private var lastPeer: HouseholdListPeerAuth.Signal? = null
  @Volatile private var lastPeerId: String? = null
  private val retry = Runnable { lastPeer?.let { p -> appContext?.let { exchangeLater(it, p, lastPeerId) } } }
  private val finishCatchUp = Runnable { finishCatchUpNow() }
  @Volatile private var appContext: Context? = null

  val state: StateFlow<HouseholdListSyncState> get() = flow
  private val now: Long get() = HouseholdListStore.clock()

  fun attach(context: Context) { appContext = context.applicationContext }

  // ---- triggers ----

  /** A change made here (screen tap or phone batch): tell the other Portals. */
  fun onLocalChange(context: Context, before: HouseholdListState, after: HouseholdListState, source: HouseholdListStore.Source) {
    val self = selfName(context)
    when (source) {
      HouseholdListStore.Source.LOCAL -> flow.update { s -> if (s.online) s else s.copy(offlineEdits = s.offlineEdits + 1) }
      HouseholdListStore.Source.PHONE, HouseholdListStore.Source.PEER -> flow.update { s ->
        val arrivals = HouseholdListSyncState.newArrivals(before, after, self, now, whileOffline = s.catchUp != null)
        val counted = if (source == HouseholdListStore.Source.PEER) s.catchUp?.let {
          it.copy(arrived = it.arrived + HouseholdListSyncState.changedCount(before, after))
        } else s.catchUp
        s.copy(arrivals = (s.arrivals + arrivals).filterValues { now - it.at <= HouseholdListSyncState.FRESH_MS * 2 }, catchUp = counted)
      }
    }
    if (source == HouseholdListStore.Source.PEER && flow.value.catchUp != null) rearmCatchUp()
    signal(context)
  }

  /** Broadcasts "my list is now <digest>, exchange with me at host:port". */
  fun signal(context: Context) {
    val app = context.applicationContext
    net.execute { // interface lookup + digest off the caller's (often main) thread
      val host = lanAddress() ?: return@execute
      val digest = HouseholdListStore.current(app).digest
      HouseholdBus.send(app, HouseholdListPeerAuth.SIGNAL_TYPE,
          HouseholdListPeerAuth.signalBody(HouseholdListPeerAuth.Signal(digest, host, FleetConfig.port(app))))
    }
  }

  /** Bus handler for [HouseholdListPeerAuth.SIGNAL_TYPE]. */
  fun onSignal(context: Context, message: HouseholdMessage) {
    val signal = HouseholdListPeerAuth.parseSignal(message.body) ?: return
    flow.update { s ->
      val prior = s.peers[message.from]
      s.copy(peers = s.peers + (message.from to HouseholdListSyncState.PeerInfo(prior?.name ?: "the other Portal", true, now)))
    }
    if (signal.digest == HouseholdListStore.current(context).digest) { markUp(); return }
    exchangeLater(context, signal, message.from)
  }

  fun onNetwork(context: Context, available: Boolean) {
    val was = flow.value.online
    flow.update { s ->
      when {
        available && !s.online -> s.copy(online = true, offlineEdits = 0,
            catchUp = HouseholdListSyncState.CatchUp(since = now, sent = s.offlineEdits))
        !available -> s.copy(online = false, lastUpAt = if (s.online) now else s.lastUpAt, busy = 0, retryAt = null)
        else -> s
      }
    }
    if (available && !was) { signal(context); rearmCatchUp() }
    if (!available) main.removeCallbacks(retry)
  }

  /** "Try now" on the failing banner. */
  fun retryNow(context: Context) {
    main.removeCallbacks(retry)
    lastPeer?.let { exchangeLater(context, it, lastPeerId) } ?: signal(context)
  }

  /** A phone read or wrote the list; counts toward "Up to date with … and 2 phones". */
  fun notePhone(name: String) = flow.update { it.copy(phones = it.phones + (name to now)) }

  fun noteExchangeFrom(peerId: String, name: String) = flow.update { s ->
    s.copy(peers = s.peers + (peerId to HouseholdListSyncState.PeerInfo(name, true, now)), everExchanged = true)
  }

  fun showNotice(text: String) = flow.update { s ->
    s.copy(notice = HouseholdListSyncState.Notice(text, now + HouseholdListSyncState.NOTICE_MS, (s.notice?.seq ?: 0) + 1))
  }

  // ---- exchange ----

  private fun exchangeLater(context: Context, signal: HouseholdListPeerAuth.Signal, peerId: String? = null) {
    lastPeer = signal
    if (peerId != null) lastPeerId = peerId
    flow.update { it.copy(busy = it.busy + 1) }
    net.execute {
      val ok = runCatching { exchange(context, signal, peerId) }
          .onFailure { Log.i(TAG, "list exchange with peer failed: ${it.javaClass.simpleName}") }
          .getOrDefault(false)
      flow.update { it.copy(busy = (it.busy - 1).coerceAtLeast(0)) }
      if (ok) markUp() else scheduleRetry(peerId)
    }
  }

  private fun exchange(context: Context, signal: HouseholdListPeerAuth.Signal, peerId: String?): Boolean {
    val key = HouseholdKey.get(context) ?: return false
    val self = RemoteCompanionStore.id(context)
    val body = JSONObject().put("name", selfName(context))
        .put("state", HouseholdListCodec.encodeState(HouseholdListStore.current(context))).toString()
    val nonce = ByteArray(12).also(random::nextBytes).let(HouseholdKey::toHex)
    val header = HouseholdListPeerAuth.requestHeader(key, self, nonce, System.currentTimeMillis(), body) ?: return false
    val conn = URL("http", signal.host, signal.port, HouseholdListPeerRoute.PATH).openConnection() as HttpURLConnection
    try {
      conn.connectTimeout = 3_000
      conn.readTimeout = 5_000
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.instanceFollowRedirects = false
      conn.setRequestProperty("Content-Type", "application/json")
      conn.setRequestProperty(HouseholdListPeerAuth.HEADER, header)
      val bytes = body.toByteArray(Charsets.UTF_8)
      conn.setFixedLengthStreamingMode(bytes.size)
      conn.outputStream.use { it.write(bytes) }
      if (conn.responseCode != 200) return false
      val text = conn.inputStream.use { input ->
        val buf = input.readBytes()
        if (buf.size > MAX_RESPONSE) return false
        String(buf, Charsets.UTF_8)
      }
      val envelope = JSONObject(text)
      val payload = envelope.optString("payload")
      if (!HouseholdListPeerAuth.verifyResponse(key, nonce, payload, envelope.optString("sig"))) return false
      val reply = JSONObject(payload)
      val theirs = HouseholdListCodec.decodeState(reply.optJSONObject("state"), now) ?: return false
      val name = HouseholdListText.cleanName(reply.optString("name")) ?: "the other Portal"
      peerId?.let { noteExchangeFrom(it, name) } ?: flow.update { it.copy(everExchanged = true) }
      HouseholdListStore.update(context, HouseholdListStore.Source.PEER) { s -> HouseholdListMerge.merge(s, theirs) to Unit }
      return true
    } finally {
      conn.disconnect()
    }
  }

  private fun markUp() {
    main.removeCallbacks(retry)
    flow.update { it.copy(lastUpAt = now, retryAt = null, retryStep = 0) }
  }

  private fun scheduleRetry(peerId: String?) {
    if (!flow.value.online) return
    val step = flow.value.retryStep
    val delay = HouseholdListSyncState.retryDelay(step)
    flow.update { s ->
      val peers = peerId?.let { id -> s.peers[id]?.let { p -> s.peers + (id to p.copy(reachable = false)) } } ?: s.peers
      s.copy(retryAt = now + delay, retryStep = step + 1, peers = peers)
    }
    if (step < MAX_RETRIES) main.postDelayed(retry, delay)
  }

  private fun rearmCatchUp() {
    main.removeCallbacks(finishCatchUp)
    main.postDelayed(finishCatchUp, CATCH_UP_SETTLE_MS)
  }

  private fun finishCatchUpNow() {
    val c = flow.value.catchUp ?: return
    flow.update { it.copy(catchUp = null, lastUpAt = now) }
    showNotice(HouseholdListSyncStatus.catchUpMessage(c.arrived, c.sent))
  }

  fun selfName(context: Context): String = HouseholdListText.cleanName(FleetConfig.name(context)) ?: "This Portal"

  /** This Portal's private LAN IPv4 address, if it has one. */
  private fun lanAddress(): String? = runCatching {
    NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .map { it.hostAddress.orEmpty() }
        .firstOrNull(HouseholdListPeerAuth::isPrivateLan)
  }.getOrNull()
}
