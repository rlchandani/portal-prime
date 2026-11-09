/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** How often "Stops in N min" re-reads the clock. */
private const val SLEEP_LABEL_TICK_MS = 15_000L

/** "Stops in 23 min" / "Stops after this song" while a timer is armed, else null. */
@Composable
internal fun rememberSleepLabel(timer: MusicSleepTimer?): String? {
  val now by produceState(System.currentTimeMillis(), timer) {
    while (timer != null) {
      value = System.currentTimeMillis()
      delay(SLEEP_LABEL_TICK_MS)
    }
  }
  return timer?.let { MusicSleepPolicy.label(it, now) }
}

/** The "Sleep timer" capsule beside Move to…; ember and showing the time left while armed. */
@Composable
internal fun MusicV3SleepPill(timer: MusicSleepTimer?, enabled: Boolean, onClick: () -> Unit) {
  val label = rememberSleepLabel(timer)
  MusicStagePill(label ?: MusicV3StageCopy.SLEEP_OFF, icon = "moon", armed = label != null, enabled = enabled,
      spoken = if (label != null) "Sleep timer, on. $label" else MusicV3StageCopy.SLEEP_OFF, onClick = onClick)
}

/** "Stops in 23 min" under the title in the header strip. */
@Composable
internal fun MusicV3SleepLine(timer: MusicSleepTimer?, modifier: Modifier = Modifier) {
  val label = rememberSleepLabel(timer) ?: return
  MusicStageNote(label, glyph = null, icon = "moon", ember = true, modifier = modifier)
}

/** "Stop the music…": Off / 15 / 30 / 60 min / End of song, an opaque card above the capsule. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MusicV3SleepPopover(timer: MusicSleepTimer?, modifier: Modifier = Modifier, onPick: (MusicSleepChoice) -> Unit) {
  val selected = MusicSleepPolicy.choice(timer)
  val ink = LocalMusicStageInk.current
  val dims = LocalMusicStageDims.current
  MusicStageCard(modifier) {
    Text(MusicV3StageCopy.SLEEP_TITLE, color = ink.ink, fontSize = (dims.body * 0.9f).sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, bottom = 14.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      MusicSleepChoice.entries.forEach { choice ->
        MusicStagePill(choice.label, style = if (choice == selected) MusicPillStyle.SOLID else MusicPillStyle.QUIET) {
          onPick(choice)
        }
      }
    }
  }
}
