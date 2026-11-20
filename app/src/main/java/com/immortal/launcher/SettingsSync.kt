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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Household settings sync: keeps every [com.immortal.launcher.settings.SettingScope.HOUSEHOLD]
 * setting the same on all Portals that share the household key. A change here goes out at once as
 * a signed `setting_changed`; every Portal also gossips a digest of its versions, and a peer whose
 * digest differs exchanges full state over the signed `/remote/settingspeer` route — so a Portal
 * that was off catches up. Received values are schema-checked and written through the registry, so
 * they take effect live exactly as if set on this Portal. Rules live in [SettingsSyncEngine];
 * wire formats in [SettingsSyncWire]. All ledger work runs on one worker thread.
 */
internal object SettingsSync {
  private const val TAG = "HearthpaneSettingsSync"
  const val GOSSIP_MS = 60_000L
  private const val DETECT_DEBOUNCE_MS = 400L
  private const val NETWORK_SETTLE_MS = 4_000L
  /** A Portal still choosing how to join is re-offered the household at most this often. */
  private const val JOINER_PUSH_MS = 10 * 60_000L
  private const val ROUTE_WAIT_MS = 4_000L
  /** A bigger batch (e.g. "reset to defaults") goes out as one digest signal, not a packet per key. */
  private const val MAX_CHANGED_SENDS = 8

  private val catalog: SettingsSyncCatalog get() = SettingsSyncCatalog.household
  private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "hearthpane-settings-sync").apply { isDaemon = true } }
  private val net = Executors.newSingleThreadExecutor { r -> Thread(r, "hearthpane-settings-net").apply { isDaemon = true } }
  private val main by lazy { Handler(Looper.getMainLooper()) }
  private val wellFormed: (String, Any?) -> Any? = { k, v -> catalog.wellFormed(k, v) }

  @Volatile private var installed = false
  @Volatile private var app: Context? = null
  /** Worker-confined. */
  @Volatile private var ledger = SyncLedger()
  private val joinerPushedAt = HashMap<String, Long>()
  private const val MAX_RATE_ENTRIES = 32
  @Volatile var paused = false
    private set

  /** Overhead counters for the fleet status (bytes are payload text, before UDP/HTTP framing). */
  private val counters = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
  private fun count(name: String, by: Long = 1) { counters.getOrPut(name) { java.util.concurrent.atomic.AtomicLong() }.addAndGet(by) }

  private val _status = MutableStateFlow(SettingsSyncStatus())
  val status: StateFlow<SettingsSyncStatus> = _status.asStateFlow()

  private val detectNow = Runnable { worker.execute { detect() } }
  private val gossip: Runnable = object : Runnable {
    override fun run() {
      worker.execute { detect(); signal() }
      main.postDelayed(this, GOSSIP_MS)
    }
  }

  fun install(context: Context) {
    if (installed) return
    installed = true
    val appContext = context.applicationContext
    app = appContext
    val householdDomains = catalog.entries.values.map { it.domain.id }.toSet()
    SettingsDomain.valuesApplied = { _, domainId, _ -> if (domainId in householdDomains) noteLocalChange() }
    HouseholdBus.register(SettingsSyncWire.CHANGED_TYPE) { _, m -> worker.execute(HouseholdBus.contained(m.type) { onChanged(m) }) }
    HouseholdBus.register(SettingsSyncWire.SIGNAL_TYPE) { _, m -> worker.execute(HouseholdBus.contained(m.type) { onSignal(m) }) }
    worker.execute {
      ledger = SettingsSyncStore.load(appContext, wellFormed) ?: SyncLedger()
      if (ledger.lastKnown.isEmpty()) ledger = ledger.copy(lastKnown = catalog.read(appContext))
      persist()
      reconcile(appContext)
      autoAdoptIfNothingToChoose(appContext)
    }
    watchNetwork(appContext)
    main.postDelayed(gossip, NETWORK_SETTLE_MS)
  }

  /** A household setting may have changed here (registry write, picker screen): look shortly. */
  fun noteLocalChange() {
    if (!installed) return
    main.removeCallbacks(detectNow)
    main.postDelayed(detectNow, DETECT_DEBOUNCE_MS)
  }

  /** The one-time join choice from Settings: adopt the household's settings, or keep this Portal's. */
  fun resolveJoin(keepMine: Boolean) = worker.execute {
    val c = app ?: return@execute
    resolveJoinNow(c, keepMine, how = if (keepMine) "kept" else "adopted")
  }

  /** "Later" on the Home card: hide the card; the choice stays in About › Sync. */
  fun dismissAdoptCard() = worker.execute {
    val c = app ?: return@execute
    SettingsSyncStore.setAdoptCardDismissed(c, true)
    publish()
  }

  /** Debug/fleet hook: stop sending and receiving (simulates this Portal being offline). */
  fun setPaused(on: Boolean) = worker.execute {
    paused = on
    publish()
    if (!on) signal()
  }

  /** Debug/fleet hook: forget the ledger and rejoin as if new (settings themselves are untouched). */
  fun rejoin() = worker.execute {
    val c = app ?: return@execute
    SettingsSyncStore.clear(c)
    SettingsSyncStore.setAdoptCardDismissed(c, false)
    ledger = SyncLedger(lastKnown = catalog.read(c))
    persist()
    signal()
  }

  fun statusJson(): org.json.JSONObject {
    val s = _status.value
    val peers = org.json.JSONArray()
    s.peers.forEach { peers.put(org.json.JSONObject().put("name", it.name).put("seenAt", it.seenAt).put("syncedAt", it.syncedAt)) }
    return org.json.JSONObject().put("available", s.available).put("paused", s.paused).put("phase", s.phase.name)
        .put("lastSyncAt", s.lastSyncAt).put("records", s.recordCount).put("peers", peers)
        .put("adoptFrom", s.adoptFrom ?: org.json.JSONObject.NULL).put("digest", SettingsSyncEngine.digest(ledger.records))
        .put("keyFingerprint", app?.let { HouseholdKey.get(it) }?.let(HouseholdKey::fingerprint) ?: org.json.JSONObject.NULL)
        .put("counters", org.json.JSONObject().apply { counters.toSortedMap().forEach { (k, v) -> put(k, v.get()) } })
  }

  /** This Portal's household values (no secrets or device-local keys exist in the catalog). */
  fun valuesJson(): org.json.JSONObject {
    val out = org.json.JSONObject()
    app?.let { catalog.read(it) }?.toSortedMap()?.forEach { (k, v) -> out.put(k, v) }
    return out
  }

  // ---- worker thread ------------------------------------------------------------------------

  private fun resolveJoinNow(c: Context, keepMine: Boolean, how: String) {
    if (ledger.pending == null) return
    val me = self(c) ?: return
    val (next, writes) = SettingsSyncEngine.resolveJoin(ledger, catalog.read(c), catalog.defaults(), me,
        System.currentTimeMillis(), keepMine)
    ledger = next
    write(c, writes)
    SettingsSyncStore.setAdoptCardDismissed(c, false)
    persist()
    signal()
    count(if (how == "auto-adopted") "joinsAuto" else "joinsChosen")
    Log.i(TAG, "joined household settings ($how, ${writes.size} written)")
  }

  /**
   * A new Portal whose settings the household wouldn't override (untouched defaults, or only keys
   * the household never set) joins silently: there is nothing to choose.
   */
  private fun autoAdoptIfNothingToChoose(c: Context) {
    if (paused || ledger.phase != SyncPhase.JOINING) return
    if (!SettingsSyncEngine.canAutoAdopt(ledger.pending, catalog.read(c), catalog.defaults())) return
    resolveJoinNow(c, keepMine = false, how = "auto-adopted")
  }

  private fun detect() {
    val c = app ?: return
    val me = self(c) ?: return
    val (next, changed) = SettingsSyncEngine.detectLocal(ledger, catalog.read(c), me, System.currentTimeMillis())
    if (ledger.phase == SyncPhase.JOINING && ledger.pending != null) {
      // Joining records nothing, but a setting put back to its default can leave nothing to choose.
      val moved = next != ledger
      ledger = next
      autoAdoptIfNothingToChoose(c)
      if (moved && ledger.phase == SyncPhase.JOINING) persist()
      return
    }
    if (next == ledger) return
    ledger = next
    persist()
    if (changed.isEmpty() || paused) return
    if (changed.size <= MAX_CHANGED_SENDS) {
      changed.forEach { r ->
        SettingsSyncWire.changedBody(r)?.let {
          if (HouseholdBus.send(c, SettingsSyncWire.CHANGED_TYPE, it)) { count("changedSent"); count("busBytesSent", it.toString().length.toLong()) }
        }
      }
    }
    signal()
    Log.i(TAG, "local change: ${changed.joinToString { it.key }}")
  }

  /**
   * After an update, applies household records this build used to refuse (e.g. an option only a
   * newer build knew) and can apply now. A local edit not yet versioned is recorded first, so it wins.
   */
  private fun reconcile(c: Context) {
    if (ledger.phase != SyncPhase.SYNCED) return
    detect()
    val current = catalog.read(c)
    val due = ledger.records.values.filter { r ->
      current[r.key] != r.value && catalog.validate(r.key, r.value) != null
    }.associate { it.key to it.value }
    if (due.isEmpty()) return
    write(c, due)
    persist()
    Log.i(TAG, "reconciled ${due.keys.joinToString()}")
  }

  private fun onChanged(m: HouseholdMessage) {
    val c = app ?: return
    if (paused || ledger.phase != SyncPhase.SYNCED) return
    count("changedReceived")
    val record = SettingsSyncWire.parseChanged(m.from, m.body) ?: return count("changedRefused")
    detect() // a pending local edit gets its version before the peer's is compared
    mergeAndWrite(c, listOf(record), m.from, null)
  }

  private fun onSignal(m: HouseholdMessage) {
    val c = app ?: return
    if (paused) return
    count("signalsReceived")
    val signal = SettingsSyncWire.parseSignal(m.body) ?: return
    val now = System.currentTimeMillis()
    ledger = SettingsSyncEngine.notePeer(ledger, m.from, null, now, synced = false)
    val me = self(c) ?: return
    if (ledger.phase == SyncPhase.JOINING) {
      when (SettingsSyncEngine.joinAction(me, m.from, signal.digest, signal.joining)) {
        JoinAction.FOUND -> {
          ledger = SettingsSyncEngine.found(ledger, catalog.read(c), catalog.defaults(), me, now)
          persist()
          signal()
          Log.i(TAG, "founded household settings with ${ledger.records.size} changed values")
        }
        // Already holding exactly this household's settings for the choice: nothing new to fetch.
        JoinAction.FETCH -> if (signal.digest == ledger.pending?.let { SettingsSyncEngine.digest(it.records) }) persist()
            else exchange(c, signal, m.from)
        JoinAction.WAIT -> persist()
      }
      return
    }
    if (signal.digest == SettingsSyncEngine.digest(ledger.records)) return persist()
    if (signal.joining && now - (joinerPushedAt[m.from] ?: 0L) < JOINER_PUSH_MS) return persist()
    if (signal.joining) {
      // Rate-limit memory only: sender ids are claimed, so never let them grow it without bound.
      if (joinerPushedAt.size >= MAX_RATE_ENTRIES) joinerPushedAt.clear()
      joinerPushedAt[m.from] = now
    }
    exchange(c, signal, m.from)
  }

  /** The peer route's half of an exchange (on the worker, for the HTTP thread). Returns our payload. */
  private fun onPeerPayload(c: Context, from: String, theirs: SettingsSyncWire.Payload): String {
    val now = System.currentTimeMillis()
    ledger = SettingsSyncEngine.notePeer(ledger, from, theirs.name, now, synced = !theirs.joining)
    if (!paused) {
      if (ledger.phase == SyncPhase.JOINING) {
        ledger = SettingsSyncEngine.addPending(ledger, from, theirs.name, theirs.records, wellFormed)
        autoAdoptIfNothingToChoose(c)
      } else if (!theirs.joining) {
        detect()
        mergeAndWrite(c, theirs.records, from, theirs.name)
      }
    }
    persist()
    val joining = ledger.phase == SyncPhase.JOINING
    return SettingsSyncWire.payload(HouseholdListSync.selfName(c), joining, if (joining) emptyList() else ledger.records.values)
  }

  fun handlePeer(context: Context, from: String, body: String): String? {
    val theirs = SettingsSyncWire.parsePayload(body) ?: return null
    if (paused) return null
    count("exchangesServed")
    return runCatching { worker.submit<String> { onPeerPayload(context, from, theirs) }.get(ROUTE_WAIT_MS, TimeUnit.MILLISECONDS) }
        .onFailure { Log.w(TAG, "peer exchange from $from timed out: ${it.javaClass.simpleName}") }
        .getOrNull()
  }

  /** Builds our side here, posts on the network thread (never blocking the worker), merges back here. */
  private fun exchange(c: Context, signal: SettingsSyncWire.Signal, peer: String) {
    val joining = ledger.phase == SyncPhase.JOINING
    val body = SettingsSyncWire.payload(HouseholdListSync.selfName(c), joining, if (joining) emptyList() else ledger.records.values)
    persist()
    count("exchangesStarted")
    count("httpBytesSent", body.length.toLong())
    net.execute {
      val reply = runCatching { SettingsSyncNet.post(c, signal.host, signal.port, body) }
          .onFailure { Log.i(TAG, "exchange with $peer failed: ${it.javaClass.simpleName}") }.getOrNull()
      val theirs = SettingsSyncWire.parsePayload(reply) ?: return@execute count("exchangesFailed")
      count("httpBytesReceived", reply?.length?.toLong() ?: 0L)
      worker.execute { onExchangeReply(c, peer, theirs) }
    }
  }

  private fun onExchangeReply(c: Context, peer: String, theirs: SettingsSyncWire.Payload) {
    if (paused) return
    ledger = SettingsSyncEngine.notePeer(ledger, peer, theirs.name, System.currentTimeMillis(), synced = true)
    if (ledger.phase == SyncPhase.JOINING) {
      ledger = SettingsSyncEngine.addPending(ledger, peer, theirs.name, theirs.records, wellFormed)
      persist()
      autoAdoptIfNothingToChoose(c)
    } else {
      mergeAndWrite(c, theirs.records, peer, theirs.name)
    }
  }

  private fun mergeAndWrite(c: Context, records: List<SyncRecord>, from: String, name: String?) {
    val result = SettingsSyncEngine.mergeAll(ledger, records, wellFormed)
    ledger = SettingsSyncEngine.notePeer(result.ledger, from, name, System.currentTimeMillis(), synced = true)
    count("recordsApplied", result.applied.size.toLong())
    count("recordsRefused", result.rejected.toLong())
    if (result.rejected > 0) Log.w(TAG, "refused ${result.rejected} malformed setting(s) from $from")
    val writes = result.applied.filter { ledger.lastKnown[it.key] != it.value }.associate { it.key to it.value }
    write(c, writes)
    persist()
    if (result.applied.isNotEmpty()) Log.i(TAG, "from $from: ${result.applied.joinToString { it.key }}")
  }

  /** Writes peer values through the registry (live side effects), then remembers what reads back. */
  private fun write(c: Context, values: Map<String, Any>) {
    if (values.isEmpty()) return
    val applied = runCatching { catalog.apply(c, values) }
        .onFailure { Log.w(TAG, "applying synced settings failed", it) }.getOrDefault(emptySet())
    val refused = values.keys - applied
    if (refused.isNotEmpty()) Log.i(TAG, "kept this Portal's value for ${refused.joinToString()} (not valid here)")
    val siblings = catalog.siblingKeys(values.keys)
    val readBack = catalog.read(c).filterKeys { it in siblings }
    ledger = SettingsSyncEngine.noteWritten(ledger, readBack)
  }

  private fun signal() {
    val c = app ?: return
    if (paused || HouseholdKey.get(c) == null) return
    val host = HouseholdListSync.lanAddress() ?: return
    val body = SettingsSyncWire.signalBody(SettingsSyncWire.Signal(SettingsSyncEngine.digest(ledger.records), host,
        FleetConfig.port(c), ledger.phase == SyncPhase.JOINING))
    if (HouseholdBus.send(c, SettingsSyncWire.SIGNAL_TYPE, body)) { count("signalsSent"); count("busBytesSent", body.toString().length.toLong()) }
  }

  private fun persist() {
    val c = app ?: return
    SettingsSyncStore.save(c, ledger)
    publish()
  }

  private fun publish() {
    val c = app ?: return
    val conflicts = ledger.pending?.let { SettingsSyncEngine.hasConflicts(it, catalog.read(c), catalog.defaults()) } ?: false
    _status.value = SettingsSyncStatus.from(ledger, HouseholdKey.get(c) != null, paused, conflicts,
        adoptCardDismissed = SettingsSyncStore.adoptCardDismissed(c))
  }

  private fun self(c: Context): String? = runCatching { RemoteCompanionStore.id(c) }.getOrNull()

  private fun watchNetwork(context: Context) {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
    runCatching {
      cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
          main.postDelayed({ worker.execute { detect(); signal() } }, NETWORK_SETTLE_MS)
        }
      })
    }.onFailure { Log.w(TAG, "network callback unavailable; catch-up relies on gossip", it) }
  }
}
