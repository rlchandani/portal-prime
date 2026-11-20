/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Seals the reminders replica for the Portal↔Portal exchange, so private titles never cross the
 * LAN in the clear: AES-256-GCM under `HMAC-SHA256(householdKey, "hearthpane-reminders-seal|v1")`,
 * a random 96-bit IV, and AAD binding the exchange nonce and direction (a sealed request can't be
 * replayed as a reply, or into another exchange). Wire form: hex(`iv ‖ ciphertext+tag`). Pure JVM.
 */
internal object ReminderSeal {
  private const val IV_BYTES = 12
  private const val TAG_BITS = 128
  private const val MAX_HEX = 2 * 1024 * 1024
  const val REQUEST = "req"
  const val RESPONSE = "resp"
  private val random = SecureRandom()

  fun key(householdKey: ByteArray): ByteArray =
      Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(householdKey, "HmacSHA256")) }
          .doFinal("hearthpane-reminders-seal|v1".toByteArray(Charsets.UTF_8))

  fun seal(householdKey: ByteArray, nonce: String, direction: String, plain: String): String {
    val iv = ByteArray(IV_BYTES).also(random::nextBytes)
    val c = cipher(Cipher.ENCRYPT_MODE, householdKey, iv, nonce, direction)
    return HouseholdKey.toHex(iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)))
  }

  /** The plaintext, or null for anything malformed, tampered, sealed for another nonce/direction or key. */
  fun open(householdKey: ByteArray, nonce: String, direction: String, sealedHex: String?): String? {
    if (sealedHex == null || sealedHex.length > MAX_HEX) return null
    val bytes = HouseholdKey.fromHex(sealedHex) ?: return null
    if (bytes.size < IV_BYTES + TAG_BITS / 8) return null
    val iv = bytes.copyOfRange(0, IV_BYTES)
    return try {
      String(cipher(Cipher.DECRYPT_MODE, householdKey, iv, nonce, direction).doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    } catch (_: GeneralSecurityException) {
      null // bad tag (tampered / wrong key / wrong nonce)
    } catch (_: IllegalArgumentException) {
      null
    }
  }

  private fun cipher(mode: Int, householdKey: ByteArray, iv: ByteArray, nonce: String, direction: String): Cipher =
      Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(key(householdKey), "AES"), GCMParameterSpec(TAG_BITS, iv))
        updateAAD("hearthpane-reminders|$nonce|$direction".toByteArray(Charsets.UTF_8))
      }
}
