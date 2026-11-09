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
  /** Default decode edge (screensaver, header mini-player, phone remote). */
  const val MAX_EDGE = 384
  /**
   * Decode edges a display size is rounded up to, so rows of the same size share one cached
   * bitmap ([MultiRoomArt] keys its LRU by URL and edge) and none is decoded far above its size.
   */
  private val EDGE_STEPS = intArrayOf(96, 128, 192, 256, MAX_EDGE)

  /** The decode edge for art drawn [displayPx] pixels wide: the next step up, at most [MAX_EDGE]. */
  fun edgeFor(displayPx: Int): Int = EDGE_STEPS.firstOrNull { it >= displayPx } ?: MAX_EDGE

  /** Resolve supported artwork URIs; failed reads/decodes leave the regular placeholder. */
  fun resolveUri(context: Context, url: String, edge: Int = MAX_EDGE): Bitmap? = resolveUri(
      url, edge,
      network = { spec, e -> MultiRoomArt.fetch(spec, e) },
      local = { spec, e -> MusicLocalArtwork.fetch(context.contentResolver, spec, e) })

  /** UI delivery owns local decodes until publication; cached network art stays shared. */
  internal suspend fun deliverUri(context: Context, url: String, edge: Int = MAX_EDGE, publish: (Bitmap?) -> Unit) {
    if (MusicLocalArtwork.routeFor(url) == MusicArtworkRoute.LOCAL) {
      deliverOwnedMusicArtwork(
          load = { resolveUri(context, url, edge) },
          dispose = { if (!it.isRecycled) it.recycle() },
          publish = publish)
    } else {
      publish(runInterruptible(Dispatchers.IO) { resolveUri(context, url, edge) })
    }
  }

  /** Admit the URI before invoking either transport, at the default edge. */
  internal fun resolveUri(
      url: String,
      network: (String, Int) -> Bitmap?,
      local: (String, Int) -> Bitmap?,
  ): Bitmap? = resolveUri(url, MAX_EDGE, network, local)

  /** Admit the URI before invoking either transport, including access to a local resolver. */
  internal fun resolveUri(
      url: String,
      edge: Int,
      network: (String, Int) -> Bitmap?,
      local: (String, Int) -> Bitmap?,
  ): Bitmap? {
    if (url.isBlank()) return null
    val e = edge.coerceIn(1, MAX_EDGE)
    return runCatching {
      when (MusicLocalArtwork.routeFor(url)) {
        MusicArtworkRoute.NETWORK -> network(url, e)
        MusicArtworkRoute.LOCAL -> local(url, e)
        MusicArtworkRoute.UNSUPPORTED -> null
      }
    }.getOrNull()
  }
}
