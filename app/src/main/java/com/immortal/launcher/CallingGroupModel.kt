/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable

/**
 * Group calls (phase 2a): up to four Portals in one home, as a mesh. Every pair of members has
 * its own peer connection set up with the same household-signed offer/answer and bound DTLS
 * fingerprints as a 1:1 call, so every pair is authenticated end to end.
 *
 * [ActiveCall.peer] stays the first other member (the 1:1 code paths are unchanged); each further
 * member is a [CallLeg]. When the first member leaves, the next connected leg takes its place.
 */
internal object CallGroup {
  /** Owner-approved cap (mock Decisions #8): this Portal plus three others. */
  const val MAX_PEOPLE = 4
  /** Legs beyond the first other member. */
  const val MAX_LEGS = MAX_PEOPLE - 2
  /**
   * Phase 2b: at most one paired phone per call. A phone has no server and is always the WebRTC
   * offerer, so two phones would have no way to reach each other inside the home.
   */
  const val MAX_PHONES = 1
}

internal enum class LegPhase {
  /** We rang them to join; their ring is up. */
  RINGING,
  /** They said yes (or a member told us they joined): offer/answer and ICE are under way. */
  JOINING,
  LIVE,
  RECONNECTING;

  val connected: Boolean get() = this == LIVE || this == RECONNECTING
}

/** Where a member's agent listens: a newcomer offers to each member there. */
@Immutable
internal data class CallMemberRef(val contact: CallContact, val host: String?, val port: Int)

/** One more member of a group call. Immutable; the reducer copies it. */
@Immutable
internal data class CallLeg(
    val peer: CallContact,
    val phase: LegPhase,
    val sinceMs: Long,
    /** True: this Portal sends the offer (we are the newcomer); false: they offer to us. */
    val offerer: Boolean,
    val host: String? = null,
    val port: Int = 0,
    /** RINGING only: their Portal said it is ringing (else it is offline after the dial wait). */
    val acked: Boolean = false,
    val connectedMs: Long? = null,
    val remoteCameraOn: Boolean = true,
    val remoteMicOn: Boolean = true,
    /** Phase 2b: this leg is where the call is moving to ("Move to phone"); we leave once it is in. */
    val moving: Boolean = false,
)

/**
 * A call being handed between this person's devices (phase 2b). [to]: the source side, the member
 * the call is moving to; [from]: the target side, the device the call is moving away from.
 */
@Immutable
internal data class CallMove(
    val to: String? = null,
    val from: String? = null,
    /** Source: when the target's own link to us came up (the settle clock starts here). */
    val targetLiveMs: Long? = null,
    /** Target: `call_moved` has gone out (everyone is connected). */
    val announced: Boolean = false,
)

/** Something worth a toast in a group call ("Priya is in quiet hours", "Walt left"). */
@Immutable
internal data class CallNotice(
    val name: String,
    val reason: CallEndReason,
    val atMs: Long,
    /** [CallEndReason.MOVED]: the device it moved to, when it is one of ours to name. */
    val to: String? = null,
    /** The ring was a move that didn't happen ("the call stays here"). */
    val move: Boolean = false,
)
