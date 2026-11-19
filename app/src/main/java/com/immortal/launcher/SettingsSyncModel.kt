/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.security.MessageDigest

/**
 * A household setting's version: a Lamport counter, with the writing Portal's id breaking ties.
 * Every Portal advances its clock past any counter it has seen, so a change made after seeing
 * another always orders after it, whatever the wall clocks say.
 */
internal data class SyncVersion(val counter: Long, val device: String) : Comparable<SyncVersion> {
  override fun compareTo(other: SyncVersion): Int =
      compareValuesBy(this, other, SyncVersion::counter, SyncVersion::device)
}

/** One household setting as last written anywhere: `domain.key`, value, version, wall time. */
internal data class SyncRecord(val key: String, val value: Any, val version: SyncVersion, val ts: Long)

/** JOINING until this Portal has founded the household's settings or chosen how to join them. */
internal enum class SyncPhase { JOINING, SYNCED }

internal data class SyncPeer(val id: String, val name: String, val seenAt: Long, val syncedAt: Long = 0L)

/** The household's settings, fetched while joining, waiting for the one-time Adopt / Keep choice. */
internal data class PendingAdopt(val fromId: String, val fromName: String, val records: Map<String, SyncRecord>)

internal data class SyncLedger(
    val phase: SyncPhase = SyncPhase.JOINING,
    /** The highest counter this Portal has written or seen. */
    val clock: Long = 0L,
    /** Every household key that has ever been changed, with its winning value and version. */
    val records: Map<String, SyncRecord> = emptyMap(),
    /** The values last read from (or written to) this Portal's settings, to notice local edits. */
    val lastKnown: Map<String, Any> = emptyMap(),
    val peers: Map<String, SyncPeer> = emptyMap(),
    val pending: PendingAdopt? = null,
    val lastSyncAt: Long = 0L,
    val adoptedFrom: String? = null,
)

internal enum class MergeOutcome { APPLIED, DUPLICATE, STALE, REJECTED }

internal data class MergeResult(val ledger: SyncLedger, val applied: List<SyncRecord>, val rejected: Int, val stale: Int)

/** What a JOINING Portal does on hearing a peer's digest. */
internal enum class JoinAction { FOUND, FETCH, WAIT }

/**
 * Pure household-settings sync rules (no Android, no clock, no I/O); [SettingsSync] is the glue.
 * Last writer wins per key by [SyncVersion]. Untouched defaults are never records, so a fresh
 * Portal has nothing to offer and always takes the household's values.
 */
internal object SettingsSyncEngine {
  /** Counters beyond this are refused (no peer can overflow the clock or pin a key forever). */
  const val MAX_COUNTER = 1L shl 40
  const val MAX_RECORDS = 512
  private val DEVICE = Regex("^[A-Za-z0-9_-]{1,64}$")

  val EMPTY_DIGEST: String = digest(emptyMap())

  /**
   * Offers one peer [record]. [wellFormed] normalises the value or returns null to refuse it (an
   * unknown key, a wrong type, oversize). A newer version replaces the current one and advances
   * the clock; an equal one is a duplicate and an older one is stale.
   */
  fun offer(ledger: SyncLedger, record: SyncRecord, wellFormed: (String, Any?) -> Any?): Pair<SyncLedger, MergeOutcome> {
    val value = wellFormed(record.key, record.value) ?: return ledger to MergeOutcome.REJECTED
    if (record.version.counter !in 1..MAX_COUNTER || !DEVICE.matches(record.version.device) || record.ts < 0) {
      return ledger to MergeOutcome.REJECTED
    }
    val current = ledger.records[record.key]
    if (current != null && current.version == record.version) return ledger to MergeOutcome.DUPLICATE
    if (current != null && current.version > record.version) return ledger to MergeOutcome.STALE
    val accepted = record.copy(value = value)
    return ledger.copy(records = ledger.records + (record.key to accepted),
        clock = maxOf(ledger.clock, record.version.counter)) to MergeOutcome.APPLIED
  }

  /** Offers every record in turn; [MergeResult.applied] are the ones that won (to write locally). */
  fun mergeAll(ledger: SyncLedger, records: Collection<SyncRecord>, wellFormed: (String, Any?) -> Any?): MergeResult {
    var next = ledger
    val applied = ArrayList<SyncRecord>()
    var rejected = 0
    var stale = 0
    records.take(MAX_RECORDS).forEach { record ->
      val (after, outcome) = offer(next, record, wellFormed)
      next = after
      when (outcome) {
        MergeOutcome.APPLIED -> applied += after.records.getValue(record.key)
        MergeOutcome.REJECTED -> rejected++
        MergeOutcome.STALE -> stale++
        MergeOutcome.DUPLICATE -> Unit
      }
    }
    return MergeResult(next, applied, rejected + (records.size - minOf(records.size, MAX_RECORDS)), stale)
  }

  /**
   * Compares this Portal's current values with the last ones it knew. Each value that changed and
   * isn't already the winning record becomes a new local record with the next counter. A key seen
   * for the first time (e.g. a setting added by an update) is only remembered, never broadcast.
   */
  fun detectLocal(ledger: SyncLedger, current: Map<String, Any>, self: String, now: Long): Pair<SyncLedger, List<SyncRecord>> {
    if (ledger.phase != SyncPhase.SYNCED) return ledger.copy(lastKnown = current) to emptyList()
    var clock = ledger.clock
    val records = ledger.records.toMutableMap()
    val changed = ArrayList<SyncRecord>()
    current.forEach { (key, value) ->
      val known = ledger.lastKnown[key] ?: return@forEach
      if (known == value || records[key]?.value == value) return@forEach
      clock = minOf(clock + 1, MAX_COUNTER)
      val record = SyncRecord(key, value, SyncVersion(clock, self), now)
      records[key] = record
      changed += record
    }
    return ledger.copy(clock = clock, records = records, lastKnown = current) to changed
  }

  /**
   * This Portal's own changes from the defaults, as new records counting up from [after] + 1.
   * Keys whose value is still the default are left out — untouched defaults never win — and so are
   * keys whose value already matches the household's record.
   */
  fun seeds(current: Map<String, Any>, defaults: Map<String, Any>, household: Map<String, SyncRecord>,
            self: String, after: Long, now: Long): List<SyncRecord> {
    var counter = after
    return current.entries
        .filter { (key, value) -> key in defaults && defaults[key] != value && household[key]?.value != value }
        .sortedBy { it.key }
        .map { (key, value) -> SyncRecord(key, value, SyncVersion(minOf(++counter, MAX_COUNTER), self), now) }
  }

  /**
   * Decides how a JOINING Portal reacts to a peer: fetch a household that already has settings;
   * found an empty one; and when two joining Portals meet, the lower id founds and the other waits.
   */
  fun joinAction(self: String, peer: String, peerDigest: String, peerJoining: Boolean): JoinAction = when {
    peerDigest != EMPTY_DIGEST -> JoinAction.FETCH
    !peerJoining -> JoinAction.FOUND
    self < peer -> JoinAction.FOUND
    else -> JoinAction.WAIT
  }

  /** The first Portal of the household: its own changes from the defaults become the records. */
  fun found(ledger: SyncLedger, current: Map<String, Any>, defaults: Map<String, Any>, self: String, now: Long): SyncLedger {
    val seeds = seeds(current, defaults, emptyMap(), self, ledger.clock, now)
    return ledger.copy(phase = SyncPhase.SYNCED, records = seeds.associateBy { it.key },
        clock = seeds.maxOfOrNull { it.version.counter } ?: ledger.clock, lastKnown = current, pending = null)
  }

  /** Folds another peer's household records into the pending choice (newest per key wins). */
  fun addPending(ledger: SyncLedger, fromId: String, fromName: String, records: Collection<SyncRecord>,
                 wellFormed: (String, Any?) -> Any?): SyncLedger {
    val base = SyncLedger(records = ledger.pending?.records.orEmpty())
    val merged = mergeAll(base, records, wellFormed).ledger.records
    if (merged.isEmpty()) return ledger
    val from = ledger.pending?.takeIf { it.records.isNotEmpty() }
    return ledger.copy(pending = PendingAdopt(from?.fromId ?: fromId, from?.fromName ?: fromName, merged))
  }

  /** Whether "Keep this Portal's" would differ from "Adopt": a changed value the household overrides. */
  fun hasConflicts(pending: PendingAdopt, current: Map<String, Any>, defaults: Map<String, Any>): Boolean =
      pending.records.values.any { r ->
        val mine = current[r.key]
        mine != null && mine != r.value && r.key in defaults && defaults[r.key] != mine
      }

  /**
   * Resolves the one-time join choice. Both take every household record; [keepMine] then lets this
   * Portal's own changes win where they differ (new versions after the household's), while adopting
   * leaves only this Portal's changes to keys the household never changed. Returns the new ledger
   * and the values to write here.
   */
  fun resolveJoin(ledger: SyncLedger, current: Map<String, Any>, defaults: Map<String, Any>, self: String,
                  now: Long, keepMine: Boolean): Pair<SyncLedger, Map<String, Any>> {
    val household = ledger.pending?.records.orEmpty()
    val clock = maxOf(ledger.clock, household.values.maxOfOrNull { it.version.counter } ?: 0L)
    val candidates = if (keepMine) current else current.filterKeys { it !in household }
    val mine = seeds(candidates, defaults, household, self, clock, now).associateBy { it.key }
    val records = household + mine
    val writes = household.filterKeys { it !in mine }.filter { (key, r) -> current[key] != r.value }
        .mapValues { it.value.value }
    return ledger.copy(phase = SyncPhase.SYNCED, records = records, pending = null,
        clock = mine.values.maxOfOrNull { it.version.counter } ?: clock,
        lastKnown = current + writes, adoptedFrom = ledger.pending?.fromName, lastSyncAt = now) to writes
  }

  /** Records the values read back after writing peer changes, so they aren't mistaken for local edits. */
  fun noteWritten(ledger: SyncLedger, readBack: Map<String, Any>): SyncLedger =
      ledger.copy(lastKnown = ledger.lastKnown + readBack)

  fun notePeer(ledger: SyncLedger, id: String, name: String?, now: Long, synced: Boolean): SyncLedger {
    val old = ledger.peers[id]
    val peer = SyncPeer(id, name?.takeIf { it.isNotBlank() } ?: old?.name ?: "Portal", now,
        if (synced) now else old?.syncedAt ?: 0L)
    val peers = (ledger.peers + (id to peer)).entries.sortedByDescending { it.value.seenAt }.take(MAX_PEERS)
        .associate { it.key to it.value }
    return ledger.copy(peers = peers, lastSyncAt = if (synced) now else ledger.lastSyncAt)
  }

  /** 12 hex chars over every (key, version): equal digests mean nothing to exchange. */
  fun digest(records: Map<String, SyncRecord>): String {
    val text = records.values.sortedBy { it.key }
        .joinToString("\n") { "${it.key}=${it.version.counter}:${it.version.device}" }
    return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .take(6).joinToString("") { "%02x".format(it) }
  }

  private const val MAX_PEERS = 16
}
