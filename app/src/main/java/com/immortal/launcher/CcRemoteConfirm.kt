/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * One-time confirm tickets for asking phone calls (pure apart from the random source, unit
 * tested). An asking call first comes back `409 confirm` with a ticket bound to the caller and to
 * exactly that call (entity, action, value); the phone shows its confirm and sends the same call
 * with the ticket. A ticket works once, for [TTL_MS], for that caller only, so neither a replayed
 * request nor a different call can skip the confirm. Tickets are never logged.
 */
internal class CcRemoteConfirm(
    private val random: (ByteArray) -> Unit = SecureRandom()::nextBytes,
) {
  private data class Pending(val caller: String, val call: String, val expires: Long)

  private val pending = LinkedHashMap<String, Pending>()

  @Synchronized
  fun issue(caller: String, call: String, now: Long): String {
    prune(now)
    while (pending.size >= MAX_PENDING) pending.remove(pending.keys.first())
    val bytes = ByteArray(TICKET_BYTES).also(random)
    val ticket = bytes.joinToString("") { "%02x".format(it.toInt() and BYTE) }
    pending[ticket] = Pending(caller, call, now + TTL_MS)
    return ticket
  }

  /** True once for a live ticket issued to [caller] for [call]; a wrong caller or call burns nothing. */
  @Synchronized
  fun redeem(ticket: String?, caller: String, call: String, now: Long): Boolean {
    prune(now)
    if (ticket.isNullOrBlank() || ticket.length != TICKET_BYTES * 2) return false
    val key = pending.keys.firstOrNull { MessageDigest.isEqual(it.toByteArray(), ticket.toByteArray()) } ?: return false
    val p = pending.getValue(key)
    if (p.caller != caller || p.call != call) return false
    pending.remove(key)
    return true
  }

  private fun prune(now: Long) { pending.entries.removeAll { it.value.expires <= now } }

  companion object {
    const val TTL_MS = 60_000L
    const val MAX_PENDING = 32
    private const val TICKET_BYTES = 16
    private const val BYTE = 0xff
  }
}
