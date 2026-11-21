/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom

/** The one-time code that introduces two homes (mock `inviteScr` / `joinScr`). */
class HomeLinkCodeTest {
  private val random = SecureRandom()

  @Test fun newCodeIsTenSymbolsFromTheAlphabet() {
    repeat(200) {
      val code = HomeLinkCode.generate(random)
      assertEquals(10, code.value.length)
      assertTrue(code.value.all { it in HomeLinkCode.ALPHABET })
    }
  }

  @Test fun alphabetHasThirtyTwoUnambiguousSymbols() {
    assertEquals(32, HomeLinkCode.ALPHABET.length)
    assertEquals(32, HomeLinkCode.ALPHABET.toSet().size)
    for (c in "ILOU") assertFalse("$c is easy to mishear", c in HomeLinkCode.ALPHABET)
  }

  @Test fun splitsIntoAPublicNameplateAndA35BitSecret() {
    val code = HomeLinkCode.parse("MPT7Q4K2PA")!!
    assertEquals("MPT", code.nameplate)
    assertEquals("7Q4K2PA", code.secret)
    assertEquals(35, HomeLinkCode.SECRET_BITS)
  }

  @Test fun showsNameplateThenTheSecretInTwoGroups() {
    assertEquals("MPT-7Q4-K2PA", HomeLinkCode.parse("MPT7Q4K2PA")!!.display)
  }

  @Test fun typedCodesAreForgiving() {
    val want = HomeLinkCode.parse("MPT7Q4K2PA")!!
    assertEquals(want, HomeLinkCode.parse("mpt-7q4-k2pa"))
    assertEquals(want, HomeLinkCode.parse(" MPT 7Q4 K2PA "))
    // O reads as zero, I and L as one (Crockford).
    assertEquals(HomeLinkCode.parse("A00111BCDE"), HomeLinkCode.parse("aOoIiLBCDE"))
  }

  @Test fun rejectsWrongLengthAndUnknownSymbols() {
    assertNull(HomeLinkCode.parse(""))
    assertNull(HomeLinkCode.parse("MPT7Q4K2P"))
    assertNull(HomeLinkCode.parse("MPT7Q4K2PAA"))
    assertNull(HomeLinkCode.parse("MPT7Q4K2PU"))
    assertNull(HomeLinkCode.parse("MPT7Q4K2P!"))
  }

  @Test fun mailboxIsStableHexFromTheNameplateOnly() {
    val code = HomeLinkCode.parse("MPT7Q4K2PA")!!
    val box = HomeLinkCode.mailboxId(code)
    assertEquals(box, HomeLinkCode.mailboxId(HomeLinkCode.parse("mpt-7q4-k2pa")!!))
    assertTrue(box.matches(Regex("[0-9a-f]{32}")))
    assertNotEquals(box, HomeLinkCode.mailboxId(HomeLinkCode.parse("MPM7Q4K2PA")!!))
  }

  /**
   * The S1 review's finding: the mailbox id was a hash of the whole code, so whoever saw it could
   * brute-force the code offline. Now every secret gives the same id, so it is no oracle at all.
   */
  @Test fun theMailboxIdSaysNothingAboutTheSecret() {
    val box = HomeLinkCode.mailboxId(HomeLinkCode.parse("MPT7Q4K2PA")!!)
    repeat(200) {
      val other = HomeLinkCode.parse("MPT" + HomeLinkCode.generate(random).secret)!!
      assertEquals(box, HomeLinkCode.mailboxId(other))
    }
  }

  @Test fun inviteLastsTenMinutes() {
    val invite = HomeLinkInvite(HomeLinkCode.parse("MPT7Q4K2PA")!!, createdAt = 1_000L)
    assertEquals(1_000L + 10 * 60_000L, invite.expiresAt)
    assertFalse(invite.isExpired(invite.expiresAt - 1))
    assertTrue(invite.isExpired(invite.expiresAt))
    assertEquals(600, invite.secondsLeft(1_000L))
    assertEquals(1, invite.secondsLeft(invite.expiresAt - 1))
    assertEquals(0, invite.secondsLeft(invite.expiresAt + 5_000))
  }

  @Test fun countdownReadsLikeTheMock() {
    val invite = HomeLinkInvite(HomeLinkCode.parse("MPT7Q4K2PA")!!, createdAt = 0L)
    assertEquals("9:42", invite.countdown(18_000L))
    assertEquals("0:05", invite.countdown(invite.expiresAt - 5_000))
    assertEquals("0:00", invite.countdown(invite.expiresAt + 1))
  }
}
