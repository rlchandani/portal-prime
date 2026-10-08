/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class RemoteCompanionInstallPolicyTest {
  @Test fun knownLatestAtOrBelowInstalledVersionSkipsDownload() {
    assertFalse(RemoteCompanionOperationPolicy.shouldDownloadUpdate(7L, 7L))
    assertFalse(RemoteCompanionOperationPolicy.shouldDownloadUpdate(7L, 6L))
  }

  @Test fun knownNewerVersionRequiresDownload() {
    assertTrue(RemoteCompanionOperationPolicy.shouldDownloadUpdate(7L, 8L))
  }

  @Test fun unknownLatestVersionRequiresDownloadedArchiveCheck() {
    assertTrue(RemoteCompanionOperationPolicy.shouldDownloadUpdate(7L, null))
  }

  @Test fun downloadedUpdateDoesNotHandOffSameOrOlderVersion() {
    assertEquals("up_to_date", archiveResult(version = 7L))
    assertEquals("up_to_date", archiveResult(version = 6L))
  }

  @Test fun compatibleNewerArchiveCanBeHandedOff() {
    assertNull(archiveResult(version = 8L, minSdk = 28))
    assertNull(archiveResult(version = 8L, minSdk = 29, sdk = 29))
  }

  @Test fun archiveRequiringNewerAndroidIsRejectedBeforeHandoff() {
    assertEquals("incompatible", archiveResult(minSdk = 29, sdk = 28))
    // Actual APK compatibility is checked even when its version is already installed.
    assertEquals("incompatible", archiveResult(version = 7L, minSdk = 29, sdk = 28))
    assertEquals("incompatible", archiveResult(minSdk = 30, sdk = 29, installed = null))
  }

  @Test fun missingArchiveSdkMetadataCannotBeVerifiedCompatible() {
    assertEquals("incompatible", archiveResult(minSdk = null))
  }

  @Test fun archiveMustMatchRequestedPackageBeforeVersionComparison() {
    assertEquals("package_mismatch", archiveResult(pkg = "com.other.app", version = 7L))
    assertEquals("package_mismatch", archiveResult(pkg = null))
  }

  @Test fun freshInstallIsNotSkippedBecauseOfAnUpdateComparison() {
    assertNull(archiveResult(version = 1L, installed = null))
  }

  private fun archiveResult(
      pkg: String? = "org.y20k.transistor",
      version: Long = 8L,
      minSdk: Int? = 24,
      sdk: Int = 28,
      installed: Long? = 7L,
  ) = RemoteCompanionOperationPolicy.archiveResult(
      "org.y20k.transistor", pkg, minSdk, sdk, version, installed)
}
