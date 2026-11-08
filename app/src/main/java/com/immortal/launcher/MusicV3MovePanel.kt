/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
 * "Move the music to…": the other Portals in the household (from the dinner roster), drawn in the
 * right pane only so the player stays uncovered. Offline and quiet rooms are listed but disabled.
 */
@Composable
internal fun BoxScope.MusicV3MovePanel(onPick: (MusicMoveRoom) -> Unit, onClose: () -> Unit) {
  val context = LocalContext.current
  val peers by DinnerCall.peers.collectAsState()
  val now by produceState(System.currentTimeMillis()) {
    while (true) { delay(ROOMS_TICK_MS); value = System.currentTimeMillis() }
  }
  LaunchedEffect(Unit) { MusicHandoff.refreshRooms(context) }
  val rooms = remember(peers, now) { MusicHandoff.rooms(context, now) }
  val none = remember { MutableInteractionSource() }
  Box(Modifier.matchParentSize().background(ApprovedHomePalette.Background.copy(alpha = 0.6f))
      .clickable(none, indication = null, onClick = onClose))
  val shape = RoundedCornerShape(32.dp)
  Column(Modifier.align(Alignment.TopEnd).padding(end = 16.dp).width(900.dp).clip(shape)
      .background(ApprovedHomePalette.Card).approvedOutline(shape)
      .clickable(none, indication = null) {}.padding(start = 36.dp, end = 36.dp, top = 32.dp, bottom = 28.dp)) {
    Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
      Text("Move the music to…", color = ApprovedHomePalette.Text, fontSize = 36.sp, fontWeight = FontWeight.Medium,
          modifier = Modifier.weight(1f))
      MusicV3RoundButton("Close", size = 72.dp, fill = ApprovedHomePalette.Chrome, onClick = onClose) {
        ApprovedIcon("close", Modifier.size(28.dp), ApprovedHomePalette.Text)
      }
    }
    if (rooms.isEmpty()) {
      Text("Looking for other Portals…", color = ApprovedHomePalette.Secondary, fontSize = 24.sp,
          modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp))
    }
    rooms.forEach { room -> RoomRow(room) { onPick(room) } }
    Text("It keeps playing here until the other Portal starts it, from the same spot.",
        color = ApprovedHomePalette.Secondary, fontSize = 22.sp, lineHeight = 30.sp,
        modifier = Modifier.padding(start = 12.dp, top = 18.dp))
  }
}

@Composable
private fun RoomRow(room: MusicMoveRoom, onClick: () -> Unit) {
  val shape = RoundedCornerShape(22.dp)
  Row(Modifier.fillMaxWidth().padding(top = 6.dp).heightIn(min = 104.dp).clip(shape)
      .alpha(if (room.enabled) 1f else 0.45f)
      .tvFocusableRow(focusFill = ApprovedHomePalette.Selected) { if (room.enabled) onClick() }
      .semantics {
        role = Role.Button
        contentDescription = if (room.enabled) "Move to ${room.name}" else "${room.name}, ${room.note}"
      }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(20.dp)) {
    MusicV3GlyphIcon(MusicV3Glyph.SPEAKER, ApprovedHomePalette.Secondary, Modifier.size(36.dp))
    Column(Modifier.weight(1f)) {
      Text(room.name, color = ApprovedHomePalette.Text, fontSize = 28.sp, fontWeight = FontWeight.Medium)
      room.note?.let { Text(it, color = ApprovedHomePalette.Secondary, fontSize = 22.sp) }
    }
    if (room.enabled) MusicV3GlyphIcon(MusicV3Glyph.RIGHT, ApprovedHomePalette.Secondary, Modifier.size(32.dp))
  }
}

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

/** Sits at the end of the volume row, like a speaker picker: Move to… / Bring it back. */
@Composable
internal fun MusicV3MoveButton(move: MusicV3MoveControls) {
  val onMove = move.onMove
  when {
    move.state is MusicMoveState.Handed -> MusicV3Button("Bring it back", style = MusicV3ButtonStyle.SOLID,
        onClick = move.onBringBack)
    onMove != null -> MusicV3Button(MusicV3MoveControls.MOVE_LABEL, onClick = onMove)
    move.state != MusicMoveState.Idle -> MusicV3Button(MusicV3MoveControls.MOVE_LABEL, enabled = false) {}
  }
}
