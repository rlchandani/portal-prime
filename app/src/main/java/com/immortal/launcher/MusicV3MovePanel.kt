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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private const val ROOMS_TICK_MS = 2_000L

/**
 * "Move the music to…": the other Portals in the household (from the dinner roster), an opaque card
 * in the right-hand column (never near the player). Offline and quiet rooms are listed but disabled.
 */
@Composable
internal fun MusicV3MoveCard(onPick: (MusicMoveRoom) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val ink = LocalMusicStageInk.current
  val dims = LocalMusicStageDims.current
  val peers by DinnerCall.peers.collectAsState()
  val now by produceState(System.currentTimeMillis()) {
    while (true) { delay(ROOMS_TICK_MS); value = System.currentTimeMillis() }
  }
  LaunchedEffect(Unit) { MusicHandoff.refreshRooms(context) }
  val rooms = remember(peers, now) { MusicHandoff.rooms(context, now) }
  MusicStageCard(modifier) {
    MusicStageHeading("Move the music to…") { MusicStagePill("Close", onClick = onClose) }
    if (rooms.isEmpty()) {
      Text("Looking for other Portals…", color = ink.muted, fontSize = dims.body.sp,
          modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp))
    }
    rooms.forEach { room -> RoomRow(room) { onPick(room) } }
    Text("It keeps playing here until the other Portal starts it, from the same spot.",
        color = ink.muted, fontSize = dims.small.sp, lineHeight = (dims.small * 1.35f).sp,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp))
  }
}

@Composable
private fun RoomRow(room: MusicMoveRoom, onClick: () -> Unit) {
  val ink = LocalMusicStageInk.current
  val dims = LocalMusicStageDims.current
  Row(Modifier.fillMaxWidth().heightIn(min = dims.row.dp).clip(RoundedCornerShape(16.dp))
      .alpha(if (room.enabled) 1f else 0.45f)
      .tvFocusableRow(focusFill = ink.selected, enabled = room.enabled, onClick = onClick)
      .semantics {
        role = Role.Button
        contentDescription = if (room.enabled) "Move to ${room.name}" else "${room.name}, ${room.note}"
      }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(24.dp)) {
    Box(Modifier.padding(horizontal = 14.dp).size(16.dp).clip(CircleShape)
        .background(if (room.enabled) ReadyDot else ink.faint))
    Column(Modifier.weight(1f)) {
      Text(room.name, color = ink.ink, fontSize = dims.body.sp, fontWeight = FontWeight.SemiBold)
      Text(room.note ?: "Ready", color = ink.muted, fontSize = dims.small.sp)
    }
    if (room.enabled) MusicV3GlyphIcon(MusicV3Glyph.RIGHT, ink.muted, Modifier.size(32.dp))
  }
}

/** The mock's green "ready" dot. */
private val ReadyDot = Color(0xFF4CBF74)

/** Library/Search without an account: a queue moved here still plays; sign in for the rest. */
@Composable
internal fun MusicV3GuestPane(canSignIn: Boolean, onSignIn: () -> Unit) {
  MusicV3Empty("info", MusicV3Message("Sign in to see your library",
      "Music moved here from another Portal plays without an account."),
      action = if (canSignIn) "Sign in" to onSignIn else null)
}

/** What the Now Playing card needs for "Move to…": the state, the open action (null = hidden) and "Bring it back". */
internal data class MusicV3MoveControls(
    val state: MusicMoveState,
    val onMove: (() -> Unit)?,
    val onBringBack: () -> Unit,
) {
  /** Replaces "Playing on <room>" while something is moving or plays elsewhere. */
  val banner: String? get() = MusicMoveMachine.banner(state)

  companion object {
    const val MOVE_LABEL = "Move to…"
    val NONE = MusicV3MoveControls(MusicMoveState.Idle, null) {}
  }
}

/** The first capsule under the volume: Move to… / Bring it back (disabled while a move is under way). */
@Composable
internal fun MusicV3MoveButton(move: MusicV3MoveControls) {
  val onMove = move.onMove
  when {
    move.state is MusicMoveState.Handed -> MusicStagePill(MusicV3StageCopy.BRING_BACK, style = MusicPillStyle.SOLID,
        icon = "back", onClick = move.onBringBack)
    onMove != null -> MusicStagePill(MusicV3MoveControls.MOVE_LABEL, glyph = MusicV3Glyph.MOVE, onClick = onMove)
    move.state != MusicMoveState.Idle -> MusicStagePill(MusicV3MoveControls.MOVE_LABEL, glyph = MusicV3Glyph.MOVE,
        enabled = false) {}
  }
}
