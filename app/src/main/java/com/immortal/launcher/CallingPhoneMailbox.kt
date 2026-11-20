/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/** One message waiting for a phone's next poll. */
@Immutable
internal data class PhoneMessage(val type: String, val body: String, val atMs: Long)

/** A paired phone as last heard by its poll. Only phones polling this Portal appear. */
@Immutable
internal data class CallPhone(
    val id: String,
    val name: String,
    val seenMs: Long,
    /** Phase 2b: this phone's id on each Portal it is paired with (it says so in every poll). */
    val aliases: Map<String, String> = emptyMap(),
    /** "Ring when Portal Toolkit is closed" is on there (its background long-poll keeps it reachable). */
    val background: Boolean = false,
    /** A long-poll from it is parked here until this time: it hears a ring at once. */
    val parkedUntilMs: Long = 0L,
) {
  fun contact() = CallContact(id, name, kind = CallContact.Kind.PHONE)
  /** It can ring now: Portal Toolkit is open (polls every 1.5 s), or its background poll is parked here. */
  fun reachable(nowMs: Long) = nowMs - seenMs <= CallPhones.REACHABLE_MS || parkedUntilMs >= nowMs
  /** Its id on [portalId], or null when it isn't paired with that Portal. */
  fun idOn(portalId: String): String? = aliases[portalId]
  fun pairedWith(portalId: String): Boolean = portalId in aliases
}

/** Pure mailbox and directory rules (JVM-tested); [CallPhoneMailbox] holds the live state. */
internal object CallPhones {
  /** A phone that hasn't polled for this long isn't open, so it can't ring. */
  const val REACHABLE_MS = 6_000L
  /** A message the phone didn't collect in this time is stale (its ring is long over). */
  const val MESSAGE_TTL_MS = 30_000L
  const val MAX_QUEUE = 32
  const val MAX_PHONES = 8
  /** Longest a background poll may wait here for a message (the phone asks; the Portal caps). */
  const val MAX_WAIT_MS = 25_000L
  /**
   * Parked long-polls hold one of the agent's HTTP threads each; never more than this at once,
   * so the companion and fleet routes always have threads. Extra phones are told to short-poll.
   */
  const val MAX_PARKED = 2

  /** How long this poll may park: what the phone asked, unless the parking spaces are taken. */
  fun grantWait(requestedMs: Long, parkedNow: Int): Long =
      if (parkedNow >= MAX_PARKED) 0L else requestedMs.coerceIn(0L, MAX_WAIT_MS)

  fun enqueue(queue: List<PhoneMessage>, msg: PhoneMessage): List<PhoneMessage> =
      (fresh(queue, msg.atMs) + msg).takeLast(MAX_QUEUE)

  fun fresh(queue: List<PhoneMessage>, nowMs: Long): List<PhoneMessage> = queue.filter { nowMs - it.atMs <= MESSAGE_TTL_MS }

  fun heard(list: List<CallPhone>, phone: CallPhone): List<CallPhone> =
      (listOf(phone) + list.filterNot { it.id == phone.id }).take(MAX_PHONES)

  fun parked(list: List<CallPhone>, id: String, untilMs: Long): List<CallPhone> =
      list.map { if (it.id == id) it.copy(parkedUntilMs = untilMs) else it }
}

/**
 * Phones have no server, so whatever this Portal "sends" a phone waits here until Portal Toolkit
 * polls (`POST /remote/call/poll`). In memory only: nothing about calls is persisted for phones.
 * Thread-safe (the HTTP pool polls, the main thread posts).
 */
internal object CallPhoneMailbox {
  private val lock = Any()
  private val queues = HashMap<String, List<PhoneMessage>>()
  private val mutablePhones = MutableStateFlow<List<CallPhone>>(emptyList())
  /** Phones heard recently, newest first (the Calls app lists them as contacts). */
  val phones: StateFlow<List<CallPhone>> = mutablePhones.asStateFlow()

  private var parkedCount = 0

  fun post(phoneId: String, type: String, body: JSONObject, nowMs: Long) = synchronized(lock) {
    queues[phoneId] = CallPhones.enqueue(queues[phoneId].orEmpty(), PhoneMessage(type, body.toString(), nowMs))
    (lock as Object).notifyAll()
  }

  /** Something else is waiting for the phone (a video message): end its parked poll now. */
  fun wake() = synchronized(lock) { (lock as Object).notifyAll() }

  data class Polled(val messages: List<PhoneMessage>, val waitedMs: Long, val grantedMs: Long)

  /**
   * The phone polled: record it as reachable and hand over (and clear) its fresh messages. A
   * background poll ([info] `w` > 0) parks here until a message arrives or the wait runs out, so a
   * ring reaches a phone whose Portal Toolkit is closed within moments. [clock] is wall-clock ms.
   */
  fun poll(phoneId: String, info: CallPhoneWire.PollInfo, nowMs: Long, clock: () -> Long = System::currentTimeMillis,
           extraWaiting: () -> Boolean = { false }): Polled {
    mutablePhones.update { CallPhones.heard(it, CallPhone(phoneId, info.name, nowMs, info.aliases, info.background)) }
    val granted = synchronized(lock) {
      val g = if (queues[phoneId].isNullOrEmpty() && !extraWaiting()) CallPhones.grantWait(info.waitMs, parkedCount) else 0L
      if (g > 0) parkedCount++
      g
    }
    if (granted <= 0) return Polled(drain(phoneId, nowMs), 0L, 0L)
    val until = nowMs + granted
    mutablePhones.update { CallPhones.parked(it, phoneId, until) }
    try {
      synchronized(lock) {
        while (queues[phoneId].isNullOrEmpty() && !extraWaiting()) {
          val left = until - clock()
          if (left <= 0) break
          (lock as Object).wait(left)
        }
      }
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
    } finally {
      synchronized(lock) { parkedCount = (parkedCount - 1).coerceAtLeast(0) }
    }
    val end = clock()
    // Seen now; no longer parked (it re-polls at once).
    mutablePhones.update { list -> list.map { if (it.id == phoneId) it.copy(seenMs = end, parkedUntilMs = 0L) else it } }
    return Polled(drain(phoneId, end), end - nowMs, granted)
  }

  private fun drain(phoneId: String, nowMs: Long): List<PhoneMessage> =
      synchronized(lock) { CallPhones.fresh(queues.remove(phoneId).orEmpty(), nowMs) }

  fun phone(id: String): CallPhone? = phones.value.firstOrNull { it.id == id }
}
