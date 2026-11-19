/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/** `msgPlayScr`: full-screen playback with play/pause, progress, Reply, Call back and Delete. */
@Composable
internal fun CallMessagePlayScreen(m: CallMessage, onClose: () -> Unit) {
  val context = LocalContext.current
  val player = remember(m.id) { runCatching { CallMessagePlayers.stored(context, m) }.getOrNull() }
  var playing by remember { mutableStateOf(false) }
  var position by remember { mutableLongStateOf(0L) }
  var confirmDelete by remember { mutableStateOf(false) }
  val layout = currentLayoutClass()
  val edge = if (layout.compact) CallsCompactLayout.edge(layout).dp else 40.dp
  DisposableEffect(player) {
    val listener = object : Player.Listener {
      override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
    }
    player?.addListener(listener)
    player?.play()
    onDispose { player?.removeListener(listener); player?.release() }
  }
  LaunchedEffect(player) { while (player != null) { position = player.currentPosition; delay(POSITION_MS) } }
  BackHandler(onBack = onClose)
  val time = remember(m.atMs) { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(m.atMs)) }
  Box(Modifier.fillMaxSize()) {
    if (player != null && !m.audioOnly) CallMessageVideo(player, Modifier.fillMaxSize())
    else Box(Modifier.fillMaxSize()) {
      CallRingBackground(m.fromName, motion = playing)
      Box(Modifier.align(Alignment.Center)) { CallRipple(320.dp, motion = playing) { CallRoomAvatar(m.fromName, 240.dp) } }
    }
    if (player == null) Box(Modifier.align(Alignment.Center).padding(top = 360.dp)) {
      CallChip("This message can’t be played on this Portal any more", "warn", CallChipTone.WARN)
    }
    Box(Modifier.align(Alignment.TopStart).padding(edge).size(88.dp).background(Color(0x73000000), CircleShape)
        .callClick("Close", true, onClose), contentAlignment = Alignment.Center) { CallIcon("x", Color.White, 38.dp) }
    Column(Modifier.align(Alignment.TopCenter).padding(top = edge + if (layout.compactPortrait) 110.dp else 0.dp)
        .background(CallColors.Bar, RoundedCornerShape(36.dp)).padding(horizontal = 30.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
      Text(m.fromName, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text("${CallMessageCopy.kind(m)} · $time", color = Color.White.copy(alpha = 0.75f), fontSize = 24.sp, maxLines = 1)
    }
    if (!layout.compact) Box(Modifier.align(Alignment.TopEnd).padding(edge)) { CallGlassChip(CallMessageCopy.STORED, "lock") }
    val controls = playControls(m, playing, position, confirmDelete,
        onPlay = { player?.let { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_ENDED) it.seekTo(0); it.play() } } },
        onReply = { onClose(); CallMessages.compose(context, CallContact(m.fromId, m.fromName)) },
        onCall = { onClose(); Calling.dial(context, m.fromId, CallKind.CALL, video = true) },
        onDelete = { if (confirmDelete) { CallMessageStore.delete(context, m.id); onClose() } else confirmDelete = true })
    val bar = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(edge)
        .background(CallColors.Controls, RoundedCornerShape(48.dp)).padding(horizontal = 32.dp, vertical = 24.dp)
    if (layout.compactPortrait) Column(bar, verticalArrangement = Arrangement.spacedBy(20.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) { controls.progress(this) }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)) { controls.actions(this) }
    } else Row(bar, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
      controls.progress(this); controls.actions(this)
    }
  }
}

private class PlayControls(val progress: @Composable RowScope.() -> Unit, val actions: @Composable RowScope.() -> Unit)

private fun playControls(m: CallMessage, playing: Boolean, position: Long, confirmDelete: Boolean, onPlay: () -> Unit,
                         onReply: () -> Unit, onCall: () -> Unit, onDelete: () -> Unit) = PlayControls(
    progress = {
      Box(Modifier.size(100.dp).background(Color.White, CircleShape).callClick(if (playing) "Pause" else "Play", true, onPlay),
          contentAlignment = Alignment.Center) { CallIcon(if (playing) "pause" else "play", Color(0xFF14161B), 44.dp) }
      Text(CallingCopy.clock(position), color = Color.White, fontSize = 24.sp)
      val share = (position.toFloat() / m.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f)
      Box(Modifier.weight(1f).height(12.dp).background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(50))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(share).background(Color.White, RoundedCornerShape(50)))
      }
      Text(CallingCopy.clock(m.durationMs), color = Color.White, fontSize = 24.sp)
    },
    actions = {
      if (CallMessages.canSend(CallContact(m.fromId, m.fromName)))
        CallPill("Reply", onReply, icon = "msg", style = CallButtonStyle.GLASS, size = CallButtonSize.SM)
      CallPill("Call ${m.fromName.substringBefore(' ')}", onCall, icon = "video", style = CallButtonStyle.OK, size = CallButtonSize.SM)
      CallPill(if (confirmDelete) "Tap to delete" else "Delete", onDelete, icon = "trash",
          style = if (confirmDelete) CallButtonStyle.BAD else CallButtonStyle.GLASS, size = CallButtonSize.SM)
    },
)

private const val POSITION_MS = 250L
