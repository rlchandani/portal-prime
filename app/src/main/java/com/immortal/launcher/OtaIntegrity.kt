/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.security.MessageDigest

/** User-facing outcome codes; [message] is shown in Settings and sent to fleetctl / the phone. */
internal enum class OtaError(val code: String, val message: String) {
  NETWORK("network", "Couldn't reach GitHub. Check the Wi-Fi and try again."),
  BAD_MANIFEST("bad_manifest", "The update information on GitHub isn't valid. Nothing was installed."),
  WRONG_PACKAGE("wrong_package", "The published update is for a different app. Nothing was installed."),
  UNSUPPORTED_DEVICE("unsupported_device", "This update needs a newer version of Android than this Portal has."),
  DOWNLOAD("download_failed", "The download didn't finish. Hearthpane will try again later."),
  INTEGRITY("integrity", "The download didn't match the published update. Nothing was installed."),
  SIGNATURE("signature", "The download isn't signed by Hearthpane. Nothing was installed."),
  INSTALL("install_failed", "Android couldn't install the update. Hearthpane will try again later."),
  PAUSED("paused", "Updates are paused on this Portal. Connect it to your computer to update."),
  DEV_MODE("dev_mode", "Automatic updates are off while developer mode is on."),
}

/** Facts read back from the downloaded APK by [android.content.pm.PackageManager.getPackageArchiveInfo]. */
internal data class OtaArchiveFacts(
    val packageName: String?,
    val versionCode: Long,
    /** Lowercase hex SHA-256 of each signing certificate. */
    val signerSha256: List<String>,
)

/** Pure integrity checks between the manifest and what was actually downloaded. */
internal object OtaIntegrity {
  private const val HEX_DIGITS = "0123456789abcdef"
  private const val NIBBLE_BITS = 4
  private const val NIBBLE_MASK = 0x0f
  private const val BYTE_MASK = 0xff

  fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

  fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
    bytes.forEach { b ->
      val v = b.toInt() and BYTE_MASK
      append(HEX_DIGITS[v ushr NIBBLE_BITS])
      append(HEX_DIGITS[v and NIBBLE_MASK])
    }
  }

  fun checkDownload(manifest: OtaManifest, receivedBytes: Long, sha256Hex: String): OtaError? =
      if (receivedBytes != manifest.size || !sha256Hex.equals(manifest.sha256, ignoreCase = true)) OtaError.INTEGRITY
      else null

  fun checkArchive(
      manifest: OtaManifest,
      facts: OtaArchiveFacts?,
      pinnedSigner: String = OtaConstants.PINNED_SIGNER_SHA256,
  ): OtaError? = when {
    facts == null -> OtaError.INTEGRITY
    facts.packageName != manifest.packageName || facts.versionCode != manifest.versionCode -> OtaError.INTEGRITY
    facts.signerSha256.isEmpty() -> OtaError.SIGNATURE
    facts.signerSha256.any { !it.equals(pinnedSigner, ignoreCase = true) } -> OtaError.SIGNATURE
    else -> null
  }

  /** How a rejected manifest is reported; null for "not newer", which simply means up to date. */
  fun errorFor(rejection: OtaRejection): OtaError? = when (rejection) {
    OtaRejection.NOT_NEWER -> null
    OtaRejection.WRONG_PACKAGE -> OtaError.WRONG_PACKAGE
    OtaRejection.UNSUPPORTED_DEVICE -> OtaError.UNSUPPORTED_DEVICE
    OtaRejection.MALFORMED, OtaRejection.SCHEMA, OtaRejection.BAD_URL,
    OtaRejection.BAD_SHA, OtaRejection.BAD_SIZE -> OtaError.BAD_MANIFEST
  }
}
