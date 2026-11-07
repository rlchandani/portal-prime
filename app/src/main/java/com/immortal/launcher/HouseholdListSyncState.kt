/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.TimeZone

/**
 * This Portal's view of sync (not replicated): who it can reach, retry timing, offline catch-up
 * and which items just arrived from elsewhere. Immutable; [HouseholdListSync] swaps whole values.
 */
internal data class HouseholdListSyncState(
    val online: Boolean = true,
    val lastUpAt: Long = 0L,
    val busy: Int = 0,
    val retryAt: Long? = null,
    val retryStep: Int = 0,
    val peers: Map<String, PeerInfo> = emptyMap(),
    val phones: Map<String, Long> = emptyMap(),
    val arrivals: Map<String, Arrival> = emptyMap(),
    val catchUp: CatchUp? = null,
    val notice: Notice? = null,
    val everExchanged: Boolean = false,
    /** Edits made on this Portal while it had no network ("Your 2 sent." after reconnecting). */
    val offlineEdits: Int = 0,
) {
  data class PeerInfo(val name: String, val reachable: Boolean, val lastAt: Long)
  data class Arrival(val at: Long, val whileOffline: Boolean)
  data class CatchUp(val since: Long, val arrived: Int = 0, val sent: Int = 0)
  data class Notice(val text: String, val until: Long, val seq: Long)

  fun status(nowMs: Long, list: HouseholdListState, zone: TimeZone = TimeZone.getDefault()): HouseholdListSyncStatus.Status =
      HouseholdListSyncStatus.compute(HouseholdListSyncStatus.Inputs(
          online = online,
          lastUpAt = lastUpAt.takeIf { it > 0 } ?: nowMs,
          peers = peers.values.sortedBy { it.name }.map { HouseholdListSyncStatus.Peer(it.name, it.reachable) },
          phones = phones.count { nowMs - it.value <= PHONE_WINDOW_MS },
          busy = busy > 0,
          retryAt = retryAt?.takeIf { it > nowMs },
          firstSyncFrom = if (busy > 0 && !everExchanged && list.items.isEmpty()) firstSource() else null,
      ), nowMs, zone)

  private fun firstSource(): String = peers.values.firstOrNull()?.name ?: "the other Portal"

  /** The arrival line for an item ("Just now, Rohit’s Pixel"), within [FRESH_MS] of its arrival. */
  fun arrivalLine(item: HouseholdItem, nowMs: Long, zone: TimeZone = TimeZone.getDefault()): String? {
    val a = arrivals[item.id] ?: return null
    if (nowMs - a.at > FRESH_MS) return null
    val whenText = when {
      a.whileOffline -> "While offline"
      a.at - item.added.at > 90_000 -> "Added ${HouseholdListSyncStatus.clock(item.added.at, zone)}"
      else -> "Just now"
    }
    return "$whenText, ${item.added.by}"
  }

  companion object {
    const val FRESH_MS = 60_000L
    const val NOTICE_MS = 6_000L
    const val PHONE_WINDOW_MS = 24L * 60 * 60 * 1000
    val RETRY_MS = longArrayOf(10_000, 30_000, 60_000, 120_000, 300_000)

    /** Items that became live here because of another device's change (new or re-added). */
    fun newArrivals(before: HouseholdListState, after: HouseholdListState, self: String, nowMs: Long,
                    whileOffline: Boolean): Map<String, Arrival> =
        after.live().filter { it.added.by != self && before.items[it.id]?.let { b -> b.alive && b.added == it.added } != true }
            .associate { it.id to Arrival(nowMs, whileOffline) }

    /** How many items a remote change touched (for the catch-up toast). */
    fun changedCount(before: HouseholdListState, after: HouseholdListState): Int =
        (before.items.keys + after.items.keys).count { before.items[it] != after.items[it] }

    fun retryDelay(step: Int): Long = RETRY_MS[step.coerceIn(0, RETRY_MS.size - 1)]
  }
}
