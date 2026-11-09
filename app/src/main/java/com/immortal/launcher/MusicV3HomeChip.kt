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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val PAUSED_NOTE_MS = 6_000L

/** What the Home music chip shows; [status] is the trailing "Playing", absent when offering to resume. */
internal data class MusicV3ChipModel(val track: YtTrack, val title: String, val status: String?, val resume: Boolean)

/**
 * Playing: the song and "Playing". Nothing playing but a queue (in the engine, or [saved] on disk
 * after a restart): "Resume · <title>", and a tap plays from the saved position. Else no chip.
 */
internal fun musicV3Chip(s: YtEngineState, saved: YtTrack?): MusicV3ChipModel? =
    musicV3Chip(MusicV3ChipSlice.of(s), saved)

internal fun musicV3Chip(s: MusicV3ChipSlice, saved: YtTrack?): MusicV3ChipModel? {
  val current = s.current
  if (current != null && s.playing) return MusicV3ChipModel(current, current.title, "Playing", resume = false)
  val track = current ?: saved ?: return null
  return MusicV3ChipModel(track, "Resume · ${track.title}", null, resume = true)
}

/** The only engine facts Home's chip reads, so position ticks don't recompose Home's header. */
internal data class MusicV3ChipSlice(val current: YtTrack?, val playing: Boolean, val empty: Boolean) {
  companion object {
    fun of(s: YtEngineState) = MusicV3ChipSlice(s.queue.current?.track, s.isPlaying, s.queue.isEmpty)
  }
}

/** True while the YouTube queue has a song (the Home music button is highlighted). */
@Composable
internal fun rememberMusicV3Loaded(): Boolean {
  val flow = remember { engineSlice { it.queue.current != null } }
  val loaded by flow.collectAsState(initial = YouTubeMusicEngine.state.value.queue.current != null)
  return loaded
}

/**
 * Home header music chip: "<title> Playing", or "Resume · <title>" when nothing plays but a queue
 * is saved. A tap returns to the player, which resumes the song ([onOpen] asks for that).
 */
@Composable
internal fun MusicV3HomeChip(onOpen: () -> Unit) {
  val context = LocalContext.current
  val flow = remember { engineSlice(MusicV3ChipSlice::of) }
  val engine by flow.collectAsState(initial = MusicV3ChipSlice.of(YouTubeMusicEngine.state.value))
  val empty = engine.empty
  // After a restart the queue is only on disk until Music opens; read it off the main thread.
  val saved by produceState<YtTrack?>(null, empty) {
    value = if (!empty) null else withContext(Dispatchers.IO) {
      runCatching { YtQueueStore(context).load()?.queue?.current?.track }.getOrNull()
    }
  }
  val chip = musicV3Chip(engine, saved) ?: return
  val shape = RoundedCornerShape(34.dp)
  val spoken = if (chip.resume) "${chip.title}. Tap to play it again" else
    "Now playing: ${chip.track.title}. Tap to return to music"
  Row(Modifier.padding(start = 12.dp).height(68.dp).widthIn(max = 520.dp).clip(shape)
      .background(ApprovedHomePalette.Card).approvedOutline(shape)
      .tvFocusable(shape, ringColor = ApprovedHomePalette.Accent) {
        if (chip.resume) YouTubeMusicEngine.restoreSaved(context)
        onOpen()
      }
      .semantics(mergeDescendants = true) { contentDescription = spoken }
      .padding(start = 10.dp, end = 22.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    MusicV3Thumb(chip.track.thumbnailUrl, Modifier.size(48.dp), RoundedCornerShape(10.dp))
    Text(chip.title, color = ApprovedHomePalette.Text, fontSize = 23.sp, fontWeight = FontWeight.Medium, maxLines = 1,
        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
    chip.status?.let { Text(it, color = ApprovedHomePalette.Secondary, fontSize = 22.sp, maxLines = 1) }
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
