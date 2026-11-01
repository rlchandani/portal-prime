/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Presentation projection only; no preferences are written and sources stay unchanged. */
internal fun musicPhotoSettings(saved: ScreensaverConfig.Settings) =
    saved.copy(mode = ScreensaverConfig.MODE_WHOLE, includeVideo = false)

/** Only inputs consumed by PhotoFrameController's native Whole Photo, photos-only session. */
internal data class MusicPhotoRestartKey(
    val source: PhotoFrameSource,
    val intervalSec: Int,
    val albumRefreshMin: Int?,
    val cacheEnabled: Boolean,
    val cacheBudgetGb: Int?,
)

internal fun musicPhotoRestartKey(saved: ScreensaverConfig.Settings): MusicPhotoRestartKey {
  val source = PhotoFrameSource.from(musicPhotoSettings(saved), allowWebPage = false)
  val usesMediaCache = source is PhotoFrameSource.Immich || source is PhotoFrameSource.WebDav
  return MusicPhotoRestartKey(
      source = source,
      intervalSec = saved.intervalSec,
      albumRefreshMin = saved.albumRefreshMin.takeIf { source is PhotoFrameSource.SharedAlbum },
      // The toggle also controls startup cache cleanup for sources that cannot use the cache.
      cacheEnabled = saved.cacheEnabled,
      cacheBudgetGb = saved.cacheBudgetGb.takeIf { saved.cacheEnabled && usesMediaCache })
}
