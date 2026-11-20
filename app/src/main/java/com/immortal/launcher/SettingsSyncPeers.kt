/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/*
 * The household side of [SettingsSyncCore] (worker thread): `setting_changed` and digest signals
 * from the bus, and both ends of the full-state exchange over `/remote/settingspeer`.
 */

internal fun SettingsSyncCore.onChanged(m: HouseholdMessage) {
  if (!started) return
  if (paused || ledger.phase != SyncPhase.SYNCED) return
  count("changedReceived")
  val record = SettingsSyncWire.parseChanged(m.from, m.body) ?: return count("changedRefused")
  detect() // a pending local edit gets its version before the peer's is compared
  mergeAndWrite(listOf(record), m.from, null)
}

internal fun SettingsSyncCore.onSignal(m: HouseholdMessage) {
  if (!started) return
  if (paused) return
  count("signalsReceived")
  val signal = SettingsSyncWire.parseSignal(m.body) ?: return
  val now = clock()
  ledger = SettingsSyncEngine.notePeer(ledger, m.from, null, now, synced = false)
  val me = host.selfId() ?: return
  if (ledger.phase == SyncPhase.JOINING) {
    when (SettingsSyncEngine.joinAction(me, m.from, signal.digest, signal.joining)) {
      JoinAction.FOUND -> {
        ledger = SettingsSyncEngine.found(ledger, settings.read(), settings.defaults(), me, now)
        persist()
        signal()
        log.i("founded household settings with ${ledger.records.size} changed values")
      }
      // Already holding exactly this household's settings for the choice: nothing new to fetch.
      JoinAction.FETCH -> if (signal.digest == ledger.pending?.let { SettingsSyncEngine.digest(it.records) }) persist()
          else exchange(signal, m.from)
      JoinAction.WAIT -> persist()
    }
    return
  }
  if (signal.digest == SettingsSyncEngine.digest(ledger.records)) return persist()
  if (signal.joining && now - (joinerPushedAt[m.from] ?: 0L) < SettingsSyncCore.JOINER_PUSH_MS) return persist()
  if (signal.joining) {
    // Rate-limit memory only: sender ids are claimed, so never let them grow it without bound.
    if (joinerPushedAt.size >= SettingsSyncCore.MAX_RATE_ENTRIES) joinerPushedAt = emptyMap()
    joinerPushedAt = joinerPushedAt + (m.from to now)
  }
  exchange(signal, m.from)
}

/** The peer route's half of an exchange (on the worker, for the HTTP thread). Returns our payload. */
internal fun SettingsSyncCore.onPeerPayload(from: String, theirs: SettingsSyncWire.Payload): String {
  ledger = SettingsSyncEngine.notePeer(ledger, from, theirs.name, clock(), synced = !theirs.joining)
  if (!paused) {
    if (ledger.phase == SyncPhase.JOINING) {
      ledger = SettingsSyncEngine.addPending(ledger, from, theirs.name, theirs.records, wellFormed)
      autoAdoptIfNothingToChoose()
    } else if (!theirs.joining) {
      detect()
      mergeAndWrite(theirs.records, from, theirs.name)
    }
  }
  persist()
  val joining = ledger.phase == SyncPhase.JOINING
  return SettingsSyncWire.payload(host.selfName(), joining, if (joining) emptyList() else ledger.records.values)
}

/** Builds our side here, posts on the network thread (never blocking the worker), merges back here. */
private fun SettingsSyncCore.exchange(signal: SettingsSyncWire.Signal, peer: String) {
  val joining = ledger.phase == SyncPhase.JOINING
  val body = SettingsSyncWire.payload(host.selfName(), joining, if (joining) emptyList() else ledger.records.values)
  persist()
  count("exchangesStarted")
  count("httpBytesSent", body.length.toLong())
  net.execute {
    val reply = runCatching { peers.post(signal.host, signal.port, body) }
        .onFailure { log.i("exchange with $peer failed: ${it.javaClass.simpleName}") }.getOrNull()
    val theirs = SettingsSyncWire.parsePayload(reply) ?: return@execute count("exchangesFailed")
    count("httpBytesReceived", reply?.length?.toLong() ?: 0L)
    worker.execute { onExchangeReply(peer, theirs) }
  }
}

private fun SettingsSyncCore.onExchangeReply(peer: String, theirs: SettingsSyncWire.Payload) {
  if (paused) return
  ledger = SettingsSyncEngine.notePeer(ledger, peer, theirs.name, clock(), synced = true)
  if (ledger.phase == SyncPhase.JOINING) {
    ledger = SettingsSyncEngine.addPending(ledger, peer, theirs.name, theirs.records, wellFormed)
    persist()
    autoAdoptIfNothingToChoose()
  } else {
    mergeAndWrite(theirs.records, peer, theirs.name)
  }
}

private fun SettingsSyncCore.mergeAndWrite(records: List<SyncRecord>, from: String, name: String?) {
  val result = SettingsSyncEngine.mergeAll(ledger, records, wellFormed)
  ledger = SettingsSyncEngine.notePeer(result.ledger, from, name, clock(), synced = true)
  count("recordsApplied", result.applied.size.toLong())
  count("recordsRefused", result.rejected.toLong())
  if (result.rejected > 0) log.w("refused ${result.rejected} malformed setting(s) from $from")
  val writes = result.applied.filter { ledger.lastKnown[it.key] != it.value }.associate { it.key to it.value }
  write(writes)
  persist()
  if (result.applied.isNotEmpty()) log.i("from $from: ${result.applied.joinToString { it.key }}")
}
