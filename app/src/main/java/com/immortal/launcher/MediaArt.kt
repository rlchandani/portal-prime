/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * Shared URI artwork facade for the screensaver, home-header mini-player and phone remote.
 * Reuses the bounded network/local readers and sampled decoders; only MultiRoomArt owns a cache.
 * Blocking — call off-main. Returned bitmaps may be shared, so callers must not recycle them.
 */
object MediaArt {
  private const val MAX_EDGE = 384

  /** Resolve supported artwork URIs; failed reads/decodes leave the regular placeholder. */
  fun resolveUri(context: Context, url: String): Bitmap? = resolveUri(
      url,
      network = { spec, edge -> MultiRoomArt.fetch(spec, edge) },
      local = { spec, edge -> MusicLocalArtwork.fetch(context.contentResolver, spec, edge) })

  /** UI delivery owns local decodes until publication; cached network art stays shared. */
  internal suspend fun deliverUri(context: Context, url: String, publish: (Bitmap?) -> Unit) {
    if (MusicLocalArtwork.routeFor(url) == MusicArtworkRoute.LOCAL) {
      deliverOwnedMusicArtwork(
          load = { resolveUri(context, url) },
          dispose = { if (!it.isRecycled) it.recycle() },
          publish = publish)
    } else {
      publish(runInterruptible(Dispatchers.IO) { resolveUri(context, url) })
    }
  }

  /** Admit the URI before invoking either transport, including access to a local resolver. */
  internal fun resolveUri(
      url: String,
      network: (String, Int) -> Bitmap?,
      local: (String, Int) -> Bitmap?,
  ): Bitmap? {
    if (url.isBlank()) return null
    return runCatching {
      when (MusicLocalArtwork.routeFor(url)) {
        MusicArtworkRoute.NETWORK -> network(url, MAX_EDGE)
        MusicArtworkRoute.LOCAL -> local(url, MAX_EDGE)
        MusicArtworkRoute.UNSUPPORTED -> null
      }
    }.getOrNull()
  }
}
