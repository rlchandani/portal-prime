/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.security.SecureRandom

/**
 * The one-time code that introduces two homes (mock `inviteScr`: `MPL-7Q4-K2P`, 10 minutes, works
 * once). Nine symbols from Crockford's 32-symbol alphabet: 45 bits, read out over a phone call.
 *
 * The code only introduces: the relay is given [mailboxId] (a hash, never the code) and messages
 * carry a MAC under [macKey] so strangers' messages are ignored. A relay that brute-forces the
 * code from the mailbox id still cannot sit in the middle unnoticed: the safety pictures
 * ([HomeLinkSafety]) carry the security, behind a commit-then-reveal exchange ([HomeLinkInviter]).
 */
@JvmInline
internal value class HomeLinkCode private constructor(val value: String) {
  /** `MP7-Q4K-2PA`. */
  val display: String get() = value.chunked(GROUP).joinToString("-")

  companion object {
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val LENGTH = 9
    private const val GROUP = 3
    private val SALT = HomeLinkHash.utf8("hearthpane-homelink-code-v1")
    private const val MAILBOX_HEX = 32

    fun generate(random: SecureRandom): HomeLinkCode =
        HomeLinkCode(String(CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }))

    /** A typed or scanned code: any case, dashes and spaces ignored, O → 0, I/L → 1. */
    fun parse(input: String): HomeLinkCode? {
      val cleaned = input.uppercase().filterNot { it == '-' || it.isWhitespace() }
          .map { when (it) { 'O' -> '0'; 'I', 'L' -> '1'; else -> it } }.joinToString("")
      if (cleaned.length != LENGTH || cleaned.any { it !in ALPHABET }) return null
      return HomeLinkCode(cleaned)
    }

    private fun root(code: HomeLinkCode): ByteArray = HomeLinkHash.hmac(SALT, HomeLinkHash.utf8(code.value))

    /** Where both homes meet on the relay. Hex, 128 bits, derived one-way from the code. */
    fun mailboxId(code: HomeLinkCode): String =
        HomeLinkHash.hex(HomeLinkHash.hmac(root(code), HomeLinkHash.utf8("mailbox"))).take(MAILBOX_HEX)

    /** Authenticates pairing messages as coming from someone who has the code. */
    fun macKey(code: HomeLinkCode): ByteArray = HomeLinkHash.hmac(root(code), HomeLinkHash.utf8("mac"))
  }
}

/** An invite this home is showing. Immutable; a new code is a new invite. */
internal data class HomeLinkInvite(val code: HomeLinkCode, val createdAt: Long) {
  val expiresAt: Long get() = createdAt + LIFETIME_MS

  fun isExpired(now: Long): Boolean = now >= expiresAt

  /** Whole seconds left, rounded up, never negative. */
  fun secondsLeft(now: Long): Int = ((expiresAt - now).coerceAtLeast(0L) + MS - 1).div(MS).toInt()

  /** "Expires in 9:42" (mock chip). */
  fun countdown(now: Long): String = secondsLeft(now).let { "%d:%02d".format(it / 60, it % 60) }

  companion object {
    const val LIFETIME_MS = 10 * 60_000L
    private const val MS = 1_000L
  }
}
