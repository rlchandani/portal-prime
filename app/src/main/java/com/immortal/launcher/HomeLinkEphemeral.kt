/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.security.GeneralSecurityException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyAgreement

/**
 * A one-exchange P-256 ECDH key, in memory only: the Diffie–Hellman half of a signed vault
 * exchange ([HomeLinkSyncWire]). Never stored, so a later key compromise can't open a recorded
 * exchange. Holds a private key: never log it.
 */
internal class HomeLinkEphemeral private constructor(private val pair: KeyPair) {
  val publicKey: String = HomeLinkIdentity.encodePublic(pair.public)

  /** The raw ECDH secret with [theirKey], or null if it isn't a valid P-256 key (or is ours). */
  fun agree(theirKey: String): ByteArray? {
    if (theirKey == publicKey) return null
    val theirs = HomeLinkIdentity.decodePublic(theirKey) ?: return null
    return try {
      KeyAgreement.getInstance("ECDH").run { init(pair.private); doPhase(theirs, true); generateSecret() }
    } catch (e: GeneralSecurityException) { null }
  }

  override fun toString(): String = "HomeLinkEphemeral(${HomeLinkIdentity.fingerprint(publicKey)})"

  companion object {
    fun generate(random: SecureRandom): HomeLinkEphemeral =
        HomeLinkEphemeral(KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair())
  }
}
