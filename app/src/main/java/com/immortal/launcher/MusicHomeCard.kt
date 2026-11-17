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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import kotlinx.coroutines.delay

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
  /** Space Home's bottom toasts leave on the right while the card shows ([MusicCoverRules]). */
  const val TOAST_RESERVE = WIDTH + 24
  private const val DISABLED_ALPHA = 0.32f
  fun alpha(enabled: Boolean) = if (enabled) 1f else DISABLED_ALPHA
}

/**
 * Home's Now Playing card (Apple-Music-style mini player in the song's colours): the ONE official
 * YouTube player, moved here from the Music screen without reloading, with previous / play-pause /
 * next, the speaker volume (quiet-hours capped) and ✕ (pause and hide). The title opens Music.
 * Nothing is drawn over it; Home's toasts avoid it and full-screen cards pause it ([MusicCoverRules]).
 */
@Composable
internal fun MusicHomeNowPlayingCard(room: String, onOpenMusic: () -> Unit, modifier: Modifier = Modifier) {
  val flow = remember(room) { engineSlice { MusicV3NowPlaying.from(it, room) } }
  val now by flow.collectAsState(initial = MusicV3NowPlaying.from(YouTubeMusicEngine.state.value, room))
  val track = now.track ?: return
  LaunchedEffect(now.playing) {
    if (now.playing) return@LaunchedEffect
    delay(MusicHomeCardRules.PAUSED_HIDE_MS)
    MusicHomeCardState.dismiss()
  }
  val palette = rememberMusicStagePalette(track, now.isAd)
  val dark = LocalApprovedPalette.current.dark
  val ink = remember(palette, dark) { MusicStageColors.of(palette, dark) }
  val shape = RoundedCornerShape(MusicHomeCardDims.RADIUS.dp)
  MusicStageTheme(ink, MusicStageDims.COMPACT) {
    Row(modifier.width(MusicHomeCardDims.WIDTH.dp).height(MusicHomeCardDims.HEIGHT.dp)
        .shadow(24.dp, shape, clip = false).clip(shape).background(ink.card).border(1.dp, ink.line, shape)
        .padding(MusicHomeCardDims.PAD.dp), horizontalArrangement = Arrangement.spacedBy(MusicHomeCardDims.GAP.dp)) {
      val playerShape = RoundedCornerShape(MusicHomeCardDims.PLAYER_RADIUS.dp)
      Box(Modifier.size(MusicHomeCardDims.PLAYER_W.dp, MusicHomeCardDims.PLAYER_H.dp).clip(playerShape)
          .background(Color.Black).semantics { contentDescription = "YouTube player" }) {
        YouTubePlayerSurface(MusicPlayerHostKind.HOME_CARD, Modifier.fillMaxSize())
      }
      Column(Modifier.width(MusicHomeCardDims.SIDE_W.dp).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        CardHeader(track, now, onOpenMusic)
        CardTransport(now)
        MusicV3StageVolume(room)
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
    MusicV3RoundButton("Pause and hide music", size = MusicHomeCardDims.CLOSE.dp, onClick = {
      YouTubeMusicEngine.pause()
      MusicHomeCardState.dismiss()
    }) { ApprovedIcon("close", Modifier.size(26.dp), ink.muted) }
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

/** Home's dialogs and sheets are drawn over the card: the music pauses while one is open ([MusicCoverRules]). */
@Composable
internal fun MusicHomeDialogCover(open: Boolean) {
  DisposableEffect(open) {
    YouTubeMusicEngine.setCovered(MusicCover.HOME_DIALOG, open)
    onDispose { if (open) YouTubeMusicEngine.setCovered(MusicCover.HOME_DIALOG, false) }
  }
}
