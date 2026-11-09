/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.youtube.YouTubeMusicLibrary
import com.immortal.launcher.youtube.YtAccount
import kotlinx.coroutines.launch

/** Gap between a popover and the control that opened it (dp). */
private const val POP_GAP = 16f
/** The Move / follow-me cards stop this far above the sheet's bar (dp). */
private const val CARD_ABOVE_BAR = 20f

/** Everything the closed-view popovers need. */
internal data class MusicV3PopoverInputs(
    val library: YouTubeMusicLibrary,
    val accounts: List<YtAccount>,
    val active: YtAccount?,
    val queueHasItems: Boolean,
    val sleep: MusicSleepTimer?,
    /** The Move / Sleep capsules' bounds in stage pixels (the sleep chips open above them). */
    val utilBounds: Rect?,
    val onDismiss: () -> Unit,
    val onToast: (String) -> Unit,
    val onAddAccount: () -> Unit,
    val onLastSignedOut: () -> Unit,
    val onMoveTo: (MusicMoveRoom) -> Unit,
)

/**
 * The open popover, drawn below the player in z-order and placed only inside zones the geometry
 * proves clear of it: the right-hand column (sleep chips, Move card) and the account menu rect.
 */
@Composable
internal fun BoxScope.MusicV3StagePopover(layout: MusicStageLayout, popover: MusicPopover, inputs: MusicV3PopoverInputs) {
  if (popover == MusicPopover.NONE) return
  MusicStageDismissLayer(inputs.onDismiss)
  when (popover) {
    MusicPopover.SLEEP -> SleepChips(layout, inputs)
    MusicPopover.MOVE -> ColumnBottomCard(layout) {
      MusicV3MoveCard(onPick = inputs.onMoveTo, onClose = inputs.onDismiss, modifier = Modifier.fillMaxWidth())
    }
    MusicPopover.ACCOUNTS -> AccountsMenu(layout, inputs)
    MusicPopover.NONE -> Unit
  }
}

/** Chips just above the capsules, inside the right column (falls back to the column's bottom). */
@Composable
private fun SleepChips(layout: MusicStageLayout, inputs: MusicV3PopoverInputs) {
  val density = LocalDensity.current
  val anchorTopDp = inputs.utilBounds?.let { with(density) { it.top.toDp().value } } ?: layout.sheetClosedTop
  Box(Modifier.stageOffset { layout.columnX to layout.spec.top }.width(layout.columnW.dp)
      .height((anchorTopDp - POP_GAP - layout.spec.top).coerceAtLeast(0f).dp)) {
    MusicV3SleepPopover(inputs.sleep, Modifier.align(Alignment.BottomStart).widthIn(max = layout.columnW.dp)) { choice ->
      inputs.onDismiss()
      MusicSleepDriver.set(choice)
    }
  }
}

/** A card filling the right column's width, bottom-aligned just above the sheet's bar. */
@Composable
internal fun ColumnBottomCard(layout: MusicStageLayout, content: @Composable BoxScope.() -> Unit) {
  Box(Modifier.stageOffset { layout.columnX to layout.spec.top }.width(layout.columnW.dp)
      .height((layout.sheetClosedTop - CARD_ABOVE_BAR - layout.spec.top).dp)) {
    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(), content = content)
  }
}

/** Accounts wiring: switching keeps the music; signing out the account in use stops it. */
@Composable
private fun AccountsMenu(layout: MusicStageLayout, inputs: MusicV3PopoverInputs) {
  val scope = rememberCoroutineScope()
  var confirming by remember { mutableStateOf<YtAccount?>(null) }
  BackHandler(enabled = confirming != null) { confirming = null }
  val actions = MusicV3AccountActions(
      switchTo = { a ->
        inputs.onDismiss()
        scope.launch { if (inputs.library.switchAccount(a.id)) inputs.onToast("Now using ${a.title}'s library") }
      },
      askSignOut = { confirming = it },
      confirmSignOut = { a ->
        confirming = null
        if (a.id == inputs.active?.id) YouTubeMusicEngine.clear()
        if (inputs.accounts.size <= 1) inputs.onLastSignedOut()
        scope.launch {
          inputs.library.signOut(a.id)
          inputs.onToast("${a.title} is signed out")
        }
      },
      cancelSignOut = { confirming = null },
      add = inputs.onAddAccount,
      close = inputs.onDismiss,
  )
  val rect = layout.accountsMenu
  Box(Modifier.stageOffset { rect.left to rect.top }.width(rect.width.dp).heightCap(rect.height)) {
    MusicV3AccountsPanel(inputs.accounts, inputs.active, confirming, inputs.queueHasItems, actions, Modifier.fillMaxWidth())
  }
}

/** Never taller than [maxDp]: keeps a card inside its proven-clear zone. */
private fun Modifier.heightCap(maxDp: Float): Modifier = layout { m, c ->
  val cap = maxDp.dp.roundToPx().coerceAtMost(c.maxHeight)
  val p = m.measure(c.copy(maxHeight = cap))
  layout(p.width, p.height) { p.place(0, 0) }
}

/** Follow-me while something is loaded and the sheet is closed: a card in the right column. */
@Composable
internal fun MusicV3FollowCard(layout: MusicStageLayout, ask: MusicFollowAsk) {
  val ink = LocalMusicStageInk.current
  val dims = LocalMusicStageDims.current
  ColumnBottomCard(layout) {
    MusicStageCard(Modifier.fillMaxWidth()) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MusicV3GlyphIcon(MusicV3Glyph.FOLLOW, ink.ember, Modifier.width(28.dp).height(28.dp))
        Text(ask.line, color = ink.ember, fontSize = dims.small.sp, fontWeight = FontWeight.Bold, maxLines = 1)
      }
      Text(ask.title, color = ink.ink, fontSize = dims.heading.sp, fontWeight = FontWeight.Bold, maxLines = 2,
          modifier = Modifier.padding(top = 8.dp))
      Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        MusicStagePill(MusicV3StageCopy.CONTINUE, style = MusicPillStyle.EMBER, glyph = MusicV3Glyph.PLAY) {
          MusicV3FollowMeInScreen.answer(ask, continueHere = true)
        }
        MusicStagePill(MusicV3StageCopy.NOT_NOW) { MusicV3FollowMeInScreen.answer(ask, continueHere = false) }
      }
    }
  }
}
