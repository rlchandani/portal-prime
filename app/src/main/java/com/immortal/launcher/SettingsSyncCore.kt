/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The household settings sync's state machine ([SettingsSync] is the facade). Ledger work runs on
 * [worker]; peer POSTs on [net], never blocking the worker. Rules live in [SettingsSyncEngine],
 * wire formats in [SettingsSyncWire]; the exchange half is in [SettingsSyncPeers].
 *
 * Pure: the Portal is reached only through the [SettingsSyncPlatform] interfaces and [clock].
 * Until [start] runs, this Portal's settings are neither read nor written by the bus side, and
 * nothing is stored or published (as before install).
 */
internal class SettingsSyncCore(
    val settings: SyncSettings,
    val host: SyncHost,
    val bus: SyncBus,
    val peers: SyncPeerPost,
    private val timers: SyncTimers,
    val log: SyncLog,
    val clock: () -> Long,
    val worker: ExecutorService,
    val net: Executor,
    private val status: MutableStateFlow<SettingsSyncStatus>,
    private val pausedFlag: AtomicBoolean,
) {
  /** Worker-confined (read elsewhere only for the fleet status). */
  @Volatile var ledger = SyncLedger()
  /** When each joining peer was last offered the household (worker only). */
  var joinerPushedAt: Map<String, Long> = emptyMap()
  @Volatile var started = false
    private set
  private val counters = AtomicReference<Map<String, Long>>(emptyMap())
  val wellFormed: (String, Any?) -> Any? = { k, v -> settings.wellFormed(k, v) }

  val paused: Boolean get() = pausedFlag.get()

  private val detectNow = Runnable { worker.execute { detect() } }
  private val gossip: Runnable = object : Runnable {
    override fun run() {
      worker.execute { detect(); signal() }
      timers.postDelayed(GOSSIP_MS, this)
    }
  }

  /** Overhead counters for the fleet status (bytes are payload text, before UDP/HTTP framing). */
  fun count(name: String, by: Long = 1) { counters.updateAndGet { it + (name to (it[name] ?: 0L) + by) } }

  /** Install, first step: from now on this Portal's settings, ledger and status are live. */
  fun bind() { started = true }

  /** Install: load the ledger, then apply what this build can now apply, then join silently if nothing to choose. */
  fun start() = worker.execute {
    ledger = host.loadLedger(wellFormed) ?: SyncLedger()
    if (ledger.lastKnown.isEmpty()) ledger = ledger.copy(lastKnown = settings.read())
    persist()
    reconcile()
    autoAdoptIfNothingToChoose()
  }

  fun startGossip() = timers.postDelayed(NETWORK_SETTLE_MS, gossip)

  /** A network came up: look for local changes and announce our digest once it has settled. */
  fun networkAvailable() = timers.postDelayed(NETWORK_SETTLE_MS, Runnable { worker.execute { detect(); signal() } })

  /** A household setting may have changed here (registry write, picker screen): look shortly. */
  fun noteLocalChange() {
    timers.cancel(detectNow)
    timers.postDelayed(DETECT_DEBOUNCE_MS, detectNow)
  }

  fun resolveJoin(keepMine: Boolean) = worker.execute {
    if (started) resolveJoinNow(keepMine, how = if (keepMine) "kept" else "adopted")
  }

  fun dismissAdoptCard() = worker.execute {
    if (!started) return@execute
    host.setAdoptCardDismissed(true)
    publish()
  }

  fun setPaused(on: Boolean) = worker.execute {
    pausedFlag.set(on)
    publish()
    if (!on) signal()
  }

  fun rejoin() = worker.execute {
    if (!started) return@execute
    host.clearLedger()
    host.setAdoptCardDismissed(false)
    ledger = SyncLedger(lastKnown = settings.read())
    persist()
    signal()
  }

  fun statusJson(): JSONObject =
      statusJson(status.value, ledger, if (started) host.householdKey()?.let(HouseholdKey::fingerprint) else null, counters.get())

  fun valuesJson(): JSONObject {
    val out = JSONObject()
    if (started) settings.read().toSortedMap().forEach { (k, v) -> out.put(k, v) }
    return out
  }

  /** The peer route's half of an exchange, on the worker for the HTTP thread. Our payload, or null. */
  fun handlePeer(from: String, body: String): String? {
    val theirs = SettingsSyncWire.parsePayload(body) ?: return null
    if (paused) return null
    count("exchangesServed")
    return runCatching { worker.submit<String> { onPeerPayload(from, theirs) }.get(ROUTE_WAIT_MS, TimeUnit.MILLISECONDS) }
        .onFailure { log.w("peer exchange from $from timed out: ${it.javaClass.simpleName}") }
        .getOrNull()
  }

  // ---- worker thread ------------------------------------------------------------------------

  fun resolveJoinNow(keepMine: Boolean, how: String) {
    if (ledger.pending == null) return
    val me = host.selfId() ?: return
    val (next, writes) = SettingsSyncEngine.resolveJoin(ledger, settings.read(), settings.defaults(), me, clock(), keepMine)
    ledger = next
    write(writes)
    host.setAdoptCardDismissed(false)
    persist()
    signal()
    count(if (how == "auto-adopted") "joinsAuto" else "joinsChosen")
    log.i("joined household settings ($how, ${writes.size} written)")
  }

  /**
   * A new Portal whose settings the household wouldn't override (untouched defaults, or only keys
   * the household never set) joins silently: there is nothing to choose.
   */
  fun autoAdoptIfNothingToChoose() {
    if (paused || ledger.phase != SyncPhase.JOINING) return
    if (!SettingsSyncEngine.canAutoAdopt(ledger.pending, settings.read(), settings.defaults())) return
    resolveJoinNow(keepMine = false, how = "auto-adopted")
  }

  fun detect() {
    if (!started) return
    val me = host.selfId() ?: return
    val (next, changed) = SettingsSyncEngine.detectLocal(ledger, settings.read(), me, clock())
    if (ledger.phase == SyncPhase.JOINING && ledger.pending != null) {
      // Joining records nothing, but a setting put back to its default can leave nothing to choose.
      val moved = next != ledger
      ledger = next
      autoAdoptIfNothingToChoose()
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
          if (bus.send(SettingsSyncWire.CHANGED_TYPE, it)) { count("changedSent"); count("busBytesSent", it.toString().length.toLong()) }
        }
      }
    }
    signal()
    log.i("local change: ${changed.joinToString { it.key }}")
  }

  /**
   * After an update, applies household records this build used to refuse (e.g. an option only a
   * newer build knew) and can apply now. A local edit not yet versioned is recorded first, so it wins.
   */
  private fun reconcile() {
    if (ledger.phase != SyncPhase.SYNCED) return
    detect()
    val current = settings.read()
    val due = ledger.records.values.filter { r -> current[r.key] != r.value && settings.validate(r.key, r.value) != null }
        .associate { it.key to it.value }
    if (due.isEmpty()) return
    write(due)
    persist()
    log.i("reconciled ${due.keys.joinToString()}")
  }

  /** Writes peer values through the registry (live side effects), then remembers what reads back. */
  fun write(values: Map<String, Any>) {
    if (values.isEmpty()) return
    val applied = runCatching { settings.apply(values) }.onFailure { log.w("applying synced settings failed", it) }.getOrDefault(emptySet())
    val refused = values.keys - applied
    if (refused.isNotEmpty()) log.i("kept this Portal's value for ${refused.joinToString()} (not valid here)")
    // Siblings too: a setter that rewrites them is the peer's doing, not a local edit to echo back.
    val siblings = settings.siblingKeys(values.keys)
    val readBack = settings.read().filterKeys { it in siblings }
    ledger = SettingsSyncEngine.noteWritten(ledger, readBack)
  }

  fun signal() {
    if (!started) return
    if (paused || host.householdKey() == null) return
    val lan = host.lanAddress() ?: return
    val body = SettingsSyncWire.signalBody(SettingsSyncWire.Signal(SettingsSyncEngine.digest(ledger.records), lan,
        host.port(), ledger.phase == SyncPhase.JOINING))
    if (bus.send(SettingsSyncWire.SIGNAL_TYPE, body)) { count("signalsSent"); count("busBytesSent", body.toString().length.toLong()) }
  }

  fun persist() {
    if (!started) return
    host.saveLedger(ledger)
    publish()
  }

  private fun publish() {
    if (!started) return
    val conflicts = ledger.pending?.let { SettingsSyncEngine.hasConflicts(it, settings.read(), settings.defaults()) } ?: false
    status.value = SettingsSyncStatus.from(ledger, host.householdKey() != null, paused, conflicts,
        adoptCardDismissed = host.adoptCardDismissed())
  }

  companion object {
    const val GOSSIP_MS = 60_000L
    private const val DETECT_DEBOUNCE_MS = 400L
    private const val NETWORK_SETTLE_MS = 4_000L
    /** A Portal still choosing how to join is re-offered the household at most this often. */
    const val JOINER_PUSH_MS = 10 * 60_000L
    private const val ROUTE_WAIT_MS = 4_000L
    /** A bigger batch (e.g. "reset to defaults") goes out as one digest signal, not a packet per key. */
    private const val MAX_CHANGED_SENDS = 8
    const val MAX_RATE_ENTRIES = 32

    /** The fleet `settings_sync` status: what [status] shows plus the digest, key fingerprint and counters. */
    fun statusJson(s: SettingsSyncStatus, ledger: SyncLedger, keyFingerprint: String?, counters: Map<String, Long>): JSONObject {
      val peers = JSONArray()
      s.peers.forEach { peers.put(JSONObject().put("name", it.name).put("seenAt", it.seenAt).put("syncedAt", it.syncedAt)) }
      return JSONObject().put("available", s.available).put("paused", s.paused).put("phase", s.phase.name)
          .put("lastSyncAt", s.lastSyncAt).put("records", s.recordCount).put("peers", peers)
          .put("adoptFrom", s.adoptFrom ?: JSONObject.NULL).put("digest", SettingsSyncEngine.digest(ledger.records))
          .put("keyFingerprint", keyFingerprint ?: JSONObject.NULL)
          .put("counters", JSONObject().apply { counters.toSortedMap().forEach { (k, v) -> put(k, v) } })
    }
  }
}
