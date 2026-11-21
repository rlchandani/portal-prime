/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Small hashing helpers shared by the home-link files (calls between homes, phase 2 slice S1).
 * Every multi-part input is length-prefixed so no two different part lists hash the same.
 */
internal object HomeLinkHash {
  fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(join(*parts))

  fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray =
      Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(join(*parts)) }

  /** Length-prefixed concatenation (4-byte big-endian length before each part). */
  fun join(vararg parts: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    for (p in parts) {
      out.write(p.size ushr 24); out.write(p.size ushr 16); out.write(p.size ushr 8); out.write(p.size)
      out.write(p)
    }
    return out.toByteArray()
  }

  fun utf8(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

  fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

  fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

  /** Strict standard Base64, or null. */
  fun unb64(text: String): ByteArray? = try { Base64.getDecoder().decode(text) } catch (e: IllegalArgumentException) { null }

  /** Constant-time equality for MACs and commitments. */
  fun same(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}
