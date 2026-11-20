/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject

/*
 * What the household settings sync needs from the Portal, as interfaces, so its rules (gossip,
 * catch-up, joining, pause, versions, echo suppression, the scope filter) run in JVM tests.
 * [SettingsSyncAndroid] implements them with one call each and no decisions of their own.
 */

/** The household settings on this Portal ([com.immortal.launcher.settings.SettingsSyncCatalog]). */
internal interface SyncSettings {
  /** Current household values (`domain.key` → value). */
  fun read(): Map<String, Any>
  /** Writes validated values through the registry (live side effects); the keys it accepted. May throw. */
  fun apply(values: Map<String, Any>): Set<String>
  fun defaults(): Map<String, Any>
  /** A known household key with a value of the right shape, or null (the scope filter). */
  fun wellFormed(key: String, raw: Any?): Any?
  /** [wellFormed] and valid on this build (options, bounds), or null. */
  fun validate(key: String, raw: Any?): Any?
  /** Every household key in the same domains as [keys] (a setter may write its siblings). */
  fun siblingKeys(keys: Collection<String>): Set<String>
}

/** This Portal's identity, network and stored ledger. */
internal interface SyncHost {
  /** The companion device id, or null while its store is unreadable. */
  fun selfId(): String?
  fun selfName(): String
  fun lanAddress(): String?
  fun port(): Int
  fun householdKey(): ByteArray?
  fun loadLedger(wellFormed: (String, Any?) -> Any?): SyncLedger?
  fun saveLedger(ledger: SyncLedger)
  fun clearLedger()
  fun adoptCardDismissed(): Boolean
  fun setAdoptCardDismissed(on: Boolean)
}

/** The signed household broadcast ([HouseholdBus]). */
internal fun interface SyncBus {
  /** False when nothing was sent (no key yet, or too big). */
  fun send(type: String, body: JSONObject): Boolean
}

/** The other half of an exchange: POSTs our payload to a peer, returns its verified payload. Blocking; may throw. */
internal fun interface SyncPeerPost {
  fun post(host: String, port: Int, body: String): String?
}

/** Delayed work on the main thread (gossip, the local-change debounce). */
internal interface SyncTimers {
  fun postDelayed(delayMs: Long, task: Runnable)
  fun cancel(task: Runnable)
}

internal interface SyncLog {
  fun i(message: String)
  fun w(message: String, error: Throwable? = null)
}
