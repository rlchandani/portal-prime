/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date

/**
 * Home "Calls" widget (mock's Calls card): favourite rooms first as big faces with presence; tap a
 * face to call. A missed call shows inline with Call back. Recent and the Calls app are one tap away.
 */
@Composable
internal fun CallsHomeWidget(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val peers by CallingStore.peers.collectAsState()
  val history by CallingStore.history.collectAsState()
  LaunchedEffect(Unit) { Calling.refreshPresence(context) }
  val now = rememberCallClock(PRESENCE_TICK_MS)
  val missed = remember(history) { CallHistory.latestUnseenMissed(history) }
  LaunchedEffect(Unit) { CallMessageStore.ensureLoaded(context) }
  val messages by CallMessageStore.messages.collectAsState()
  val message = remember(messages) { CallInbox.latestUnseen(messages) }
  ImmortalWidgetShell(title = "Calls", openPage = "calls", modifier = modifier, onOpen = { CallsActivity.open(context) },
      scrollContent = false) {
    // A new video message takes the missed-call card (with Play); otherwise the plain missed call.
    if (message != null) CallMessageStrip(message) else if (missed != null) MissedStrip(missed)
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
      val count = (maxWidth / FACE_WIDTH).toInt().coerceIn(1, MAX_FACES)
      val faces = CallPeers.forHome(peers, count)
      when {
        HouseholdKey.get(context) == null -> ApprovedHomeWidgetMessage("Pair this Portal in Portal Toolkit to call other rooms")
        faces.isEmpty() -> ApprovedHomeWidgetMessage("Other Portals in this home appear here")
        else -> Row(Modifier.fillMaxWidth().align(Alignment.Center), horizontalArrangement = Arrangement.SpaceEvenly) {
          faces.forEach { peer -> Face(peer, CallPolicy.presence(peer, now)) }
        }
      }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
      Text(CallingCopy.WIDGET_FOOTER, color = ApprovedHomePalette.Secondary, fontSize = 20.sp, modifier = Modifier.weight(1f),
          maxLines = 1, overflow = TextOverflow.Ellipsis)
      CallPill("Recent", { CallsActivity.open(context, recent = true) }, icon = "clock", style = CallButtonStyle.GLASS,
          size = CallButtonSize.SM)
    }
  }
}

@Composable
private fun Face(peer: CallPeer, presence: CallPresence) {
  val context = LocalContext.current
  Column(Modifier.width(FACE_WIDTH).callClick("Call ${peer.name}", true) { Calling.dial(context, peer.id, CallKind.CALL, video = true) }
      .padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
    CallRoomAvatar(peer.name, FACE_AVATAR, presence, ring = Color(0xFF1F2227))
    Text(peer.name, color = ApprovedHomePalette.Text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    Text(presence.label, color = ApprovedHomePalette.Secondary, fontSize = 21.sp, maxLines = 1)
  }
}

@Composable
private fun MissedStrip(e: CallHistoryEntry) {
  val context = LocalContext.current
  val time = remember(e.atMs) { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(e.atMs)) }
  Row(Modifier.fillMaxWidth().background(Color(0x33000000), RoundedCornerShape(28.dp)).padding(horizontal = 24.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    Column(Modifier.weight(1f)) {
      Text("Missed call from ${e.peerName}", color = ApprovedHomePalette.Text, fontSize = 25.sp, fontWeight = FontWeight.SemiBold,
          maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(CallingCopy.historyLine(e, time), color = ApprovedHomePalette.Secondary, fontSize = 20.sp, maxLines = 1)
    }
    CallPill("Call back", { CallingStore.markSeen(context); Calling.dial(context, e.peerId, CallKind.CALL, video = true) },
        icon = "phone", style = CallButtonStyle.OK, size = CallButtonSize.SM)
  }
  Spacer(Modifier.height(12.dp))
}

private val FACE_WIDTH = 200.dp
private val FACE_AVATAR = 132.dp
private const val MAX_FACES = 4
private const val PRESENCE_TICK_MS = 15_000L
