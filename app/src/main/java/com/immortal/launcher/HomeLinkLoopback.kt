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

  fun exchange(invite: HomeLinkInvite, typed: HomeLinkCode, inviter: HomeLinkSelf, joiner: HomeLinkSelf, random: SecureRandom,
               now: Long): Result {
    val hello = HomeLinkInviter.start(invite, random)
    var i = hello.state
    var j = HomeLinkJoiner.start(typed, random)
    var toJoiner: HomeLinkMessage? = hello.outgoing
    while (toJoiner != null) {
      val jStep = HomeLinkJoiner.receive(j, joiner, relay(toJoiner) ?: break, random)
      j = jStep.state
      val toInviter = jStep.outgoing?.let(::relay) ?: break
      val iStep = HomeLinkInviter.receive(i, inviter, toInviter, now)
      i = iStep.state
      toJoiner = iStep.outgoing
    }
    return Result(i, j)
  }

  private fun relay(m: HomeLinkMessage): HomeLinkMessage? = HomeLinkWire.parse(HomeLinkWire.encode(m))
}
