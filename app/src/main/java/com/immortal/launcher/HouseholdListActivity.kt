/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import com.immortal.launcher.ui.theme.PortalPrimeTheme

/**
 * The full Household list sheet over Home (translucent window, scrim, rounded sheet). Opened by
 * the widget, the Tools "Household list" tile (To-do's old home) and the widget's Settings.
 */
class HouseholdListActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val settings = intent.getBooleanExtra(EXTRA_SETTINGS, false)
    setContent {
      PortalPrimeTheme(darkTheme = false) {
        if (rememberGuestHides(GuestContent.HOUSEHOLD_LIST)) {
          GuestHiddenScreen("The household list is hidden while guests are here.", ::finish)
        } else HouseholdListSheet(startInSettings = settings, onClose = ::finish)
      }
    }
  }

  override fun onResume() {
    super.onResume()
    HouseholdListSync.signal(this) // opening the list is a good moment to check we're current
  }

  companion object {
    private const val EXTRA_SETTINGS = "settings"

    fun open(context: Context, settings: Boolean = false) {
      runCatching {
        context.startActivity(Intent(context, HouseholdListActivity::class.java)
            .putExtra(EXTRA_SETTINGS, settings).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      }
    }
  }
}

/** Which overlay is up inside the sheet. */
internal sealed class ListDialog {
  data class Item(val id: String) : ListDialog()
  object ClearDone : ListDialog()
  data class Migration(val summary: HouseholdListMigration.Summary) : ListDialog()
}

@Composable
internal fun HouseholdListSheet(startInSettings: Boolean, onClose: () -> Unit) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val view = rememberHouseholdListView()
  val toast = remember { HouseholdListToast() }
  var showSettings by remember { mutableStateOf(startInSettings) }
  var dialog by remember { mutableStateOf<ListDialog?>(null) }
  var typing by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) {
    HouseholdListStore.pendingMigrationDialog(context)?.let {
      dialog = ListDialog.Migration(it)
      HouseholdListStore.markMigrationDialogSeen(context)
    }
  }
  val palette = LocalApprovedPalette.current
  val layoutClass = currentLayoutClass()
  val sheetFrame = if (layoutClass.compact) {
    Modifier.padding(start = 16.dp, end = 16.dp, top = if (layoutClass.compactPortrait) 96.dp else 72.dp, bottom = 16.dp)
  } else {
    Modifier.padding(start = 24.dp, end = 24.dp, top = 108.dp, bottom = 24.dp)
  }
  val sheetInset = if (layoutClass.compact) {
    Modifier.padding(horizontal = 28.dp, vertical = 24.dp)
  } else {
    Modifier.padding(horizontal = 44.dp, vertical = 36.dp)
  }
  Box(Modifier.fillMaxSize().background(Color(0x99000000))
      .dismissScrim(onDismiss = onClose)) {
    Column(Modifier.fillMaxSize().then(sheetFrame)
        .clip(RoundedCornerShape(36.dp)).background(palette.background)
        .blockScrimTaps()
        .then(sheetInset)) {
      if (showSettings) {
        HouseholdListSettingsPanel(view, onBack = { showSettings = false }, onClose = onClose)
      } else if (layoutClass.compact) {
        CompactSheetHeader(view, onSettings = { showSettings = true }, onClose = onClose)
        SheetBanner(view)
        if (view.status.kind == HouseholdListSyncStatus.Kind.FIRST) FirstSyncBody(view)
        else CompactSheetBody(view, toast, typing, layoutClass.compactPortrait, onTyping = { typing = it },
            onOpenItem = { dialog = ListDialog.Item(it) }, onClearDone = { dialog = ListDialog.ClearDone })
      } else {
        SheetHeader(view, onSettings = { showSettings = true }, onClose = onClose)
        SheetBanner(view)
        if (view.status.kind == HouseholdListSyncStatus.Kind.FIRST) FirstSyncBody(view)
        else Row(Modifier.fillMaxSize().padding(top = 28.dp), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
          HouseholdAddPane(view, toast, typing, onTyping = { typing = it }, modifier = Modifier.width(540.dp).fillMaxHeight())
          HouseholdListColumns(view, toast, singleColumn = typing, onOpenItem = { dialog = ListDialog.Item(it) },
              onClearDone = { dialog = ListDialog.ClearDone }, modifier = Modifier.weight(1f).fillMaxHeight())
        }
      }
    }
    val toastPlace = if (layoutClass.compact) {
      Modifier.align(Alignment.BottomCenter).padding(start = 32.dp, end = 32.dp, bottom = 40.dp)
    } else {
      Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp)
    }
    HouseholdSheetToast(view, toast, toastPlace)
    dialog?.let { d ->
      HouseholdListDialogs(d, view, toast, onDismiss = { dialog = null })
    }
  }
}

@Composable
private fun SheetHeader(view: HouseholdListView, onSettings: () -> Unit, onClose: () -> Unit) {
  val palette = LocalApprovedPalette.current
  val status = view.status
  Row(Modifier.fillMaxWidth().height(76.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(20.dp)) {
    Text("Household list", color = palette.text, fontSize = 44.sp, lineHeight = 52.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.weight(1f))
    SyncGlyph(status.kind, if (status.warning) HouseholdListColors.Amber else HouseholdListColors.Green)
    Text(status.long, color = if (status.warning) HouseholdListColors.Amber else palette.secondary, fontSize = 24.sp, lineHeight = 32.sp)
    RoundIconButton("gear", "List settings", onSettings)
    RoundIconButton("x", "Close", onClose)
  }
}

/**
 * Compact body. Portrait: the add field and "Used before" on top, one list column below, "Shared with"
 * at the end of the list. Landscape: a narrower add pane beside a single list column.
 */
@Composable
private fun ColumnScope.CompactSheetBody(view: HouseholdListView, toast: HouseholdListToast, typing: Boolean, portrait: Boolean,
                                         onTyping: (Boolean) -> Unit, onOpenItem: (String) -> Unit, onClearDone: () -> Unit) {
  if (portrait) {
    Column(Modifier.fillMaxWidth().weight(1f).padding(top = 20.dp)) {
      HouseholdAddPane(view, toast, typing, onTyping = onTyping, modifier = Modifier.fillMaxWidth(),
          compact = true, sharedWith = false)
      HouseholdListColumns(view, toast, singleColumn = true, onOpenItem = onOpenItem, onClearDone = onClearDone,
          modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 24.dp), footer = { HouseholdSharedWith(view) })
    }
  } else {
    Row(Modifier.fillMaxWidth().weight(1f).padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
      HouseholdAddPane(view, toast, typing, onTyping = onTyping, compact = true,
          modifier = Modifier.width(COMPACT_ADD_PANE_W).fillMaxHeight().verticalScroll(rememberScrollState()))
      HouseholdListColumns(view, toast, singleColumn = true, onOpenItem = onOpenItem, onClearDone = onClearDone,
          modifier = Modifier.weight(1f).fillMaxHeight())
    }
  }
}

private val COMPACT_ADD_PANE_W = 440.dp

/** Compact header: title and buttons on one line, the sync status on its own line below. */
@Composable
private fun CompactSheetHeader(view: HouseholdListView, onSettings: () -> Unit, onClose: () -> Unit) {
  val palette = LocalApprovedPalette.current
  val status = view.status
  Row(Modifier.fillMaxWidth().height(76.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    Text("Household list", color = palette.text, fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.SemiBold,
        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    RoundIconButton("gear", "List settings", onSettings)
    RoundIconButton("x", "Close", onClose)
  }
  Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    SyncGlyph(status.kind, if (status.warning) HouseholdListColors.Amber else HouseholdListColors.Green)
    Text(status.long, color = if (status.warning) HouseholdListColors.Amber else palette.secondary, fontSize = 22.sp,
        lineHeight = 30.sp, modifier = Modifier.weight(1f))
  }
}

@Composable
internal fun RoundIconButton(icon: String, label: String, onClick: () -> Unit) {
  val palette = LocalApprovedPalette.current
  Box(Modifier.size(76.dp).clip(CircleShape).background(palette.surface2)
      .tvFocusable(CircleShape, ringColor = palette.accent, onClick = onClick)
      .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
    ApprovedHomeIcon(icon, Modifier.size(32.dp), tint = palette.text)
  }
}

@Composable
private fun SheetBanner(view: HouseholdListView) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val palette = LocalApprovedPalette.current
  val status = view.status
  val text = when (status.kind) {
    HouseholdListSyncStatus.Kind.OFFLINE -> "This Portal is offline. You can still add and tick. Changes sync when it reconnects."
    HouseholdListSyncStatus.Kind.FAILING -> status.long
    else -> return
  }
  Row(Modifier.fillMaxWidth().padding(top = 16.dp).clip(RoundedCornerShape(20.dp)).background(palette.surface2)
      .padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    SyncGlyph(status.kind, HouseholdListColors.Amber)
    Text(text, color = palette.text, fontSize = 22.sp, lineHeight = 30.sp, modifier = Modifier.weight(1f))
    if (status.kind == HouseholdListSyncStatus.Kind.FAILING) ApprovedHomePillButton("Try now") { HouseholdListSync.retryNow(context) }
  }
}

@Composable
private fun FirstSyncBody(view: HouseholdListView) {
  val palette = LocalApprovedPalette.current
  Column(Modifier.fillMaxSize().padding(top = 60.dp).verticalScroll(rememberScrollState())) {
    Text(view.status.long, color = palette.text, fontSize = 34.sp, lineHeight = 42.sp)
    Spacer(Modifier.height(12.dp))
    Text("This Portal was just paired. The list appears in a moment; nothing is lost.", color = palette.secondary,
        fontSize = 22.sp, lineHeight = 30.sp)
  }
}

@Composable
private fun HouseholdSheetToast(view: HouseholdListView, toast: HouseholdListToast, modifier: Modifier) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val palette = LocalApprovedPalette.current
  val current = toast.current?.takeIf { view.now - it.second < HouseholdListSyncState.NOTICE_MS }
  LaunchedEffect(current) { if (current != null) { kotlinx.coroutines.delay(HouseholdListSyncState.NOTICE_MS); toast.clear() } }
  val text = current?.first?.text ?: view.notice?.text ?: return
  val compact = currentLayoutClass().compact
  Row(modifier.widthIn(max = 1100.dp).clip(RoundedCornerShape(24.dp)).background(palette.surface2)
      .padding(horizontal = 28.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(20.dp)) {
    // Compact: the message gives way to Undo and wraps by whole words.
    Text(text, color = palette.text, fontSize = 24.sp, lineHeight = 32.sp,
        modifier = if (compact) Modifier.weight(1f, fill = false) else Modifier)
    current?.first?.undo?.let { undo ->
      ApprovedHomeTextButton("Undo") { context.runListCommand(toast) { c, s, now -> c.undo(s, undo, now) } }
    }
  }
}
