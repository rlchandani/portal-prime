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
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.settings.HouseholdListSettings
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import org.json.JSONObject

/** Item details, Clear done confirmation and the one-time migration dialog. */
@Composable
internal fun HouseholdListDialogs(dialog: ListDialog, view: HouseholdListView, toast: HouseholdListToast, onDismiss: () -> Unit) {
  val context = LocalContext.current
  DialogFrame(onDismiss) {
    when (dialog) {
      is ListDialog.Item -> ItemDetails(dialog.id, view, toast, onDismiss)
      ListDialog.ClearDone -> {
        val n = view.list.done().size
        DialogTitle(if (n == 1) "Clear 1 done item?" else "Clear $n done items?")
        DialogBody("They’re removed from the other Portals and everyone’s phone too. You can undo for a few seconds.")
        DialogActions {
          ApprovedHomeTextButton("Cancel", onClick = onDismiss)
          ApprovedHomePillButton("Clear done") {
            context.runListCommand(toast) { c, s, now -> c.clearDone(s, now) }
            onDismiss()
          }
        }
      }
      is ListDialog.Migration -> {
        DialogTitle(dialog.summary.title)
        DialogBody(dialog.summary.body)
        DialogActions { ApprovedHomePillButton("Show the list", onClick = onDismiss) }
      }
    }
  }
}

@Composable
private fun ItemDetails(id: String, view: HouseholdListView, toast: HouseholdListToast, onDismiss: () -> Unit) {
  val context = LocalContext.current
  val palette = LocalApprovedPalette.current
  val item = view.list.items[id]?.takeIf { it.alive }
  if (item == null) {
    DialogTitle("This item was removed")
    DialogBody("Someone removed it on another device a moment ago.")
    DialogActions { ApprovedHomePillButton("OK", onClick = onDismiss) }
    return
  }
  var renaming by remember { mutableStateOf<String?>(null) }
  val focus = remember { FocusRequester() }
  val draft = renaming
  if (draft != null) {
    Row(Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(48.dp)).background(palette.surface2)
        .padding(start = 32.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
      BasicTextField(draft, { renaming = it.take(HouseholdListText.MAX_TEXT) }, singleLine = true,
          textStyle = TextStyle(color = palette.text, fontSize = 32.sp), cursorBrush = SolidColor(palette.accent),
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
          keyboardActions = KeyboardActions(onDone = {
            context.runListCommand(toast) { c, s, now -> c.rename(s, id, draft, now) }; renaming = null
          }),
          modifier = Modifier.weight(1f).focusRequester(focus))
      ApprovedHomePillButton("Save") { context.runListCommand(toast) { c, s, now -> c.rename(s, id, draft, now) }; renaming = null }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
  } else DialogTitle(item.text.value)
  Spacer(Modifier.height(12.dp))
  DialogBody("Added by ${item.added.by}, ${HouseholdListTime.long(item.added.at, view.now)}")
  if (item.text.stamp.at > item.added.at) DialogBody("Renamed by ${item.text.stamp.by}, ${HouseholdListTime.long(item.text.stamp.at, view.now)}")
  if (item.done.value) DialogBody("Ticked by ${item.done.stamp.by}, ${HouseholdListTime.long(item.done.stamp.at, view.now)}")
  HouseholdListNotes.note(item)?.let { note ->
    Column(Modifier.fillMaxWidth().padding(top = 20.dp).clip(RoundedCornerShape(20.dp)).background(palette.surface2).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
      Text(note.text, color = palette.text, fontSize = 22.sp, lineHeight = 30.sp)
      if (note.kind == HouseholdListNotes.Kind.EDITED) {
        ApprovedHomePillButton("Use “${note.alt}” instead") { context.runListCommand(toast) { c, s, now -> c.useAlt(s, id, now) } }
        ApprovedHomeTextButton("Keep “${item.text.value}”") { context.runListCommand(toast) { c, s, now -> c.keepName(s, id, now) } }
      } else {
        ApprovedHomeTextButton("OK") { context.runListCommand(toast) { c, s, now -> c.acknowledgeKept(s, id, now) } }
      }
    }
  }
  DialogActions {
    if (draft == null) ApprovedHomeTextButton("Rename") { renaming = item.text.value }
    if (!item.done.value) ApprovedHomeTextButton("Move to top") { context.runListCommand(toast) { c, s, now -> c.moveToTop(s, id, now) } }
    ApprovedHomeTextButton("Remove") { context.runListCommand(toast) { c, s, now -> c.remove(s, id, now) }; onDismiss() }
    Spacer(Modifier.weight(1f))
    ApprovedHomePillButton("Done", onClick = onDismiss)
  }
}

@Composable
private fun DialogFrame(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
  val palette = LocalApprovedPalette.current
  Box(Modifier.fillMaxSize().background(Color(0x66000000)).clickable(MutableInteractionSource(), null, onClick = onDismiss),
      contentAlignment = Alignment.Center) {
    Column(Modifier.widthIn(max = 980.dp).fillMaxWidth(0.6f).clip(RoundedCornerShape(32.dp)).background(palette.surface)
        .clickable(MutableInteractionSource(), null) {}.padding(40.dp), content = content)
  }
}

@Composable private fun DialogTitle(text: String) =
    Text(text, color = LocalApprovedPalette.current.text, fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold)

@Composable private fun DialogBody(text: String) =
    Text(text, color = LocalApprovedPalette.current.secondary, fontSize = 22.sp, lineHeight = 30.sp, modifier = Modifier.padding(top = 8.dp))

@Composable private fun DialogActions(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically, content = content)

/** "Household list settings": the registry-driven rows for this Portal's display options. */
@Composable
internal fun HouseholdListSettingsPanel(view: HouseholdListView, onBack: () -> Unit, onClose: () -> Unit) {
  val context = LocalContext.current
  val palette = LocalApprovedPalette.current
  var snapshot by remember { mutableStateOf(HouseholdListConfig.load(context)) }
  Row(Modifier.fillMaxWidth().height(76.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(20.dp)) {
    RoundIconButton("back", "Back", onBack)
    Text("Household list settings", color = palette.text, fontSize = 40.sp, lineHeight = 48.sp,
        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
    RoundIconButton("x", "Close", onClose)
  }
  Spacer(Modifier.height(24.dp))
  Column(Modifier.widthIn(max = 1000.dp)) {
    SettingsRows(HouseholdListSettings.domain, snapshot) { key, value ->
      HouseholdListSettings.domain.apply(context, JSONObject().put(key, value))
      snapshot = HouseholdListConfig.load(context)
    }
    Spacer(Modifier.height(24.dp))
    Text("The list is the same everywhere. These display settings are for this Portal only. ${view.status.long}.",
        color = palette.secondary, fontSize = 22.sp, lineHeight = 30.sp)
  }
}
