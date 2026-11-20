/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.PrimeBlue

/**
 * Home-header mini-player: premium card with rounded album art, glass-style card background,
 * a subtle left PrimeBlue border indicating active playback, and refined typography.
 * The art area is the play/pause touch target. Driven by [NowPlayingHub].
 */
/**
 * Left-column "Now Playing" card. Subscribes to [NowPlayingHub] and renders the current track with
 * a tap-to-play/pause art target; self-hides (renders nothing) when nothing is playing, matching the
 * other self-hiding dashboard cards. Replaces the old always-present "Tap to play" stub.
 */
@Composable
private fun NowPlayingCard(modifier: Modifier = Modifier) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
  var state by remember { mutableStateOf(NowPlayingHub.current) }
  DisposableEffect(Unit) {
    val l = NowPlayingHub.Listener { s -> mainHandler.post { state = s } }
    NowPlayingHub.addListener(l) // replays current immediately
    onDispose { NowPlayingHub.removeListener(l) }
  }
  val np = state ?: return // nothing playing → card hides entirely

  var urlArt by remember(np.artUrl, np.artBitmap) { mutableStateOf<android.graphics.Bitmap?>(null) }
  LaunchedEffect(np.artUrl, np.artBitmap) {
    if (np.artBitmap == null && np.artUrl.isNotBlank()) {
      MediaArt.deliverUri(context, np.artUrl) { urlArt = it }
    } else urlArt = null
  }
  val cover = np.artBitmap ?: urlArt

  LiquidGlassWidget(modifier = modifier.height(80.dp)) {
    Row(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      LiquidGlass(
          modifier = Modifier.size(48.dp).tvFocusable(RoundedCornerShape(8.dp)) {
            NowPlayingHub.playPause()
          },
          cornerRadius = 8.dp,
      ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          if (cover != null) {
            Image(
                bitmap = cover.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
          } else {
            Text("♫", color = Color.White, fontSize = 18.sp)
          }
        }
      }
      Column(modifier = Modifier.weight(1f)) {
        Text(
            np.title.ifBlank { "Now Playing" },
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (np.artist.isNotBlank()) {
          Text(
              np.artist,
              color = Color.White.copy(alpha = 0.55f),
              fontSize = 11.sp,
              fontWeight = FontWeight.Light,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ApprovedHomeHeaderPlayer(modifier: Modifier = Modifier, onOpenMusic: () -> Unit) {
  // Playback/position notifications invalidate only the player, not the widget dashboard.
  var state by remember { mutableStateOf(NowPlayingHub.current) }
  val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
  DisposableEffect(Unit) {
    val listener = homeNowPlayingListener({ mainHandler.post(it) }) { state = it }
    NowPlayingHub.addListener(listener)
    onDispose { NowPlayingHub.removeListener(listener) }
  }
  state?.takeIf { it.active && it.source != SOURCE_YOUTUBE }?.let { MiniPlayer(it, modifier, onOpenMusic) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MiniPlayer(state: NowPlayingState, modifier: Modifier = Modifier, onOpenMusic: () -> Unit) {
  val context = androidx.compose.ui.platform.LocalContext.current
  // Some media apps hand cover art as a URI rather than an embedded bitmap. Resolve it off the
  // main thread (content:// or http) so the header shows the cover too — the screensaver and the
  // phone remote already do this via [MediaArt]; the mini-player was the one place that didn't.
  var urlArt by remember(state.artUrl, state.artBitmap) { mutableStateOf<android.graphics.Bitmap?>(null) }
  androidx.compose.runtime.LaunchedEffect(state.artUrl, state.artBitmap) {
    if (state.artBitmap == null && state.artUrl.isNotBlank()) {
      MediaArt.deliverUri(context, state.artUrl) { urlArt = it }
    } else urlArt = null
  }
  val cover = state.artBitmap ?: urlArt
  Row(
      modifier =
          modifier
              .clip(RoundedCornerShape(16.dp))
              .background(ApprovedHomePalette.Chrome)
              .border(1.dp, ApprovedHomePalette.Border, RoundedCornerShape(16.dp))
              .padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    // Album art with 8dp rounded corners; tapping toggles playback.
    Box(
        modifier =
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .tvFocusable(RoundedCornerShape(8.dp)) { NowPlayingHub.playPause() },
        contentAlignment = Alignment.Center,
    ) {
      val bmp = cover
      if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(56.dp),
        )
      } else {
        ApprovedHomeIcon("music", Modifier.size(40.dp), ApprovedHomePalette.Secondary)
      }
      // Subtle dark scrim + play/pause glyph overlay on the art.
      Box(
          Modifier.size(56.dp).background(Color(0x3D000000)),
          contentAlignment = Alignment.Center,
      ) {
        PlayPauseGlyph(playing = state.state == PlaybackState.PLAYING)
      }
    }
    Spacer(Modifier.size(12.dp))
    Column(modifier = Modifier.weight(1f).semantics { contentDescription = "Open music player" }
        .tvFocusable { onOpenMusic() }) {
      Text(
          state.title,
          color = ApprovedHomePalette.Text,
          fontSize = 26.sp,
          lineHeight = 35.sp,
          fontWeight = FontWeight.Medium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
      )
      if (state.artist.isNotBlank()) {
        Text(
            state.artist,
            color = ApprovedHomePalette.Secondary,
            fontSize = 22.sp,
            lineHeight = 30.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
      }
    }
  }
}

@Composable
private fun PlayPauseGlyph(playing: Boolean) {
  Canvas(modifier = Modifier.size(30.dp)) {
    val w = size.minDimension
    if (playing) {
      // Two slim, fully-rounded bars with a clear gap between them — close-set square
      // bars blur into a single block from across the room.
      val barW = w * 0.13f
      val top = w * 0.24f
      val barH = w * 0.52f
      val r = CornerRadius(barW * 0.5f, barW * 0.5f)
      drawRoundRect(Color.White, topLeft = Offset(w * 0.30f, top), size = Size(barW, barH), cornerRadius = r)
      drawRoundRect(Color.White, topLeft = Offset(w * 0.57f, top), size = Size(barW, barH), cornerRadius = r)
    } else {
      drawPath(
          Path().apply {
            moveTo(w * 0.32f, w * 0.22f)
            lineTo(w * 0.32f, w * 0.78f)
            lineTo(w * 0.80f, w * 0.50f)
            close()
          },
          Color.White,
      )
    }
  }
}
