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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** The connection pill's four looks. */
internal enum class HaConnection { LIVE, CONNECTING, OFFLINE, SIGN_IN }

/** The connection pill for [state]; null hides it (not set up). */
internal fun connectionOf(state: HaDashboardState): HaConnection? = when (state) {
  HaDashboardState.NotConfigured -> null
  HaDashboardState.Loading -> HaConnection.CONNECTING
  HaDashboardState.Unauthorized -> HaConnection.SIGN_IN
  is HaDashboardState.Unreachable, is HaDashboardState.Offline -> HaConnection.OFFLINE
  HaDashboardState.NoRooms, is HaDashboardState.Ready -> HaConnection.LIVE
}

/** Back · "HOME / Good evening / subline" · connection pill · settings. */
@Composable
internal fun HaHeader(greeting: String, subline: String, connection: HaConnection?, onBack: () -> Unit,
    onSettings: () -> Unit) {
  val d = LocalHaDims.current
  val c = LocalHaColors.current
  Row(Modifier.fillMaxWidth().height(d.headerHeight.dp).padding(horizontal = d.pad.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
    HaRoundButton("back", "Back to Home", onBack)
    Column(Modifier.weight(1f)) {
      HaLabel("Home")
      HaText(greeting, d.title, c.text, Modifier.semantics { heading() }, serif = true, lineHeight = 1.05f)
      Spacer(Modifier.height(4.dp))
      HaText(subline, d.sub, c.sec)
    }
    if (!d.portrait && connection != null) HaConnectionPill(connection)
    HaRoundButton("gear", "Home Assistant settings", onSettings, iconSize = 28.dp)
  }
}

@Composable
private fun HaConnectionPill(connection: HaConnection) {
  val d = LocalHaDims.current
  val c = LocalHaColors.current
  Row(Modifier.height(d.target.dp).haCard(c, HaShape.Pill).padding(horizontal = 22.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    when (connection) {
      HaConnection.LIVE -> {
        HaBrandGlyph()
        HaText("Home Assistant", d.sub, c.text)
        HaText("· live", d.sub, c.sec)
      }
      HaConnection.CONNECTING -> { HaDot(c.amber); HaText("Connecting…", d.sub, c.text) }
      HaConnection.OFFLINE -> { HaDot(c.amber); HaText("Offline · last known", d.sub, c.text) }
      HaConnection.SIGN_IN -> { HaDot(c.red); HaText("Sign-in needed", d.sub, c.text) }
    }
  }
}

/** Header copy for the states that replace the clock line. */
internal fun stateSubline(state: HaDashboardState): String? = when (state) {
  HaDashboardState.NotConfigured -> "Connect Home Assistant to see your rooms here"
  HaDashboardState.Loading -> "Connecting to Home Assistant…"
  HaDashboardState.Unauthorized -> "Home Assistant needs you to sign in again"
  HaDashboardState.NoRooms -> "No rooms found yet"
  is HaDashboardState.Unreachable -> "Can't reach Home Assistant"
  else -> null
}
