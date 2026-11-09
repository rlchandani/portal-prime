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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A hidden player waits this far left of the screen, so it never takes a touch meant for the stage. */
private const val PARKED_X = -4_000f

/** What sits in the player's place when the player itself is hidden. */
internal sealed interface MusicV3SlotPlaceholder {
  /** Moved: "Playing in Kitchen" with Bring it back (owner decision: keep the placeholder). */
  data class Moved(val room: String, val onBringBack: () -> Unit) : MusicV3SlotPlaceholder
  /** Nothing loaded, sheet open: a quiet note tile in the strip. */
  data object Empty : MusicV3SlotPlaceholder
}

/**
 * The ONE official YouTube player of the stage. It is composed once and only ever moves (its
 * position is read in the layout phase from [motion]); it is never re-created, so the WebView never
 * reloads. It is the top layer apart from toasts, and the geometry keeps everything else off its
 * rectangle. The WebView is attached one frame after the stage first draws, so opening Music never
 * waits on it ([YouTubeMusicEngine.prewarm] has already paid Chromium's start-up at idle).
 */
@Composable
internal fun MusicV3PlayerSlot(
    layout: MusicStageLayout,
    motion: MusicStageMotion,
    shown: Boolean,
    placeholder: MusicV3SlotPlaceholder?,
) {
  val spec = layout.spec
  val shape = RoundedCornerShape(spec.playerRadius.dp)
  var attach by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) {
    withFrameNanos { }
    attach = true
  }
  val at = Modifier.stageOffset { motion.frame(layout).player.let { it.left to it.top } }
      .size(spec.playerW.dp, spec.playerH.dp)
  if (!shown && placeholder != null) Placeholder(placeholder, at, shape)
  val parked = Modifier.stageOffset { PARKED_X to layout.playerClosed.top }.size(spec.playerW.dp, spec.playerH.dp)
  Box((if (shown) at.shadow(24.dp, shape, clip = false) else parked).clip(shape).background(Color.Black)
      .semantics { contentDescription = "YouTube player" }) {
    if (attach) YouTubePlayerSurface(Modifier.fillMaxSize(), shown = shown)
  }
}

@Composable
private fun Placeholder(p: MusicV3SlotPlaceholder, modifier: Modifier, shape: RoundedCornerShape) {
  val ink = LocalMusicStageInk.current
  val dims = LocalMusicStageDims.current
  Box(modifier.clip(shape).background(ink.fill).border(2.dp, ink.line, shape).padding(16.dp),
      contentAlignment = Alignment.Center) {
    when (p) {
      is MusicV3SlotPlaceholder.Moved -> Column(horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(MusicV3StageCopy.movedSlot(p.room), color = ink.muted, fontSize = dims.small.sp, textAlign = TextAlign.Center)
        MusicStagePill(MusicV3StageCopy.BRING_BACK, style = MusicPillStyle.SOLID, icon = "back", onClick = p.onBringBack)
      }
      MusicV3SlotPlaceholder.Empty -> ApprovedIcon("music", Modifier.size(64.dp), ink.faint)
    }
  }
}
