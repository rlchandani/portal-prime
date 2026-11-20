/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable

/** What this Portal does with a ring that just arrived. */
internal sealed interface IncomingDecision {
  /** Ring the full-screen card. [silent]: quiet hours with the visual-only option, no sound. */
  data class Ring(val silent: Boolean) : IncomingDecision
  /** A permitted Chime-then-join drop-in: chime, banner, 3 s countdown, then join. */
  data object Countdown : IncomingDecision
  /**
   * Grandma mode auto-answer for a chosen caller: chime, a 10 s countdown with Not now, then the
   * call connects. Only ever decided outside quiet hours (see [CallPolicy.decide] order).
   */
  data object AutoAnswer : IncomingDecision
  /** Turn it away; the caller is told why. Nothing rings and the screen stays dark. */
  data class Reject(val reply: CallReply) : IncomingDecision
}

/** Everything the ring decision depends on, gathered by the runtime from prefs and clocks. */
@Immutable
internal data class IncomingContext(
    val kind: CallKind,
    val whoCanCall: CallWhoCanCall,
    val quiet: Boolean,
    val quietMode: CallQuietMode,
    val dropInsOn: Boolean,
    /** This Portal's rule for drop-ins from the calling room. */
    val dropInFromCaller: DropInPolicy,
    val busy: Boolean,
    /** [CallAutoAnswer.eligible] for this caller right now (Grandma mode, chosen, not snoozed). */
    val autoAnswer: Boolean = false,
    /** The caller is a paired phone: phones never drop in. */
    val fromPhone: Boolean = false,
    /** Settings › Calls › Phones: "Phones can call this Portal". */
    val phonesAllowed: Boolean = true,
    /** Phase 2b: someone is moving their own call here ("To Portal"); at night it shows silently. */
    val move: Boolean = false,
)

/**
 * Ring, quiet-hours, drop-in and permission rules from the approved mock and the feasibility
 * doc's privacy section. Pure, so every branch is JVM-tested.
 */
internal object CallPolicy {
  fun decide(c: IncomingContext): IncomingDecision = when {
    c.busy -> IncomingDecision.Reject(CallReply.BUSY)
    c.whoCanCall == CallWhoCanCall.NOBODY -> IncomingDecision.Reject(CallReply.CALLS_OFF)
    c.fromPhone && (!c.phonesAllowed || c.kind == CallKind.DROP_IN) -> IncomingDecision.Reject(CallReply.CALLS_OFF)
    c.kind == CallKind.DROP_IN -> dropIn(c)
    // Quiet hours come before auto-answer: a Grandma-mode Portal never answers by itself at night.
    c.quiet -> quietRing(c)
    c.autoAnswer -> IncomingDecision.AutoAnswer
    else -> IncomingDecision.Ring(silent = false)
  }

  /**
   * Drop-ins are off until enabled, then per room pair. Never in quiet hours: there a drop-in
   * becomes a normal ring request (blocked, or a silent ring if the owner chose that) and never
   * joins by itself.
   */
  private fun dropIn(c: IncomingContext): IncomingDecision = when {
    !c.dropInsOn || c.dropInFromCaller == DropInPolicy.OFF -> IncomingDecision.Reject(CallReply.DROP_INS_OFF)
    c.quiet -> quietRing(c)
    c.dropInFromCaller == DropInPolicy.ASK -> IncomingDecision.Ring(silent = false)
    else -> IncomingDecision.Countdown
  }

  /** A move is someone carrying their own call to this room: in quiet hours it never sounds, but it may show. */
  private fun quietRing(c: IncomingContext): IncomingDecision =
      if (c.quietMode == CallQuietMode.VISUAL || c.move) IncomingDecision.Ring(silent = true) else IncomingDecision.Reject(CallReply.QUIET)

  /** How a room looks in the Calls app right now. */
  fun presence(peer: CallPeer, nowMs: Long): CallPresence = when {
    nowMs - peer.seenMs > CallTimings.OFFLINE_AFTER_MS -> CallPresence.OFFLINE
    peer.inCall -> CallPresence.IN_CALL
    peer.quiet -> CallPresence.QUIET
    else -> CallPresence.AVAILABLE
  }

  /** What a drop-in on [peer] from this Portal would do, from the policy it last announced for us. */
  fun dropInOn(peer: CallPeer, selfId: String): DropInPolicy =
      if (!peer.dropInsOn) DropInPolicy.OFF else peer.dropInPolicies[selfId] ?: DropInPolicy.ASK
}

/** Another Portal in this home as heard on the bus (`call_hello`). */
@Immutable
internal data class CallPeer(
    val id: String,
    val name: String,
    val seenMs: Long,
    val inCall: Boolean = false,
    val quiet: Boolean = false,
    val acceptsCalls: Boolean = true,
    val dropInsOn: Boolean = false,
    /** That Portal's rule for drop-ins from each room, by device id (its column of the grid). */
    val dropInPolicies: Map<String, DropInPolicy> = emptyMap(),
    val favourite: Boolean = false,
    /** Where its fleet agent listens (from its signed hello), for video messages. Null if unknown. */
    val host: String? = null,
    val port: Int = 0,
) {
  fun contact() = CallContact(id, name)
}

/** The rooms this Portal knows, newest first, with merge rules. Pure. */
internal object CallPeers {
  const val CAPACITY = 8

  fun merge(list: List<CallPeer>, heard: CallPeer): List<CallPeer> {
    val old = list.firstOrNull { it.id == heard.id }
    val merged = heard.copy(favourite = old?.favourite ?: heard.favourite)
    return (listOf(merged) + list.filterNot { it.id == heard.id }).take(CAPACITY)
  }

  /** Adds a room only heard of indirectly (Call everyone's roster) without overwriting fresher data. */
  fun learn(list: List<CallPeer>, id: String, name: String, seenMs: Long): List<CallPeer> {
    val old = list.firstOrNull { it.id == id }
    if (old != null) return if (seenMs > old.seenMs) list.map { if (it.id == id) it.copy(seenMs = seenMs) else it } else list
    return (list + CallPeer(id, name, seenMs)).take(CAPACITY)
  }

  fun setFavourite(list: List<CallPeer>, id: String, on: Boolean): List<CallPeer> =
      list.map { if (it.id == id) it.copy(favourite = on) else it }

  /** Home widget order: favourites first, then the rest; each group by name. */
  fun forHome(list: List<CallPeer>, max: Int): List<CallPeer> =
      list.sortedWith(compareByDescending<CallPeer> { it.favourite }.thenBy { it.name.lowercase() }).take(max)
}

/** The drop-in grid column this Portal owns: its rule for drops from each other room. */
internal object DropInGrid {
  private val ID = Regex("^[A-Za-z0-9_-]{1,64}$")

  fun encode(map: Map<String, DropInPolicy>): String =
      map.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value.wire}" }

  /** Null for malformed text (the settings boundary rejects it); "" is the empty map. */
  fun decode(raw: String?): Map<String, DropInPolicy>? {
    if (raw.isNullOrBlank()) return emptyMap()
    val out = LinkedHashMap<String, DropInPolicy>()
    for (part in raw.split(',')) {
      val cut = part.lastIndexOf(':')
      if (cut <= 0) return null
      val id = part.substring(0, cut).takeIf(ID::matches) ?: return null
      out[id] = DropInPolicy.fromWire(part.substring(cut + 1)) ?: return null
    }
    return out.takeIf { it.size <= MAX_ROOMS }
  }

  /** Rooms default to Ask once drop-ins are allowed (mock Decisions: "rooms default to Ask"). */
  fun policyFor(map: Map<String, DropInPolicy>, roomId: String): DropInPolicy = map[roomId] ?: DropInPolicy.ASK

  fun cycle(map: Map<String, DropInPolicy>, roomId: String): Map<String, DropInPolicy> =
      map + (roomId to policyFor(map, roomId).next())

  const val MAX_ROOMS = 16
}
