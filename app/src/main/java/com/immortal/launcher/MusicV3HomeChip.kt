/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private const val PAUSED_NOTE_MS = 6_000L

/** Home chip label for the engine's current song: Paused (always, off the player) or Playing. */
internal fun musicV3ChipStatus(s: YtEngineState): String = if (s.isPlaying) "Playing" else "Paused"

/** True while the YouTube queue has a song (the Home music button is highlighted). */
@Composable
internal fun rememberMusicV3Loaded(): Boolean {
  val engine by YouTubeMusicEngine.state.collectAsState()
  return engine.queue.current != null
}

/**
 * Home header "Now playing" chip (art, title, Paused). Shown while the queue has an item; a tap
 * returns to the player, which resumes the song ([onOpen] asks for that).
 */
@Composable
internal fun MusicV3HomeChip(onOpen: () -> Unit) {
  val engine by YouTubeMusicEngine.state.collectAsState()
  val track = engine.queue.current?.track ?: return
  val status = musicV3ChipStatus(engine)
  val shape = RoundedCornerShape(34.dp)
  Row(Modifier.padding(start = 12.dp).height(68.dp).widthIn(max = 520.dp).clip(shape)
      .background(ApprovedHomePalette.Card).approvedOutline(shape)
      .tvFocusable(shape, ringColor = ApprovedHomePalette.Accent, onClick = onOpen)
      .semantics(mergeDescendants = true) {
        contentDescription = "Now playing: ${track.title}, ${status.lowercase()}. Tap to return to music"
      }
      .padding(start = 10.dp, end = 22.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    MusicV3Thumb(track.thumbnailUrl, Modifier.size(48.dp), RoundedCornerShape(10.dp))
    Text(track.title, color = ApprovedHomePalette.Text, fontSize = 23.sp, fontWeight = FontWeight.Medium, maxLines = 1,
        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
    Text(status, color = ApprovedHomePalette.Secondary, fontSize = 22.sp, maxLines = 1)
    MusicV3GlyphIcon(MusicV3Glyph.RIGHT, ApprovedHomePalette.Secondary, Modifier.size(24.dp))
  }
}

/** The one-time "why it paused" toast on Home after leaving the player mid-song. */
@Composable
internal fun MusicV3PausedNote(modifier: Modifier = Modifier) {
  val show by MusicV3Session.showPausedNote.collectAsState()
  if (!show) return
  LaunchedEffect(Unit) { delay(PAUSED_NOTE_MS); MusicV3Session.pausedNoteShown() }
  MusicV3Toast(MusicV3Session.PAUSED_NOTE, modifier, icon = "music")
}
