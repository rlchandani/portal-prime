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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.youtube.YtAccount

/** Accounts panel callbacks (all run by the screen, which owns the library). */
internal data class MusicV3AccountActions(
    val switchTo: (YtAccount) -> Unit,
    val askSignOut: (YtAccount) -> Unit,
    val confirmSignOut: (YtAccount) -> Unit,
    val cancelSignOut: () -> Unit,
    val add: () -> Unit,
    val close: () -> Unit,
)

/**
 * "Accounts on this Portal", drawn inside the right pane only (with its own dim layer), so the
 * player on the left is never covered. [confirming] switches it to the sign-out confirm.
 */
@Composable
internal fun BoxScope.MusicV3AccountsPanel(
    accounts: List<YtAccount>,
    active: YtAccount?,
    confirming: YtAccount?,
    queueHasItems: Boolean,
    actions: MusicV3AccountActions,
) {
  val none = remember { MutableInteractionSource() }
  Box(Modifier.matchParentSize().background(ApprovedHomePalette.Background.copy(alpha = 0.6f))
      .clickable(none, indication = null, onClick = actions.close))
  val shape = RoundedCornerShape(32.dp)
  Column(Modifier.align(Alignment.TopEnd).padding(top = 0.dp, end = 16.dp).width(900.dp).clip(shape)
      .background(ApprovedHomePalette.Card).approvedOutline(shape)
      .clickable(none, indication = null) {}.padding(start = 36.dp, end = 36.dp, top = 32.dp, bottom = 28.dp)) {
    if (confirming != null) {
      SignOutConfirm(confirming, stopsMusic = confirming.id == active?.id && queueHasItems, actions)
    } else {
      AccountList(accounts, active, actions)
    }
  }
}

@Composable
private fun AccountList(accounts: List<YtAccount>, active: YtAccount?, actions: MusicV3AccountActions) {
  Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
    Text("Accounts on this Portal", color = ApprovedHomePalette.Text, fontSize = 36.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.weight(1f))
    MusicV3RoundButton("Close", size = 72.dp, fill = ApprovedHomePalette.Chrome, onClick = actions.close) {
      ApprovedIcon("close", Modifier.size(28.dp), ApprovedHomePalette.Text)
    }
  }
  accounts.forEach { account -> AccountRow(account, account.id == active?.id, actions) }
  if (MusicV3Rules.canAddAccount(accounts.size)) AddRow(actions.add)
  Text("Tap a name to switch. Each person sees their own playlists and liked songs.",
      color = ApprovedHomePalette.Secondary, fontSize = 22.sp, lineHeight = 30.sp,
      modifier = Modifier.padding(start = 12.dp, top = 18.dp))
}

@Composable
private fun AccountRow(account: YtAccount, inUse: Boolean, actions: MusicV3AccountActions) {
  val shape = RoundedCornerShape(22.dp)
  Row(Modifier.fillMaxWidth().padding(top = 6.dp).heightIn(min = 112.dp).clip(shape)
      .background(if (inUse) ApprovedHomePalette.Selected else Color.Transparent)
      .tvFocusableRow(focusFill = ApprovedHomePalette.Selected) { if (!inUse) actions.switchTo(account) }
      .semantics { role = Role.Button; contentDescription = if (inUse) "Using ${account.title}" else "Switch to ${account.title}" }
      .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(22.dp)) {
    MusicV3Avatar(account.title, account.thumbnailUrl, 72.dp)
    Column(Modifier.weight(1f)) {
      Text(account.title, color = ApprovedHomePalette.Text, fontSize = 28.sp, fontWeight = FontWeight.Medium, maxLines = 1)
      if (account.needsSignIn) Text("Needs to sign in again", color = ApprovedHomePalette.Secondary, fontSize = 22.sp)
    }
    if (inUse) {
      MusicV3GlyphIcon(MusicV3Glyph.CHECK, ApprovedHomePalette.Accent, Modifier.size(26.dp))
      Text("In use", color = ApprovedHomePalette.Accent, fontSize = 23.sp)
    }
    MusicV3Button("Sign out") { actions.askSignOut(account) }
  }
}

@Composable
private fun AddRow(onAdd: () -> Unit) {
  Row(Modifier.fillMaxWidth().padding(top = 6.dp).heightIn(min = 112.dp).clip(RoundedCornerShape(22.dp))
      .tvFocusableRow(focusFill = ApprovedHomePalette.Selected, onClick = onAdd)
      .semantics { role = Role.Button }.padding(horizontal = 12.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
    Box(Modifier.size(72.dp).clip(RoundedCornerShape(36.dp)).background(ApprovedHomePalette.Chrome),
        contentAlignment = Alignment.Center) { ApprovedIcon("plus", Modifier.size(32.dp), ApprovedHomePalette.Text) }
    Column {
      Text("Add another account", color = ApprovedHomePalette.Text, fontSize = 28.sp, fontWeight = FontWeight.Medium)
      Text("Sign in with a phone", color = ApprovedHomePalette.Secondary, fontSize = 22.sp)
    }
  }
}

@Composable
private fun SignOutConfirm(account: YtAccount, stopsMusic: Boolean, actions: MusicV3AccountActions) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
    MusicV3Avatar(account.title, account.thumbnailUrl, 72.dp)
    Text("Sign out ${account.title}?", color = ApprovedHomePalette.Text, fontSize = 36.sp, fontWeight = FontWeight.Medium)
  }
  Text(MusicV3Rules.signOutBody(account.title, stopsMusic), color = ApprovedHomePalette.Secondary, fontSize = 24.sp,
      lineHeight = 34.sp, modifier = Modifier.padding(top = 20.dp))
  Row(Modifier.fillMaxWidth().padding(top = 28.dp), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End)) {
    MusicV3Button("Cancel", onClick = actions.cancelSignOut)
    Spacer(Modifier.width(0.dp))
    MusicV3Button("Sign out", style = MusicV3ButtonStyle.DANGER) { actions.confirmSignOut(account) }
  }
}
