/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** What a Portal needs to join an intercom session: the shared [HouseholdKey] and its own id. */
internal class IntercomCredentials(val key: ByteArray, val deviceId: String) {
  init {
    require(HouseholdKey.isValidKey(key)) { "invalid household key" }
    require(IntercomAuth.isValidDeviceId(deviceId)) { "invalid device id" }
  }

  companion object {
    /** Shown instead of starting when [load] returns null. */
    const val SETUP_MESSAGE = "Set up the household from Portal Toolkit first."

    /** Null until the phone provisions a household key: Intercom then refuses to run. */
    fun load(context: Context): IntercomCredentials? {
      val key = HouseholdKey.get(context) ?: return null
      val id = runCatching { RemoteCompanionStore.id(context) }.getOrNull() ?: return null
      return runCatching { IntercomCredentials(key, id) }.getOrNull()
    }
  }
}

/**
 * The intercom's household proofs, all HMAC-SHA256 under the [HouseholdKey] with the
 * [LABEL] and a role byte for domain separation (a listener proof can never pass as the
 * broadcaster's, nor as any [HouseholdMessage] signature, which signs ASCII text):
 *
 * - listener proof = HMAC(K, LABEL || LISTENER || broadcasterNonce || listenerNonce || listenerId)
 * - broadcaster proof = HMAC(K, LABEL || BROADCASTER || listenerNonce || broadcasterNonce)
 *
 * Both nonces are fresh per connection, so recorded proofs are useless on any later connection.
 * The audio key is HKDF-SHA256 (RFC 5869) of K, salted with both nonces, so it's unique per session.
 */
internal object IntercomAuth {
  const val NONCE_SIZE = 16
  const val MAC_SIZE = 32
  const val SESSION_KEY_SIZE = 32
  const val MAX_DEVICE_ID = 64
  private const val LABEL = "intercom-v1"
  private const val LISTENER: Byte = 1
  private const val BROADCASTER: Byte = 2
  private const val AUDIO_INFO = "intercom-v1/audio"
  private const val HMAC = "HmacSHA256"
  private val DEVICE_ID = Regex("^[A-Za-z0-9_-]{1,$MAX_DEVICE_ID}$")

  fun isValidDeviceId(id: String): Boolean = DEVICE_ID.matches(id)

  fun listenerProof(key: ByteArray, broadcasterNonce: ByteArray, listenerNonce: ByteArray, listenerId: String) =
      hmac(key, prefix(LISTENER) + broadcasterNonce + listenerNonce + listenerId.toByteArray(Charsets.US_ASCII))

  fun broadcasterProof(key: ByteArray, broadcasterNonce: ByteArray, listenerNonce: ByteArray) =
      hmac(key, prefix(BROADCASTER) + listenerNonce + broadcasterNonce)

  /** Constant-time comparison, as in [HouseholdMessage.decode]. */
  fun matches(expected: ByteArray, given: ByteArray): Boolean = MessageDigest.isEqual(expected, given)

  fun sessionKey(key: ByteArray, broadcasterNonce: ByteArray, listenerNonce: ByteArray, listenerId: String): ByteArray {
    val prk = hmac(broadcasterNonce + listenerNonce, key) // HKDF-Extract(salt, ikm)
    val info = AUDIO_INFO.toByteArray(Charsets.US_ASCII) + listenerId.toByteArray(Charsets.US_ASCII)
    return hmac(prk, info + byteArrayOf(1)).copyOf(SESSION_KEY_SIZE) // HKDF-Expand, one block
  }

  private fun prefix(role: Byte): ByteArray = LABEL.toByteArray(Charsets.US_ASCII) + role

  private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
      Mac.getInstance(HMAC).apply { init(SecretKeySpec(key, HMAC)) }.doFinal(data)
}
