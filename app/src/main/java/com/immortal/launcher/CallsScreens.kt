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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import java.text.DateFormat
import java.util.Date

/** Rooms tab: every other Portal in this home with presence, Video call and Drop in. */
@Composable
internal fun CallsRooms(onEveryone: () -> Unit) {
  val context = LocalContext.current
  val peers by CallingStore.peers.collectAsState()
  val self = remember { Calling.selfId(context).orEmpty() }
  val now = rememberCallClock(PRESENCE_TICK_MS)
  when {
    !CallMediaRuntime.available() -> CallsNotice("warn", "Calls can’t run on this Portal",
        "Hearthpane Calling needs a 64-bit Portal. This one can still use Call everyone.")
    HouseholdKey.get(context) == null -> CallsNotice("key", "Calls aren’t set up yet",
        "Open Portal Toolkit on your phone and pair this Portal. It shares the household key, so only Portals in this home can call.")
    peers.isEmpty() -> CallsNotice("users", "Looking for other Portals…",
        "Rooms appear here when another Portal in this home answers. Check it’s on the same Wi-Fi.") {
      CallPill("Try again", { Calling.announce(context, ask = true) }, icon = "redo", surface = callsSurface())
    }
    else -> LazyVerticalGrid(GridCells.Fixed(COLUMNS), Modifier.fillMaxSize(), contentPadding = PaddingValues(56.dp, 8.dp, 56.dp, 40.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp), verticalArrangement = Arrangement.spacedBy(32.dp)) {
      items(peers.sortedBy { it.name.lowercase() }, key = { it.id }) { peer -> RoomCard(peer, self, now) }
      item(span = { GridItemSpan(COLUMNS) }) { EveryoneCard(onEveryone) }
      item(span = { GridItemSpan(COLUMNS) }) {
        Text(CallingCopy.CALLS_FOOTER, color = LocalApprovedPalette.current.secondary, fontSize = 22.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
      }
    }
  }
}

@Composable
private fun RoomCard(peer: CallPeer, self: String, now: Long) {
  val context = LocalContext.current
  val p = LocalApprovedPalette.current
  val surface = callsSurface()
  val presence = CallPolicy.presence(peer, now)
  val drop = CallPolicy.dropInOn(peer, self)
  val calls = peer.acceptsCalls
  Column(Modifier.callPanel(p.surface, RoundedCornerShape(32.dp), p.outline).padding(36.dp).alpha(if (presence == CallPresence.OFFLINE) 0.72f else 1f),
      verticalArrangement = Arrangement.spacedBy(18.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(26.dp)) {
      CallRoomAvatar(peer.name, 160.dp, presence, ring = p.surface)
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(peer.name, color = p.text, fontSize = 36.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          Box(Modifier.size(14.dp).background(CallColors.presence(presence), CircleShape))
          Text(presence.label, color = p.secondary, fontSize = 24.sp)
        }
      }
      CallRoundButton("star", if (peer.favourite) "Remove from Home" else "Show on Home",
          { CallingStore.setFavourite(context, peer.id, !peer.favourite) }, surface = if (peer.favourite)
            surface.copy(fill = HearthpaneMark.EmberLight.copy(alpha = 0.3f), ink = HearthpaneMark.Ember) else surface, size = 72.dp)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      val plain = p.surface2
      when {
        !calls -> CallChip("Calls off", "bellOff", CallChipTone.RED, plain, p.secondary)
        drop == DropInPolicy.CHIME -> CallChip("Drop-ins on", "door", CallChipTone.OK, plain, p.secondary)
        drop == DropInPolicy.ASK -> CallChip("Asks first", "door", CallChipTone.PLAIN, plain, p.secondary)
        else -> CallChip("Drop-ins off", "door", CallChipTone.PLAIN, plain, p.secondary)
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      CallPill("Video call", { Calling.dial(context, peer.id, CallKind.CALL, video = true) }, icon = "video",
          style = CallButtonStyle.OK, enabled = calls, surface = surface)
      CallPill(when (drop) { DropInPolicy.CHIME -> "Drop in"; DropInPolicy.ASK -> "Ask to join"; DropInPolicy.OFF -> "Not allowed" },
          { Calling.dial(context, peer.id, CallKind.DROP_IN, video = true) }, icon = "door", enabled = calls && drop != DropInPolicy.OFF,
          surface = surface)
      CallRoundButton("phone", "Audio call", { Calling.dial(context, peer.id, CallKind.CALL, video = false) }, surface = surface)
    }
  }
}

@Composable
private fun EveryoneCard(onEveryone: () -> Unit) {
  val p = LocalApprovedPalette.current
  Row(Modifier.fillMaxWidth().callPanel(p.surface, RoundedCornerShape(32.dp), p.outline).padding(36.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(26.dp)) {
    CallIcon("users", p.secondary, 56.dp)
    Column(Modifier.weight(1f)) {
      Text("Need everyone at once?", color = p.text, fontSize = 29.sp, fontWeight = FontWeight.SemiBold)
      Text("Call everyone chimes a short message in every room. Drop-ins always chime and show a banner first.",
          color = p.secondary, fontSize = 22.5.sp)
    }
    CallPill("Call everyone", onEveryone, icon = "bell", style = CallButtonStyle.PRIMARY, surface = callsSurface())
  }
}

/** Recent: missed calls and history, grouped by day; lives on this Portal only. */
@Composable
internal fun CallsRecent(history: List<CallHistoryEntry>) {
  val context = LocalContext.current
  val p = LocalApprovedPalette.current
  var filter by rememberSaveable { mutableStateOf(CallHistoryFilter.ALL) }
  var confirm by remember { mutableStateOf(false) }
  val rows = CallHistory.filter(history, filter)
  val now = Calling.now()
  val zone = Calling.zone()
  Column(Modifier.fillMaxSize().padding(horizontal = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    CallsSegmented(CallHistoryFilter.entries.map { it.label }, filter.ordinal) { filter = CallHistoryFilter.entries[it] }
    Spacer(Modifier.height(24.dp))
    if (rows.isEmpty()) {
      Box(Modifier.weight(1f)) {
        CallsNotice("clock", if (filter == CallHistoryFilter.MISSED) "No missed calls" else "No calls yet",
            "Calls and drop-ins between rooms show up here.")
      }
    } else LazyColumn(Modifier.weight(1f).width(1200.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      val groups = rows.groupBy { CallingCopy.historyDay(it.atMs, now, zone) }
      groups.forEach { (day, list) ->
        item(key = "h-$day") {
          Text(day, color = p.secondary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 32.dp, top = 18.dp, bottom = 12.dp))
        }
        items(list, key = { it.id }) { e -> HistoryRow(e) }
      }
      item(key = "footer") {
        Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
          Text(CallingCopy.HISTORY_FOOTER, color = p.secondary, fontSize = 22.sp)
          CallPill(if (confirm) "Tap again to clear" else "Clear history", {
            if (confirm) { CallingStore.clearHistory(context); confirm = false } else confirm = true
          }, icon = "trash", style = if (confirm) CallButtonStyle.BAD else CallButtonStyle.DEFAULT, size = CallButtonSize.SM,
              surface = callsSurface())
        }
      }
    }
  }
}

@Composable
private fun HistoryRow(e: CallHistoryEntry) {
  val context = LocalContext.current
  val p = LocalApprovedPalette.current
  val time = remember(e.atMs) { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(e.atMs)) }
  val (icon, tint) = when {
    e.missed -> "inc" to p.error
    e.kind == CallKind.DROP_IN -> "door" to if (p.dark) HearthpaneMark.EmberLight else EMBER_TEXT
    e.direction == CallDirection.INCOMING -> "inc" to p.green
    else -> "phone" to p.green
  }
  Row(Modifier.fillMaxWidth().background(p.surface, RoundedCornerShape(24.dp)).padding(horizontal = 32.dp, vertical = 16.dp)
      .height(78.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(26.dp)) {
    Box(Modifier.size(64.dp).background(tint.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) { CallIcon(icon, tint, 32.dp) }
    Column(Modifier.weight(1f)) {
      Text(e.peerName, color = if (e.missed) p.error else p.text, fontSize = 29.sp, fontWeight = FontWeight.SemiBold)
      Text(CallingCopy.historyLine(e, time), color = p.secondary, fontSize = 22.5.sp)
    }
    if (e.missed) CallChip("Missed", tone = CallChipTone.RED)
    CallPill("Call back", { Calling.dial(context, e.peerId, CallKind.CALL, video = true) }, icon = "phone",
        style = CallButtonStyle.OK, size = CallButtonSize.SM, surface = callsSurface())
  }
}

private const val COLUMNS = 2
private const val PRESENCE_TICK_MS = 5_000L
private val EMBER_TEXT = androidx.compose.ui.graphics.Color(0xFFB4561C)
