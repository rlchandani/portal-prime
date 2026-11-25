/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable

/**
 * Decides whether an offerer link should restart ICE to recover a live call after the LAN blips
 * (DHCP renew, AP roam, a brief Wi-Fi drop). Pure: every timing and attempt rule lives here and is
 * pinned by tests, because the live recovery itself can only be confirmed on two Portals.
 *
 * In-home only and glare-free by construction: only the side that sent the original offer re-offers
 * (the callee in 1:1; the recorded [CallLeg.offerer] in a group), re-using the existing signed
 * offer/answer path — so `lanOnly`, the DTLS fingerprint and household signing all still apply.
 * The mechanism that acts on [Action.RESTART_ICE] is wired in [CallMediaLink]/[CallMediaHub] and,
 * until a live 2-Portal test confirms it, is enabled only in measurement builds
 * (`BuildConfig.PORTAL_CALL_LOOPBACK`); shipping calls keep today's behaviour exactly.
 */
internal object CallReconnectPolicy {
  /** Let a brief blip self-heal before spending a renegotiation (ICE often recovers on its own). */
  const val GRACE_MS = 2_000L
  /** Space repeated attempts so a flapping link doesn't renegotiate on every stats tick. */
  const val ATTEMPT_BACKOFF_MS = 3_000L
  /** After this many attempts, stop and let [CallTimings.RECONNECT_MS] end the call honestly. */
  const val MAX_ATTEMPTS = 3

  enum class Action { WAIT, RESTART_ICE, GIVE_UP }

  @Immutable
  data class State(val downSinceMs: Long? = null, val attempts: Int = 0, val lastAttemptMs: Long? = null)

  /** The link dropped. The first drop records when; a repeat keeps the original instant. */
  fun down(state: State, now: Long): State = if (state.downSinceMs == null) state.copy(downSinceMs = now) else state

  /** The link is healthy again: clear the whole recovery record. */
  fun up(): State = State()

  /** What the offerer should do now. A non-offerer or a healthy link always [Action.WAIT]s. */
  fun decide(state: State, now: Long, offerer: Boolean, enabled: Boolean): Action {
    val since = state.downSinceMs
    if (!enabled || !offerer || since == null) return Action.WAIT
    if (state.attempts >= MAX_ATTEMPTS) return Action.GIVE_UP
    if (now - since < GRACE_MS) return Action.WAIT
    state.lastAttemptMs?.let { if (now - it < ATTEMPT_BACKOFF_MS) return Action.WAIT }
    return Action.RESTART_ICE
  }

  /** Record that a restart was just fired, so the next one waits out [ATTEMPT_BACKOFF_MS]. */
  fun attempted(state: State, now: Long): State = state.copy(attempts = state.attempts + 1, lastAttemptMs = now)
}

/**
 * Whether this Portal should open media to answer an offer from [from] right now. It accepts the
 * initial offer while the member is being set up, and a signed re-offer (an ICE restart) while the
 * member is already up — but never an offer for a call we are not in, or from a device that is not
 * a member. Pure, so the rule is testable away from the HTTP route. The signed header, `lanOnly`
 * and the DTLS fingerprint are still checked by [CallPeerRoute]/[CallMediaLink] before any of this.
 */
internal object CallAnswerGate {
  fun accepts(call: ActiveCall?, callId: String, from: String): Boolean {
    val c = call ?: return false
    if (c.id != callId) return false
    if (c.peer.deviceId == from) return c.phase == CallPhase.CONNECTING || c.phase.connected
    val leg = c.leg(from) ?: return false
    return leg.phase == LegPhase.JOINING || leg.phase.connected
  }
}
