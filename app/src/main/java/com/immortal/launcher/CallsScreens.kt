/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.jarvis.LocalJarvisPulse
import com.immortal.launcher.jarvis.ScreenContext
import com.immortal.launcher.jarvis.jarvisPulse

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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.widthIn
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
  val phones by CallPhoneMailbox.phones.collectAsState()
  val self = remember { Calling.selfId(context).orEmpty() }
  val now = rememberCallClock(PRESENCE_TICK_MS)
  val layout = currentLayoutClass()
  when {
    !CallMediaRuntime.available() -> CallsNotice("warn", "Calls can’t run on this Portal",
        "Hearthpane Calling needs a 64-bit Portal. This one can still use Call everyone.")
    HouseholdKey.get(context) == null -> CallsNotice("key", "Calls aren’t set up yet",
        "Open Portal Toolkit on your phone and pair this Portal. It shares the household key, so only Portals in this home can call.")
    peers.isEmpty() -> CallsNotice("users", "Looking for other Portals…",
        "Rooms appear here when another Portal in this home answers. Check it’s on the same Wi-Fi.") {
      CallPill("Try again", { Calling.announce(context, ask = true) }, icon = "redo", surface = callsSurface())
    }
    layout.compact -> CallsRoomsCompact(peers, phones, self, now, layout, onEveryone)
    else -> LazyVerticalGrid(GridCells.Fixed(COLUMNS), Modifier.fillMaxSize(), contentPadding = PaddingValues(56.dp, 8.dp, 56.dp, 40.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp), verticalArrangement = Arrangement.spacedBy(32.dp)) {
      items(peers.sortedBy { it.name.lowercase() }, key = { it.id }) { peer -> RoomCard(peer, self, now) }
      items(phones, key = { it.id }) { phone -> CallPhoneCard(phone, now, compact = false) }
      if (phones.isNotEmpty()) item(span = { GridItemSpan(COLUMNS) }) { CallPhonesNote() }
      item(span = { GridItemSpan(COLUMNS) }) { EveryoneCard(onEveryone) }
      item(span = { GridItemSpan(COLUMNS) }) {
        Text(CallingCopy.CALLS_FOOTER, color = LocalApprovedPalette.current.secondary, fontSize = 22.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
      }
    }
  }
}

/**
 * Compact rooms (the Mini): one column upright, two turned; narrower padding, a smaller avatar,
 * and the call buttons wrap onto a second line rather than overflow a narrow card.
 */
@Composable
private fun CallsRoomsCompact(peers: List<CallPeer>, phones: List<CallPhone>, self: String, now: Long, layout: LayoutClass,
                              onEveryone: () -> Unit) {
  val columns = CallsCompactLayout.roomColumns(layout)
  val pad = CallsCompactLayout.pagePadding(layout).dp
  LazyVerticalGrid(GridCells.Fixed(columns), Modifier.fillMaxSize(), contentPadding = PaddingValues(pad, 4.dp, pad, 32.dp),
      horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
    items(peers.sortedBy { it.name.lowercase() }, key = { it.id }) { peer -> RoomCardCompact(peer, self, now) }
    items(phones, key = { it.id }) { phone -> CallPhoneCard(phone, now, compact = true) }
    if (phones.isNotEmpty()) item(span = { GridItemSpan(columns) }) { CallPhonesNote() }
    item(span = { GridItemSpan(columns) }) { EveryoneCardCompact(onEveryone, stacked = layout.compactPortrait) }
    item(span = { GridItemSpan(columns) }) {
      Text(CallingCopy.CALLS_FOOTER, color = LocalApprovedPalette.current.secondary, fontSize = 20.sp, textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoomCardCompact(peer: CallPeer, self: String, now: Long) {
  val context = LocalContext.current
  val p = LocalApprovedPalette.current
  val surface = callsSurface()
  val presence = CallPolicy.presence(peer, now)
  val drop = CallPolicy.dropInOn(peer, self)
  val calls = peer.acceptsCalls
  Column(Modifier.jarvisPulse(LocalJarvisPulse.current == ScreenContext.CALL_ROOM_TARGET + peer.id, 32.dp)
      .callPanel(p.surface, RoundedCornerShape(32.dp), p.outline).padding(28.dp).alpha(if (presence == CallPresence.OFFLINE) 0.72f else 1f),
      verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
      CallRoomAvatar(peer.name, 120.dp, presence, ring = p.surface)
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(peer.name, color = p.text, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          Box(Modifier.size(14.dp).background(CallColors.presence(presence), CircleShape))
          Text(presence.label, color = p.secondary, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
      CallPill("Video call", { Calling.dial(context, peer.id, CallKind.CALL, video = true) },
          Modifier.jarvisPulse(LocalJarvisPulse.current == ScreenContext.CALL_VIDEO_TARGET + peer.id, 48.dp), icon = "video",
          style = CallButtonStyle.OK, enabled = calls, surface = surface, size = CallButtonSize.SM)
      CallPill(when (drop) { DropInPolicy.CHIME -> "Drop in"; DropInPolicy.ASK -> "Ask to join"; DropInPolicy.OFF -> "Not allowed" },
          { Calling.dial(context, peer.id, CallKind.DROP_IN, video = true) }, icon = "door", enabled = calls && drop != DropInPolicy.OFF,
          surface = surface, size = CallButtonSize.SM)
      CallRoundButton("phone", "Audio call", { Calling.dial(context, peer.id, CallKind.CALL, video = false) }, surface = surface, size = 72.dp)
    }
  }
}

/** Compact Call everyone: upright the button goes under the text, so neither is squeezed. */
@Composable
private fun EveryoneCardCompact(onEveryone: () -> Unit, stacked: Boolean) {
  val p = LocalApprovedPalette.current
  val text: @Composable (Modifier) -> Unit = { m ->
    Column(m) {
      Text("Need everyone at once?", color = p.text, fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
      Text("Call everyone chimes a short message in every room. Drop-ins always chime and show a banner first.",
          color = p.secondary, fontSize = 21.sp)
    }
  }
  val panel = Modifier.fillMaxWidth().callPanel(p.surface, RoundedCornerShape(32.dp), p.outline).padding(28.dp)
  if (stacked) Column(panel, verticalArrangement = Arrangement.spacedBy(20.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
      CallIcon("users", p.secondary, 48.dp)
      text(Modifier.weight(1f))
    }
    CallPill("Call everyone", onEveryone, Modifier.align(Alignment.End), icon = "bell", style = CallButtonStyle.PRIMARY, surface = callsSurface())
  } else Row(panel, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
    CallIcon("users", p.secondary, 48.dp)
    text(Modifier.weight(1f))
    CallPill("Call everyone", onEveryone, icon = "bell", style = CallButtonStyle.PRIMARY, surface = callsSurface())
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
  Column(Modifier.jarvisPulse(LocalJarvisPulse.current == ScreenContext.CALL_ROOM_TARGET + peer.id, 32.dp)
      .callPanel(p.surface, RoundedCornerShape(32.dp), p.outline).padding(36.dp).alpha(if (presence == CallPresence.OFFLINE) 0.72f else 1f),
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
      CallPill("Video call", { Calling.dial(context, peer.id, CallKind.CALL, video = true) },
          Modifier.jarvisPulse(LocalJarvisPulse.current == ScreenContext.CALL_VIDEO_TARGET + peer.id, 48.dp), icon = "video",
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

/** Recent: calls, missed calls and video messages (received and sent), grouped by day; lives on this Portal only. */
@Composable
internal fun CallsRecent(history: List<CallHistoryEntry>) {
  val context = LocalContext.current
  val p = LocalApprovedPalette.current
  LaunchedEffect(Unit) { CallMessageStore.ensureLoaded(context) }
  val messages by CallMessageStore.messages.collectAsState()
  var filter by rememberSaveable { mutableStateOf(CallHistoryFilter.ALL) }
  var confirm by remember { mutableStateOf(false) }
  val rows = remember(history, messages, filter) { CallRecent.items(history, messages, filter) }
  val now = Calling.now()
  val zone = Calling.zone()
  val compact = currentLayoutClass().compact
  Column(Modifier.fillMaxSize().padding(horizontal = if (compact) 32.dp else 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    CallsSegmented(CallHistoryFilter.entries.map { it.label }, filter.ordinal) { filter = CallHistoryFilter.entries[it] }
    Spacer(Modifier.height(24.dp))
    if (rows.isEmpty()) {
      Box(Modifier.weight(1f)) {
        when (filter) {
          CallHistoryFilter.MISSED -> CallsNotice("clock", "No missed calls", "Calls and drop-ins between rooms show up here.")
          CallHistoryFilter.MESSAGES -> CallsNotice("msg", "No video messages", "Messages you send and receive show up here.")
          else -> CallsNotice("clock", "No calls yet", "Calls and drop-ins between rooms show up here.")
        }
      }
    } else LazyColumn(Modifier.weight(1f).then(if (compact) Modifier.widthIn(max = 1200.dp).fillMaxWidth() else Modifier.width(1200.dp)),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
      val groups = rows.groupBy { CallingCopy.historyDay(it.atMs, now, zone) }
      groups.forEach { (day, list) ->
        item(key = "h-$day") {
          Text(day, color = p.secondary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 32.dp, top = 18.dp, bottom = 12.dp))
        }
        items(list, key = { it.key }) { item -> RecentRow(item, compact) }
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

/**
 * One Recent row. Compact (Mini) keeps tighter padding and ellipsizes the words; the actions keep
 * their size. A row with a message gets Play (mock `historyScr` / `homemissed`: Watch + Call back).
 */
@Composable
private fun RecentRow(item: RecentItem, compact: Boolean) {
  val context = LocalContext.current
  val p = LocalApprovedPalette.current
  val time = remember(item.atMs) { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(item.atMs)) }
  val missed = item is RecentItem.Call && item.e.missed
  val (icon, tint) = when (item) {
    is RecentItem.Message -> "msg" to p.accent
    is RecentItem.Call -> when {
      item.e.missed -> "inc" to p.error
      item.e.kind == CallKind.DROP_IN -> "door" to if (p.dark) HearthpaneMark.EmberLight else EMBER_TEXT
      item.e.direction == CallDirection.INCOMING -> "inc" to p.green
      else -> "phone" to p.green
    }
  }
  val unseen = item.message?.let { !it.seen && !it.outgoing } == true
  Row(Modifier.fillMaxWidth().background(p.surface, RoundedCornerShape(24.dp))
      .padding(horizontal = if (compact) 22.dp else 32.dp, vertical = if (compact) 14.dp else 16.dp)
      .height(78.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 18.dp else 26.dp)) {
    val badge = if (compact) 60.dp else 64.dp
    Box(Modifier.size(badge).background(tint.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
      CallIcon(icon, tint, if (compact) 30.dp else 32.dp)
    }
    Column(Modifier.weight(1f)) {
      Text(item.peerName, color = if (missed) p.error else p.text, fontSize = if (compact) 27.sp else 29.sp,
          fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(CallRecent.line(item, time), color = if (unseen) p.text else p.secondary, fontSize = if (compact) 21.sp else 22.5.sp,
          maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (missed && !compact) CallChip("Missed", tone = CallChipTone.RED)
    item.message?.let { m ->
      CallPill("Play", { CallMessages.play(context, m.id) }, icon = "play", style = CallButtonStyle.PRIMARY,
          size = CallButtonSize.SM, surface = callsSurface())
    }
    CallPill("Call back", { Calling.dial(context, item.peerId, CallKind.CALL, video = true) }, icon = "phone",
        style = CallButtonStyle.OK, size = CallButtonSize.SM, surface = callsSurface())
  }
}

private const val COLUMNS = 2
private const val PRESENCE_TICK_MS = 5_000L
private val EMBER_TEXT = androidx.compose.ui.graphics.Color(0xFFB4561C)
