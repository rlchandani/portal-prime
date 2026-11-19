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
internal data class CallPhone(val id: String, val name: String, val seenMs: Long) {
  fun contact() = CallContact(id, name, kind = CallContact.Kind.PHONE)
  /** Portal Toolkit is open on it right now (it polls every 1.5 s while open). */
  fun reachable(nowMs: Long) = nowMs - seenMs <= CallPhones.REACHABLE_MS
}

/** Pure mailbox and directory rules (JVM-tested); [CallPhoneMailbox] holds the live state. */
internal object CallPhones {
  /** A phone that hasn't polled for this long isn't open, so it can't ring. */
  const val REACHABLE_MS = 6_000L
  /** A message the phone didn't collect in this time is stale (its ring is long over). */
  const val MESSAGE_TTL_MS = 30_000L
  const val MAX_QUEUE = 32
  const val MAX_PHONES = 8

  fun enqueue(queue: List<PhoneMessage>, msg: PhoneMessage): List<PhoneMessage> =
      (fresh(queue, msg.atMs) + msg).takeLast(MAX_QUEUE)

  fun fresh(queue: List<PhoneMessage>, nowMs: Long): List<PhoneMessage> = queue.filter { nowMs - it.atMs <= MESSAGE_TTL_MS }

  fun heard(list: List<CallPhone>, phone: CallPhone): List<CallPhone> =
      (listOf(phone) + list.filterNot { it.id == phone.id }).take(MAX_PHONES)
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

  fun post(phoneId: String, type: String, body: JSONObject, nowMs: Long) = synchronized(lock) {
    queues[phoneId] = CallPhones.enqueue(queues[phoneId].orEmpty(), PhoneMessage(type, body.toString(), nowMs))
  }

  /** The phone polled: record it as reachable and hand over (and clear) its fresh messages. */
  fun poll(phoneId: String, name: String, nowMs: Long): List<PhoneMessage> {
    mutablePhones.update { CallPhones.heard(it, CallPhone(phoneId, name, nowMs)) }
    return synchronized(lock) { CallPhones.fresh(queues.remove(phoneId).orEmpty(), nowMs) }
  }

  fun phone(id: String): CallPhone? = phones.value.firstOrNull { it.id == id }
}
