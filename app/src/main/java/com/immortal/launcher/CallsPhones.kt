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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.LocalApprovedPalette

/**
 * A paired phone in the Calls app. Honest about phase 2a: a Portal can ring a phone only while
 * Portal Toolkit is open on it (no push yet), and phones are called, never dropped in on.
 */
@Composable
internal fun CallPhoneCard(phone: CallPhone, now: Long, compact: Boolean) {
  val context = LocalContext.current
  val p = LocalApprovedPalette.current
  val surface = callsSurface()
  val open = phone.reachable(now)
  val avatar = if (compact) 112.dp else 160.dp
  Column(Modifier.callPanel(p.surface, RoundedCornerShape(32.dp), p.outline).padding(if (compact) 28.dp else 36.dp)
      .alpha(if (open) 1f else OFF_ALPHA), verticalArrangement = Arrangement.spacedBy(18.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
      CallRoomAvatar(phone.name, avatar, if (open) CallPresence.AVAILABLE else CallPresence.OFFLINE, ring = p.surface)
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(phone.name, color = p.text, fontSize = if (compact) 30.sp else 36.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        Text(if (open) OPEN else CLOSED, color = p.secondary, fontSize = if (compact) 21.sp else 24.sp, maxLines = 2)
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      CallPill("Video call", { Calling.dial(context, phone.id, CallKind.CALL, video = true) }, icon = "video",
          style = CallButtonStyle.OK, enabled = open, surface = surface)
      CallRoundButton("phone", "Audio call", { if (open) Calling.dial(context, phone.id, CallKind.CALL, video = false) }, surface = surface)
    }
  }
}

/** The line under the phones: what works today. */
@Composable
internal fun CallPhonesNote(modifier: Modifier = Modifier) {
  Text("Phones call rooms. Only Portals can drop in. A Portal rings a phone while Portal Toolkit is open on it; " +
      "ringing a phone in your pocket comes in a later update.", color = LocalApprovedPalette.current.secondary, fontSize = 21.sp,
      modifier = modifier)
}

private const val OPEN = "Portal Toolkit is open · rings now"
private const val CLOSED = "Open Portal Toolkit on the phone to ring it"
private const val OFF_ALPHA = 0.72f
