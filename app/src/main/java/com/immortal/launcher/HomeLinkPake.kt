/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.SecureRandom
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
 * 1. Both sides map (secret, mailbox) to a curve point `G` whose discrete log nobody knows, with
 *    RFC 9380 hash_to_curve (P256_XMD:SHA-256_SSWU_RO_, [HomeLinkHashToCurve]) under [DST].
 * 2. Each picks a random scalar `y` and sends the x-coordinate of `y·G` (its share). A share is
 *    a uniformly random point whatever the secret, so it tells an eavesdropper nothing.
 * 3. Each computes `K = x(y_mine · Y_theirs)`; both get `x(y_i·y_j·G)` only if both used the same
 *    secret. Checking a guess against a recorded transcript needs `y_i` or `y_j` (Diffie–Hellman
 *    hardness), so the only way to test a guess is to take part, once per join attempt.
 *
 * x-only shares work because `x(k·P) = x(k·(−P))`. A received share is validated before use
 * ([decodeShare], [finish]): exactly 32 bytes in canonical Base64, an x below p that lifts to a
 * finite point on the curve (an x-only share can't spell infinity, and P-256's cofactor is 1, so
 * that point is in the group). The secret-scalar multiplications go through the platform's ECDH
 * ([KeyAgreement]), never through [BigInteger]; the hash to the curve is BigInteger arithmetic
 * (timing caveat in [HomeLinkHashToCurve]). Pure apart from the [SecureRandom] the caller injects.
 */
internal object HomeLinkPake {
  const val SHARE_BYTES = 32
  /** RFC 9380 domain separation tag for linking homes: this protocol, its version, and the suite ([HomeLinkDomain.pakeDst]). */
  val DST: ByteArray get() = HomeLinkDomain.HOME.pakeDst
  private val CURVE: ECParameterSpec get() = HomeLinkP256.CURVE
  private val PRIME: BigInteger get() = HomeLinkP256.PRIME
  private val SQRT_EXP: BigInteger by lazy { PRIME.add(BigInteger.ONE).shiftRight(2) }

  /** This side's half for a pairing in [mailbox] with [secret] (the code's secret part), in [domain]. */
  fun start(secret: String, mailbox: String, random: SecureRandom, domain: HomeLinkDomain = HomeLinkDomain.HOME): HomeLinkPakeHalf {
    val g = generator(secret, mailbox, domain)
    while (true) {
      val scalar = BigInteger(CURVE.order.bitLength(), random)
      if (scalar.signum() == 0 || scalar >= CURVE.order) continue
      val x = multiply(scalar, g) ?: continue
      return HomeLinkPakeHalf(scalar, HomeLinkHash.b64(x))
    }
  }

  /** The shared point's x-coordinate, or null for a share that isn't a curve point (or is ours reflected back). */
  fun finish(mine: HomeLinkPakeHalf, theirShare: String): ByteArray? {
    val point = decodeShare(theirShare) ?: return null
    if (point.affineX == decodeShare(mine.share)?.affineX) return null
    return finish(mine, point)
  }

  /** [finish] for an already-decoded point: refuses infinity and anything off the curve or out of range. */
  fun finish(mine: HomeLinkPakeHalf, point: ECPoint): ByteArray? =
      if (HomeLinkP256.isValidPoint(point)) multiply(mine.scalar, point) else null

  /** A share on the wire: the canonical Base64 of exactly 32 bytes naming the x-coordinate of a curve point. */
  fun decodeShare(share: String): ECPoint? {
    val bytes = HomeLinkHash.unb64(share)?.takeIf { it.size == SHARE_BYTES } ?: return null
    if (HomeLinkHash.b64(bytes) != share) return null
    return lift(BigInteger(1, bytes))?.takeIf(HomeLinkP256::isValidPoint)
  }

  /** The password-derived base point. Visible for tests (it is not secret-free: never send it). */
  internal fun generator(secret: String, mailbox: String, domain: HomeLinkDomain = HomeLinkDomain.HOME): ECPoint {
    val g = HomeLinkHashToCurve.hashToCurve(HomeLinkHash.join(HomeLinkHash.utf8(secret), HomeLinkHash.utf8(mailbox)), domain.pakeDst)
    // Q0 + Q1 = infinity has probability about 2^-256.
    check(HomeLinkP256.isValidPoint(g)) { "hash_to_curve gave no usable point" }
    return g
  }

  /** The curve point with this x (either y; x-only arithmetic doesn't care), or null if none. Public input only. */
  private fun lift(x: BigInteger): ECPoint? {
    if (x >= PRIME) return null
    val rhs = HomeLinkP256.rhs(x)
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
internal class HomeLinkSession private constructor(val sid: String, val domain: HomeLinkDomain, private val toInviter: ByteArray,
                                                   private val toJoiner: ByteArray) {
  fun macKey(towardInviter: Boolean): ByteArray = (if (towardInviter) toInviter else toJoiner).copyOf()

  override fun toString(): String = "HomeLinkSession(sid=$sid)"

  companion object {
    fun derive(mailbox: String, inviterShare: String, joinerShare: String, shared: ByteArray,
               domain: HomeLinkDomain = HomeLinkDomain.HOME): HomeLinkSession {
      val transcript = arrayOf(HomeLinkHash.utf8(mailbox), HomeLinkHash.utf8(inviterShare), HomeLinkHash.utf8(joinerShare))
      val sid = HomeLinkHash.b64(HomeLinkHash.sha256(domain.sidLabel, *transcript))
      val isk = HomeLinkHash.hmac(HomeLinkHash.sha256(domain.iskLabel, *transcript), shared)
      return HomeLinkSession(sid, domain, HomeLinkHash.hmac(isk, HomeLinkHash.utf8("to-inviter")),
          HomeLinkHash.hmac(isk, HomeLinkHash.utf8("to-joiner")))
    }
  }
}
