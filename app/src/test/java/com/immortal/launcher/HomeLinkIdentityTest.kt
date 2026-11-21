/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** A household's identity key: P-256 (in every Android 9/10 provider), sign, verify, link key. */
class HomeLinkIdentityTest {
  private val random = SecureRandom()
  private val maple = HomeLinkIdentity.generate(random)
  private val lake = HomeLinkIdentity.generate(random)

  @Test fun publicKeyRoundTrips() {
    val decoded = HomeLinkIdentity.decodePublic(maple.publicKey)
    assertNotNull(decoded)
    assertEquals(maple.publicKey, HomeLinkIdentity.encodePublic(decoded!!))
  }

  @Test fun privateKeyRoundTripsForStorage() {
    val restored = HomeLinkIdentity.restore(maple.publicKey, maple.encodePrivate())
    assertNotNull(restored)
    assertTrue(HomeLinkIdentity.verify(maple.publicKey, "hi".toByteArray(), restored!!.sign("hi".toByteArray())))
  }

  @Test fun restoreRejectsAPrivateKeyFromAnotherHome() {
    assertNull(HomeLinkIdentity.restore(maple.publicKey, lake.encodePrivate()))
    assertNull(HomeLinkIdentity.restore(maple.publicKey, "not a key"))
  }

  @Test fun decodeRejectsGarbageAndOtherCurves() {
    assertNull(HomeLinkIdentity.decodePublic(""))
    assertNull(HomeLinkIdentity.decodePublic("%%%"))
    assertNull(HomeLinkIdentity.decodePublic(Base64.getEncoder().encodeToString(ByteArray(91))))
    val p384 = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp384r1")) }.generateKeyPair()
    assertNull(HomeLinkIdentity.decodePublic(Base64.getEncoder().encodeToString(p384.public.encoded)))
    val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
    assertNull(HomeLinkIdentity.decodePublic(Base64.getEncoder().encodeToString(rsa.public.encoded)))
  }

  @Test fun signaturesVerifyOnlyForTheSignerAndTheExactBytes() {
    val data = "offer|sha-256 AA".toByteArray()
    val sig = maple.sign(data)
    assertTrue(HomeLinkIdentity.verify(maple.publicKey, data, sig))
    assertFalse(HomeLinkIdentity.verify(lake.publicKey, data, sig))
    assertFalse(HomeLinkIdentity.verify(maple.publicKey, "offer|sha-256 AB".toByteArray(), sig))
    assertFalse(HomeLinkIdentity.verify(maple.publicKey, data, "bm9wZQ=="))
    assertFalse(HomeLinkIdentity.verify("junk", data, sig))
  }

  @Test fun bothHomesDeriveTheSameLinkKey() {
    val atMaple = maple.linkKey(lake.publicKey, inviterKey = maple.publicKey)
    val atLake = lake.linkKey(maple.publicKey, inviterKey = maple.publicKey)
    assertNotNull(atMaple)
    assertEquals(32, atMaple!!.size)
    assertArrayEquals(atMaple, atLake)
  }

  @Test fun linkKeyDiffersPerPairAndRejectsBadKeys() {
    val third = HomeLinkIdentity.generate(random)
    val a = maple.linkKey(lake.publicKey, maple.publicKey)!!
    val b = maple.linkKey(third.publicKey, maple.publicKey)!!
    assertFalse(a.contentEquals(b))
    assertNull(maple.linkKey("junk", maple.publicKey))
  }

  @Test fun fingerprintIsShortStableHex() {
    val fp = HomeLinkIdentity.fingerprint(maple.publicKey)
    assertTrue(fp.matches(Regex("[0-9a-f]{16}")))
    assertEquals(fp, HomeLinkIdentity.fingerprint(maple.publicKey))
    assertNotEquals(fp, HomeLinkIdentity.fingerprint(lake.publicKey))
  }
}
