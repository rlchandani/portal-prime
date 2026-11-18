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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
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
 * Home header music chip, compact so the header buttons never squish ([HomeHeaderBudget]): the
 * song's cover (a music glyph until it loads) then Play/Pause and Next, at a fixed
 * [MusicV3HomeChipDims.WIDTH]. No title text; the cover's accessibility label names the song. A tap
 * on the cover opens Music ([onOpen]), which resumes the song. Play brings Home's Now Playing card
 * back (the player must be on screen to play, see [MusicHomeCardRules]) and starts the song there.
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
  val shape = RoundedCornerShape(MusicV3HomeChipDims.HEIGHT.dp / 2)
  Row(Modifier.size(MusicV3HomeChipDims.WIDTH.dp, MusicV3HomeChipDims.HEIGHT.dp).clip(shape)
      .background(ApprovedHomePalette.Card).approvedOutline(shape)
      .padding(start = MusicV3HomeChipDims.PAD_START.dp, end = MusicV3HomeChipDims.PAD_END.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(MusicV3HomeChipDims.GAP.dp)) {
    val thumbShape = RoundedCornerShape(MusicV3HomeChipDims.THUMB_RADIUS.dp)
    MusicV3Thumb(chip.track.thumbnailUrl, Modifier.size(MusicV3HomeChipDims.THUMB.dp)
        .tvFocusable(thumbShape, ringColor = ApprovedHomePalette.Accent) {
          if (chip.resume) YouTubeMusicEngine.restoreSaved(context)
          onOpen()
        }
        .semantics { contentDescription = musicV3ChipSpoken(chip) },
        thumbShape, displaySize = MusicV3HomeChipDims.THUMB.dp)
    ChipButtons(engine.playing) { if (empty) YouTubeMusicEngine.restoreSaved(context) }
  }
}

/** The cover's accessibility label: the chip shows no text, so this names the song. */
internal fun musicV3ChipSpoken(chip: MusicV3ChipModel): String =
    if (chip.resume) "${chip.title}. Open music to play it again" else "Now playing: ${chip.track.title}. Open music"

/** Play/Pause and Next at the chip's right end. [ensureQueue] loads a saved queue first. */
@Composable
private fun ChipButtons(playing: Boolean, ensureQueue: () -> Unit) {
  val ink = ApprovedHomePalette.Text
  MusicV3RoundButton(if (playing) "Pause music" else "Play music", size = MusicV3HomeChipDims.BUTTON.dp, onClick = {
    if (playing) {
      YouTubeMusicEngine.pause()
    } else {
      ensureQueue()
      MusicHomeCardState.restore()
      YouTubeMusicEngine.dispatch(YtAction.PlayWhenVisible)
    }
  }) { MusicV3GlyphIcon(if (playing) MusicV3Glyph.PAUSE else MusicV3Glyph.PLAY, ink, Modifier.size(30.dp)) }
  MusicV3RoundButton("Next song", size = MusicV3HomeChipDims.BUTTON.dp, onClick = {
    ensureQueue()
    YouTubeMusicEngine.next()
  }) { MusicV3GlyphIcon(MusicV3Glyph.NEXT, ink, Modifier.size(28.dp)) }
}

/** The compact header chip's geometry (dp): cover + two 64 dp touch targets, fixed width. */
internal object MusicV3HomeChipDims {
  const val HEIGHT = 68
  const val THUMB = 52
  const val THUMB_RADIUS = 12
  const val BUTTON = 64
  const val GAP = 4
  const val PAD_START = 8
  const val PAD_END = 2
  const val WIDTH = PAD_START + THUMB + GAP + BUTTON + GAP + BUTTON + PAD_END
}

/** The one-time "why it paused" toast on Home after leaving the player mid-song. */
@Composable
internal fun MusicV3PausedNote(modifier: Modifier = Modifier) {
  val show by MusicV3Session.showPausedNote.collectAsState()
  if (!show) return
  LaunchedEffect(Unit) { delay(PAUSED_NOTE_MS); MusicV3Session.pausedNoteShown() }
  MusicV3Toast(MusicV3Session.PAUSED_NOTE, modifier, icon = "music")
}
