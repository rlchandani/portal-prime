/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** What Settings › About › Sync shows. Pure: built from the ledger, formatted against [now]. */
internal data class SettingsSyncStatus(
    val available: Boolean = false,
    val paused: Boolean = false,
    val phase: SyncPhase = SyncPhase.JOINING,
    val lastSyncAt: Long = 0L,
    val peers: List<SyncPeer> = emptyList(),
    val recordCount: Int = 0,
    /** Set while the one-time "Adopt settings from …" choice is waiting. */
    val adoptFrom: String? = null,
    /** Whether "Keep this Portal's settings" would keep anything adopting wouldn't. */
    val adoptHasConflicts: Boolean = false,
    val adoptedFrom: String? = null,
) {
  val summary: String
    get() = when {
      !available -> "Set up the household key with Portal Toolkit to sync settings between Portals."
      paused -> "Paused on this Portal."
      adoptFrom != null -> "Waiting for you to choose: adopt the settings from $adoptFrom, or keep this Portal's."
      phase == SyncPhase.JOINING -> "Looking for your other Portals."
      else -> "Household settings stay the same on every Portal. Room name, rotation, brightness, " +
          "Shared room, Grandma mode, drop-ins and passwords stay on each Portal."
    }

  fun lastSyncLabel(now: Long): String = if (lastSyncAt <= 0L) "Not yet" else ago(now - lastSyncAt)

  fun peerLabel(peer: SyncPeer, now: Long): String = "Seen ${ago(now - peer.seenAt)}"

  companion object {
    fun from(ledger: SyncLedger, available: Boolean, paused: Boolean, conflicts: Boolean): SettingsSyncStatus =
        SettingsSyncStatus(
            available = available, paused = paused, phase = ledger.phase, lastSyncAt = ledger.lastSyncAt,
            peers = ledger.peers.values.sortedByDescending { it.seenAt }, recordCount = ledger.records.size,
            adoptFrom = ledger.pending?.fromName, adoptHasConflicts = conflicts, adoptedFrom = ledger.adoptedFrom)

    fun ago(ms: Long): String {
      val s = (ms / 1000).coerceAtLeast(0)
      return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3600} h ago"
        else -> "${s / 86_400} d ago"
      }
    }
  }
}
