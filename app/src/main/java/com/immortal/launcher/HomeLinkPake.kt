/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

/**
 * One side's PAKE half: a random scalar and the share it sends. Holds a secret: never log it
 * ([toString] says nothing).
 */
internal class HomeLinkPakeHalf internal constructor(internal val scalar: BigInteger, val share: String) {
  override fun toString(): String = "HomeLinkPakeHalf(share=$share)"
}

/**
 * A balanced PAKE over P-256 in the style of CPace (draft-irtf-cfrg-cpace), so the code's secret
 * part can't be guessed offline by anyone who records the relay's traffic:
 *
 * 1. Both sides map (secret, mailbox) to a curve point `G` whose discrete log nobody knows.
 * 2. Each picks a random scalar `y` and sends the x-coordinate of `y·G` (its share). A share is
 *    a uniformly random point whatever the secret, so it tells an eavesdropper nothing.
 * 3. Each computes `K = x(y_mine · Y_theirs)`; both get `x(y_i·y_j·G)` only if both used the same
 *    secret. Checking a guess against a recorded transcript needs `y_i` or `y_j` (Diffie–Hellman
 *    hardness), so the only way to test a guess is to take part, once per join attempt.
 *
 * x-only shares work because `x(k·P) = x(k·(−P))`. The secret-scalar multiplications go through
 * the platform's ECDH ([KeyAgreement]), never through [BigInteger]. The map to the curve is
 * try-and-increment, always over [CANDIDATES] candidates so its running time doesn't depend on
 * which one works. Pure apart from the [SecureRandom] the caller injects.
 */
internal object HomeLinkPake {
  const val SHARE_BYTES = 32
  /** Chance all 64 candidates miss the curve: 2^-64. */
  private const val CANDIDATES = 64
  private val MAP_DOMAIN = HomeLinkHash.utf8("hearthpane-homelink-cpace-map-v1")
  private val CURVE: ECParameterSpec by lazy {
    AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
  }
  private val PRIME: BigInteger by lazy { (CURVE.curve.field as ECFieldFp).p }
  private val SQRT_EXP: BigInteger by lazy { PRIME.add(BigInteger.ONE).shiftRight(2) }

  /** This side's half for a pairing in [mailbox] with [secret] (the code's secret part). */
  fun start(secret: String, mailbox: String, random: SecureRandom): HomeLinkPakeHalf {
    val g = generator(secret, mailbox)
    while (true) {
      val scalar = BigInteger(CURVE.order.bitLength(), random)
      if (scalar.signum() == 0 || scalar >= CURVE.order) continue
      val x = multiply(scalar, g) ?: continue
      return HomeLinkPakeHalf(scalar, HomeLinkHash.b64(x))
    }
  }

  /** The shared point's x-coordinate, or null for a share that isn't a curve point (or is ours reflected back). */
  fun finish(mine: HomeLinkPakeHalf, theirShare: String): ByteArray? {
    if (theirShare == mine.share) return null
    val point = decodeShare(theirShare) ?: return null
    return multiply(mine.scalar, point)
  }

  /** A share on the wire: exactly 32 bytes of Base64 naming the x-coordinate of a curve point. */
  fun decodeShare(share: String): ECPoint? {
    val bytes = HomeLinkHash.unb64(share)?.takeIf { it.size == SHARE_BYTES } ?: return null
    return lift(BigInteger(1, bytes))
  }

  /** The password-derived base point. Visible for tests (it is not secret-free: never send it). */
  internal fun generator(secret: String, mailbox: String): ECPoint {
    val seed = HomeLinkHash.sha256(MAP_DOMAIN, HomeLinkHash.utf8(secret), HomeLinkHash.utf8(mailbox))
    var found: ECPoint? = null
    for (i in 0 until CANDIDATES) {
      val x = BigInteger(1, HomeLinkHash.hmac(seed, byteArrayOf(i.toByte()))).mod(PRIME)
      val point = lift(x)
      if (found == null) found = point
    }
    return checkNotNull(found) { "no curve point in $CANDIDATES candidates" }
  }

  /** The curve point with this x (either y; x-only arithmetic doesn't care), or null if none. */
  private fun lift(x: BigInteger): ECPoint? {
    if (x >= PRIME) return null
    val rhs = x.pow(3).add(CURVE.curve.a.multiply(x)).add(CURVE.curve.b).mod(PRIME)
    if (rhs.signum() == 0) return null
    val y = rhs.modPow(SQRT_EXP, PRIME)
    return if (y.multiply(y).mod(PRIME) == rhs) ECPoint(x, y) else null
  }

  /** x(scalar · point), padded to 32 bytes, through the platform ECDH. */
  private fun multiply(scalar: BigInteger, point: ECPoint): ByteArray? = try {
    val kf = KeyFactory.getInstance("EC")
    KeyAgreement.getInstance("ECDH").run {
      init(kf.generatePrivate(ECPrivateKeySpec(scalar, CURVE)))
      doPhase(kf.generatePublic(ECPublicKeySpec(point, CURVE)), true)
      generateSecret()
    }.takeIf { it.size == SHARE_BYTES }
  } catch (e: GeneralSecurityException) { null } catch (e: IllegalArgumentException) { null }
}

/**
 * Keys for one pairing session, from the PAKE result and both shares (inviter first). One MAC key
 * per direction, so a message can't be reflected back to its sender. [sid] is public: it binds
 * the identity signatures to this session. Holds secrets: [toString] says nothing.
 */
internal class HomeLinkSession private constructor(val sid: String, private val toInviter: ByteArray, private val toJoiner: ByteArray) {
  fun macKey(towardInviter: Boolean): ByteArray = (if (towardInviter) toInviter else toJoiner).copyOf()

  override fun toString(): String = "HomeLinkSession(sid=$sid)"

  companion object {
    private val SID = HomeLinkHash.utf8("hearthpane-homelink-sid-v1")
    private val ISK = HomeLinkHash.utf8("hearthpane-homelink-isk-v1")

    fun derive(mailbox: String, inviterShare: String, joinerShare: String, shared: ByteArray): HomeLinkSession {
      val transcript = arrayOf(HomeLinkHash.utf8(mailbox), HomeLinkHash.utf8(inviterShare), HomeLinkHash.utf8(joinerShare))
      val sid = HomeLinkHash.b64(HomeLinkHash.sha256(SID, *transcript))
      val isk = HomeLinkHash.hmac(HomeLinkHash.sha256(ISK, *transcript), shared)
      return HomeLinkSession(sid, HomeLinkHash.hmac(isk, HomeLinkHash.utf8("to-inviter")), HomeLinkHash.hmac(isk, HomeLinkHash.utf8("to-joiner")))
    }
  }
}
