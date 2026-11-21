/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.security.SecureRandom

/**
 * The one live inviter for one invite. [HomeLinkInviter] is a pure reducer, so two callers that
 * each read [HomeLinkInviterState.Waiting] and apply a wrong commit would both count try 1 and the
 * 3-try limit would leak; here every message is applied under one lock, against the latest state,
 * so parallel joins can't get more than [HomeLinkInviter.MAX_TRIES] tries between them.
 *
 * Memory only, by design: the invite, its PAKE half and the try count are never stored or
 * restored. A process restart ends the invite (its PAKE scalar is gone), so restarting the
 * inviter can't reset the count: there is nothing left to resume, and a new invite has a new code.
 */
internal class HomeLinkInviterSession private constructor(private val self: HomeLinkSelf, first: HomeLinkStep<HomeLinkInviterState>) {
  private val lock = Any()
  private var current: HomeLinkInviterState = first.state

  /** Post this to the mailbox when the invite is shown. */
  val hello: HomeLinkMessage = checkNotNull(first.outgoing) { "an invite starts with a hello" }

  val state: HomeLinkInviterState get() = synchronized(lock) { current }

  fun receive(m: HomeLinkMessage, now: Long): HomeLinkStep<HomeLinkInviterState> = synchronized(lock) {
    HomeLinkInviter.receive(current, self, m, now).also { current = it.state }
  }

  fun tick(now: Long): HomeLinkInviterState = synchronized(lock) { HomeLinkInviter.tick(current, now).also { current = it } }

  /** A person's answer on the Verify screen; nothing else ends Comparing. */
  fun confirm(matched: Boolean): HomeLinkInviterState =
      synchronized(lock) { HomeLinkInviter.confirm(current, matched).also { current = it } }

  override fun toString(): String = "HomeLinkInviterSession(${state::class.simpleName})"

  companion object {
    fun start(invite: HomeLinkInvite, self: HomeLinkSelf, random: SecureRandom): HomeLinkInviterSession =
        HomeLinkInviterSession(self, HomeLinkInviter.start(invite, random))
  }
}
