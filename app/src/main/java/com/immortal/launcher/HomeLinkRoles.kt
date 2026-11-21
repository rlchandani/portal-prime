/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.security.SecureRandom

/** The inviting home's side (mock `inviteScr` → `verifyScr` → `permsScr`). Immutable states. */
internal sealed interface HomeLinkInviterState {
  /** Showing the code and QR. */
  data class Waiting(val invite: HomeLinkInvite) : HomeLinkInviterState
  /** Someone with the code committed; our key went back; waiting for theirs. */
  data class AwaitingOpen(val invite: HomeLinkInvite, val commitment: String) : HomeLinkInviterState
  /** Both screens show [safety]; people compare. */
  data class Comparing(val peer: HomeLinkPeer, val safety: HomeLinkSafetyNumber) : HomeLinkInviterState
  data class Linked(val peer: HomeLinkPeer) : HomeLinkInviterState
  data class Failed(val reason: HomeLinkFailure) : HomeLinkInviterState
}

/** The joining home's side (mock `joinScr` → `verifyScr`). Immutable states. */
internal sealed interface HomeLinkJoinerState {
  /** Typed the code and posted a commitment; waiting for the inviter's key. */
  data class Committing(val code: HomeLinkCode, val nonce: String) : HomeLinkJoinerState
  data class Comparing(val peer: HomeLinkPeer, val safety: HomeLinkSafetyNumber) : HomeLinkJoinerState
  data class Linked(val peer: HomeLinkPeer) : HomeLinkJoinerState
  data class Failed(val reason: HomeLinkFailure) : HomeLinkJoinerState
}

/**
 * Linking two homes, inviter side. Commit-then-reveal (as in ZRTP): the joiner's key is fixed by
 * its commitment before it sees ours, and ours is fixed before we see theirs, so a relay in the
 * middle can't pick keys that make both screens show the same pictures. The first valid commit
 * uses up the code. Pure: the caller posts [HomeLinkStep.outgoing] and keeps [HomeLinkStep.state].
 */
internal object HomeLinkInviter {
  fun start(invite: HomeLinkInvite): HomeLinkInviterState = HomeLinkInviterState.Waiting(invite)

  /** Expires a still-open invite when its 10 minutes are up (the screen's countdown calls this). */
  fun tick(state: HomeLinkInviterState, now: Long): HomeLinkInviterState =
      if (inviteOf(state)?.isExpired(now) == true) HomeLinkInviterState.Failed(HomeLinkFailure.EXPIRED) else state

  fun receive(state: HomeLinkInviterState, self: HomeLinkSelf, m: HomeLinkMessage, now: Long): HomeLinkStep<HomeLinkInviterState> {
    val invite = inviteOf(state)
    if (invite == null) {
      val used = m is HomeLinkMessage.Commit && state !is HomeLinkInviterState.Failed
      return HomeLinkStep(state, ignored = if (used) HomeLinkIgnored.ALREADY_USED else HomeLinkIgnored.UNEXPECTED)
    }
    if (invite.isExpired(now)) return HomeLinkStep(HomeLinkInviterState.Failed(HomeLinkFailure.EXPIRED))
    return when {
      state is HomeLinkInviterState.Waiting && m is HomeLinkMessage.Commit -> onCommit(state, self, m)
      state is HomeLinkInviterState.AwaitingOpen && m is HomeLinkMessage.Open -> onOpen(state, self, m)
      state is HomeLinkInviterState.AwaitingOpen && m is HomeLinkMessage.Commit -> HomeLinkStep(state, ignored = HomeLinkIgnored.ALREADY_USED)
      else -> HomeLinkStep(state, ignored = HomeLinkIgnored.UNEXPECTED)
    }
  }

  /** "They match" pins the key; "They don't match" keeps nothing. Ignored outside Comparing. */
  fun confirm(state: HomeLinkInviterState, matched: Boolean): HomeLinkInviterState = when {
    state !is HomeLinkInviterState.Comparing -> state
    matched -> HomeLinkInviterState.Linked(state.peer)
    else -> HomeLinkInviterState.Failed(HomeLinkFailure.MISMATCH)
  }

  private fun inviteOf(state: HomeLinkInviterState): HomeLinkInvite? = when (state) {
    is HomeLinkInviterState.Waiting -> state.invite
    is HomeLinkInviterState.AwaitingOpen -> state.invite
    else -> null
  }

  private fun onCommit(state: HomeLinkInviterState.Waiting, self: HomeLinkSelf, m: HomeLinkMessage.Commit): HomeLinkStep<HomeLinkInviterState> {
    val code = state.invite.code
    if (!HomeLinkPairingProof.fromCode(code, m)) return HomeLinkStep(state, ignored = HomeLinkIgnored.WRONG_CODE)
    val sig = self.identity.sign(HomeLinkPairingProof.revealText(m.mailbox, self.name))
    val reveal = HomeLinkPairingProof.withMac(code, HomeLinkMessage.Reveal(m.mailbox, self.identity.publicKey, self.name, sig))
    return HomeLinkStep(HomeLinkInviterState.AwaitingOpen(state.invite, m.commitment), reveal)
  }

  private fun onOpen(state: HomeLinkInviterState.AwaitingOpen, self: HomeLinkSelf, m: HomeLinkMessage.Open): HomeLinkStep<HomeLinkInviterState> {
    if (!HomeLinkPairingProof.fromCode(state.invite.code, m)) return HomeLinkStep(state, ignored = HomeLinkIgnored.WRONG_CODE)
    val tampered = HomeLinkStep<HomeLinkInviterState>(HomeLinkInviterState.Failed(HomeLinkFailure.TAMPERED))
    val committed = HomeLinkPairingProof.commitment(m.key, m.nonce, m.name)
    if (committed == null || !HomeLinkHash.same(HomeLinkHash.utf8(committed), HomeLinkHash.utf8(state.commitment))) return tampered
    val text = HomeLinkPairingProof.openText(m.mailbox, self.identity.publicKey, m.nonce, m.name)
    if (m.key == self.identity.publicKey || !HomeLinkIdentity.verify(m.key, text, m.sig)) return tampered
    val safety = HomeLinkSafety.derive(self.identity.publicKey, m.key, m.nonce) ?: return tampered
    return HomeLinkStep(HomeLinkInviterState.Comparing(HomeLinkPeer(m.name, m.key), safety))
  }
}

/** Linking two homes, joiner side (the home that typed the code). Pure, like [HomeLinkInviter]. */
internal object HomeLinkJoiner {
  fun start(code: HomeLinkCode, self: HomeLinkSelf, random: SecureRandom): HomeLinkStep<HomeLinkJoinerState> {
    val nonce = HomeLinkHash.b64(ByteArray(HomeLinkPairingProof.NONCE_BYTES).also { random.nextBytes(it) })
    val commitment = requireNotNull(HomeLinkPairingProof.commitment(self.identity.publicKey, nonce, self.name)) { "fresh nonce" }
    val commit = HomeLinkPairingProof.withMac(code, HomeLinkMessage.Commit(HomeLinkCode.mailboxId(code), commitment))
    return HomeLinkStep(HomeLinkJoinerState.Committing(code, nonce), commit)
  }

  fun receive(state: HomeLinkJoinerState, self: HomeLinkSelf, m: HomeLinkMessage): HomeLinkStep<HomeLinkJoinerState> {
    if (state !is HomeLinkJoinerState.Committing || m !is HomeLinkMessage.Reveal) return HomeLinkStep(state, ignored = HomeLinkIgnored.UNEXPECTED)
    if (!HomeLinkPairingProof.fromCode(state.code, m)) return HomeLinkStep(state, ignored = HomeLinkIgnored.WRONG_CODE)
    val tampered = HomeLinkStep<HomeLinkJoinerState>(HomeLinkJoinerState.Failed(HomeLinkFailure.TAMPERED))
    if (m.key == self.identity.publicKey) return tampered
    if (!HomeLinkIdentity.verify(m.key, HomeLinkPairingProof.revealText(m.mailbox, m.name), m.sig)) return tampered
    val safety = HomeLinkSafety.derive(m.key, self.identity.publicKey, state.nonce) ?: return tampered
    val sig = self.identity.sign(HomeLinkPairingProof.openText(m.mailbox, m.key, state.nonce, self.name))
    val open = HomeLinkPairingProof.withMac(state.code,
        HomeLinkMessage.Open(m.mailbox, self.identity.publicKey, state.nonce, self.name, sig))
    return HomeLinkStep(HomeLinkJoinerState.Comparing(HomeLinkPeer(m.name, m.key), safety), open)
  }

  fun confirm(state: HomeLinkJoinerState, matched: Boolean): HomeLinkJoinerState = when {
    state !is HomeLinkJoinerState.Comparing -> state
    matched -> HomeLinkJoinerState.Linked(state.peer)
    else -> HomeLinkJoinerState.Failed(HomeLinkFailure.MISMATCH)
  }
}
