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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import java.text.DateFormat
import java.util.Date

/** `missedScr`: missed calls and video messages in one list, with a preview card (Portal+) or rows (Mini). */
@Composable
internal fun CallMessagesTab() {
  val context = LocalContext.current
  LaunchedEffect(Unit) { CallMessageStore.ensureLoaded(context); CallMessageStore.prune(context) }
  val messages by CallMessageStore.messages.collectAsState()
  val history by CallingStore.history.collectAsState()
  val items = remember(messages, history) { CallInbox.items(messages, history) }
  var selected by rememberSaveable { mutableStateOf<String?>(null) }
  val compact = currentLayoutClass().compact
  if (items.isEmpty()) {
    CallsNotice("msg", "No missed calls or messages", "When nobody answers, callers can leave a video message here. " +
        "It stays on this Portal for 30 days.")
    return
  }
  if (compact) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 32.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      items(items, key = { it.key }) { InboxRow(it, selected = false, compact = true) { open(context, it) } }
      item(key = "footer") { Footer() }
    }
    return
  }
  val current = items.firstOrNull { it.key == selected } ?: items.first()
  Row(Modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, bottom = 40.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
    LazyColumn(Modifier.width(760.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      items(items, key = { it.key }) { InboxRow(it, selected = it.key == current.key, compact = false) { selected = it.key } }
      item(key = "footer") { Footer() }
    }
    Detail(current, Modifier.weight(1f).fillMaxSize())
  }
}

private fun open(context: android.content.Context, item: InboxItem) {
  when (item) {
    is InboxItem.Message -> CallMessages.play(context, item.m.id)
    is InboxItem.Missed -> Calling.dial(context, item.fromId, CallKind.CALL, video = true)
  }
}

@Composable
private fun Footer() {
  val p = LocalApprovedPalette.current
  Text(CallMessageCopy.FOOTER, color = p.secondary, fontSize = 22.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 24.dp))
}

@Composable
private fun InboxRow(item: InboxItem, selected: Boolean, compact: Boolean, onClick: () -> Unit) {
  val p = LocalApprovedPalette.current
  val time = remember(item.atMs) { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(item.atMs)) }
  val unseen = (item as? InboxItem.Message)?.m?.seen == false
  Row(Modifier.fillMaxWidth().background(if (selected) p.surface2 else p.surface, RoundedCornerShape(24.dp))
      .callClick("${item.fromName}, ${line(item, time)}", true, onClick).padding(horizontal = if (compact) 22.dp else 32.dp, vertical = 16.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 18.dp else 26.dp)) {
    CallRoomAvatar(item.fromName, if (compact) 80.dp else 96.dp)
    Column(Modifier.weight(1f)) {
      Text(item.fromName, color = p.text, fontSize = 29.sp, fontWeight = if (unseen) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1,
          overflow = TextOverflow.Ellipsis)
      Text(line(item, time), color = p.secondary, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    when (item) {
      is InboxItem.Message -> CallChip(CallingCopy.clock(item.m.durationMs), "msg", if (unseen) CallChipTone.OK else CallChipTone.PLAIN,
          plainFill = p.surface2, plainInk = p.secondary)
      is InboxItem.Missed -> CallChip("Missed", "phone", CallChipTone.RED)
    }
  }
}

private fun line(item: InboxItem, time: String): String = when (item) {
  is InboxItem.Message -> CallMessageCopy.line(item.m, time)
  is InboxItem.Missed -> "$time · ${if (item.e.reason == CallEndReason.QUIET) "quiet hours, nothing rang" else "no message left"}"
}

/** The preview card: dark picture area (Play for a message), then name, line, Watch / Call back / Delete. */
@Composable
private fun Detail(item: InboxItem, modifier: Modifier) {
  val context = LocalContext.current
  val p = LocalApprovedPalette.current
  val time = remember(item.atMs) { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(item.atMs)) }
  var confirm by remember(item.key) { mutableStateOf(false) }
  Column(modifier.clip(RoundedCornerShape(32.dp)).background(p.surface)) {
    Box(Modifier.weight(1f).fillMaxWidth()) {
      // A video message shows its first frame (decoded in memory from the sealed file); until then,
      // and for sound-only messages or missed calls, the room's colours and avatar.
      val poster = if (item is InboxItem.Message) rememberCallMessagePoster(item.m).value else null
      if (poster != null) {
        androidx.compose.foundation.Image(poster, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Color(0x33000000)))
      } else {
        CallRingBackground(item.fromName, motion = false)
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
          CallRoomAvatar(item.fromName, 180.dp)
          if (item is InboxItem.Missed) Text("No message left", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        }
      }
      if (item is InboxItem.Message) Box(Modifier.align(Alignment.Center).size(160.dp).background(Color(0x73000000), CircleShape)
          .callClick("Play message", true) { CallMessages.play(context, item.m.id) }, contentAlignment = Alignment.Center) {
        CallIcon("play", Color.White, 70.dp)
      }
      if (item is InboxItem.Message) Box(Modifier.align(Alignment.TopEnd).padding(28.dp)) { CallGlassChip(CallMessageCopy.STORED, "lock") }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 28.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)) {
      Column(Modifier.weight(1f)) {
        Text(item.fromName, color = p.text, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(when (item) { is InboxItem.Message -> CallMessageCopy.kind(item.m) + " · " + time; is InboxItem.Missed -> line(item, time) },
            color = p.secondary, fontSize = 23.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
      if (item is InboxItem.Message) {
        CallPill("Watch", { CallMessages.play(context, item.m.id) }, icon = "play", style = CallButtonStyle.PRIMARY, size = CallButtonSize.SM,
            surface = callsSurface())
        CallPill(if (confirm) "Tap to delete" else "Delete", {
          if (confirm) CallMessageStore.delete(context, item.m.id) else confirm = true
        }, icon = "trash", style = if (confirm) CallButtonStyle.BAD else CallButtonStyle.DEFAULT, size = CallButtonSize.SM, surface = callsSurface())
      }
      CallPill("Call back", { Calling.dial(context, item.fromId, CallKind.CALL, video = true) }, icon = "video", style = CallButtonStyle.OK,
          size = CallButtonSize.SM, surface = callsSurface())
    }
  }
}

/** The Home Calls widget's card for a new message: "Video message from Rose", "2:14 PM · left a 0:48 video message", Play. */
@Composable
internal fun CallMessageStrip(m: CallMessage) {
  val context = LocalContext.current
  val time = remember(m.atMs) { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(m.atMs)) }
  Row(Modifier.fillMaxWidth().background(Color(0x33000000), RoundedCornerShape(28.dp)).padding(horizontal = 24.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    CallRoomAvatar(m.fromName, 64.dp)
    Column(Modifier.weight(1f)) {
      Text("Missed call from ${m.fromName}", color = ApprovedHomePalette.Text, fontSize = 25.sp, fontWeight = FontWeight.SemiBold,
          maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(CallMessageCopy.line(m, time), color = ApprovedHomePalette.Secondary, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    CallPill("Play", { CallingStore.markSeen(context); CallMessages.play(context, m.id) }, icon = "play", style = CallButtonStyle.OK,
        size = CallButtonSize.SM)
  }
  Spacer(Modifier.height(12.dp))
}
