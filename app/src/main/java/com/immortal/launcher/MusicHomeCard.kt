/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.LocalApprovedPalette

/** Home card geometry (dp). The player matches the Music stage's 384×216, above YouTube's 200×200. */
internal object MusicHomeCardDims {
  const val PLAYER_W = 384
  const val PLAYER_H = 216
  const val PLAYER_RADIUS = 18
  const val PAD = 20
  const val GAP = 24
  const val SIDE_W = 340
  const val RADIUS = 32
  const val SKIP = 64
  const val PLAY = 76
  const val CLOSE = 64
  const val WIDTH = PAD + PLAYER_W + GAP + SIDE_W + PAD
  const val HEIGHT = PAD + PLAYER_H + PAD

  /** The minimized tile: the player alone at YouTube's minimum (with a little margin), square. */
  const val TILE_PLAYER = 208
  /** The tile's expand / drag rail, beside the player (never over it). */
  const val RAIL_W = 64
  const val RAIL_GAP = 8
  const val TILE_W = TILE_PLAYER + RAIL_GAP + RAIL_W
  const val TILE_H = TILE_PLAYER
  private const val DISABLED_ALPHA = 0.32f
  fun alpha(enabled: Boolean) = if (enabled) 1f else DISABLED_ALPHA

  fun widthOf(mode: MusicHomeCardMode) = if (mode == MusicHomeCardMode.CARD) WIDTH else TILE_W
  fun heightOf(mode: MusicHomeCardMode) = if (mode == MusicHomeCardMode.CARD) HEIGHT else TILE_H
}

/**
 * Home's Now Playing card (Apple-Music-style mini player in the song's colours) or, after ✕, its
 * video-only tile: the ONE official YouTube player, moved here from the Music screen without
 * reloading. The player is always this Row's first child, so switching modes only resizes the same
 * view (no re-attach, no reload). Card: previous / play-pause / next, the speaker volume
 * (quiet-hours capped), ✕ (minimize; the music keeps playing) and the title (opens Music). Tile: the
 * player and, beside it, expand and a drag grip. Nothing is drawn over the player.
 */
@Composable
internal fun MusicHomeCardBody(
    mode: MusicHomeCardMode,
    track: YtTrack,
    now: MusicV3NowPlaying,
    room: String,
    onOpenMusic: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val palette = rememberMusicStagePalette(track, now.isAd)
  val dark = LocalApprovedPalette.current.dark
  val ink = remember(palette, dark) { MusicStageColors.of(palette, dark) }
  val card = mode == MusicHomeCardMode.CARD
  val shape = RoundedCornerShape(MusicHomeCardDims.RADIUS.dp)
  val frame = if (card) Modifier.shadow(24.dp, shape, clip = false).clip(shape).background(ink.card)
      .border(1.dp, ink.line, shape).padding(MusicHomeCardDims.PAD.dp) else Modifier
  val gap = if (card) MusicHomeCardDims.GAP else MusicHomeCardDims.RAIL_GAP
  MusicStageTheme(ink, MusicStageDims.COMPACT) {
    Row(modifier.width(MusicHomeCardDims.widthOf(mode).dp).height(MusicHomeCardDims.heightOf(mode).dp).then(frame),
        horizontalArrangement = Arrangement.spacedBy(gap.dp)) {
      val playerShape = RoundedCornerShape(MusicHomeCardDims.PLAYER_RADIUS.dp)
      val playerSize = if (card) Modifier.size(MusicHomeCardDims.PLAYER_W.dp, MusicHomeCardDims.PLAYER_H.dp)
      else Modifier.size(MusicHomeCardDims.TILE_PLAYER.dp)
      Box(playerSize.clip(playerShape).background(Color.Black).semantics { contentDescription = "YouTube player" }) {
        YouTubePlayerSurface(MusicPlayerHostKind.HOME_CARD, Modifier.fillMaxSize())
      }
      if (card) {
        Column(Modifier.width(MusicHomeCardDims.SIDE_W.dp).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
          CardHeader(track, now, onOpenMusic)
          CardTransport(now)
          MusicV3StageVolume(room)
        }
      } else {
        TileRail(track)
      }
    }
  }
}

@Composable
private fun CardHeader(track: YtTrack, now: MusicV3NowPlaying, onOpenMusic: () -> Unit) {
  val ink = LocalMusicStageInk.current
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    val titleShape = RoundedCornerShape(16.dp)
    Column(Modifier.weight(1f).heightIn(min = MusicHomeCardDims.CLOSE.dp).clip(titleShape)
        .tvFocusable(titleShape, ringColor = ink.ember, onClick = onOpenMusic)
        .semantics(mergeDescendants = true) { contentDescription = "${track.title}. Open Music" }
        .padding(horizontal = 4.dp), verticalArrangement = Arrangement.Center) {
      Text(if (now.isAd) MusicV3NowPlaying.AD_LINE else track.title, color = ink.ink, fontSize = 24.sp,
          fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(track.artist, color = ink.muted, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    MusicV3RoundButton("Minimize player, music keeps playing", size = MusicHomeCardDims.CLOSE.dp,
        onClick = MusicHomeCardState::minimize) {
      MusicV3GlyphIcon(MusicV3Glyph.COLLAPSE, ink.muted, Modifier.size(28.dp))
    }
  }
}

/** The tile's rail beside the player: expand (the full card again) above a drag grip. */
@Composable
private fun TileRail(track: YtTrack) {
  val ink = LocalMusicStageInk.current
  val shape = RoundedCornerShape(MusicHomeCardDims.RAIL_W.dp / 2)
  Column(Modifier.width(MusicHomeCardDims.RAIL_W.dp).fillMaxHeight().shadow(12.dp, shape, clip = false).clip(shape)
      .background(ink.card).border(1.dp, ink.line, shape), horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.SpaceBetween) {
    MusicV3RoundButton("Expand player: ${track.title}", size = MusicHomeCardDims.RAIL_W.dp,
        onClick = MusicHomeCardState::expand) {
      MusicV3GlyphIcon(MusicV3Glyph.EXPAND, ink.ink, Modifier.size(28.dp))
    }
    MusicV3GlyphIcon(MusicV3Glyph.GRIP, ink.muted, Modifier.padding(bottom = 18.dp).size(26.dp))
  }
}

@Composable
private fun CardTransport(now: MusicV3NowPlaying) {
  val ink = LocalMusicStageInk.current
  val enabled = now.transportEnabled
  Row(Modifier.fillMaxWidth().alpha(MusicHomeCardDims.alpha(enabled)), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally)) {
    MusicV3RoundButton("Previous", size = MusicHomeCardDims.SKIP.dp, enabled = enabled,
        onClick = YouTubeMusicEngine::previous) {
      MusicV3GlyphIcon(MusicV3Glyph.PREVIOUS, ink.ink, Modifier.size(30.dp))
    }
    MusicV3RoundButton(if (now.playing) "Pause" else "Play", size = MusicHomeCardDims.PLAY.dp, fill = ink.ink,
        enabled = enabled, onClick = { if (now.playing) YouTubeMusicEngine.pause() else YouTubeMusicEngine.play() }) {
      MusicV3GlyphIcon(if (now.playing) MusicV3Glyph.PAUSE else MusicV3Glyph.PLAY, ink.inverse, Modifier.size(36.dp))
    }
    MusicV3RoundButton("Next", size = MusicHomeCardDims.SKIP.dp, enabled = enabled, onClick = YouTubeMusicEngine::next) {
      MusicV3GlyphIcon(MusicV3Glyph.NEXT, ink.ink, Modifier.size(30.dp))
    }
  }
}

/**
 * Keeps the screen awake while a YouTube video plays in any host (the screensaver would cover the
 * player); [DreamPolicy] reads the same fact from [YtVideoScreenState].
 */
@Composable
internal fun MusicVideoScreenHold() {
  val flow = remember { engineSlice(YtVideoScreenState::holds) }
  val holds by flow.collectAsState(initial = YtVideoScreenState.holds(YouTubeMusicEngine.state.value))
  if (holds) KeepInteractiveScreenAwake()
}

/** Whether Home shows its Now Playing card now ([MusicHomeCardRules]); also lifts a dismissal once music plays. */
@Composable
internal fun rememberMusicHomeCardShown(musicOpen: Boolean, customizing: Boolean): Boolean {
  val current = remember { engineSlice { it.queue.current != null } }
  val playing = remember { engineSlice { it.isPlaying } }
  val hasCurrent by current.collectAsState(initial = YouTubeMusicEngine.state.value.queue.current != null)
  val isPlaying by playing.collectAsState(initial = YouTubeMusicEngine.state.value.isPlaying)
  val dismissed by MusicHomeCardState.dismissed.collectAsState()
  LaunchedEffect(isPlaying) { MusicHomeCardState.onPlaying(isPlaying) }
  return MusicHomeCardRules.shows(MusicHomeCardInputs(hasCurrent, musicOpen, customizing, dismissed))
}

/** Dp side padding for Home's bottom toasts so they centre beside the card ([MusicCardAvoidance]). */
internal data class MusicToastPadding(val start: Dp, val end: Dp)

@Composable
internal fun rememberMusicToastInsets(cardShown: Boolean): MusicToastPadding {
  val placement by MusicHomeCardState.placement.collectAsState()
  val density = LocalDensity.current
  val card = placement.takeIf { cardShown }
  return remember(card, density) {
    with(density) {
      val px = MusicCardAvoidance.toastInsets(card, MusicHomeFloatDims.TOAST_BAND.toPx(), MusicHomeFloatDims.TOAST_GAP.toPx())
      MusicToastPadding(px.start.toDp(), px.end.toDp())
    }
  }
}

/** Home's dialogs and sheets are drawn over the card: the music pauses while one is open ([MusicCoverRules]). */
@Composable
internal fun MusicHomeDialogCover(open: Boolean) {
  DisposableEffect(open) {
    YouTubeMusicEngine.setCovered(MusicCover.HOME_DIALOG, open)
    onDispose { if (open) YouTubeMusicEngine.setCovered(MusicCover.HOME_DIALOG, false) }
  }
}
