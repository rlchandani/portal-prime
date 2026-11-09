/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One palette per song, computed once off the main thread from YouTube's small `mqdefault`
 * thumbnail (320×180, ~10 KB; decoded at [FETCH_EDGE] px) and kept in a small LRU by video id.
 */
internal object MusicStagePalettes {
  private const val CACHE_SONGS = 48
  private const val FETCH_EDGE = 96
  private const val LOAD_TIMEOUT_MS = 6_000L
  private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{6,20}$")
  private val cache = LruCache<String, MusicArtPalette>(CACHE_SONGS)

  fun cached(videoId: String): MusicArtPalette? = synchronized(cache) { cache.get(videoId) }

  suspend fun load(videoId: String, fallbackUrl: String): MusicArtPalette? {
    cached(videoId)?.let { return it }
    val url = if (VIDEO_ID.matches(videoId)) "https://i.ytimg.com/vi/$videoId/mqdefault.jpg" else fallbackUrl
    if (url.isBlank()) return null
    val bitmap = withTimeoutOrNull(LOAD_TIMEOUT_MS) {
      runInterruptible(Dispatchers.IO) { MultiRoomArt.fetch(url, FETCH_EDGE) }
    }?.takeIf { !it.isRecycled } ?: return null
    val palette = withContext(Dispatchers.Default) { runCatching { extract(bitmap) }.getOrNull() } ?: return null
    synchronized(cache) { cache.put(videoId, palette) }
    return palette
  }

  /** [source] may be shared by the art cache, so only the bitmaps made here are recycled. */
  private fun extract(source: Bitmap): MusicArtPalette {
    val (x, y, side) = MusicArtPaletteMath.coverCrop(source.width, source.height)
    val crop = Bitmap.createBitmap(source, x, y, side, side)
    val edge = MusicArtPaletteMath.SAMPLE_EDGE
    val small = Bitmap.createScaledBitmap(crop, edge, edge, true)
    val pixels = IntArray(edge * edge)
    small.getPixels(pixels, 0, edge, 0, 0, edge, edge)
    if (small !== crop && small !== source) small.recycle()
    if (crop !== source) crop.recycle()
    return MusicArtPaletteMath.extract(pixels)
  }
}

/**
 * The wash palette for what's on screen: neutral during an ad, the warm idle wash with nothing
 * loaded, else the song's (the previous song's stays until the new one is ready, so nothing flashes).
 */
@Composable
internal fun rememberMusicStagePalette(track: YtTrack?, ad: Boolean): MusicArtPalette {
  val id = track?.videoId
  val song by produceState(id?.let(MusicStagePalettes::cached) ?: MusicArtPalette.IDLE, id) {
    if (track != null) MusicStagePalettes.load(track.videoId, track.thumbnailUrl)?.let { value = it }
  }
  return when {
    ad -> MusicArtPalette.NEUTRAL
    track == null -> MusicArtPalette.IDLE
    else -> song
  }
}
