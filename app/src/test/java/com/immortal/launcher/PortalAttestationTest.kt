/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64

/**
 * The attestation chain check recorded at pinning, against real sample chains (resources/attestation,
 * from google/android-key-attestation): a TEE chain whose root is Google's RSA root, and a
 * StrongBox chain with a non-Google root. Absent or broken chains are recorded, never thrown.
 */
class PortalAttestationTest {
  private fun chain(level: String): List<ByteArray> = (0..3).map { i ->
    val path = "attestation/ec_${level}_$i.pem"
    val pem = requireNotNull(javaClass.classLoader!!.getResourceAsStream(path)) { "missing $path" }.use { String(it.readBytes()) }
    Base64.getMimeDecoder().decode(pem.substringAfter("-----BEGIN CERTIFICATE-----").substringBefore("-----END CERTIFICATE-----"))
  }

  private fun leafKey(chain: List<ByteArray>): String = HomeLinkHash.b64(
      java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(chain[0].inputStream()).publicKey.encoded)

  private val tee = chain("TEE")
  private val strongBox = chain("StrongBox")

  @Test fun aGoogleRootedTeeChainForThePinnedKeyIsTee() {
    assertEquals(PortalAttestationLevel.TEE, PortalAttestation.verify(tee, leafKey(tee)))
    assertTrue(PortalAttestationLevel.TEE.hardware)
  }

  @Test fun aChainForAnotherKeyIsInvalid() {
    val other = HomeLinkIdentity.generate(SecureRandom()).publicKey
    assertEquals(PortalAttestationLevel.INVALID, PortalAttestation.verify(tee, other))
  }

  @Test fun aWellFormedChainUnderAnotherRootIsRecordedAsSuch() {
    assertEquals(PortalAttestationLevel.UNTRUSTED_ROOT, PortalAttestation.verify(strongBox, leafKey(strongBox)))
    assertEquals(PortalAttestationLevel.UNTRUSTED_ROOT, PortalAttestation.verify(tee, leafKey(tee), roots = emptyList()))
  }

  @Test fun theChallengeMustMatchWhenGiven() {
    assertEquals(PortalAttestationLevel.INVALID, PortalAttestation.verify(tee, leafKey(tee), challenge = PortalDeviceKeyChallenge.bytes))
  }

  @Test fun noChainOrOneSelfSignedCertificateIsAbsent() {
    assertEquals(PortalAttestationLevel.ABSENT, PortalAttestation.verify(emptyList(), leafKey(tee)))
    assertEquals(PortalAttestationLevel.ABSENT, PortalAttestation.verify(tee.take(1), leafKey(tee)))
  }

  @Test fun brokenOrderedOrOversizedChainsAreInvalid() {
    val key = leafKey(tee)
    assertEquals(PortalAttestationLevel.INVALID, PortalAttestation.verify(listOf(tee[0], tee[2], tee[3]), key))
    assertEquals(PortalAttestationLevel.INVALID, PortalAttestation.verify(listOf(tee[0], strongBox[1], tee[2], tee[3]), key))
    assertEquals(PortalAttestationLevel.INVALID, PortalAttestation.verify(listOf(ByteArray(40) { 1 }, tee[1]), key))
    assertEquals(PortalAttestationLevel.INVALID, PortalAttestation.verify(tee + tee, key))
    assertEquals(PortalAttestationLevel.INVALID, PortalAttestation.verify(listOf(ByteArray(PortalAttestation.MAX_CERT_BYTES + 1), tee[1]), key))
    val flipped = tee[0].copyOf().also { it[it.size - 10] = (it[it.size - 10].toInt() xor 1).toByte() }
    assertEquals(PortalAttestationLevel.INVALID, PortalAttestation.verify(listOf(flipped) + tee.drop(1), key))
  }

  @Test fun theDerReaderRefusesOverrunsAndWrongTags() {
    assertThrows(IllegalArgumentException::class.java) { Der(byteArrayOf(0x04, 0x05, 1, 2)).next(Der.OCTET_STRING) }
    assertThrows(IllegalArgumentException::class.java) { Der(byteArrayOf(0x02, 0x01, 1)).next(Der.OCTET_STRING) }
    assertThrows(IllegalArgumentException::class.java) { Der(byteArrayOf(0x04, 0x84.toByte(), 0, 0, 0, 1)).next(Der.OCTET_STRING) }
    assertArrayEquals(byteArrayOf(9), Der(byteArrayOf(0x04, 0x81.toByte(), 1, 9)).next(Der.OCTET_STRING))
  }

  /** Google's roots are pinned as public keys; the list holds the RSA root and the 2025 EC root. */
  @Test fun theGoogleRootsAreRealPublicKeys() {
    assertEquals(2, PortalAttestation.GOOGLE_ROOTS.size)
    PortalAttestation.GOOGLE_ROOTS.forEach { b64 ->
      val spec = java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(b64))
      val alg = if (b64.startsWith("MIICIj")) "RSA" else "EC"
      assertNotNull(java.security.KeyFactory.getInstance(alg).generatePublic(spec))
    }
  }
}

/** The challenge a Portal's device key is made with (the same bytes as [PortalDeviceKey.CHALLENGE], which needs Android). */
private object PortalDeviceKeyChallenge {
  val bytes: ByteArray = HomeLinkHash.utf8("hearthpane-portal-device-v1")
}
