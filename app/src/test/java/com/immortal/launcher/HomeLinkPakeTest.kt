/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.security.SecureRandom

/** The PAKE that keeps the code's secret part safe from anyone recording the relay. */
class HomeLinkPakeTest {
  private val random = SecureRandom()
  private val mailbox = HomeLinkCode.mailboxId(HomeLinkCode.parse("MPT7Q4K2PA")!!)

  @Test fun theSameSecretGivesBothSidesTheSameKey() {
    val a = HomeLinkPake.start("7Q4K2PA", mailbox, random)
    val b = HomeLinkPake.start("7Q4K2PA", mailbox, random)
    val ka = HomeLinkPake.finish(a, b.share)!!
    val kb = HomeLinkPake.finish(b, a.share)!!
    assertArrayEquals(ka, kb)
    assertEquals(32, ka.size)
  }

  @Test fun aDifferentSecretGivesDifferentKeys() {
    val a = HomeLinkPake.start("7Q4K2PA", mailbox, random)
    val b = HomeLinkPake.start("7Q4K2PB", mailbox, random)
    assertFalse(HomeLinkPake.finish(a, b.share)!!.contentEquals(HomeLinkPake.finish(b, a.share)!!))
  }

  @Test fun theSameSecretInAnotherMailboxGivesDifferentKeys() {
    val other = HomeLinkCode.mailboxId(HomeLinkCode.parse("XYZ7Q4K2PA")!!)
    val a = HomeLinkPake.start("7Q4K2PA", mailbox, random)
    val b = HomeLinkPake.start("7Q4K2PA", other, random)
    assertFalse(HomeLinkPake.finish(a, b.share)!!.contentEquals(HomeLinkPake.finish(b, a.share)!!))
  }

  /** A share is a fresh random point every time, so two runs with one secret share nothing. */
  @Test fun sharesAreFreshAndValidPoints() {
    val shares = (1..20).map { HomeLinkPake.start("7Q4K2PA", mailbox, random).share }
    assertEquals(20, shares.toSet().size)
    for (s in shares) assertNotNull(HomeLinkPake.decodeShare(s))
  }

  /**
   * No offline oracle: an eavesdropper who records both shares and even knows the right secret
   * still can't compute the key without one side's scalar, so a recorded transcript can't be used
   * to check guesses. (Its own half with the right secret lands on a different key.)
   */
  @Test fun knowingTheSecretIsNotEnoughToCheckARecordedTranscript() {
    val a = HomeLinkPake.start("7Q4K2PA", mailbox, random)
    val b = HomeLinkPake.start("7Q4K2PA", mailbox, random)
    val real = HomeLinkPake.finish(a, b.share)!!
    val eve = HomeLinkPake.start("7Q4K2PA", mailbox, random)
    assertFalse(real.contentEquals(HomeLinkPake.finish(eve, a.share)!!))
    assertFalse(real.contentEquals(HomeLinkPake.finish(eve, b.share)!!))
  }

  @Test fun theGeneratorDependsOnSecretAndMailboxAndIsDeterministic() {
    assertEquals(HomeLinkPake.generator("7Q4K2PA", mailbox), HomeLinkPake.generator("7Q4K2PA", mailbox))
    assertNotEquals(HomeLinkPake.generator("7Q4K2PA", mailbox), HomeLinkPake.generator("7Q4K2PB", mailbox))
  }

  @Test fun rejectsSharesThatAreNotCurvePoints() {
    val a = HomeLinkPake.start("7Q4K2PA", mailbox, random)
    assertNull(HomeLinkPake.finish(a, ""))
    assertNull(HomeLinkPake.finish(a, "not base64!"))
    assertNull(HomeLinkPake.finish(a, HomeLinkHash.b64(ByteArray(31))))
    assertNull(HomeLinkPake.finish(a, HomeLinkHash.b64(ByteArray(33) { 1 })))
    // An x beyond the field prime, and an x with no point (found by search, deterministic).
    assertNull(HomeLinkPake.finish(a, HomeLinkHash.b64(ByteArray(32) { 0xFF.toByte() })))
    val offCurve = (0 until 64).map { HomeLinkHash.b64(BigInteger.valueOf(it.toLong() + 2).toByteArray().let { b ->
      ByteArray(32 - b.size) + b }) }.first { HomeLinkPake.decodeShare(it) == null }
    assertNull(HomeLinkPake.finish(a, offCurve))
  }

  /** The base point is RFC 9380 hash_to_curve (P256_XMD:SHA-256_SSWU_RO_) of (secret, mailbox) under the PAKE's own DST. */
  @Test fun theGeneratorIsRfc9380HashToCurveOfSecretAndMailbox() {
    val g = HomeLinkPake.generator("7Q4K2PA", mailbox)
    val msg = HomeLinkHash.join(HomeLinkHash.utf8("7Q4K2PA"), HomeLinkHash.utf8(mailbox))
    assertEquals(HomeLinkHashToCurve.hashToCurve(msg, HomeLinkPake.DST), g)
    assertTrue(HomeLinkP256.isValidPoint(g))
    assertTrue(HomeLinkPake.DST.size in 16..255)
    assertTrue(String(HomeLinkPake.DST, Charsets.UTF_8).endsWith("P256_XMD:SHA-256_SSWU_RO_"))
  }

  /** x-only shares can't spell infinity; every other encoding of it (and of real points) is refused. */
  @Test fun rejectsInfinityAndOtherPointEncodings() {
    val a = HomeLinkPake.start("7Q4K2PA", mailbox, random)
    val b = HomeLinkPake.start("7Q4K2PA", mailbox, random)
    assertNull(HomeLinkPake.finish(a, HomeLinkHash.b64(byteArrayOf(0))))          // SEC1 infinity
    assertNull(HomeLinkPake.finish(a, HomeLinkHash.b64(ByteArray(0))))
    val x = HomeLinkHash.unb64(b.share)!!
    assertNull(HomeLinkPake.finish(a, HomeLinkHash.b64(byteArrayOf(2) + x)))      // SEC1 compressed
    assertNull(HomeLinkPake.finish(a, HomeLinkHash.b64(byteArrayOf(4) + x + x)))  // SEC1 uncompressed
    assertNull(HomeLinkPake.finish(a, b.share + "="))
    assertNull(HomeLinkPake.finish(a, " " + b.share))
    // The point checks behind decoding: infinity, off the curve, coordinates out of range.
    val p = HomeLinkPake.decodeShare(b.share)!!
    assertNull(HomeLinkPake.finish(a, java.security.spec.ECPoint.POINT_INFINITY))
    assertNull(HomeLinkPake.finish(a, java.security.spec.ECPoint(p.affineX, p.affineY.add(BigInteger.ONE))))
    assertNull(HomeLinkPake.finish(a, java.security.spec.ECPoint(p.affineX.add(HomeLinkP256.PRIME), p.affineY)))
    assertArrayEquals(HomeLinkPake.finish(a, b.share), HomeLinkPake.finish(a, p))
  }

  /** A share must be the one canonical Base64 of its 32 bytes, so our own share can't come back in disguise. */
  @Test fun refusesANonCanonicalSpellingOfItsOwnShare() {
    val a = HomeLinkPake.start("7Q4K2PA", mailbox, random)
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val last = a.share[42]
    val disguised = a.share.substring(0, 42) + alphabet[alphabet.indexOf(last) xor 1] + a.share.substring(43)
    assertNotEquals(a.share, disguised)
    assertNull(HomeLinkPake.finish(a, disguised))
  }

  @Test fun refusesItsOwnShareReflectedBack() {
    val a = HomeLinkPake.start("7Q4K2PA", mailbox, random)
    assertNull(HomeLinkPake.finish(a, a.share))
  }

  @Test fun secretsNeverShowInToString() {
    val a = HomeLinkPake.start("7Q4K2PA", mailbox, random)
    assertFalse(a.toString().contains(a.scalar.toString()))
    assertFalse(a.toString().contains(a.scalar.toString(16)))
    val s = HomeLinkSession.derive(mailbox, a.share, a.share, ByteArray(32))
    assertEquals("HomeLinkSession(sid=${s.sid})", s.toString())
  }

  @Test fun sessionKeysDifferByDirectionAndByTranscript() {
    val s = HomeLinkSession.derive(mailbox, "i", "j", ByteArray(32))
    assertFalse(s.macKey(towardInviter = true).contentEquals(s.macKey(towardInviter = false)))
    val swapped = HomeLinkSession.derive(mailbox, "j", "i", ByteArray(32))
    assertNotEquals(s.sid, swapped.sid)
    assertFalse(s.macKey(true).contentEquals(swapped.macKey(true)))
  }
}
