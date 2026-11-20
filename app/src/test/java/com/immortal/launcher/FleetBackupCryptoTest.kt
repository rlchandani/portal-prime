/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The fleet backup seal: only the backup key opens it, and any change to it is refused. */
class FleetBackupCryptoTest {
  private val header = """{"format":"hearthpane-sealed-backup","version":1,"alg":"RSA-OAEP-256/A256GCM/gzip","device":"d"}"""
      .toByteArray()
  private val plain = """{"secret":"household-key-value"}""".toByteArray()

  private fun code(block: () -> Unit): String {
    try {
      block()
    } catch (e: FleetBackupCrypto.SealException) {
      return e.code
    }
    fail("expected a SealException")
    return ""
  }

  private fun copy(e: FleetBackupCrypto.Envelope, header: ByteArray = e.header, wrapped: ByteArray = e.wrappedKey,
      nonce: ByteArray = e.nonce, body: ByteArray = e.ciphertext) = FleetBackupCrypto.Envelope(header, wrapped, nonce, body)

  @Test fun sealedArchiveOpensWithTheBackupKeyOnly() {
    val sealed = FleetBackupCrypto.seal(plain, header, KEYS.public)
    assertArrayEquals(plain, FleetBackupCrypto.open(sealed, KEYS.private, 1024))
    assertEquals("wrong_key", code { FleetBackupCrypto.open(sealed, OTHER.private, 1024) })
  }

  @Test fun theCiphertextNeverCarriesThePlaintext() {
    val sealed = FleetBackupCrypto.seal(plain, header, KEYS.public)
    val wire = sealed.toJson().toString()
    assertFalse(wire.contains("household-key-value"))
    assertFalse(String(sealed.ciphertext, Charsets.ISO_8859_1).contains("household"))
  }

  @Test fun everyDataKeyAndNonceIsFresh() {
    val a = FleetBackupCrypto.seal(plain, header, KEYS.public)
    val b = FleetBackupCrypto.seal(plain, header, KEYS.public)
    assertFalse(a.nonce.contentEquals(b.nonce))
    assertFalse(a.ciphertext.contentEquals(b.ciphertext))
  }

  @Test fun aChangedHeaderBreaksTheSeal() {
    val sealed = FleetBackupCrypto.seal(plain, header, KEYS.public)
    val forged = String(header).replace("\"d\"", "\"e\"").toByteArray()
    assertEquals("seal_broken", code { FleetBackupCrypto.open(copy(sealed, header = forged), KEYS.private, 1024) })
  }

  @Test fun aChangedCiphertextBreaksTheSeal() {
    val sealed = FleetBackupCrypto.seal(plain, header, KEYS.public)
    val body = sealed.ciphertext.copyOf().also { it[3] = (it[3].toInt() xor 1).toByte() }
    assertEquals("seal_broken", code { FleetBackupCrypto.open(copy(sealed, body = body), KEYS.private, 1024) })
  }

  @Test fun aTruncatedOrSwappedWrappedKeyIsRefused() {
    val sealed = FleetBackupCrypto.seal(plain, header, KEYS.public)
    assertEquals("wrong_key", code { FleetBackupCrypto.open(copy(sealed, wrapped = sealed.wrappedKey.copyOf(10)), KEYS.private, 1024) })
    val other = FleetBackupCrypto.seal(plain, header, KEYS.public)
    assertEquals("seal_broken", code { FleetBackupCrypto.open(copy(sealed, wrapped = other.wrappedKey), KEYS.private, 1024) })
  }

  @Test fun inflatingPastTheCapIsRefused() {
    val big = ByteArray(200_000) // compresses to a few hundred bytes
    val sealed = FleetBackupCrypto.seal(big, header, KEYS.public)
    assertTrue(sealed.ciphertext.size < 2_000)
    assertEquals("backup_too_large", code { FleetBackupCrypto.open(sealed, KEYS.private, 100_000) })
    assertEquals(200_000, FleetBackupCrypto.open(sealed, KEYS.private, 200_000).size)
  }

  @Test fun theEnvelopeRoundTripsThroughJson() {
    val sealed = FleetBackupCrypto.seal(plain, header, KEYS.public)
    val parsed = FleetBackupCrypto.parseEnvelope(JSONObject(sealed.toJson().toString()), 1 shl 20)
    assertArrayEquals(plain, FleetBackupCrypto.open(parsed, KEYS.private, 1024))
  }

  @Test fun malformedEnvelopesAreRefused() {
    val good = FleetBackupCrypto.seal(plain, header, KEYS.public).toJson()
    assertEquals("bad_envelope", code { FleetBackupCrypto.parseEnvelope(null, 1 shl 20) })
    for (field in listOf("header", "wrappedKey", "nonce", "ciphertext")) {
      val missing = JSONObject(good.toString()).apply { remove(field) }
      assertEquals(field, "bad_envelope", code { FleetBackupCrypto.parseEnvelope(missing, 1 shl 20) })
    }
    assertEquals("bad_envelope", code { FleetBackupCrypto.parseEnvelope(JSONObject(good.toString()).put("version", 2), 1 shl 20) })
    assertEquals("bad_envelope", code { FleetBackupCrypto.parseEnvelope(JSONObject(good.toString()).put("nonce", "%%%"), 1 shl 20) })
    assertEquals("backup_too_large", code { FleetBackupCrypto.parseEnvelope(good, 4) })
    val oldAlg = JSONObject(good.toString()).put("header", b64("""{"format":"hearthpane-sealed-backup","version":1,"alg":"none"}"""))
    assertEquals("bad_header", code { FleetBackupCrypto.parseEnvelope(oldAlg, 1 shl 20) })
  }

  @Test fun onlyRsaKeysOf3072To4096BitsAreAccepted() {
    FleetBackupCrypto.parsePublicKey(KEYS.public.encoded)
    val small = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    assertEquals("bad_public_key_size", code { FleetBackupCrypto.parsePublicKey(small.public.encoded) })
    val ec = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    assertEquals("bad_public_key", code { FleetBackupCrypto.parsePublicKey(ec.public.encoded) })
    assertEquals("bad_public_key", code { FleetBackupCrypto.parsePublicKey(ByteArray(40)) })
  }

  @Test fun theKeyIdIsAStableShortDigest() {
    val id = FleetBackupCrypto.keyId(KEYS.public.encoded)
    assertEquals(16, id.length)
    assertEquals(id, FleetBackupCrypto.keyId(KEYS.public.encoded.copyOf()))
    assertNotEquals(id, FleetBackupCrypto.keyId(OTHER.public.encoded))
  }

  @Test fun anImportKeyIsAFresh3072BitPair() {
    val pair = FleetBackupCrypto.newImportKeyPair()
    FleetBackupCrypto.parsePublicKey(pair.public.encoded)
    val sealed = FleetBackupCrypto.seal(plain, header, pair.public)
    assertArrayEquals(plain, FleetBackupCrypto.open(sealed, pair.private, 1024))
  }

  /** A vector sealed by the Mac's Python helper opens here, so both sides agree on the format. */
  @Test fun aPythonSealedVectorOpens() {
    val vector = JSONObject(javaClass.classLoader!!.getResource("fleet_backup_python_vector.json")!!.readText())
    val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(vector.getString("privateKeyPkcs8"))))
    val envelope = FleetBackupCrypto.parseEnvelope(vector.getJSONObject("envelope"), 1 shl 20)
    val opened = FleetBackupCrypto.open(envelope, key, 1 shl 20)
    val archive = FleetBackupArchive.decode(opened) { _, v -> v }
    assertEquals("0123456789abcdef0123456789abcdef", archive.device)
    assertEquals(FleetBackupArchive.PrefValue.IntV(7), archive.prefs.getValue("immortal_screensaver").getValue("dark_room_lux"))
    assertEquals(FleetBackupArchive.PrefValue.FloatV(0.75f), archive.prefs.getValue("immortal_screensaver").getValue("scale"))
    assertEquals("{\"items\":[]}", String(archive.files.getValue("files/household_list.json")))
  }

  private fun b64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())

  companion object {
    private val KEYS: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
    private val OTHER: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
  }
}
