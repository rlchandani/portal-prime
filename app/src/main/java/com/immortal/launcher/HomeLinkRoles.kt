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
  /** Showing the code and QR; our [pake] share is in the mailbox. [failedTries] joins had the wrong code. */
  data class Waiting(val invite: HomeLinkInvite, val pake: HomeLinkPakeHalf, val failedTries: Int = 0) : HomeLinkInviterState
  /** Someone with the code committed; our key went back; waiting for theirs. */
  data class AwaitingOpen(val invite: HomeLinkInvite, val session: HomeLinkSession, val commitment: String) : HomeLinkInviterState
  /** Both screens show [safety]; people compare. */
  data class Comparing(val peer: HomeLinkPeer, val safety: HomeLinkSafetyNumber) : HomeLinkInviterState
  data class Linked(val peer: HomeLinkPeer) : HomeLinkInviterState
  data class Failed(val reason: HomeLinkFailure) : HomeLinkInviterState
}

/** The joining home's side (mock `joinScr` → `verifyScr`). Immutable states. */
internal sealed interface HomeLinkJoinerState {
  /** Typed the code; waiting to read the inviter's PAKE share from the mailbox. */
  data class AwaitingHello(val code: HomeLinkCode, val pake: HomeLinkPakeHalf) : HomeLinkJoinerState
  /** Posted our share and a commitment; waiting for the inviter's key. */
  data class Committing(val code: HomeLinkCode, val session: HomeLinkSession, val nonce: String) : HomeLinkJoinerState
  data class Comparing(val peer: HomeLinkPeer, val safety: HomeLinkSafetyNumber) : HomeLinkJoinerState
  data class Linked(val peer: HomeLinkPeer) : HomeLinkJoinerState
  data class Failed(val reason: HomeLinkFailure) : HomeLinkJoinerState
}

/**
 * Linking two homes, inviter side: hello → commit → reveal → open, then the pictures.
 *
 * The code's secret part runs a PAKE ([HomeLinkPake]), so a wrong guess shows only as a commit
 * whose MAC fails; after [MAX_TRIES] of those the invite is burned. Commit-then-reveal (as in
 * ZRTP) then fixes both identity keys before either side sees the other's, so even someone who
 * learnt the code gets one guess at the safety pictures, not a search. The first valid commit
 * uses up the code. Pure: the caller posts [HomeLinkStep.outgoing] and keeps [HomeLinkStep.state].
 */
internal object HomeLinkInviter {
  /** Wrong-code joins one invite survives. Security-critical: with 35 secret bits, 3 tries ≈ 1 in 11 billion. */
  const val MAX_TRIES = 3

  /** A new invite and the hello to post to its mailbox. */
  fun start(invite: HomeLinkInvite, random: SecureRandom): HomeLinkStep<HomeLinkInviterState> {
    val mailbox = HomeLinkCode.mailboxId(invite.code)
    val pake = HomeLinkPake.start(invite.code.secret, mailbox, random, invite.code.domain)
    return HomeLinkStep(HomeLinkInviterState.Waiting(invite, pake), HomeLinkMessage.Hello(mailbox, pake.share))
  }

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
    if (m.mailbox != HomeLinkCode.mailboxId(invite.code)) return HomeLinkStep(state, ignored = HomeLinkIgnored.WRONG_CODE)
    return when {
      state is HomeLinkInviterState.Waiting && m is HomeLinkMessage.Commit ->
          if (m.seq != HomeLinkMessage.Commit.SEQ) outOfSequence(state) else onCommit(state, self, m)
      state is HomeLinkInviterState.AwaitingOpen && m is HomeLinkMessage.Open ->
          if (m.seq != HomeLinkMessage.Open.SEQ) outOfSequence(state) else onOpen(state, self, m)
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
    val domain = state.invite.code.domain
    val shared = HomeLinkPake.finish(state.pake, m.share)
    val session = shared?.let { HomeLinkSession.derive(m.mailbox, state.pake.share, m.share, it, domain) }
    if (session == null || !HomeLinkPairingProof.fromPeer(session, m.mailbox, m)) return wrongTry(state)
    val device = HomeLinkDevicePolicy.own(self, domain) ?: return HomeLinkStep(state, ignored = HomeLinkIgnored.UNEXPECTED)
    val sig = self.identity.sign(HomeLinkPairingProof.revealText(session, m.mailbox, self.name, device))
    val reveal = HomeLinkPairingProof.withMac(session,
        HomeLinkMessage.Reveal(m.mailbox, self.identity.publicKey, self.name, sig, device = device))
    return HomeLinkStep(HomeLinkInviterState.AwaitingOpen(state.invite, session, m.commitment), reveal)
  }

  /** A join with the wrong code (or garbage): counts against the invite, which burns at [MAX_TRIES]. */
  private fun wrongTry(state: HomeLinkInviterState.Waiting): HomeLinkStep<HomeLinkInviterState> {
    val tries = state.failedTries + 1
    return if (tries >= MAX_TRIES) HomeLinkStep(HomeLinkInviterState.Failed(HomeLinkFailure.TOO_MANY_TRIES))
    else HomeLinkStep(state.copy(failedTries = tries), ignored = HomeLinkIgnored.WRONG_CODE)
  }

  private fun onOpen(state: HomeLinkInviterState.AwaitingOpen, self: HomeLinkSelf, m: HomeLinkMessage.Open): HomeLinkStep<HomeLinkInviterState> {
    if (!HomeLinkPairingProof.fromPeer(state.session, m.mailbox, m)) return HomeLinkStep(state, ignored = HomeLinkIgnored.WRONG_CODE)
    val tampered = HomeLinkStep<HomeLinkInviterState>(HomeLinkInviterState.Failed(HomeLinkFailure.TAMPERED))
    val domain = state.session.domain
    if (!HomeLinkDevicePolicy.acceptsPeer(self, domain, m.device)) return tampered
    val committed = HomeLinkPairingProof.commitment(m.key, m.nonce, m.name, domain, m.device)
    if (committed == null || !HomeLinkHash.same(HomeLinkHash.utf8(committed), HomeLinkHash.utf8(state.commitment))) return tampered
    val text = HomeLinkPairingProof.openText(state.session, m.mailbox, self.identity.publicKey, m.nonce, m.name, m.device)
    if (m.key == self.identity.publicKey || !HomeLinkIdentity.verify(m.key, text, m.sig)) return tampered
    val safety = HomeLinkSafety.derive(self.identity.publicKey, m.key, m.nonce) ?: return tampered
    return HomeLinkStep(HomeLinkInviterState.Comparing(HomeLinkPeer(m.name, m.key, m.device), safety))
  }
}

internal fun <S> outOfSequence(state: S): HomeLinkStep<S> = HomeLinkStep(state, ignored = HomeLinkIgnored.OUT_OF_SEQUENCE)

/** Linking two homes, joiner side (the home that typed the code). Pure, like [HomeLinkInviter]. */
internal object HomeLinkJoiner {
  fun start(code: HomeLinkCode, random: SecureRandom): HomeLinkJoinerState =
      HomeLinkJoinerState.AwaitingHello(code, HomeLinkPake.start(code.secret, HomeLinkCode.mailboxId(code), random, code.domain))

  fun receive(state: HomeLinkJoinerState, self: HomeLinkSelf, m: HomeLinkMessage, random: SecureRandom): HomeLinkStep<HomeLinkJoinerState> = when {
    state is HomeLinkJoinerState.AwaitingHello && m is HomeLinkMessage.Hello ->
        if (m.seq != HomeLinkMessage.Hello.SEQ) outOfSequence(state) else onHello(state, self, m, random)
    state is HomeLinkJoinerState.Committing && m is HomeLinkMessage.Reveal ->
        if (m.seq != HomeLinkMessage.Reveal.SEQ) outOfSequence(state) else onReveal(state, self, m)
    else -> HomeLinkStep(state, ignored = HomeLinkIgnored.UNEXPECTED)
  }

  private fun onHello(state: HomeLinkJoinerState.AwaitingHello, self: HomeLinkSelf, m: HomeLinkMessage.Hello,
                      random: SecureRandom): HomeLinkStep<HomeLinkJoinerState> {
    val mailbox = HomeLinkCode.mailboxId(state.code)
    if (m.mailbox != mailbox) return HomeLinkStep(state, ignored = HomeLinkIgnored.WRONG_CODE)
    val shared = HomeLinkPake.finish(state.pake, m.share) ?: return HomeLinkStep(state, ignored = HomeLinkIgnored.UNEXPECTED)
    val domain = state.code.domain
    val session = HomeLinkSession.derive(mailbox, m.share, state.pake.share, shared, domain)
    val device = HomeLinkDevicePolicy.own(self, domain) ?: return HomeLinkStep(HomeLinkJoinerState.Failed(HomeLinkFailure.TAMPERED))
    val nonce = HomeLinkHash.b64(ByteArray(HomeLinkPairingProof.NONCE_BYTES).also { random.nextBytes(it) })
    val commitment = requireNotNull(HomeLinkPairingProof.commitment(self.identity.publicKey, nonce, self.name, domain, device)) { "fresh nonce" }
    val commit = HomeLinkPairingProof.withMac(session, HomeLinkMessage.Commit(mailbox, state.pake.share, commitment))
    return HomeLinkStep(HomeLinkJoinerState.Committing(state.code, session, nonce), commit)
  }

  private fun onReveal(state: HomeLinkJoinerState.Committing, self: HomeLinkSelf, m: HomeLinkMessage.Reveal): HomeLinkStep<HomeLinkJoinerState> {
    val mailbox = HomeLinkCode.mailboxId(state.code)
    if (!HomeLinkPairingProof.fromPeer(state.session, mailbox, m)) return HomeLinkStep(state, ignored = HomeLinkIgnored.WRONG_CODE)
    val tampered = HomeLinkStep<HomeLinkJoinerState>(HomeLinkJoinerState.Failed(HomeLinkFailure.TAMPERED))
    val domain = state.session.domain
    if (m.key == self.identity.publicKey || !HomeLinkDevicePolicy.acceptsPeer(self, domain, m.device)) return tampered
    if (!HomeLinkIdentity.verify(m.key, HomeLinkPairingProof.revealText(state.session, mailbox, m.name, m.device), m.sig)) return tampered
    val safety = HomeLinkSafety.derive(m.key, self.identity.publicKey, state.nonce) ?: return tampered
    val device = HomeLinkDevicePolicy.own(self, domain) ?: return tampered
    val sig = self.identity.sign(HomeLinkPairingProof.openText(state.session, mailbox, m.key, state.nonce, self.name, device))
    val open = HomeLinkPairingProof.withMac(state.session,
        HomeLinkMessage.Open(mailbox, self.identity.publicKey, state.nonce, self.name, sig, device = device))
    return HomeLinkStep(HomeLinkJoinerState.Comparing(HomeLinkPeer(m.name, m.key, m.device), safety), open)
  }

  fun confirm(state: HomeLinkJoinerState, matched: Boolean): HomeLinkJoinerState = when {
    state !is HomeLinkJoinerState.Comparing -> state
    matched -> HomeLinkJoinerState.Linked(state.peer)
    else -> HomeLinkJoinerState.Failed(HomeLinkFailure.MISMATCH)
  }
}

/**
 * Device ids in the pairing messages: pinning ([HomeLinkDomain.bindsDevice]) sends and signs this
 * Portal's household id and requires the other Portal's; linking homes sends none and refuses one.
 */
internal object HomeLinkDevicePolicy {
  /** What we send: our device id when pinning, else nothing. Null if pinning without one (we can't take part). */
  fun own(self: HomeLinkSelf, domain: HomeLinkDomain): String? = when {
    !domain.bindsDevice -> ""
    self.deviceId.isEmpty() -> null
    else -> self.deviceId
  }

  /** The other side's device id is a Portal's (not ours) when pinning, and absent when linking homes. */
  fun acceptsPeer(self: HomeLinkSelf, domain: HomeLinkDomain, device: String): Boolean =
      if (domain.bindsDevice) HomeLinkWire.validDevice(device) && device != self.deviceId else device.isEmpty()
}
