/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import kotlinx.coroutines.delay

/**
 * The minimized tile's ✕ and its Undo ([MusicTileCloseRules]): engine calls here, state in
 * [MusicHomeCardState]. Main thread (Compose click handlers).
 */
internal object MusicTileClose {
  private const val TAG = "ImmortalMusicTile"

  /** Pause (queue and position saved, so it's "paused" everywhere), then hide the tile and offer Undo. */
  fun close() {
    val wasPlaying = MusicTileCloseRules.wasPlaying(YouTubeMusicEngine.state.value)
    YouTubeMusicEngine.pause()
    MusicHomeCardState.close(SystemClock.elapsedRealtime(), wasPlaying)
    Log.i(TAG, "tile closed: paused (wasPlaying=$wasPlaying), Undo for ${MusicTileCloseRules.UNDO_MS / 1000} s")
  }

  /** The tile comes back; the music resumes there if it was playing (it plays once the player shows). */
  fun undo() {
    val resume = MusicHomeCardState.undoClose(SystemClock.elapsedRealtime())
    Log.i(TAG, if (resume == null) "undo expired" else "undo: tile back, resume=$resume")
    if (resume == true) YouTubeMusicEngine.dispatch(YtAction.PlayWhenVisible)
  }
}

/** Geometry of the "Paused · Undo" toast (dp). Undo is a full 64 dp target. */
internal object MusicTileUndoDims {
  const val HEIGHT = 84
  const val UNDO_H = 64
  const val UNDO_MIN_W = 112
  const val MAX_W = 560
}

/**
 * "Paused · Undo", shown for [MusicTileCloseRules.UNDO_MS] after the tile's ✕, in the same inverted
 * pill as Home's other music toasts ([MusicV3Toast]). On every launcher tab the tile floats over.
 */
@Composable
internal fun MusicTileUndoToast(modifier: Modifier = Modifier) {
  val offer by MusicHomeCardState.undo.collectAsState()
  val current = offer ?: return
  LaunchedEffect(current) {
    delay(MusicTileCloseRules.remainingMs(current, SystemClock.elapsedRealtime()))
    MusicHomeCardState.undoExpired(current)
  }
  val palette = LocalApprovedPalette.current
  val pill = RoundedCornerShape((MusicTileUndoDims.HEIGHT / 2).dp)
  Row(modifier.widthIn(max = MusicTileUndoDims.MAX_W.dp).heightIn(min = MusicTileUndoDims.HEIGHT.dp).clip(pill)
      .background(palette.text).padding(start = 26.dp, end = 10.dp, top = 10.dp, bottom = 10.dp)
      .semantics { liveRegion = LiveRegionMode.Polite },
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    ApprovedIcon("music", Modifier.size(32.dp), palette.surface)
    Text("Paused", color = palette.surface, fontSize = 24.sp)
    Text("·", color = palette.surface.copy(alpha = 0.6f), fontSize = 24.sp)
    val undoShape = RoundedCornerShape((MusicTileUndoDims.UNDO_H / 2).dp)
    Box(Modifier.heightIn(min = MusicTileUndoDims.UNDO_H.dp).widthIn(min = MusicTileUndoDims.UNDO_MIN_W.dp)
        .clip(undoShape).border(1.5.dp, palette.surface.copy(alpha = 0.45f), undoShape)
        .tvFocusable(undoShape, ringColor = palette.surface, onClick = MusicTileClose::undo)
        .semantics { contentDescription = "Undo: bring the player back and resume" },
        contentAlignment = Alignment.Center) {
      Text("Undo", color = palette.surface, fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
          modifier = Modifier.padding(horizontal = 22.dp))
    }
  }
}
