/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.net.URI
import org.json.JSONObject

/** A validated `hearthpane-latest.json` entry (schema [OtaConstants.SCHEMA]). */
internal data class OtaManifest(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    /** Lowercase hex SHA-256 of the APK. */
    val sha256: String,
    val size: Long,
    val apkUrl: String,
    val minSdk: Int,
    val notes: String,
)

internal enum class OtaRejection { MALFORMED, SCHEMA, WRONG_PACKAGE, NOT_NEWER, BAD_URL, BAD_SHA, BAD_SIZE, UNSUPPORTED_DEVICE }

internal sealed interface OtaManifestResult {
  data class Valid(val manifest: OtaManifest) : OtaManifestResult
  data class Rejected(val reason: OtaRejection) : OtaManifestResult
}

/** Pure manifest validation: everything is checked before a single APK byte is fetched. */
internal object OtaManifestParser {
  private val HEX = Regex("[0-9a-f]{${OtaConstants.SHA256_HEX_LENGTH}}")

  fun parse(json: String, expectedPackage: String, installedVersionCode: Long, sdkInt: Int): OtaManifestResult {
    val j = runCatching { JSONObject(json) }.getOrNull() ?: return rejected(OtaRejection.MALFORMED)
    if (whole(j.opt("schema")) != OtaConstants.SCHEMA.toLong()) return rejected(OtaRejection.SCHEMA)
    val packageName = j.optString("packageName")
    val versionCode = whole(j.opt("versionCode")) ?: return rejected(OtaRejection.MALFORMED)
    val versionName = j.optString("versionName").trim()
    if (versionName.isEmpty() || versionName.length > OtaConstants.MAX_VERSION_NAME) return rejected(OtaRejection.MALFORMED)
    if (packageName != expectedPackage) return rejected(OtaRejection.WRONG_PACKAGE)
    if (versionCode <= installedVersionCode) return rejected(OtaRejection.NOT_NEWER)
    val apkUrl = j.optString("apkUrl")
    if (!OtaUrlPolicy.isAllowedApkUrl(apkUrl)) return rejected(OtaRejection.BAD_URL)
    val sha = j.optString("sha256").lowercase()
    if (!HEX.matches(sha)) return rejected(OtaRejection.BAD_SHA)
    val size = whole(j.opt("size"))
    if (size == null || size <= 0L || size > OtaConstants.MAX_APK_BYTES) return rejected(OtaRejection.BAD_SIZE)
    val minSdk = whole(j.opt("minSdk")) ?: 1L
    if (minSdk > sdkInt) return rejected(OtaRejection.UNSUPPORTED_DEVICE)
    val notes = j.optString("notes").trim().take(OtaConstants.MAX_NOTES)
    return OtaManifestResult.Valid(
        OtaManifest(packageName, versionCode, versionName, sha, size, apkUrl, minSdk.toInt(), notes))
  }

  private fun rejected(reason: OtaRejection) = OtaManifestResult.Rejected(reason)

  /** Integer JSON numbers only; strings and fractions are refused, not coerced. */
  private fun whole(value: Any?): Long? = when (value) {
    is Int -> value.toLong()
    is Long -> value
    else -> null
  }
}

/** Where update traffic may go: https on GitHub's release hosts only, no credentials or odd ports. */
internal object OtaUrlPolicy {
  private const val HTTPS = "https"
  private const val HTTPS_PORT = 443
  private const val NO_PORT = -1

  fun isAllowedHop(url: String): Boolean {
    val uri = parse(url) ?: return false
    return uri.scheme.equals(HTTPS, ignoreCase = true) && uri.rawUserInfo == null &&
        (uri.port == NO_PORT || uri.port == HTTPS_PORT) &&
        uri.host?.lowercase() in OtaConstants.HOP_HOSTS
  }

  /** The manifest's APK must be a release asset of our own repo on github.com. */
  fun isAllowedApkUrl(url: String): Boolean {
    val uri = parse(url) ?: return false
    val path = uri.rawPath ?: return false
    return isAllowedHop(url) && uri.host.equals(OtaConstants.APK_HOST, ignoreCase = true) &&
        path == uri.normalize().rawPath && path.startsWith(OtaConstants.APK_PATH_PREFIX) &&
        !path.contains("%2e", ignoreCase = true)
  }

  /** The resolved redirect target from [current], or null when missing or not allowed. */
  fun nextHop(current: String, location: String?): String? {
    if (location.isNullOrBlank()) return null
    val next = runCatching { URI(current).resolve(location.trim()).toString() }.getOrNull() ?: return null
    return next.takeIf(::isAllowedHop)
  }

  private fun parse(url: String): URI? = runCatching { URI(url) }.getOrNull()?.takeIf { it.isAbsolute }
}
