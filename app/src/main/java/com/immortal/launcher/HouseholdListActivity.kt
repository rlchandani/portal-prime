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
  Box(Modifier.fillMaxSize().background(Color(0x99000000))
      .clickable(MutableInteractionSource(), indication = null, onClick = onClose)) {
    Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 108.dp, bottom = 24.dp)
        .clip(RoundedCornerShape(36.dp)).background(palette.background)
        .clickable(MutableInteractionSource(), indication = null) {}
        .padding(horizontal = 44.dp, vertical = 36.dp)) {
      if (showSettings) {
        HouseholdListSettingsPanel(view, onBack = { showSettings = false }, onClose = onClose)
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
    HouseholdSheetToast(view, toast, Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp))
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
  Row(modifier.widthIn(max = 1100.dp).clip(RoundedCornerShape(24.dp)).background(palette.surface2)
      .padding(horizontal = 28.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(20.dp)) {
    Text(text, color = palette.text, fontSize = 24.sp, lineHeight = 32.sp)
    current?.first?.undo?.let { undo ->
      ApprovedHomeTextButton("Undo") { context.runListCommand(toast) { c, s, now -> c.undo(s, undo, now) } }
    }
  }
}
