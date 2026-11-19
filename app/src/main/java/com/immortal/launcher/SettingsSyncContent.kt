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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** The page note shown on settings pages that hold household rows, or null for none. */
internal fun settingsSyncPageNote(status: SettingsSyncStatus): String? = when {
  !status.available || status.paused -> null
  status.adoptFrom != null -> "Not synced yet. Choose how to join in About & Health › Sync."
  status.phase == SyncPhase.SYNCED -> SYNCED_PAGE_NOTE
  else -> null
}

/** Whether registry rows label themselves "This Portal only" (household sync is on). */
internal val LocalSettingsSyncLabels = androidx.compose.runtime.staticCompositionLocalOf { false }

internal const val SYNCED_PAGE_NOTE = "Synced across your Portals"
internal const val THIS_PORTAL_ONLY = "This Portal only"

/** A row label with "This Portal only" in front of its help. */
internal fun thisPortalOnlyHelp(help: String?): String =
    if (help.isNullOrBlank()) THIS_PORTAL_ONLY else "$THIS_PORTAL_ONLY. $help"

private const val CLOCK_TICK_MS = 30_000L

/** Settings › About & Health › Sync: the one-time join choice, then last sync and the other Portals. */
@Composable
internal fun SettingsSyncBlock() {
  val status by SettingsSync.status.collectAsState()
  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(Unit) {
    while (true) {
      now = System.currentTimeMillis()
      delay(CLOCK_TICK_MS)
    }
  }
  Column {
    status.adoptFrom?.let { from -> AdoptCard(from, status.adoptHasConflicts) }
    Card {
      SyncInfoRow("Status", when {
        !status.available -> "Off"
        status.paused -> "Paused"
        status.adoptFrom != null -> "Waiting for you"
        status.phase == SyncPhase.JOINING -> "Looking for Portals"
        else -> "On"
      })
      if (status.available) {
        Divider()
        SyncInfoRow("Last sync", status.lastSyncLabel(now))
        status.peers.forEach { peer ->
          Divider()
          SyncInfoRow(peer.name, status.peerLabel(peer, now))
        }
      }
    }
    FooterNote(status.summary)
  }
}

@Composable
private fun AdoptCard(from: String, conflicts: Boolean) {
  Card {
    Column(Modifier.padding(horizontal = 28.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text("Adopt settings from $from?", color = IosLabel, fontSize = Sizes.rowTitle, lineHeight = 34.sp)
      Text(
          if (conflicts) "Your other Portals already share settings such as the clock, screensaver and quiet hours. " +
              "Adopt them here, or keep the ones you changed on this Portal and share those instead."
          else "Your other Portals already share settings such as the clock, screensaver and quiet hours. " +
              "This Portal will use them too. Room name, brightness and drop-ins stay as they are.",
          color = IosSecondary, fontSize = Sizes.rowSub, lineHeight = 28.sp)
      Spacer(Modifier.height(4.dp))
      PrimaryPillButton("Adopt settings from $from") { SettingsSync.resolveJoin(keepMine = false) }
      if (conflicts) CardPillButton("Keep this Portal’s settings") { SettingsSync.resolveJoin(keepMine = true) }
    }
  }
  Spacer(Modifier.height(16.dp))
}

@Composable
private fun SyncInfoRow(title: String, value: String) {
  Row(
      Modifier.fillMaxWidth().defaultMinSize(minHeight = Sizes.rowMinHeight)
          .semantics(mergeDescendants = true) { contentDescription = "$title, $value" }
          .padding(horizontal = 28.dp, vertical = 22.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(title, color = IosLabel, fontSize = Sizes.rowTitle, lineHeight = 34.sp, modifier = Modifier.weight(1f))
    Text(value, color = IosSecondary, fontSize = Sizes.rowTitle, lineHeight = 34.sp)
  }
}
