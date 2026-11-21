/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.ProviderException
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * This Portal's own device key (calling-phase-2.md, "Portal-only device keys"): ECDSA P-256 made
 * in the Android Keystore, non-exportable, sign-only. Phones never get it, so it is what lets
 * another Portal tell a real Portal from a phone that holds the household key.
 *
 * Sign-only because Keystore ECDH (`PURPOSE_AGREE_KEY`) needs API 31 and Portals run Android 9/10:
 * the vault exchange ([HomeLinkSyncWire]) uses fresh in-memory ECDH keys and *signs* them with
 * this key instead (signed Diffie–Hellman), which also gives forward secrecy.
 *
 * Asks for key attestation (API 24+) so a later pinning step can check the key is hardware-backed;
 * a Keystore that refuses attestation still gets a key, [hardwareBacked] says what it is. Holds
 * no exportable secret; logs nothing but failures.
 */
internal class PortalDeviceKey private constructor(private val private: PrivateKey, override val publicKey: String,
                                                   val hardwareBacked: Boolean) : HomeLinkSigner {
  val kid: String = HomeLinkIdentity.fingerprint(publicKey)

  override fun sign(data: ByteArray): String =
      HomeLinkHash.b64(Signature.getInstance(SIGNATURE).run { initSign(private); update(data); sign() })

  override fun toString(): String = "PortalDeviceKey($kid, hardware=$hardwareBacked)"

  companion object {
    private const val TAG = "HearthpanePortalKey"
    private const val STORE = "AndroidKeyStore"
    private const val ALIAS = "hearthpane.portal.device.v1"
    private const val SIGNATURE = "SHA256withECDSA"
    private val CHALLENGE = HomeLinkHash.utf8("hearthpane-portal-device-v1")
    @Volatile private var shared: PortalDeviceKey? = null

    /** This Portal's key, made on first use. Null if the Keystore can't make or load one (sync stays off). */
    fun get(): PortalDeviceKey? = shared ?: synchronized(this) {
      shared ?: runCatching { loadOrCreate() }.onFailure { Log.w(TAG, "no device key: ${it.javaClass.simpleName}") }
          .getOrNull()?.also { shared = it }
    }

    private fun loadOrCreate(): PortalDeviceKey {
      val ks = KeyStore.getInstance(STORE).apply { load(null) }
      if (!ks.containsAlias(ALIAS)) create()
      val private = ks.getKey(ALIAS, null) as PrivateKey
      val public = HomeLinkIdentity.encodePublic(ks.getCertificate(ALIAS).publicKey)
      val info = KeyFactory.getInstance(private.algorithm, STORE).getKeySpec(private, KeyInfo::class.java)
      return PortalDeviceKey(private, public, info.isInsideSecureHardware)
    }

    private fun create() {
      try {
        generate(attest = true)
      } catch (e: ProviderException) {
        Log.i(TAG, "attestation unavailable, plain Keystore key")
        generate(attest = false)
      }
    }

    private fun generate(attest: Boolean) {
      val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
          .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
          .setDigests(KeyProperties.DIGEST_SHA256)
          .apply { if (attest) setAttestationChallenge(CHALLENGE) }
          .build()
      KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, STORE).apply { initialize(spec) }.generateKeyPair()
    }
  }
}
