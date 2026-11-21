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
import java.util.Base64

/** The four pictures and twelve digits both homes compare (mock `verifyScr`). */
class HomeLinkSafetyTest {
  private val random = SecureRandom()
  private val maple = HomeLinkIdentity.generate(random).publicKey
  private val lake = HomeLinkIdentity.generate(random).publicKey
  private val nonce = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

  @Test fun sixtyFourDistinctPicturesAndWords() {
    assertEquals(64, HomeLinkSafety.PICTURES.size)
    assertEquals(64, HomeLinkSafety.PICTURES.map { it.emoji }.toSet().size)
    assertEquals(64, HomeLinkSafety.PICTURES.map { it.word.lowercase() }.toSet().size)
    // No variation selectors or joiners: they render as two glyphs on the Portal's Android 9 font.
    for (p in HomeLinkSafety.PICTURES) assertTrue(p.word, p.emoji.none { it == '\uFE0F' || it == '\u200D' })
  }

  @Test fun startsWithTheMockPictures() {
    assertEquals(listOf("Fox", "Moon", "Pear", "Boat"), HomeLinkSafety.PICTURES.take(4).map { it.word })
  }

  @Test fun sameInputsGiveTheSameNumberOnBothScreens() {
    val a = HomeLinkSafety.derive(maple, lake, nonce)!!
    val b = HomeLinkSafety.derive(maple, lake, nonce)!!
    assertEquals(a, b)
    assertEquals(4, a.pictures.size)
    assertTrue(a.digits.matches(Regex("[0-9]{12}")))
  }

  @Test fun digitsShowInThreeGroupsOfFour() {
    val s = HomeLinkSafetyNumber(HomeLinkSafety.PICTURES.take(4), "482109376612")
    assertEquals("4821 0937 6612", s.displayDigits)
  }

  @Test fun anyChangedInputChangesTheNumber() {
    val base = HomeLinkSafety.derive(maple, lake, nonce)!!
    val other = HomeLinkIdentity.generate(random).publicKey
    val otherNonce = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
    assertNotEquals(base, HomeLinkSafety.derive(maple, other, nonce))
    assertNotEquals(base, HomeLinkSafety.derive(other, lake, nonce))
    assertNotEquals(base, HomeLinkSafety.derive(maple, lake, otherNonce))
    // Roles are fixed: the inviter's key always goes first.
    assertNotEquals(base, HomeLinkSafety.derive(lake, maple, nonce))
  }

  @Test fun rejectsBadInputs() {
    assertNull(HomeLinkSafety.derive("junk", lake, nonce))
    assertNull(HomeLinkSafety.derive(maple, lake, "short"))
  }

  @Test fun picturesSpreadAcrossTheWholeSet() {
    val seen = HashSet<String>()
    repeat(400) {
      val n = Base64.getEncoder().encodeToString(ByteArray(32).also { random.nextBytes(it) })
      HomeLinkSafety.derive(maple, lake, n)!!.pictures.forEach { seen += it.word }
    }
    assertTrue("only ${seen.size} pictures used", seen.size >= 60)
  }

  @Test fun spokenFormNamesEveryPicture() {
    val s = HomeLinkSafetyNumber(HomeLinkSafety.PICTURES.take(4), "482109376612")
    assertEquals("Fox, Moon, Pear, Boat. 4821 0937 6612", s.spoken)
  }
}
