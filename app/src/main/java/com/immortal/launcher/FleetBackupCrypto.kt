/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * The seal on a fleet settings backup (`/backup/export`, `/backup/import`). Pure JVM.
 *
 * A fresh 256-bit data key encrypts the gzipped archive with AES-256-GCM; the header bytes are the
 * GCM associated data, so the device binding and key id can't be swapped. The data key is wrapped
 * with RSA-OAEP (SHA-256, MGF1-SHA-256) to the operator's backup public key, so only the Mac's
 * private key opens an export. RSA-OAEP is in every Android 10 provider; X25519 is not.
 *
 * Import reuses the same envelope: the Mac unwraps the data key and re-wraps only those 32 bytes to
 * a one-time key the Portal made for that import ([newImportKeyPair]), so neither the archive nor
 * its key is ever readable on the wire.
 */
internal object FleetBackupCrypto {
  const val FORMAT = "hearthpane-sealed-backup"
  const val VERSION = 1
  const val ALG = "RSA-OAEP-256/A256GCM/gzip"
  const val MIN_RSA_BITS = 3072
  const val MAX_RSA_BITS = 4096
  const val IMPORT_RSA_BITS = 3072
  private const val DATA_KEY_BYTES = 32
  private const val NONCE_BYTES = 12
  private const val TAG_BITS = 128

  private val random = SecureRandom()
  private val oaep = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)

  class SealException(val code: String) : Exception(code)

  /** The sealed parts. [header] is the exact authenticated byte string, kept as sent. */
  class Envelope(val header: ByteArray, val wrappedKey: ByteArray, val nonce: ByteArray, val ciphertext: ByteArray) {
    fun toJson(): JSONObject = JSONObject().put("format", FORMAT).put("version", VERSION)
        .put("header", b64(header)).put("wrappedKey", b64(wrappedKey))
        .put("nonce", b64(nonce)).put("ciphertext", b64(ciphertext))

    fun headerJson(): JSONObject = runCatching { JSONObject(String(header, Charsets.UTF_8)) }
        .getOrElse { throw SealException("bad_header") }
  }

  /** An operator backup key, checked: RSA, 3072–4096 bits, X.509 SubjectPublicKeyInfo. */
  fun parsePublicKey(spki: ByteArray): RSAPublicKey {
    val key = runCatching { KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(spki)) }
        .getOrNull() as? RSAPublicKey ?: throw SealException("bad_public_key")
    if (key.modulus.bitLength() !in MIN_RSA_BITS..MAX_RSA_BITS) throw SealException("bad_public_key_size")
    return key
  }

  /** A short, non-secret identifier for a public key: the first 16 hex of SHA-256(SPKI). */
  fun keyId(spki: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(spki)).take(16)

  fun seal(plaintext: ByteArray, header: ByteArray, recipient: PublicKey): Envelope {
    val dataKey = ByteArray(DATA_KEY_BYTES).also(random::nextBytes)
    try {
      val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
      val body = gcm(Cipher.ENCRYPT_MODE, dataKey, nonce, header).doFinal(gzip(plaintext))
      return Envelope(header, wrap(dataKey, recipient), nonce, body)
    } finally {
      dataKey.fill(0)
    }
  }

  /** Opens [envelope] with [key]; the result is at most [maxPlain] bytes after inflating. */
  fun open(envelope: Envelope, key: PrivateKey, maxPlain: Int): ByteArray {
    val dataKey = unwrap(envelope.wrappedKey, key)
    try {
      if (envelope.nonce.size != NONCE_BYTES) throw SealException("bad_nonce")
      val packed = try {
        gcm(Cipher.DECRYPT_MODE, dataKey, envelope.nonce, envelope.header).doFinal(envelope.ciphertext)
      } catch (_: GeneralSecurityException) {
        throw SealException("seal_broken")
      }
      return gunzip(packed, maxPlain)
    } finally {
      dataKey.fill(0)
    }
  }

  /** The envelope as sent, every field present, base64 decoded, within [maxBytes] of ciphertext. */
  fun parseEnvelope(json: JSONObject?, maxBytes: Int): Envelope {
    if (json == null || json.optString("format") != FORMAT || json.opt("version") != VERSION) {
      throw SealException("bad_envelope")
    }
    fun field(name: String, max: Int): ByteArray {
      val text = json.opt(name) as? String ?: throw SealException("bad_envelope")
      if (text.length > (max / 3 + 1) * 4) throw SealException("backup_too_large")
      return runCatching { Base64.getDecoder().decode(text) }.getOrNull() ?: throw SealException("bad_envelope")
    }
    val envelope = Envelope(field("header", 4096), field("wrappedKey", 1024), field("nonce", 64), field("ciphertext", maxBytes))
    val header = envelope.headerJson()
    if (header.optString("format") != FORMAT || header.opt("version") != VERSION || header.optString("alg") != ALG) {
      throw SealException("bad_header")
    }
    return envelope
  }

  fun newImportKeyPair(): KeyPair =
      KeyPairGenerator.getInstance("RSA").apply { initialize(IMPORT_RSA_BITS, random) }.generateKeyPair()

  private fun wrap(dataKey: ByteArray, recipient: PublicKey): ByteArray =
      Cipher.getInstance("RSA/ECB/OAEPPadding").apply { init(Cipher.ENCRYPT_MODE, recipient, oaep) }.doFinal(dataKey)

  private fun unwrap(wrapped: ByteArray, key: PrivateKey): ByteArray {
    val raw = try {
      Cipher.getInstance("RSA/ECB/OAEPPadding").apply { init(Cipher.DECRYPT_MODE, key, oaep) }.doFinal(wrapped)
    } catch (_: GeneralSecurityException) {
      throw SealException("wrong_key")
    }
    if (raw.size != DATA_KEY_BYTES) {
      raw.fill(0)
      throw SealException("wrong_key")
    }
    return raw
  }

  private fun gcm(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher =
      Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        updateAAD(aad)
      }

  private fun gzip(data: ByteArray): ByteArray =
      ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(data) } }.toByteArray()

  /** Inflates with a hard cap, so a small authenticated body can't expand without bound. */
  private fun gunzip(data: ByteArray, maxPlain: Int): ByteArray {
    val out = ByteArrayOutputStream()
    try {
      GZIPInputStream(ByteArrayInputStream(data)).use { input ->
        val buf = ByteArray(16 * 1024)
        while (true) {
          val n = input.read(buf)
          if (n < 0) break
          if (out.size() + n > maxPlain) throw SealException("backup_too_large")
          out.write(buf, 0, n)
        }
      }
    } catch (e: SealException) {
      throw e
    } catch (_: Exception) {
      throw SealException("bad_archive")
    }
    return out.toByteArray()
  }

  fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

  fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
