/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.components.OnResume
import kotlinx.coroutines.delay

internal val ShareNoteAmber = Color(0xFFE8C35A)
private const val UNDO_MS = 6_000L
private const val CHIME_BUBBLE_MS = 4_000L

/** Home's view of shared notes: the newest note, plus the Dismiss → Undo window. */
@Stable
internal class ShareHomeState(
    val note: HomeNote?,
    val undoId: String?,
    val restored: Boolean,
    val dismiss: (HomeNote) -> Unit,
    val undo: () -> Unit,
)

/**
 * INTEGRATOR: call once in HomeActivity's Home tab, then feed [homeNotePlacement] with
 * `HomeSlots.emptySlots(widgets, 0).isNotEmpty()` and `editMode`. Applies the 4:00 AM clear with
 * one deadline timer (no polling) and on resume.
 */
@Composable
internal fun rememberShareHomeState(): ShareHomeState {
  val context = LocalContext.current.applicationContext
  val notes by remember { HomeNoteStore.notes(context) }.collectAsState()
  var undoId by remember { mutableStateOf<String?>(null) }
  var restored by remember { mutableStateOf(false) }
  OnResume { HomeNoteStore.clearIfDue(context) }
  LaunchedEffect(notes.active) {
    val wait = HomeNoteStore.nextClearInMs(context) ?: return@LaunchedEffect
    delay(wait + 1_000)
    HomeNoteStore.clearIfDue(context)
  }
  LaunchedEffect(undoId) { if (undoId != null) { delay(UNDO_MS); undoId = null } }
  LaunchedEffect(restored) { if (restored) { delay(UNDO_MS / 2); restored = false } }
  return ShareHomeState(notes.newest, undoId, restored,
      dismiss = { ShareFeature.dismiss(context, it.id); undoId = it.id; restored = false },
      undo = { undoId?.let { ShareFeature.undo(context, it) }; undoId = null; restored = true })
}

/** The note in a free page-1 slot (1 column × full height), like the prototype's amber card. */
@Composable
internal fun HomeNoteCard(state: ShareHomeState, modifier: Modifier = Modifier) {
  val note = state.note ?: return
  val context = LocalContext.current
  val zone = remember { HomeNoteStore.zone() }
  val shape = RoundedCornerShape(24.dp)
  Box(modifier.border(3.dp, ShareNoteAmber, shape).background(ApprovedHomePalette.Card, shape)) {
    Column(Modifier.fillMaxSize().padding(30.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (note.url != null) "Link" else "Note", color = ApprovedHomePalette.Secondary, fontSize = 24.sp,
            modifier = Modifier.weight(1f))
        DismissButton { state.dismiss(note) }
      }
      Text(note.text ?: note.title ?: note.url.orEmpty(), color = ApprovedHomePalette.Text, fontSize = 30.sp, lineHeight = 40.sp,
          overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp).weight(1f))
      note.url?.let { url -> ApprovedHomeTextButton("Open", onClick = { ShareFeature.openLink(context, url) }) }
      Text(HomeNoteTime.byline(note, zone), color = ApprovedHomePalette.Secondary, fontSize = 20.sp)
    }
  }
}

/** The note as a one-line strip in the greeting row, when page 1 has no free slot. */
@Composable
internal fun HomeNoteStrip(state: ShareHomeState, modifier: Modifier = Modifier) {
  val note = state.note ?: return
  val context = LocalContext.current
  val zone = remember { HomeNoteStore.zone() }
  val shape = RoundedCornerShape(22.dp)
  Row(modifier.fillMaxWidth().heightIn(min = 96.dp).border(3.dp, ShareNoteAmber, shape)
      .background(ApprovedHomePalette.Card, shape).padding(start = 28.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
      verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text(note.text ?: note.title ?: note.url.orEmpty(), color = ApprovedHomePalette.Text, fontSize = 26.sp,
          maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(HomeNoteTime.byline(note, zone), color = ApprovedHomePalette.Secondary, fontSize = 20.sp)
    }
    note.url?.let { url -> ApprovedHomeTextButton("Open", onClick = { ShareFeature.openLink(context, url) }) }
    DismissButton { state.dismiss(note) }
  }
}

@Composable
private fun DismissButton(onClick: () -> Unit) {
  Box(Modifier.size(64.dp).semantics { contentDescription = "Dismiss note" }
      .tvFocusable(CircleShape, onClick = onClick).background(ApprovedHomePalette.Chrome, CircleShape),
      contentAlignment = Alignment.Center) {
    Text("✕", color = ApprovedHomePalette.Text, fontSize = 24.sp)
  }
}

/** "Note dismissed · Undo" for 6 s, then "Note is back on Home" after Undo. Place over the dock. */
@Composable
internal fun ShareHomeToasts(state: ShareHomeState, modifier: Modifier = Modifier) {
  val shape = RoundedCornerShape(40.dp)
  when {
    state.undoId != null -> Row(modifier.background(Color(0xFFF1F2F4), shape).padding(start = 34.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Text("Note dismissed", color = Color(0xFF16181C), fontSize = 24.sp)
      Box(Modifier.heightIn(min = 56.dp).tvFocusable(RoundedCornerShape(28.dp), onClick = state.undo)
          .padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text("Undo", color = Color(0xFF0B6B57), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
      }
    }
    state.restored -> Box(modifier.background(Color(0xFFF1F2F4), shape).padding(horizontal = 34.dp, vertical = 20.dp)) {
      Text("✓  Note is back on Home", color = Color(0xFF16181C), fontSize = 24.sp)
    }
  }
}

/** The "Soft chime" bubble in the header for a few seconds after a note chimed. */
@Composable
internal fun HomeNoteChimeBubble(modifier: Modifier = Modifier) {
  val chimedAt by HomeNoteStore.chimedAt.collectAsState()
  var visible by remember { mutableStateOf(false) }
  LaunchedEffect(chimedAt) {
    if (chimedAt == 0L) return@LaunchedEffect
    visible = true; delay(CHIME_BUBBLE_MS); visible = false
  }
  if (!visible) return
  Box(modifier.border(2.dp, ShareNoteAmber, RoundedCornerShape(36.dp)).padding(horizontal = 26.dp, vertical = 12.dp)) {
    Text("🔔  Soft chime", color = ApprovedHomePalette.Text, fontSize = 24.sp)
  }
}

/** Tools → Shared from phones: notes that left Home, with "Put back on Home". */
@Composable
internal fun SharedFromPhonesPane(modifier: Modifier = Modifier) {
  val context = LocalContext.current.applicationContext
  val notes by remember { HomeNoteStore.notes(context) }.collectAsState()
  val zone = remember { HomeNoteStore.zone() }
  Column(modifier.padding(30.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Text("Shared from phones", color = ApprovedHomePalette.Text, fontSize = 32.sp, fontWeight = FontWeight.Medium)
    if (notes.active.isEmpty() && notes.gone.isEmpty())
      Text("Notes and links shared from Portal Toolkit show up here.", color = ApprovedHomePalette.Secondary, fontSize = 22.sp)
    notes.active.forEach { NoteRow(it, "On Home · ${HomeNoteTime.byline(it, zone)}", null) }
    notes.gone.forEach { gone ->
      val why = when (gone.why) {
        HomeNotes.CLEARED -> "Cleared overnight"
        HomeNotes.RETRACTED -> "Taken back from the phone"
        else -> "Dismissed"
      }
      NoteRow(gone.note, "$why · ${HomeNoteTime.clock(gone.at, zone)}") { ShareFeature.undo(context, gone.note.id) }
    }
  }
}

@Composable
private fun NoteRow(note: HomeNote, line: String, putBack: (() -> Unit)?) {
  ApprovedHomeSurface(Modifier.fillMaxWidth()) {
    Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(note.text ?: note.title ?: note.url.orEmpty(), color = ApprovedHomePalette.Text, fontSize = 24.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.size(4.dp))
        Text(line, color = ApprovedHomePalette.Secondary, fontSize = 20.sp)
      }
      putBack?.let { ApprovedHomeTextButton("Put back on Home", onClick = it) }
    }
  }
}
