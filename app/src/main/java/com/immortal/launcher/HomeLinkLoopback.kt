/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.security.SecureRandom

/**
 * Measurement builds only ([BuildConfig.PORTAL_CALL_LOOPBACK]): both homes on this one Portal, so
 * the linking screens and protocol can be tried before there is a relay. Every message goes
 * through [HomeLinkWire] as it would over a relay. The joining "home" uses a throwaway key.
 */
internal object HomeLinkLoopback {
  data class Result(val inviter: HomeLinkInviterState, val joiner: HomeLinkJoinerState)

  /** One join against a fresh invite (tests). */
  fun exchange(invite: HomeLinkInvite, typed: HomeLinkCode, inviter: HomeLinkSelf, joiner: HomeLinkSelf, random: SecureRandom,
               now: Long): Result = exchange(HomeLinkInviterSession.start(invite, inviter, random), typed, joiner, random, now)

  /**
   * One join against the invite's live [session], so wrong codes add up across joins and the
   * third one burns the invite, as it would with a real relay.
   */
  fun exchange(session: HomeLinkInviterSession, typed: HomeLinkCode, joiner: HomeLinkSelf, random: SecureRandom, now: Long): Result {
    var j = HomeLinkJoiner.start(typed, random)
    var toJoiner: HomeLinkMessage? = session.hello
    while (toJoiner != null) {
      val jStep = HomeLinkJoiner.receive(j, joiner, relay(toJoiner) ?: break, random)
      j = jStep.state
      val toInviter = jStep.outgoing?.let(::relay) ?: break
      toJoiner = session.receive(toInviter, now).outgoing
    }
    return Result(session.state, j)
  }

  private fun relay(m: HomeLinkMessage): HomeLinkMessage? = HomeLinkWire.parse(HomeLinkWire.encode(m))
}
