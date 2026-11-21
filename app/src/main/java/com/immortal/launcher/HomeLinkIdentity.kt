/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/**
 * A household's identity key for calls between homes: ECDSA/ECDH on P-256, because X25519 and
 * Ed25519 are not in the Android 9/10 providers (see [FleetBackupCrypto]). Every Portal in a home
 * shares one identity (slice S3); a linked home pins its public half after the safety-picture
 * check, and from then on every cross-home offer/answer is signed with it, binding the DTLS
 * fingerprint end to end like [CallSignalAuth] does with the household key on the LAN.
 *
 * Public keys travel as Base64 X.509 (SubjectPublicKeyInfo). Holds the private key: never log it.
 */
internal class HomeLinkIdentity private constructor(private val pair: KeyPair) : HomeLinkSigner {
  override val publicKey: String = encodePublic(pair.public)

  override fun sign(data: ByteArray): String =
      HomeLinkHash.b64(Signature.getInstance(SIGNATURE).run { initSign(pair.private); update(data); sign() })

  /** PKCS#8, Base64, for the encrypted on-device store only. */
  fun encodePrivate(): String = HomeLinkHash.b64(pair.private.encoded)

  /**
   * The symmetric key this home shares with one linked home: ECDH, then HMAC-based extraction
   * salted with both public keys in fixed (inviter first) order so both sides agree. Null if
   * [theirKey] isn't a valid P-256 key.
   */
  fun linkKey(theirKey: String, inviterKey: String): ByteArray? {
    val theirs = decodePublic(theirKey) ?: return null
    val secret = try {
      KeyAgreement.getInstance("ECDH").run { init(pair.private); doPhase(theirs, true); generateSecret() }
    } catch (e: GeneralSecurityException) { return null }
    val joinerKey = if (inviterKey == publicKey) theirKey else publicKey
    val salt = HomeLinkHash.sha256(HomeLinkHash.utf8(inviterKey), HomeLinkHash.utf8(joinerKey))
    return HomeLinkHash.hmac(HomeLinkHash.hmac(salt, secret), LINK_INFO)
  }

  companion object {
    private const val CURVE = "secp256r1"
    private const val SIGNATURE = "SHA256withECDSA"
    private const val FINGERPRINT_BYTES = 8
    /** Base64 of a P-256 SubjectPublicKeyInfo is 124 characters; allow a little slack. */
    private const val MAX_KEY_CHARS = 200
    private val P256: ECParameterSpec by lazy {
      AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec(CURVE)) }.getParameterSpec(ECParameterSpec::class.java)
    }
    private val LINK_INFO = HomeLinkHash.utf8("hearthpane-homelink-link-v1")

    fun generate(random: SecureRandom): HomeLinkIdentity =
        HomeLinkIdentity(KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(CURVE), random) }.generateKeyPair())

    /** Rebuilds a stored identity; null unless the private key belongs to [publicKey]. */
    fun restore(publicKey: String, privateKey: String): HomeLinkIdentity? {
      val pub = decodePublic(publicKey) ?: return null
      val bytes = HomeLinkHash.unb64(privateKey) ?: return null
      val priv: PrivateKey = try {
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(bytes))
      } catch (e: GeneralSecurityException) { return null }
      val identity = HomeLinkIdentity(KeyPair(pub, priv))
      val probe = HomeLinkHash.utf8("hearthpane-homelink-restore")
      return identity.takeIf { verify(publicKey, probe, it.sign(probe)) }
    }

    fun encodePublic(key: PublicKey): String = HomeLinkHash.b64(key.encoded)

    /** A P-256 public key, or null for anything else (garbage, other curves, RSA). */
    fun decodePublic(text: String): PublicKey? {
      if (text.isEmpty() || text.length > MAX_KEY_CHARS) return null
      val bytes = HomeLinkHash.unb64(text) ?: return null
      val key = try {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
      } catch (e: GeneralSecurityException) { return null } catch (e: IllegalArgumentException) { return null }
      return (key as? ECPublicKey)?.takeIf { it.params.curve == P256.curve && it.params.order == P256.order }
    }

    fun verify(publicKey: String, data: ByteArray, signature: String): Boolean {
      val key = decodePublic(publicKey) ?: return false
      val sig = HomeLinkHash.unb64(signature) ?: return false
      return try {
        Signature.getInstance(SIGNATURE).run { initVerify(key); update(data); verify(sig) }
      } catch (e: GeneralSecurityException) { false }
    }

    /** A short, stable id for a linked home (and its row key): 64 bits of its key's hash, hex. */
    fun fingerprint(publicKey: String): String =
        HomeLinkHash.hex(HomeLinkHash.sha256(HomeLinkHash.utf8(publicKey))).take(FINGERPRINT_BYTES * 2)
  }
}
