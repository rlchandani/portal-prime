/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable

/**
 * "To phone" (mock `cb('handoff','To phone')` and scene `handoff`), the Portal side. Pure: which
 * paired phones can take this call over right now, and what the control says.
 *
 * A move is an Add whose ring says "continue this call here": the phone rings, its owner picks up
 * (camera and mic open only after that tap), it connects to everyone, and then this Portal leaves.
 * Nothing drops: the old leg closes only after the new one is in.
 */
internal object CallMoves {
  enum class Option {
    READY,
    /** Not a live call yet, or a drop-in (drop-ins have no add, share or handoff). */
    NOT_NOW,
    /** Answered by itself (Grandma mode) on either side: it stays between these two. */
    LOCKED,
    /** A move is already under way. */
    MOVING,
    /** A phone is already in this call (one phone per call). */
    PHONE_IN_CALL,
    /** Four people already; someone needs to leave first. */
    FULL,
    /** Portal Toolkit isn't open there and background ringing is off. */
    CLOSED,
    /** The phone isn't paired with every other Portal in the call. */
    NOT_PAIRED,
  }

  fun option(call: ActiveCall, phone: CallPhone, selfId: String?, nowMs: Long): Option = when {
    call.phase != CallPhase.LIVE || call.kind != CallKind.CALL -> Option.NOT_NOW
    call.locked -> Option.LOCKED
    call.move != null -> Option.MOVING
    call.phones >= CallGroup.MAX_PHONES -> Option.PHONE_IN_CALL
    call.full -> Option.FULL
    !phone.reachable(nowMs) -> Option.CLOSED
    CallAddPeople.unpaired(call, phone, selfId).isNotEmpty() -> Option.NOT_PAIRED
    else -> Option.READY
  }

  /** What the in-call "To phone" control does. */
  @Immutable
  data class Control(val label: String, val enabled: Boolean, val target: CallPhone?, val pick: Boolean, val why: String?)

  /**
   * One ready phone: tap moves straight to it. Several: tap opens the picker. None: the control is
   * dimmed and a tap explains why (the most useful reason first).
   */
  fun control(call: ActiveCall, phones: List<CallPhone>, selfId: String?, nowMs: Long): Control {
    if (call.move != null) return Control(MOVING_LABEL, false, null, false, null)
    val options = phones.map { it to option(call, it, selfId, nowMs) }
    val ready = options.filter { it.second == Option.READY }.map { it.first }
    return when {
      ready.size == 1 -> Control(LABEL, true, ready.single(), false, null)
      ready.size > 1 -> Control(LABEL, true, null, true, null)
      else -> Control(LABEL, false, null, false, why(call, options))
    }
  }

  private fun why(call: ActiveCall, options: List<Pair<CallPhone, Option>>): String = when {
    call.kind != CallKind.CALL || call.phase != CallPhase.LIVE -> "You can move a call once it’s connected"
    call.locked -> "This call was answered automatically, so it stays on this Portal"
    call.phones >= CallGroup.MAX_PHONES -> "A phone is already in this call"
    call.full -> "Calls hold up to ${CallGroup.MAX_PEOPLE}. Someone needs to leave before the call can move"
    options.isEmpty() -> "Pair a phone with Portal Toolkit to move calls to it"
    options.any { it.second == Option.NOT_PAIRED } -> "${options.first { it.second == Option.NOT_PAIRED }.first.name} " +
        "isn’t paired with every Portal in this call"
    else -> "Open Portal Toolkit on your phone, or turn on “Ring when Portal Toolkit is closed”"
  }

  /** The banner while the phone rings: "Ringing Sam’s phone · pick up there to move the call". */
  fun movingLine(call: ActiveCall): String? {
    val to = call.move?.to ?: return null
    val leg = call.leg(to) ?: return null
    return when (leg.phase) {
      LegPhase.RINGING -> "Ringing ${leg.peer.name} · pick up there to move the call"
      LegPhase.JOINING -> "Moving to ${leg.peer.name}…"
      LegPhase.LIVE, LegPhase.RECONNECTING -> "${leg.peer.name} is in · this Portal leaves in a moment"
    }
  }

  const val LABEL = "To phone"
  const val MOVING_LABEL = "Moving…"
}
