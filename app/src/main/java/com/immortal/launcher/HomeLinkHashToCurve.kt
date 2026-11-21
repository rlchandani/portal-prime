/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.MessageDigest
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint

/** NIST P-256 (secp256r1) from the platform, plus the few affine operations the PAKE needs. */
internal object HomeLinkP256 {
  val CURVE: ECParameterSpec by lazy {
    AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
  }
  val PRIME: BigInteger by lazy { (CURVE.curve.field as ECFieldFp).p }
  val A: BigInteger by lazy { CURVE.curve.a }
  val B: BigInteger by lazy { CURVE.curve.b }
  private val THREE = BigInteger.valueOf(3)

  /** x³ + a·x + b mod p. */
  fun rhs(x: BigInteger): BigInteger = x.multiply(x).multiply(x).add(A.multiply(x)).add(B).mod(PRIME)

  /**
   * A finite point with both coordinates in [0, p) that satisfies the curve equation. P-256 has
   * cofactor 1, so every such point is in the prime-order group: no subgroup check is needed.
   */
  fun isValidPoint(p: ECPoint): Boolean {
    if (p == ECPoint.POINT_INFINITY) return false
    val x = p.affineX
    val y = p.affineY
    if (x.signum() < 0 || x >= PRIME || y.signum() < 0 || y >= PRIME) return false
    return y.multiply(y).mod(PRIME) == rhs(x)
  }

  /**
   * Affine addition, including doubling and P + (−P) = infinity. Only for public inputs or the
   * hash-to-curve output (see [HomeLinkHashToCurve] for the timing caveat); scalar multiplication
   * with a secret scalar goes through the platform ECDH instead.
   */
  fun add(p: ECPoint, q: ECPoint): ECPoint {
    if (p == ECPoint.POINT_INFINITY) return q
    if (q == ECPoint.POINT_INFINITY) return p
    val (x1, y1) = p.affineX to p.affineY
    val (x2, y2) = q.affineX to q.affineY
    val lambda = if (x1 == x2) {
      if (y1.add(y2).mod(PRIME).signum() == 0) return ECPoint.POINT_INFINITY
      THREE.multiply(x1).multiply(x1).add(A).multiply(y1.shiftLeft(1).modInverse(PRIME))
    } else {
      y2.subtract(y1).multiply(x2.subtract(x1).mod(PRIME).modInverse(PRIME))
    }.mod(PRIME)
    val x3 = lambda.multiply(lambda).subtract(x1).subtract(x2).mod(PRIME)
    val y3 = lambda.multiply(x1.subtract(x3)).subtract(y1).mod(PRIME)
    return ECPoint(x3, y3)
  }
}

/**
 * RFC 9380 `hash_to_curve` for the suite **P256_XMD:SHA-256_SSWU_RO_** (section 8.2):
 * `expand_message_xmd` with SHA-256 (section 5.3.1), `hash_to_field` with m = 1, L = 48, count = 2
 * (section 5.2), the simplified SWU map with Z = −10 (section 6.6.2), Q0 + Q1, cofactor 1. Checked
 * against the RFC's Appendix J.1.1 and K.1 vectors (`HomeLinkHashToCurveTest`).
 *
 * The map is straight-line: every input does the same field operations (inversion as x^(p−2),
 * `is_square` as Euler's criterion, both square roots as x^((p+1)/4) since p ≡ 3 mod 4), and the
 * RFC's CMOV selections are arithmetic (`a + c·(b − a)`), not branches. What it can't promise is
 * constant time *inside* [BigInteger], whose multiply/mod/modPow/modInverse run in time that can
 * depend on operand values, and the final affine addition, which inverts with `modInverse`. Only
 * the device holding the code's secret computes this (once per PAKE start), so exploiting it would
 * need fine-grained local timing of that Portal or phone while it pairs.
 */
internal object HomeLinkHashToCurve {
  private const val HASH_BYTES = 32
  private const val BLOCK_BYTES = 64
  /** L = ceil((ceil(log2(p)) + k) / 8) with k = 128. */
  private const val FIELD_BYTES = 48
  private const val MAX_DST = 255
  private const val MAX_BLOCKS = 255
  private val P get() = HomeLinkP256.PRIME
  private val Z: BigInteger by lazy { P.subtract(BigInteger.TEN) }
  private val INV_EXP: BigInteger by lazy { P.subtract(BigInteger.valueOf(2)) }
  private val SQUARE_EXP: BigInteger by lazy { P.subtract(BigInteger.ONE).shiftRight(1) }
  private val ZERO_EXP: BigInteger by lazy { P.subtract(BigInteger.ONE) }
  private val SQRT_EXP: BigInteger by lazy { P.add(BigInteger.ONE).shiftRight(2) }
  /** −B / A. */
  private val C1: BigInteger by lazy { HomeLinkP256.B.negate().multiply(inv0(HomeLinkP256.A)).mod(P) }
  /** B / (Z·A), the value for the exceptional case tv1 = 0. */
  private val C2: BigInteger by lazy { HomeLinkP256.B.multiply(inv0(Z.multiply(HomeLinkP256.A))).mod(P) }

  fun hashToCurve(msg: ByteArray, dst: ByteArray): ECPoint {
    val (u0, u1) = hashToField(msg, dst)
    return HomeLinkP256.add(mapToCurve(u0), mapToCurve(u1))
  }

  fun hashToField(msg: ByteArray, dst: ByteArray): Pair<BigInteger, BigInteger> {
    val bytes = expandMessageXmd(msg, dst, 2 * FIELD_BYTES)
    fun element(i: Int) = BigInteger(1, bytes.copyOfRange(i * FIELD_BYTES, (i + 1) * FIELD_BYTES)).mod(P)
    return element(0) to element(1)
  }

  /** RFC 9380 section 5.3.1. */
  fun expandMessageXmd(msg: ByteArray, dst: ByteArray, len: Int): ByteArray {
    val blocks = (len + HASH_BYTES - 1) / HASH_BYTES
    require(len in 1..0xFFFF && blocks <= MAX_BLOCKS) { "expand_message_xmd length out of range" }
    require(dst.size <= MAX_DST) { "DST longer than $MAX_DST bytes" }
    val dstPrime = dst + byteArrayOf(dst.size.toByte())
    val b0 = sha256(ByteArray(BLOCK_BYTES), msg, byteArrayOf((len ushr 8).toByte(), len.toByte()), byteArrayOf(0), dstPrime)
    val out = ByteArray(blocks * HASH_BYTES)
    var previous = sha256(b0, byteArrayOf(1), dstPrime)
    previous.copyInto(out, 0)
    for (i in 2..blocks) {
      val mixed = ByteArray(HASH_BYTES) { (b0[it].toInt() xor previous[it].toInt()).toByte() }
      previous = sha256(mixed, byteArrayOf(i.toByte()), dstPrime)
      previous.copyInto(out, (i - 1) * HASH_BYTES)
    }
    return out.copyOf(len)
  }

  /** RFC 9380 section 6.6.2, simplified SWU for A ≠ 0, B ≠ 0, as straight-line field arithmetic. */
  fun mapToCurve(u: BigInteger): ECPoint {
    val uu = u.mod(P)
    val zu2 = Z.multiply(uu).multiply(uu).mod(P)
    val tv1 = inv0(zu2.multiply(zu2).add(zu2).mod(P))
    val x1 = cmov(C1.multiply(BigInteger.ONE.add(tv1)).mod(P), C2, isZero(tv1))
    val gx1 = HomeLinkP256.rhs(x1)
    val x2 = zu2.multiply(x1).mod(P)
    val gx2 = HomeLinkP256.rhs(x2)
    val square = isSquare(gx1)
    val x = cmov(x2, x1, square)
    val y = cmov(gx2.modPow(SQRT_EXP, P), gx1.modPow(SQRT_EXP, P), square)
    val flip = sgn0(uu) xor sgn0(y)
    return ECPoint(x, cmov(y, P.subtract(y).mod(P), flip))
  }

  private fun inv0(x: BigInteger): BigInteger = x.mod(P).modPow(INV_EXP, P)

  /** 1 when x = 0, else 0 (x^(p−1) is 1 for every non-zero x). */
  private fun isZero(x: BigInteger): Int = 1 - x.modPow(ZERO_EXP, P).toInt()

  /**
   * Euler's criterion: x^((p−1)/2) is 1 for a non-zero square, p − 1 (even) for a non-square, so
   * its low bit decides. x = 0 (a square) would give 0, but gx1 = 0 means a point with y = 0, of
   * order 2, which prime-order P-256 doesn't have.
   */
  private fun isSquare(x: BigInteger): Int = x.modPow(SQUARE_EXP, P).and(BigInteger.ONE).toInt()

  private fun sgn0(x: BigInteger): Int = x.and(BigInteger.ONE).toInt()

  /** b when c = 1, a when c = 0, by arithmetic rather than a branch. */
  private fun cmov(a: BigInteger, b: BigInteger, c: Int): BigInteger =
      a.add(BigInteger.valueOf(c.toLong()).multiply(b.subtract(a))).mod(P)

  private fun sha256(vararg parts: ByteArray): ByteArray =
      MessageDigest.getInstance("SHA-256").run { parts.forEach(::update); digest() }
}
