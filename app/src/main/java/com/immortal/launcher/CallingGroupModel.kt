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
)

/** Something worth a toast in a group call ("Priya is in quiet hours", "Walt left"). */
@Immutable
internal data class CallNotice(val name: String, val reason: CallEndReason, val atMs: Long)
