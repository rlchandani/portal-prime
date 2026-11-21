/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.security.SecureRandom

/**
 * The one-time code that introduces two homes (mock `inviteScr`: `MPT-7Q4-K2PA`, 10 minutes,
 * works once). Ten symbols from Crockford's 32-symbol alphabet, read out over a phone call, split
 * the way magic-wormhole splits its codes:
 *
 * - [nameplate] (first 3 symbols, 15 bits) is **public**: it only says which relay mailbox to
 *   meet in ([mailboxId]). It is not a secret and carries no information about the rest.
 * - [secret] (last 7 symbols, 35 bits) never reaches the relay in any form it can test offline:
 *   it only feeds the PAKE ([HomeLinkPake]), so each guess at it costs one live join attempt, and
 *   an invite allows at most [HomeLinkInviter.MAX_TRIES] of those.
 *
 * After the PAKE, the safety pictures ([HomeLinkSafety]) are a second, independent check.
 */
@JvmInline
internal value class HomeLinkCode private constructor(val value: String) {
  /** Locates the mailbox. Public. */
  val nameplate: String get() = value.take(NAMEPLATE)

  /** Feeds the PAKE only. Never log, never send. */
  val secret: String get() = value.drop(NAMEPLATE)

  /** `MPT-7Q4-K2PA`: nameplate, then the secret in two groups. */
  val display: String get() = listOf(nameplate, secret.take(GROUP), secret.drop(GROUP)).joinToString("-")

  companion object {
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val LENGTH = 10
    const val NAMEPLATE = 3
    const val SECRET_BITS = (LENGTH - NAMEPLATE) * 5
    private const val GROUP = 3
    private val SALT = HomeLinkHash.utf8("hearthpane-homelink-nameplate-v2")
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

    /**
     * Where both homes meet on the relay: a function of the public [nameplate] only, so it says
     * nothing about [secret] (the S1 review found the old hash-of-the-whole-code id let anyone
     * who saw it brute-force the 45-bit code offline).
     */
    fun mailboxId(code: HomeLinkCode): String =
        HomeLinkHash.hex(HomeLinkHash.hmac(SALT, HomeLinkHash.utf8(code.nameplate))).take(MAILBOX_HEX)
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
