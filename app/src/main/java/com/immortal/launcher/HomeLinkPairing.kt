/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher


/** This home, as it introduces itself to another. */
internal class HomeLinkSelf(val identity: HomeLinkSigner, val name: String) {
  init { require(HomeLinkWire.validName(name)) { "home name must be 1-${HomeLinkWire.MAX_NAME} printable characters" } }
}

/** The other home: its name as it told us, and the key we pin once people say the pictures match. */
internal data class HomeLinkPeer(val name: String, val publicKey: String)

internal enum class HomeLinkFailure {
  /** The code's 10 minutes ran out. */
  EXPIRED,
  /** Someone with the code broke the protocol (bad signature or commitment). Nothing is kept. */
  TAMPERED,
  /** People said the pictures don't match (mock `mismatchScr`). Nothing is kept. */
  MISMATCH,
  /** [HomeLinkInviter.MAX_TRIES] joins with the wrong code: the invite is burned so guessing stops. */
  TOO_MANY_TRIES,
}

/** Why a message changed nothing. The pairing carries on. */
internal enum class HomeLinkIgnored {
  /** Not from someone holding the code (MAC under the PAKE key failed), or not for this mailbox. */
  WRONG_CODE,
  ALREADY_USED,
  /** A message type this state doesn't take. */
  UNEXPECTED,
  /** The right type with the wrong sequence number (the MAC covers it, so it was never relabelled in transit). */
  OUT_OF_SEQUENCE,
}

/** One step of a pairing: the new state, what to post to the mailbox, or why nothing happened. */
internal data class HomeLinkStep<S>(val state: S, val outgoing: HomeLinkMessage? = null, val ignored: HomeLinkIgnored? = null)

/** MACs, commitments and signed texts for the pairing exchange. Pure. */
internal object HomeLinkPairingProof {
  const val NONCE_BYTES = 32
  private val COMMIT = HomeLinkHash.utf8("hearthpane-homelink-commit-v1")
  private const val REVEAL = "hearthpane-homelink-reveal-v2"
  private const val OPEN = "hearthpane-homelink-open-v2"

  fun commitment(key: String, nonce: String, name: String): String? {
    val n = HomeLinkHash.unb64(nonce)?.takeIf { it.size == NONCE_BYTES } ?: return null
    return HomeLinkHash.b64(HomeLinkHash.sha256(COMMIT, HomeLinkHash.utf8(key), n, HomeLinkHash.utf8(name)))
  }

  /** What the inviter signs: bound to this PAKE session, so it can't be replayed into another. */
  fun revealText(session: HomeLinkSession, mailbox: String, name: String): ByteArray =
      HomeLinkHash.join(*listOf(REVEAL, mailbox, session.sid, name).bytes())

  fun openText(session: HomeLinkSession, mailbox: String, inviterKey: String, nonce: String, name: String): ByteArray =
      HomeLinkHash.join(*listOf(OPEN, mailbox, session.sid, inviterKey, nonce, name).bytes())

  /** Joiner → inviter messages use one key, inviter → joiner the other. Hello is never MAC'd. */
  private fun towardInviter(m: HomeLinkMessage): Boolean = m is HomeLinkMessage.Commit || m is HomeLinkMessage.Open

  fun withMac(session: HomeLinkSession, m: HomeLinkMessage): HomeLinkMessage {
    val mac = HomeLinkHash.b64(macBytes(session, m))
    return when (m) {
      is HomeLinkMessage.Hello -> m
      is HomeLinkMessage.Commit -> m.copy(mac = mac)
      is HomeLinkMessage.Reveal -> m.copy(mac = mac)
      is HomeLinkMessage.Open -> m.copy(mac = mac)
    }
  }

  /** From the other end of this PAKE session (so someone holding the code), to [mailbox]. */
  fun fromPeer(session: HomeLinkSession, mailbox: String, m: HomeLinkMessage): Boolean {
    if (m is HomeLinkMessage.Hello || m.mailbox != mailbox) return false
    val got = HomeLinkHash.unb64(m.mac) ?: return false
    return HomeLinkHash.same(got, macBytes(session, m))
  }

  private fun macBytes(session: HomeLinkSession, m: HomeLinkMessage): ByteArray =
      HomeLinkHash.hmac(session.macKey(towardInviter(m)), HomeLinkHash.utf8(HomeLinkWire.signable(m)))

  private fun List<String>.bytes(): Array<ByteArray> = map { HomeLinkHash.utf8(it) }.toTypedArray()
}
