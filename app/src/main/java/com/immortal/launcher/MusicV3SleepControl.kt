/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** How often "Stops in N min" re-reads the clock. */
private const val SLEEP_LABEL_TICK_MS = 15_000L
private val CHOOSER_HEIGHT = 120.dp

/** The moon button beside volume; highlighted while a sleep timer is armed. */
@Composable
internal fun MusicV3SleepButton(timer: MusicSleepTimer?, enabled: Boolean, onClick: () -> Unit) {
  val armed = timer != null
  val tint = when {
    !enabled -> ApprovedHomePalette.Secondary.copy(alpha = 0.4f)
    armed -> ApprovedHomePalette.Accent
    else -> ApprovedHomePalette.Secondary
  }
  MusicV3RoundButton(if (armed) "Sleep timer, on" else "Sleep timer", Modifier.clip(CircleShape),
      fill = if (armed) ApprovedHomePalette.Selected else ApprovedHomePalette.Chrome, enabled = enabled,
      onClick = onClick) {
    ApprovedIcon("moon", Modifier.size(30.dp), tint)
  }
}

/** "Stops in 23 min" / "Stops after this song" while a timer is armed. */
@Composable
internal fun MusicV3SleepLine(timer: MusicSleepTimer?) {
  timer ?: return
  val now by produceState(System.currentTimeMillis(), timer) {
    while (true) {
      value = System.currentTimeMillis()
      delay(SLEEP_LABEL_TICK_MS)
    }
  }
  val label = MusicSleepPolicy.label(timer, now) ?: return
  Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    ApprovedIcon("moon", Modifier.size(24.dp), ApprovedHomePalette.Secondary)
    Text(label, color = ApprovedHomePalette.Secondary, fontSize = 23.sp, maxLines = 1)
  }
}

/** Off / 15 / 30 / 60 min / End of song, shown inline in place of the transport while choosing. */
@Composable
internal fun MusicV3SleepChooser(timer: MusicSleepTimer?, onPick: (MusicSleepChoice) -> Unit) {
  val selected = MusicSleepPolicy.choice(timer)
  Row(Modifier.fillMaxWidth().padding(top = 18.dp).height(CHOOSER_HEIGHT), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
    MusicSleepChoice.entries.forEach { choice ->
      MusicV3Button(choice.label, style = if (choice == selected) MusicV3ButtonStyle.SOLID else MusicV3ButtonStyle.LINE) {
        onPick(choice)
      }
    }
  }
}
