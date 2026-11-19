/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.settings

import android.content.Context
import com.immortal.launcher.FaceCatalog
import com.immortal.launcher.ScreensaverConfig
import com.immortal.launcher.SettingsGuard

/**
 * Photo-frame settings that have their own picker screens instead of registry rows (the clock-face
 * picker and the photo-source forms), declared here so household sync can carry them. This domain
 * is deliberately not in [SettingsDomains.all]: it adds nothing to the on-device pages, the phone
 * remote or the legacy screensaver wire format. It is listed in [SettingsDomains.syncOnly].
 */
internal object PhotoFrameSyncSettings {
  const val SHARED_DEFAULT = "default"
  const val SHARED_URL_PREFIX = "url:"
  private const val MAX_URL = 2048
  private val faceIds: List<String> = FaceCatalog.entries.map { it.id }

  /**
   * The photo source as a household value: the built-in feed, or a shared album link. Every other
   * source (this Portal's own folder, and the Immich / NAS / WebDAV connections whose credentials
   * stay on the Portal) reads as "" — a private source that is never pushed to other Portals.
   */
  fun sharedSource(s: ScreensaverConfig.Settings): String = when {
    s.source == ScreensaverConfig.SOURCE_DEFAULT -> SHARED_DEFAULT
    s.source == ScreensaverConfig.SOURCE_URL && !s.albumUrl.isNullOrBlank() -> SHARED_URL_PREFIX + s.albumUrl.trim()
    else -> ""
  }

  /** A shared-source value another Portal may apply: the built-in feed or an http(s) album link. */
  fun isValidSharedSource(v: String): Boolean {
    if (v == SHARED_DEFAULT) return true
    if (!v.startsWith(SHARED_URL_PREFIX)) return false
    val url = v.removePrefix(SHARED_URL_PREFIX)
    return url.length in 1..MAX_URL && (url.startsWith("https://") || url.startsWith("http://")) &&
        url.none { it.isWhitespace() || it.isISOControl() }
  }

  private fun applySharedSource(c: Context, v: String) {
    if (v == SHARED_DEFAULT) ScreensaverConfig.useDefault(c)
    else ScreensaverConfig.setAlbumUrl(c, v.removePrefix(SHARED_URL_PREFIX))
  }

  val domain: SettingsDomain<ScreensaverConfig.Settings> =
      SettingsDomain(
          id = "photoframe",
          title = "Photo frame",
          load = ScreensaverConfig::load,
          specs = listOf(
              BoolSpec("facesEnabled", "Clock on the photo frame", get = { it.facesEnabled },
                  set = ScreensaverConfig::setFacesEnabled, scope = SettingScope.HOUSEHOLD),
              EnumSpec("faceId", "Clock face", get = { it.faceId }, set = ScreensaverConfig::setFaceId,
                  options = faceIds.map { it to it }, coerce = { v -> v.takeIf { it in faceIds } },
                  scope = SettingScope.HOUSEHOLD),
              IntSpec("faceSizeIndex", "Clock size", get = { it.faceSizeIndex },
                  set = ScreensaverConfig::setFaceSizeIndex, min = 0, max = 2, scope = SettingScope.HOUSEHOLD),
              StringSpec("sharedSource", "Photo source", get = ::sharedSource, set = ::applySharedSource,
                  applyWhen = ::isValidSharedSource, scope = SettingScope.HOUSEHOLD),
          ),
          defaults = { ScreensaverConfig.Settings() },
          onApplied = { c, keys -> SettingsGuard.afterScreensaverApply(c, keys) },
      )
}
