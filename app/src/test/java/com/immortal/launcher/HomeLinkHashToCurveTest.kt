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
import java.security.KeyFactory
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

/**
 * RFC 9380 hash_to_curve for P256_XMD:SHA-256_SSWU_RO_, checked against the RFC's own test
 * vectors (Appendix J.1.1 for the suite, Appendix K.1 for expand_message_xmd). Every value must
 * match exactly: a near miss would still give a curve point, just not the standard one.
 */
class HomeLinkHashToCurveTest {
  private class Vector(val msg: String, val u0: String, val u1: String,
                       val q0: Pair<String, String>, val q1: Pair<String, String>, val p: Pair<String, String>)

  private val dst = HomeLinkHash.utf8("QUUX-V01-CS02-with-P256_XMD:SHA-256_SSWU_RO_")

  /** RFC 9380 Appendix J.1.1, copied from the RFC text (msg, u[0], u[1], Q0, Q1, P). */
  private val vectors = listOf(
      Vector("", u0 = "ad5342c66a6dd0ff080df1da0ea1c04b96e0330dd89406465eeba11582515009", u1 = "8c0f1d43204bd6f6ea70ae8013070a1518b43873bcd850aafa0a9e220e2eea5a",
          q0 = "ab640a12220d3ff283510ff3f4b1953d09fad35795140b1c5d64f313967934d5" to "dccb558863804a881d4fff3455716c836cef230e5209594ddd33d85c565b19b1",
          q1 = "51cce63c50d972a6e51c61334f0f4875c9ac1cd2d3238412f84e31da7d980ef5" to "b45d1a36d00ad90e5ec7840a60a4de411917fbe7c82c3949a6e699e5a1b66aac",
          p = "2c15230b26dbc6fc9a37051158c95b79656e17a1a920b11394ca91c44247d3e4" to "8a7a74985cc5c776cdfe4b1f19884970453912e9d31528c060be9ab5c43e8415"),
      Vector("abc", u0 = "afe47f2ea2b10465cc26ac403194dfb68b7f5ee865cda61e9f3e07a537220af1", u1 = "379a27833b0bfe6f7bdca08e1e83c760bf9a338ab335542704edcd69ce9e46e0",
          q0 = "5219ad0ddef3cc49b714145e91b2f7de6ce0a7a7dc7406c7726c7e373c58cb48" to "7950144e52d30acbec7b624c203b1996c99617d0b61c2442354301b191d93ecf",
          q1 = "019b7cb4efcfeaf39f738fe638e31d375ad6837f58a852d032ff60c69ee3875f" to "589a62d2b22357fed5449bc38065b760095ebe6aeac84b01156ee4252715446e",
          p = "0bb8b87485551aa43ed54f009230450b492fead5f1cc91658775dac4a3388a0f" to "5c41b3d0731a27a7b14bc0bf0ccded2d8751f83493404c84a88e71ffd424212e"),
      Vector("abcdef0123456789", u0 = "0fad9d125a9477d55cf9357105b0eb3a5c4259809bf87180aa01d651f53d312c", u1 = "b68597377392cd3419d8fcc7d7660948c8403b19ea78bbca4b133c9d2196c0fb",
          q0 = "a17bdf2965eb88074bc01157e644ed409dac97cfcf0c61c998ed0fa45e79e4a2" to "4f1bc80c70d411a3cc1d67aeae6e726f0f311639fee560c7f5a664554e3c9c2e",
          q1 = "7da48bb67225c1a17d452c983798113f47e438e4202219dd0715f8419b274d66" to "b765696b2913e36db3016c47edb99e24b1da30e761a8a3215dc0ec4d8f96e6f9",
          p = "65038ac8f2b1def042a5df0b33b1f4eca6bff7cb0f9c6c1526811864e544ed80" to "cad44d40a656e7aff4002a8de287abc8ae0482b5ae825822bb870d6df9b56ca3"),
      Vector("q128_" + "q".repeat(128), u0 = "3bbc30446f39a7befad080f4d5f32ed116b9534626993d2cc5033f6f8d805919", u1 = "76bb02db019ca9d3c1e02f0c17f8baf617bbdae5c393a81d9ce11e3be1bf1d33",
          q0 = "c76aaa823aeadeb3f356909cb08f97eee46ecb157c1f56699b5efebddf0e6398" to "776a6f45f528a0e8d289a4be12c4fab80762386ec644abf2bffb9b627e4352b1",
          q1 = "418ac3d85a5ccc4ea8dec14f750a3a9ec8b85176c95a7022f391826794eb5a75" to "fd6604f69e9d9d2b74b072d14ea13050db72c932815523305cb9e807cc900aff",
          p = "4be61ee205094282ba8a2042bcb48d88dfbb609301c49aa8b078533dc65a0b5d" to "98f8df449a072c4721d241a3b1236d3caccba603f916ca680f4539d2bfb3c29e"),
      Vector("a512_" + "a".repeat(512), u0 = "4ebc95a6e839b1ae3c63b847798e85cb3c12d3817ec6ebc10af6ee51adb29fec", u1 = "4e21af88e22ea80156aff790750121035b3eefaa96b425a8716e0d20b4e269ee",
          q0 = "d88b989ee9d1295df413d4456c5c850b8b2fb0f5402cc5c4c7e815412e926db8" to "bb4a1edeff506cf16def96afff41b16fc74f6dbd55c2210e5b8f011ba32f4f40",
          q1 = "a281e34e628f3a4d2a53fa87ff973537d68ad4fbc28d3be5e8d9f6a2571c5a4b" to "f6ed88a7aab56a488100e6f1174fa9810b47db13e86be999644922961206e184",
          p = "457ae2981f70ca85d8e24c308b14db22f3e3862c5ea0f652ca38b5e49cd64bc5" to "ecb9f0eadc9aeed232dabc53235368c1394c78de05dd96893eefa62b0f4757dc"),
  )

  private fun big(hex: String) = BigInteger(hex, 16)
  private fun point(xy: Pair<String, String>) = ECPoint(big(xy.first), big(xy.second))

  @Test fun expandMessageXmdMatchesRfc9380AppendixK1() {
    val kDst = HomeLinkHash.utf8("QUUX-V01-CS02-with-expander-SHA256-128")
    assertEquals("68a985b87eb6b46952128911f2a4412bbc302a9d759667f87f7a21d803f07235",
        HomeLinkHash.hex(HomeLinkHashToCurve.expandMessageXmd(ByteArray(0), kDst, 0x20)))
    assertEquals("d8ccab23b5985ccea865c6c97b6e5b8350e794e603b4b97902f53a8a0d605615",
        HomeLinkHash.hex(HomeLinkHashToCurve.expandMessageXmd(HomeLinkHash.utf8("abc"), kDst, 0x20)))
  }

  @Test fun expandMessageXmdRefusesWhatTheRfcForbids() {
    assertThrows(IllegalArgumentException::class.java) { HomeLinkHashToCurve.expandMessageXmd(ByteArray(0), ByteArray(256), 32) }
    assertThrows(IllegalArgumentException::class.java) { HomeLinkHashToCurve.expandMessageXmd(ByteArray(0), dst, 256 * 32) }
    assertThrows(IllegalArgumentException::class.java) { HomeLinkHashToCurve.expandMessageXmd(ByteArray(0), dst, 0) }
  }

  @Test fun hashToFieldMatchesRfc9380AppendixJ11() {
    for (v in vectors) {
      val (u0, u1) = HomeLinkHashToCurve.hashToField(HomeLinkHash.utf8(v.msg), dst)
      assertEquals("u[0] for '${v.msg.take(8)}'", big(v.u0), u0)
      assertEquals("u[1] for '${v.msg.take(8)}'", big(v.u1), u1)
    }
  }

  @Test fun mapToCurveSimpleSwuMatchesRfc9380AppendixJ11() {
    for (v in vectors) {
      assertEquals("Q0 for '${v.msg.take(8)}'", point(v.q0), HomeLinkHashToCurve.mapToCurve(big(v.u0)))
      assertEquals("Q1 for '${v.msg.take(8)}'", point(v.q1), HomeLinkHashToCurve.mapToCurve(big(v.u1)))
    }
  }

  @Test fun hashToCurveMatchesRfc9380AppendixJ11() {
    for (v in vectors) {
      val p = HomeLinkHashToCurve.hashToCurve(HomeLinkHash.utf8(v.msg), dst)
      assertEquals("P for '${v.msg.take(8)}'", point(v.p), p)
      assertTrue(HomeLinkP256.isValidPoint(p))
    }
  }

  /** The exceptional input u = 0 (tv1 = 0) takes the B / (Z·A) branch and still lands on the curve. */
  @Test fun mapToCurveHandlesTheExceptionalCase() {
    assertTrue(HomeLinkP256.isValidPoint(HomeLinkHashToCurve.mapToCurve(BigInteger.ZERO)))
    assertTrue(HomeLinkP256.isValidPoint(HomeLinkHashToCurve.mapToCurve(BigInteger.ONE)))
  }

  @Test fun pointAdditionHandlesDoublingAndInverses() {
    val q = point(vectors[0].q0)
    val minusQ = ECPoint(q.affineX, HomeLinkP256.PRIME.subtract(q.affineY))
    assertEquals(ECPoint.POINT_INFINITY, HomeLinkP256.add(q, minusQ))
    assertEquals(q, HomeLinkP256.add(q, ECPoint.POINT_INFINITY))
    val doubled = HomeLinkP256.add(q, q)
    assertTrue(HomeLinkP256.isValidPoint(doubled))
    // x(2Q) from the platform's ECDH (scalar 2, public Q) agrees with the doubling formula.
    val kf = KeyFactory.getInstance("EC")
    val x2 = KeyAgreement.getInstance("ECDH").run {
      init(kf.generatePrivate(ECPrivateKeySpec(BigInteger.valueOf(2), HomeLinkP256.CURVE)))
      doPhase(kf.generatePublic(ECPublicKeySpec(q, HomeLinkP256.CURVE)), true)
      generateSecret()
    }
    assertEquals(BigInteger(1, x2), doubled.affineX)
  }

  @Test fun pointValidationRejectsInfinityOffCurveAndOutOfRange() {
    val q = point(vectors[0].q0)
    assertTrue(HomeLinkP256.isValidPoint(q))
    assertFalse(HomeLinkP256.isValidPoint(ECPoint.POINT_INFINITY))
    assertFalse(HomeLinkP256.isValidPoint(ECPoint(q.affineX, q.affineY.add(BigInteger.ONE))))
    assertFalse(HomeLinkP256.isValidPoint(ECPoint(q.affineX.add(HomeLinkP256.PRIME), q.affineY)))
    assertFalse(HomeLinkP256.isValidPoint(ECPoint(q.affineX, q.affineY.add(HomeLinkP256.PRIME))))
    assertFalse(HomeLinkP256.isValidPoint(ECPoint(q.affineX.negate(), q.affineY)))
  }
}
