/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The song's square cover for Now Playing, like the YouTube Music app. YouTube's `sddefault` /
 * `hqdefault` thumbnails are 4:3 and letterbox a 16:9 frame; for music ("Topic") tracks that frame
 * is the square cover between black pillars, so a square crop zoomed by [ZOOM] shows just the cover.
 */
internal object MusicV3CoverArt {
  const val ZOOM = 4f / 3f
  private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{6,20}$")

  /** Image URLs to try in order: the sharp one first, then the one YouTube always has. */
  fun candidates(videoId: String, fallbackUrl: String): List<String> =
      if (VIDEO_ID.matches(videoId)) listOf("sddefault", "hqdefault").map { "https://i.ytimg.com/vi/$videoId/$it.jpg" }
      else listOfNotNull(fallbackUrl.takeIf { it.isNotBlank() })
}

private const val LOAD_TIMEOUT_MS = 8_000L
/** `sddefault` is 640 px wide; decode at that size so the cropped cover stays sharp. */
private const val COVER_EDGE_PX = 640
/** YouTube's tiny `default.jpg` placeholder is 120 px wide; anything that small isn't a cover. */
private const val MIN_ART_PX = 120

/** Square cover art; a quiet note glyph until (or unless) an image loads. */
@Composable
internal fun MusicV3Cover(track: YtTrack, modifier: Modifier) {
  val art by produceState<Bitmap?>(null, track.videoId) {
    value = null
    for (url in MusicV3CoverArt.candidates(track.videoId, track.thumbnailUrl)) {
      // MediaArt caps at 384 px for list rows; the cover is large, so fetch it sharper here.
      val bitmap = withTimeoutOrNull(LOAD_TIMEOUT_MS) {
        runInterruptible(Dispatchers.IO) { MultiRoomArt.fetch(url, COVER_EDGE_PX) }
      }?.takeIf { !it.isRecycled && it.width > MIN_ART_PX }
      if (bitmap != null) { value = bitmap; break }
    }
  }
  Box(modifier.clip(RoundedCornerShape(24.dp)).background(ApprovedHomePalette.Chrome), contentAlignment = Alignment.Center) {
    val bitmap = art
    if (bitmap != null) {
      Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = MusicV3CoverArt.ZOOM; scaleY = MusicV3CoverArt.ZOOM })
    } else {
      ApprovedIcon("music", Modifier.size(72.dp), ApprovedHomePalette.Secondary)
    }
  }
}
