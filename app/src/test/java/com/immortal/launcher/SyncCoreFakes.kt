/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/**
 * A thread on the caller's stack: a task runs at once unless one is already running on this
 * executor, then it waits its turn (as on a real single thread). Two of them stand in for the
 * sync's worker and network threads.
 */
internal class TrampolineExecutor : AbstractExecutorService() {
  private val queue = ArrayDeque<Runnable>()
  private var running = false

  override fun execute(command: Runnable) {
    queue.addLast(command)
    if (running) return
    running = true
    try { while (queue.isNotEmpty()) queue.removeFirst().run() } finally { running = false }
  }

  override fun shutdown() = Unit
  override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
  override fun isShutdown() = false
  override fun isTerminated() = false
  override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
}

/**
 * Household settings in memory. "clock.face" behaves like the real clock face: setting it also
 * rewrites its sibling "clock.style" (the 1.261 echo case). Keys outside [known] are not household.
 */
internal class FakeSyncSettings(initial: Map<String, Any> = emptyMap()) : SyncSettings {
  val defaults = mapOf("chime.volume" to 70, "chime.on" to false, "clock.face" to "classic", "clock.style" to "classic", "theme" to "light")
  var values: Map<String, Any> = defaults + initial
  val applied = ArrayList<Map<String, Any>>()
  /** Values this build refuses (an option only a newer build knows). */
  var invalid: Set<Any> = setOf("hologram", 500)
  var failApply = false

  override fun read() = values
  override fun apply(values: Map<String, Any>): Set<String> {
    if (failApply) error("registry down")
    applied += values
    val ok = values.filter { (k, v) -> validate(k, v) != null }
    this.values = this.values + ok + (ok["clock.face"]?.let { mapOf("clock.style" to it) } ?: emptyMap())
    return ok.keys
  }
  override fun defaults() = defaults
  override fun wellFormed(key: String, raw: Any?): Any? = raw?.takeIf { key in defaults && it::class == defaults.getValue(key)::class }
  override fun validate(key: String, raw: Any?): Any? = wellFormed(key, raw)?.takeIf { it !in invalid }
  override fun siblingKeys(keys: Collection<String>): Set<String> =
      keys.flatMap { k -> if (k.startsWith("clock.")) listOf("clock.face", "clock.style") else listOf(k) }.filter { it in defaults }.toSet()
}

internal class FakeSyncHost : SyncHost {
  var id: String? = SELF
  var name = "Family"
  var lan: String? = listOf(10, 0, 0, 5).joinToString(".")
  var key: ByteArray? = ByteArray(HouseholdKey.SIZE) { 3 }
  var stored: SyncLedger? = null
  var cardDismissed = false

  override fun selfId() = id
  override fun selfName() = name
  override fun lanAddress() = lan
  override fun port() = PORT
  override fun householdKey() = key
  override fun loadLedger(wellFormed: (String, Any?) -> Any?) = stored
  override fun saveLedger(ledger: SyncLedger) { stored = ledger }
  override fun clearLedger() { stored = null }
  override fun adoptCardDismissed() = cardDismissed
  override fun setAdoptCardDismissed(on: Boolean) { cardDismissed = on }

  companion object {
    const val SELF = "m-self"
    const val PORT = 8765
  }
}

internal class FakeSyncTimers : SyncTimers {
  val posted = ArrayList<Pair<Long, Runnable>>()
  override fun postDelayed(delayMs: Long, task: Runnable) { posted += delayMs to task }
  override fun cancel(task: Runnable) { posted.removeAll { it.second === task } }
  /** Runs everything queued so far (what it queues waits for the next fire). */
  fun fire() { val due = posted.toList(); posted.clear(); due.forEach { it.second.run() } }
}

internal class RecordingSyncLog : SyncLog {
  val lines = ArrayList<String>()
  override fun i(message: String) { lines += "i $message" }
  override fun w(message: String, error: Throwable?) { lines += "w $message" }
}

/** A [SettingsSyncCore] on fakes; [peerReply] answers our exchanges (null = no answer). */
internal class SyncRig(settings: Map<String, Any> = emptyMap(), configure: FakeSyncHost.() -> Unit = {}) {
  val settings = FakeSyncSettings(settings)
  val host = FakeSyncHost().apply(configure)
  val sent = ArrayList<Pair<String, JSONObject>>()
  val posts = ArrayList<Triple<String, Int, String>>()
  var peerReply: (String) -> String? = { null }
  var now = 1_000_000L
  val timers = FakeSyncTimers()
  val log = RecordingSyncLog()
  val status = MutableStateFlow(SettingsSyncStatus())
  val paused = AtomicBoolean(false)
  val core = SettingsSyncCore(this.settings, host, { type, body -> sent += type to body; true },
      { h, p, body -> posts += Triple(h, p, body); peerReply(body) }, timers, log, { now }, TrampolineExecutor(), TrampolineExecutor(),
      status, paused)

  fun start(): SyncRig { core.bind(); core.start(); return this }

  fun signal(from: String, digest: String, joining: Boolean = false, port: Int = FakeSyncHost.PORT) =
      core.worker.execute { core.onSignal(msg(SettingsSyncWire.SIGNAL_TYPE, from,
          SettingsSyncWire.signalBody(SettingsSyncWire.Signal(digest, PEER_HOST, port, joining)))) }

  fun changed(from: String, record: SyncRecord) =
      core.worker.execute { core.onChanged(msg(SettingsSyncWire.CHANGED_TYPE, from, SettingsSyncWire.recordJson(record))) }

  fun payload(name: String, joining: Boolean, vararg records: SyncRecord) = SettingsSyncWire.payload(name, joining, records.toList())

  fun sentOf(type: String) = sent.filter { it.first == type }.map { it.second }
  fun counter(name: String): Long = core.statusJson().getJSONObject("counters").optLong(name, 0L)
  fun digest() = SettingsSyncEngine.digest(core.ledger.records)

  private fun msg(type: String, from: String, body: JSONObject) = HouseholdMessage(type, from, now, "n${nonce++}", body)
  private var nonce = 0

  companion object {
    const val HI = "z-peer"
    const val LO = "a-peer"
    val EMPTY: String = SettingsSyncEngine.digest(emptyMap())
    val PEER_HOST = listOf(10, 0, 0, 7).joinToString(".")
    fun rec(key: String, value: Any, v: Long, by: String = HI) = SyncRecord(key, value, SyncVersion(v, by), 1L)
    fun synced(vararg records: SyncRecord, known: Map<String, Any> = emptyMap()) = SyncLedger(phase = SyncPhase.SYNCED,
        clock = records.maxOfOrNull { it.version.counter } ?: 0L, records = records.associateBy { it.key },
        lastKnown = FakeSyncSettings().defaults + records.associate { it.key to it.value } + known)
  }
}
