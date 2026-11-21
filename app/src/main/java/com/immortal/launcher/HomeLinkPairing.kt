/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher


/** This home, as it introduces itself to another. */
internal class HomeLinkSelf(val identity: HomeLinkIdentity, val name: String) {
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
}

/** Why a message changed nothing. The pairing carries on. */
internal enum class HomeLinkIgnored { WRONG_CODE, ALREADY_USED, UNEXPECTED }

/** One step of a pairing: the new state, what to post to the mailbox, or why nothing happened. */
internal data class HomeLinkStep<S>(val state: S, val outgoing: HomeLinkMessage? = null, val ignored: HomeLinkIgnored? = null)

/** MACs, commitments and signed texts for the pairing exchange. Pure. */
internal object HomeLinkPairingProof {
  const val NONCE_BYTES = 32
  private val COMMIT = HomeLinkHash.utf8("hearthpane-homelink-commit-v1")
  private const val REVEAL = "hearthpane-homelink-reveal-v1"
  private const val OPEN = "hearthpane-homelink-open-v1"

  fun commitment(key: String, nonce: String, name: String): String? {
    val n = HomeLinkHash.unb64(nonce)?.takeIf { it.size == NONCE_BYTES } ?: return null
    return HomeLinkHash.b64(HomeLinkHash.sha256(COMMIT, HomeLinkHash.utf8(key), n, HomeLinkHash.utf8(name)))
  }

  fun revealText(mailbox: String, name: String): ByteArray = HomeLinkHash.join(*listOf(REVEAL, mailbox, name).bytes())

  fun openText(mailbox: String, inviterKey: String, nonce: String, name: String): ByteArray =
      HomeLinkHash.join(*listOf(OPEN, mailbox, inviterKey, nonce, name).bytes())

  fun withMac(code: HomeLinkCode, m: HomeLinkMessage): HomeLinkMessage {
    val mac = mac(code, m)
    return when (m) {
      is HomeLinkMessage.Commit -> m.copy(mac = mac)
      is HomeLinkMessage.Reveal -> m.copy(mac = mac)
      is HomeLinkMessage.Open -> m.copy(mac = mac)
    }
  }

  /** From someone holding this code, to this code's mailbox. */
  fun fromCode(code: HomeLinkCode, m: HomeLinkMessage): Boolean {
    if (m.mailbox != HomeLinkCode.mailboxId(code)) return false
    val got = HomeLinkHash.unb64(m.mac) ?: return false
    return HomeLinkHash.same(got, macBytes(code, m))
  }

  private fun mac(code: HomeLinkCode, m: HomeLinkMessage): String = HomeLinkHash.b64(macBytes(code, m))

  private fun macBytes(code: HomeLinkCode, m: HomeLinkMessage): ByteArray =
      HomeLinkHash.hmac(HomeLinkCode.macKey(code), HomeLinkHash.utf8(HomeLinkWire.signable(m)))

  private fun List<String>.bytes(): Array<ByteArray> = map { HomeLinkHash.utf8(it) }.toTypedArray()
}
