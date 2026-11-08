/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OtaIntegrityTest {
  private val pinned = OtaConstants.PINNED_SIGNER_SHA256
  private val manifest = OtaManifest(
      packageName = "com.immortal.launcher.debug", versionCode = 224, versionName = "1.229",
      sha256 = "ab".repeat(32), size = 100, apkUrl = "https://github.com/x", minSdk = 24, notes = "")
  private val facts = OtaArchiveFacts("com.immortal.launcher.debug", 224, listOf(pinned))

  @Test fun sha256MatchesTheStandardVector() {
    assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        OtaIntegrity.sha256Hex("abc".toByteArray()))
  }

  @Test fun pinnedDigestIsTheReleaseSigner() {
    assertEquals("4f9a3b1d0c86028e831add351642a6167d06e9d3ed6ded6dcf5a790a560b45d6", pinned)
  }

  @Test fun downloadMustMatchSizeAndSha() {
    assertNull(OtaIntegrity.checkDownload(manifest, 100, "ab".repeat(32)))
    assertNull(OtaIntegrity.checkDownload(manifest, 100, "AB".repeat(32)))
    assertEquals(OtaError.INTEGRITY, OtaIntegrity.checkDownload(manifest, 99, "ab".repeat(32)))
    assertEquals(OtaError.INTEGRITY, OtaIntegrity.checkDownload(manifest, 100, "cd".repeat(32)))
  }

  @Test fun archiveMustMatchPackageVersionAndSigner() {
    assertNull(OtaIntegrity.checkArchive(manifest, facts))
    assertNull(OtaIntegrity.checkArchive(manifest, facts.copy(signerSha256 = listOf(pinned.uppercase()))))
    assertEquals(OtaError.INTEGRITY, OtaIntegrity.checkArchive(manifest, null))
    assertEquals(OtaError.INTEGRITY, OtaIntegrity.checkArchive(manifest, facts.copy(packageName = "com.other")))
    assertEquals(OtaError.INTEGRITY, OtaIntegrity.checkArchive(manifest, facts.copy(packageName = null)))
    assertEquals(OtaError.INTEGRITY, OtaIntegrity.checkArchive(manifest, facts.copy(versionCode = 223)))
  }

  @Test fun anyForeignSignerIsRejected() {
    assertEquals(OtaError.SIGNATURE, OtaIntegrity.checkArchive(manifest, facts.copy(signerSha256 = emptyList())))
    assertEquals(OtaError.SIGNATURE,
        OtaIntegrity.checkArchive(manifest, facts.copy(signerSha256 = listOf("00".repeat(32)))))
    assertEquals(OtaError.SIGNATURE,
        OtaIntegrity.checkArchive(manifest, facts.copy(signerSha256 = listOf(pinned, "00".repeat(32)))))
  }
}
