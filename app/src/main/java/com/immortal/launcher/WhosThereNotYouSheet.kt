/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val PAUSE_ONE_HOUR_MS = 60 * 60_000L

/**
 * "Who's here?" — the one-tap correction. Picking someone switches the Home (their private
 * items stay hidden: a tap is not a face match), "Someone else" returns the shared Home.
 * Nothing here ever changes anyone's face template; corrections are counted in the audit only.
 */
@Composable
internal fun WhosThereNotYouSheet(ui: WhosThereUi, onSettings: () -> Unit, onDismiss: () -> Unit) {
  val shown = WhosThereViewPolicy.people(ui.view)
  val thought = shown.mapNotNull { ui.person(it)?.name }.joinToString(" & ")
  ApprovedHomeDialog("Who’s here?", onDismiss) {
    Text(if (thought.isEmpty()) "Tap the right person. Nothing about anyone’s face data changes."
        else "Portal thought this was $thought. Tap the right person — nothing about anyone’s face data changes.",
        color = ApprovedHomePalette.Secondary, fontSize = 22.sp, lineHeight = 30.sp)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
      ui.people.forEach { p ->
        PersonTile(p.name, p.role.label, selected = p.id in shown, onClick = { WhosThere.correct(p.id); onDismiss() }) { WhosThereAvatar(p, 88.dp) }
      }
      PersonTile("Someone else", "Shared Home", selected = shown.isEmpty(), onClick = { WhosThere.correct(null); onDismiss() }) {
        WhosThereAvatar(null, 88.dp)
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      ApprovedHomePillButton("Don’t recognise me for 1 hour") { WhosThere.pauseShown(PAUSE_ONE_HOUR_MS); onDismiss() }
      ApprovedHomePillButton("Recognition settings") { onDismiss(); onSettings() }
    }
    Row(verticalAlignment = Alignment.Top) {
      ApprovedHomeIcon("info", Modifier.size(26.dp))
      Text("Corrections are counted in this Portal’s audit (“Not you?” × 1) but never update anyone’s face template. " +
          "If it keeps getting it wrong, set that person up again in Settings › People.",
          color = ApprovedHomePalette.Secondary, fontSize = 19.sp, lineHeight = 26.sp, modifier = Modifier.padding(start = 10.dp))
    }
  }
}

@Composable
private fun PersonTile(name: String, role: String, selected: Boolean, onClick: () -> Unit, avatar: @Composable () -> Unit) {
  val shape = RoundedCornerShape(22.dp)
  Column(Modifier.width(150.dp).clip(shape).background(ApprovedHomePalette.Chrome)
      .then(if (selected) Modifier.border(3.dp, ApprovedHomePalette.Accent, shape) else Modifier)
      .tvFocusable(shape, focusScale = 1f, ringColor = ApprovedHomePalette.Accent, onClick = onClick)
      .padding(vertical = 16.dp, horizontal = 8.dp),
      horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Box { avatar() }
    Text(name, color = ApprovedHomePalette.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, textAlign = TextAlign.Center)
    Text(role, color = ApprovedHomePalette.Secondary, fontSize = 17.sp, maxLines = 1)
  }
}
