/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Words on the Home adopt card (pure, unit tested). */
internal object SettingsSyncHomeCopy {
  fun title(from: String): String = "Use your household’s settings from $from?"
  const val BODY = "This Portal has a few settings of its own. You can also choose later in About › Sync."
  const val USE = "Use"
  const val KEEP = "Keep this Portal’s"
  const val LATER = "Later"
}

/**
 * The one-time "join the household's settings" card on Home, for a new Portal whose own changes
 * would be overridden (a Portal with untouched defaults adopts silently and never sees it). It reads
 * [SettingsSync.status] directly, so it costs nothing on Home while no choice is waiting.
 */
@Composable
internal fun SettingsSyncHomeCard(modifier: Modifier = Modifier) {
  // Only the card's own question is observed: peer/last-sync ticks never recompose Home.
  val from by remember {
    SettingsSync.status.map { if (it.showAdoptCard) it.adoptFrom else null }.distinctUntilChanged()
  }.collectAsState(null)
  val household = from ?: return
  val upright = currentLayoutClass().compactPortrait
  ApprovedHomeSurface(modifier.fillMaxWidth().heightIn(min = 96.dp), radius = 22.dp) {
    if (upright) {
      // Upright Mini: the words get the full width, the choices sit on their own row below.
      Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 14.dp, top = 14.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          ApprovedHomeIcon("home", Modifier.size(34.dp), tint = ApprovedHomePalette.Accent)
          AdoptWords(household, Modifier.weight(1f).padding(start = 18.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically) { AdoptChoices() }
      }
    } else {
      Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
          verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ApprovedHomeIcon("home", Modifier.size(34.dp), tint = ApprovedHomePalette.Accent)
        AdoptWords(household, Modifier.weight(1f).padding(start = 6.dp))
        AdoptChoices()
      }
    }
  }
}

@Composable
private fun AdoptWords(from: String, modifier: Modifier) {
  Column(modifier) {
    Text(SettingsSyncHomeCopy.title(from), color = ApprovedHomePalette.Text, fontSize = 26.sp, lineHeight = 34.sp,
        fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.semantics { heading() })
    Text(SettingsSyncHomeCopy.BODY, color = ApprovedHomePalette.Secondary, fontSize = 20.sp, lineHeight = 28.sp,
        maxLines = 2, overflow = TextOverflow.Ellipsis)
  }
}

@Composable
private fun AdoptChoices() {
  ApprovedHomePillButton(SettingsSyncHomeCopy.USE) { SettingsSync.resolveJoin(keepMine = false) }
  ApprovedHomePillButton(SettingsSyncHomeCopy.KEEP) { SettingsSync.resolveJoin(keepMine = true) }
  ApprovedHomeTextButton(SettingsSyncHomeCopy.LATER) { SettingsSync.dismissAdoptCard() }
}
