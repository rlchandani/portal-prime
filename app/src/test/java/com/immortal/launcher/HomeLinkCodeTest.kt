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

  @Test fun newCodeIsNineSymbolsFromTheAlphabet() {
    repeat(200) {
      val code = HomeLinkCode.generate(random)
      assertEquals(9, code.value.length)
      assertTrue(code.value.all { it in HomeLinkCode.ALPHABET })
    }
  }

  @Test fun alphabetHasThirtyTwoUnambiguousSymbols() {
    assertEquals(32, HomeLinkCode.ALPHABET.length)
    assertEquals(32, HomeLinkCode.ALPHABET.toSet().size)
    for (c in "ILOU") assertFalse("$c is easy to mishear", c in HomeLinkCode.ALPHABET)
  }

  @Test fun showsInThreeGroups() {
    assertEquals("MP7-Q4K-2PA", HomeLinkCode.parse("MP7Q4K2PA")!!.display)
  }

  @Test fun typedCodesAreForgiving() {
    val want = HomeLinkCode.parse("MP7Q4K2PA")!!
    assertEquals(want, HomeLinkCode.parse("mp7-q4k-2pa"))
    assertEquals(want, HomeLinkCode.parse(" MP7 Q4K 2PA "))
    // O reads as zero, I and L as one (Crockford).
    assertEquals(HomeLinkCode.parse("A00111BCD"), HomeLinkCode.parse("aOoIiLBCD"))
  }

  @Test fun rejectsWrongLengthAndUnknownSymbols() {
    assertNull(HomeLinkCode.parse(""))
    assertNull(HomeLinkCode.parse("MP7Q4K2P"))
    assertNull(HomeLinkCode.parse("MP7Q4K2PAA"))
    assertNull(HomeLinkCode.parse("MP7Q4K2PU"))
    assertNull(HomeLinkCode.parse("MP7Q4K2P!"))
  }

  @Test fun mailboxIsStableHexAndNeverTheCode() {
    val code = HomeLinkCode.parse("MP7Q4K2PA")!!
    val box = HomeLinkCode.mailboxId(code)
    assertEquals(box, HomeLinkCode.mailboxId(HomeLinkCode.parse("mp7-q4k-2pa")!!))
    assertTrue(box.matches(Regex("[0-9a-f]{32}")))
    assertFalse(box.contains(code.value.lowercase()))
    assertNotEquals(box, HomeLinkCode.mailboxId(HomeLinkCode.parse("MP7Q4K2PB")!!))
  }

  @Test fun macKeyDiffersFromMailboxAndPerCode() {
    val a = HomeLinkCode.parse("MP7Q4K2PA")!!
    val b = HomeLinkCode.parse("MP7Q4K2PB")!!
    assertEquals(32, HomeLinkCode.macKey(a).size)
    assertFalse(HomeLinkCode.macKey(a).contentEquals(HomeLinkCode.macKey(b)))
  }

  @Test fun inviteLastsTenMinutes() {
    val invite = HomeLinkInvite(HomeLinkCode.parse("MP7Q4K2PA")!!, createdAt = 1_000L)
    assertEquals(1_000L + 10 * 60_000L, invite.expiresAt)
    assertFalse(invite.isExpired(invite.expiresAt - 1))
    assertTrue(invite.isExpired(invite.expiresAt))
    assertEquals(600, invite.secondsLeft(1_000L))
    assertEquals(1, invite.secondsLeft(invite.expiresAt - 1))
    assertEquals(0, invite.secondsLeft(invite.expiresAt + 5_000))
  }

  @Test fun countdownReadsLikeTheMock() {
    val invite = HomeLinkInvite(HomeLinkCode.parse("MP7Q4K2PA")!!, createdAt = 0L)
    assertEquals("9:42", invite.countdown(18_000L))
    assertEquals("0:05", invite.countdown(invite.expiresAt - 5_000))
    assertEquals("0:00", invite.countdown(invite.expiresAt + 1))
  }
}
