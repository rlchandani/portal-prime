/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.LocalApprovedPalette

/**
 * Measurement builds only: send a test message made of generated frames (no camera, no mic) to a
 * room, so video messages can be tested at night without recording anyone. "Record 5 s and send"
 * runs the whole flow without taps.
 */
@Composable
internal fun CallMessageTestSheet(onDismiss: () -> Unit) {
  if (!BuildConfig.PORTAL_CALL_LOOPBACK) return
  val context = LocalContext.current
  val p = LocalApprovedPalette.current
  val peers by CallingStore.peers.collectAsState()
  val targets = peers.filter(CallMessages::canSend)
  Box(Modifier.fillMaxSize().background(CallColors.Scrim).callClick("Close", true, onDismiss), contentAlignment = Alignment.Center) {
    Column(Modifier.widthIn(max = 980.dp).fillMaxWidth().padding(32.dp).background(p.surface, RoundedCornerShape(36.dp)).padding(40.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
      Text("Test message (test frames)", color = p.text, fontSize = 36.sp, fontWeight = FontWeight.Bold)
      Text("Generated frames, no camera, no sound. Delete it on the other Portal afterwards.", color = p.secondary, fontSize = 24.sp)
      if (targets.isEmpty()) Text("No room has announced its address yet.", color = p.secondary, fontSize = 24.sp)
      targets.forEach { peer ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
          Text(peer.name, color = p.text, fontSize = 28.sp, modifier = Modifier.weight(1f))
          CallPill("Open", { onDismiss(); CallMessages.compose(context, peer.contact(), testFrames = true) }, size = CallButtonSize.SM,
              surface = callsSurface())
          CallPill("Record 5 s and send", { onDismiss(); CallMessages.compose(context, peer.contact(), testFrames = true, auto = true) },
              style = CallButtonStyle.OK, size = CallButtonSize.SM, surface = callsSurface())
        }
      }
    }
  }
}
