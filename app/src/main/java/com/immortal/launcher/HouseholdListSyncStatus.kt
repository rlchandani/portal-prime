/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil

/** The widget's last line and the sheet's header line. Pure: time and zone are passed in. */
internal object HouseholdListSyncStatus {
  enum class Kind { OK, PARTIAL, BUSY, OFFLINE, FAILING, FIRST }

  data class Peer(val name: String, val reachable: Boolean)

  data class Inputs(
      val online: Boolean,
      val lastUpAt: Long,
      val peers: List<Peer> = emptyList(),
      val phones: Int = 0,
      val busy: Boolean = false,
      /** When a failed exchange will be retried; null when nothing is failing. */
      val retryAt: Long? = null,
      /** Name of the Portal this freshly re-paired one is copying the list from. */
      val firstSyncFrom: String? = null,
  )

  data class Status(val kind: Kind, val short: String, val long: String) {
    val warning: Boolean get() = kind == Kind.OFFLINE || kind == Kind.FAILING
  }

  fun compute(i: Inputs, nowMs: Long, zone: TimeZone = TimeZone.getDefault()): Status {
    i.firstSyncFrom?.let { return Status(Kind.FIRST, "Getting the list…", "Getting the list from $it…") }
    if (!i.online) {
      val t = clock(i.lastUpAt, zone)
      return Status(Kind.OFFLINE, "Not synced. Showing the list from $t.", "Not synced since $t")
    }
    i.retryAt?.let { at ->
      val s = maxOf(1, ceil((at - nowMs) / 1000.0).toInt())
      return Status(Kind.FAILING, "Couldn’t sync. Trying again in $s s.", "Couldn’t reach the other devices. Trying again in $s s.")
    }
    if (i.busy) return Status(Kind.BUSY, "Syncing…", "Syncing…")
    val down = i.peers.filter { !it.reachable }.map { it.name }
    if (down.isNotEmpty()) {
      val verb = if (down.size > 1) "are" else "is"
      return Status(Kind.PARTIAL, "Up to date here", "Up to date here. ${joinAnd(down)} $verb offline and will catch up.")
    }
    val parts = listOfNotNull(
        joinAnd(i.peers.map { it.name }).takeIf { it.isNotEmpty() },
        when (i.phones) { 0 -> null; 1 -> "1 phone"; else -> "${i.phones} phones" },
    )
    return Status(Kind.OK, "Up to date", if (parts.isEmpty()) "Up to date on this Portal" else "Up to date with ${parts.joinToString(" and ")}")
  }

  /** The toast after an offline Portal reconnects and has caught up. */
  fun catchUpMessage(arrived: Int, sent: Int): String {
    val head = when (arrived) {
      0 -> "Back online. Nothing changed elsewhere."
      1 -> "Back online. 1 change arrived from while this Portal was offline."
      else -> "Back online. $arrived changes arrived from while this Portal was offline."
    }
    return if (sent > 0) "$head Your $sent sent." else head
  }

  fun clock(ms: Long, zone: TimeZone): String =
      SimpleDateFormat("h:mm", Locale.US).apply { timeZone = zone }.format(Date(ms))

  private fun joinAnd(parts: List<String>): String = when (parts.size) {
    0 -> ""
    1 -> parts[0]
    else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
  }
}
