/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.LocalApprovedPalette

/**
 * Left pane of the sheet: the large "Add an item" field (Portal on-screen keyboard), suggestions
 * while typing, "Used before" one-tap chips and the "Shared with" device list.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun HouseholdAddPane(view: HouseholdListView, toast: HouseholdListToast, typing: Boolean,
                              onTyping: (Boolean) -> Unit, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val palette = LocalApprovedPalette.current
  var draft by remember { mutableStateOf("") }
  val focus = remember { FocusRequester() }
  val keyboard = LocalSoftwareKeyboardController.current
  fun submit(text: String) {
    context.runListCommand(toast) { c, s, now -> c.add(s, text, now) }
    draft = "" // stays focused so several items can be added in a row
  }
  Column(modifier) {
    Row(Modifier.fillMaxWidth().height(108.dp).clip(RoundedCornerShape(54.dp)).background(palette.surface2)
        .tvFocusableRow { focus.requestFocus(); keyboard?.show() }.padding(start = 36.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
      Text("+", color = palette.secondary, fontSize = 40.sp, lineHeight = 44.sp)
      Box(Modifier.weight(1f)) {
        if (draft.isEmpty()) Text("Add an item", color = palette.secondary, fontSize = 32.sp, lineHeight = 40.sp)
        BasicTextField(draft, { draft = it.take(HouseholdListText.MAX_TEXT) }, singleLine = true,
            textStyle = TextStyle(color = palette.text, fontSize = 32.sp, lineHeight = 40.sp),
            cursorBrush = SolidColor(palette.accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (draft.isNotBlank()) submit(draft) else { keyboard?.hide(); onTyping(false) } }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { onTyping(it.isFocused) })
      }
      if (draft.isNotBlank()) ApprovedHomePillButton("Add") { submit(draft) }
    }
    if (typing) {
      val suggestions = remember(draft, view.list) { suggestionsFor(view.list, draft) }
      if (suggestions.isNotEmpty()) {
        Spacer(Modifier.height(20.dp))
        ChipFlow(suggestions) { submit(it) }
      }
      return@Column
    }
    val used = remember(view.list) { HouseholdListOps.usedBefore(view.list, limit = 6) }
    if (used.isNotEmpty()) {
      Spacer(Modifier.height(44.dp))
      Text("Used before", color = palette.secondary, fontSize = 20.sp, lineHeight = 26.sp)
      Spacer(Modifier.height(16.dp))
      ChipFlow(used) { submit(it) }
    }
    Spacer(Modifier.weight(1f))
    SharedWith(view)
  }
}

/** Up to three earlier names starting with what's typed (or the top "Used before" when empty). */
internal fun suggestionsFor(s: HouseholdListState, typed: String): List<String> {
  val pool = HouseholdListOps.usedBefore(s, limit = 50)
  val n = HouseholdListText.norm(typed)
  if (n.isEmpty()) return pool.take(3)
  return pool.filter { HouseholdListText.norm(it).startsWith(n) && HouseholdListText.norm(it) != n }.take(3)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(labels: List<String>, onPick: (String) -> Unit) {
  val palette = LocalApprovedPalette.current
  FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
    labels.forEach { label ->
      Row(Modifier.height(76.dp).clip(RoundedCornerShape(38.dp)).background(palette.surface2)
          .tvFocusableRow { onPick(label) }.padding(horizontal = 24.dp),
          verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("+", color = palette.secondary, fontSize = 28.sp)
        Text(label, color = palette.text, fontSize = 28.sp, lineHeight = 34.sp, maxLines = 1)
      }
    }
  }
}

@Composable
private fun SharedWith(view: HouseholdListView) {
  val palette = LocalApprovedPalette.current
  val peers = view.sync.peers.values.sortedBy { it.name }
  val phones = view.sync.phones.filter { view.now - it.value <= HouseholdListSyncState.PHONE_WINDOW_MS }.keys.sorted()
  if (peers.isEmpty() && phones.isEmpty()) return
  Text("Shared with", color = palette.secondary, fontSize = 20.sp, lineHeight = 26.sp)
  Spacer(Modifier.height(12.dp))
  peers.forEach { p ->
    DeviceRow(p.name, ok = p.reachable && view.sync.online,
        why = when { !view.sync.online -> "Waiting for network"; p.reachable -> "Synced"; else -> "Not answering" })
  }
  phones.forEach { DeviceRow(it, ok = true, why = "Synced") }
}

@Composable
private fun DeviceRow(name: String, ok: Boolean, why: String) {
  val palette = LocalApprovedPalette.current
  Row(Modifier.fillMaxWidth().height(54.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    Box(Modifier.size(14.dp).clip(CircleShape).background(if (ok) HouseholdListColors.Green else palette.secondary))
    Text(name, color = palette.text, fontSize = 24.sp, lineHeight = 30.sp, modifier = Modifier.weight(1f))
    Text(why, color = palette.secondary, fontSize = 22.sp, lineHeight = 28.sp)
  }
}
