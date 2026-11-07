/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/**
 * Home's "Routines" widget (1×1): each routine's next run or today's result. Tapping opens the
 * Routines sheet with Start now; while a routine runs, tapping brings the takeover back.
 */
@Composable
internal fun RoutinesHomeWidget(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  remember(context) { RoutineEngine.init(context) }
  val revision = RoutineEngine.revision
  var now by remember { mutableLongStateOf(RoutineEngine.repo.clock.nowLocalMs()) }
  // The status copy only changes per minute; recompose once a minute while Home shows it.
  LaunchedEffect(Unit) { while (true) { delay(60_000 - now % 60_000); now = RoutineEngine.repo.clock.nowLocalMs() } }
  val doc = remember(revision, now) { RoutineEngine.repo.doc() }
  val runs = remember(revision, now) { RoutineEngine.repo.runs() }
  var sheet by remember { mutableStateOf(false) }
  val onOpen = { if (RoutineSchedule.active(runs) != null) RoutineEngine.reopen() else sheet = true }
  Column(modifier.padding(30.dp).clickable { onOpen() }) {
    ApprovedHomeWidgetHeading("Routines", "tools", HomeRefinements.HeaderAction.OPEN) { onOpen() }
    Spacer(Modifier.height(22.dp))
    val list = doc.routines.sortedBy { it.start }
    if (list.isEmpty()) {
      Text("No routines yet", color = ApprovedHomePalette.Text, fontSize = 26.sp, lineHeight = 34.sp)
      ApprovedHomeWidgetMessage("Make one in Toolkit on your phone")
    } else Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
      list.take(3).forEach { r -> WidgetRow(r, RoutineSchedule.widgetStatus(r, runs, now)) }
    }
  }
  if (sheet) RoutinesSheet(doc, runs, now) { sheet = false }
}

@Composable
private fun WidgetRow(r: Routine, status: RoutineSchedule.Status) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    Image(painterResource(RoutinePictureRes.pic(r.cards.firstOrNull()?.pic ?: "shirt")), null, Modifier.size(48.dp))
    Column(Modifier.weight(1f)) {
      Text(r.name, color = ApprovedHomePalette.Text, fontSize = 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text((if (status.tone == RoutineSchedule.Tone.DONE) "✓ " else "") + status.text, fontSize = 20.sp, maxLines = 1,
          overflow = TextOverflow.Ellipsis, color = when (status.tone) {
            RoutineSchedule.Tone.LIVE, RoutineSchedule.Tone.DONE -> RoutineColors.Green
            RoutineSchedule.Tone.NORMAL -> ApprovedHomePalette.Secondary
          })
    }
  }
}

/** The Routines sheet: every routine with its status and Start now (edits live in Toolkit). */
@Composable
internal fun RoutinesSheet(doc: RoutineDoc, runs: Map<String, RoutineRun>, now: Long, onClose: () -> Unit) {
  val busy = RoutineSchedule.active(runs) != null
  Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Column(Modifier.widthIn(max = 1400.dp).fillMaxWidth(0.8f).clip(RoundedCornerShape(40.dp))
        .background(RoutineColors.Sheet).border(1.dp, RoutineColors.Border, RoundedCornerShape(40.dp)).padding(48.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Routines", color = RoutineColors.Text, fontSize = 48.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(20.dp))
        Text("Edit them in Toolkit on your phone", color = RoutineColors.Muted, fontSize = 24.sp, modifier = Modifier.weight(1f))
        SheetButton("Close", solid = false, enabled = true, onClose)
      }
      Spacer(Modifier.height(28.dp))
      if (doc.routines.isEmpty()) {
        Text("No routines on this Portal", color = RoutineColors.Text, fontSize = 34.sp)
        Text("Open Toolkit on your phone, then Home › Routines › New routine.", color = RoutineColors.Muted, fontSize = 22.sp)
      }
      Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        doc.routines.sortedBy { it.start }.forEach { r ->
          Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(RoutineColors.Card).padding(24.dp),
              verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              r.cards.take(6).forEach { Image(painterResource(RoutinePictureRes.pic(it.pic)), null, Modifier.size(56.dp)) }
            }
            Column(Modifier.weight(1f)) {
              Text(r.name, color = RoutineColors.Text, fontSize = 34.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
              Text("${RoutineSchedule.daysText(r.days)}, ${RoutineDates.clock12(r.start)} to ${RoutineDates.clock12(r.end)}. " +
                  RoutineSchedule.statusLine(r, runs, now), color = RoutineColors.Muted, fontSize = 22.sp)
            }
            SheetButton("Start now", solid = true, enabled = !busy && r.enabled) { onClose(); RoutineEngine.startNow(r.id) }
          }
        }
      }
    }
  }
}

@Composable
private fun SheetButton(label: String, solid: Boolean, enabled: Boolean, onClick: () -> Unit) {
  Box(Modifier.heightIn(min = 72.dp).clip(RoundedCornerShape(36.dp))
      .background(if (solid) RoutineColors.Text.copy(alpha = if (enabled) 1f else 0.3f) else RoutineColors.Raised)
      .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
    Text(label, color = if (solid) RoutineColors.Bg else RoutineColors.Text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
  }
}

/**
 * The toast Home shows after a routine ("School morning done at 7:41", "closed for today" + Undo,
 * "closed at 8:25. 2 of 5 done."). Place it bottom-centre over Home; it hides itself.
 */
@Composable
internal fun RoutineHomeToast(modifier: Modifier = Modifier) {
  val t = RoutineEngine.toast ?: return
  if (RoutineEngine.screen != null || RoutineEngine.cheer != null) return
  Row(modifier.padding(bottom = 140.dp).clip(RoundedCornerShape(40.dp)).background(RoutineColors.Sheet)
      .border(1.dp, RoutineColors.Border, RoundedCornerShape(40.dp)).padding(horizontal = 34.dp, vertical = 20.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
    Text("✓", color = RoutineColors.Green, fontSize = 30.sp)
    Text(buildAnnotatedString {
      withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(t.name) }
      append(t.rest)
    }, color = RoutineColors.Text, fontSize = 28.sp)
    t.undoRid?.let { rid ->
      Text("Undo", color = RoutineColors.Green, fontSize = 28.sp, fontWeight = FontWeight.SemiBold,
          modifier = Modifier.clickable { RoutineEngine.undoClose(rid) }.padding(8.dp))
    }
  }
}
