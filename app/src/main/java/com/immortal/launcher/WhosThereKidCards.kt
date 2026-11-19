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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Kids' and teens' cards reuse the existing Chores and Routines data: recognition only picks the
 * board. A person matches a chores kid / routine kid by name (case-insensitive), so the household
 * sets those up once in Portal Toolkit as today.
 */
@Composable
internal fun WhosThereKidChoresCard(p: WhosTherePerson, modifier: Modifier) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  LaunchedEffect(Unit) { withContext(Dispatchers.IO) { ChoreStore.ensureLoaded(context) } }
  val state by ChoreStore.state.collectAsState()
  var day by remember { mutableLongStateOf(ChoreStore.today().toLong()) }
  LaunchedEffect(Unit) { while (true) { delay(60_000); day = ChoreStore.today().toLong() } }
  val mine = remember(state, day, p.name) {
    ChoreBoard.today(state, day.toInt()).firstOrNull { it.kid.name.trim().equals(p.name.trim(), ignoreCase = true) }
  }
  ApprovedHomeSurface(modifier) {
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (mine == null) "Chores" else "Chores · ${mine.week.stars} ⭐", color = ApprovedHomePalette.Secondary,
            fontSize = 23.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        OwnerChip(p)
      }
      if (mine == null) {
        ApprovedHomeWidgetMessage("Add ${p.name} to Chores in Portal Toolkit to see their jobs here")
        return@Column
      }
      if (mine.items.isEmpty()) ApprovedHomeWidgetMessage("All done for today 🎉")
      mine.items.take(5).forEach { item ->
        val done = item.tick?.status?.counts == true
        val shape = RoundedCornerShape(18.dp)
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(shape)
            .tvFocusable(shape, focusScale = 1f, ringColor = ApprovedHomePalette.Accent) {
              scope.launch(Dispatchers.IO) { runCatching { ChoresFeature.toggle(context, mine.kid.id, item) } }
            }.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
          Box(Modifier.size(34.dp).clip(CircleShape).background(if (done) WtGreen else ApprovedHomePalette.Chrome),
              contentAlignment = Alignment.Center) {
            if (done) WhosThereGlyph(WtGlyph.CHECK, androidx.compose.ui.graphics.Color.White, 22.dp)
          }
          Text(item.chore.title, color = ApprovedHomePalette.Text, fontSize = 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
              textDecoration = if (done) TextDecoration.LineThrough else null, modifier = Modifier.padding(start = 14.dp).weight(1f))
          Text("+${item.chore.stars}", color = ApprovedHomePalette.Secondary, fontSize = 20.sp)
        }
      }
    }
  }
}

@Composable
internal fun WhosThereKidRoutineCard(p: WhosTherePerson, modifier: Modifier) {
  val context = LocalContext.current
  remember(context) { RoutineEngine.init(context) }
  val revision = RoutineEngine.revision
  var now by remember { mutableLongStateOf(RoutineEngine.repo.clock.nowLocalMs()) }
  LaunchedEffect(Unit) { while (true) { delay(60_000 - now % 60_000); now = RoutineEngine.repo.clock.nowLocalMs() } }
  val doc = remember(revision, now) { RoutineEngine.repo.doc() }
  val runs = remember(revision, now) { RoutineEngine.repo.runs() }
  val kid = doc.kids.firstOrNull { it.name.trim().equals(p.name.trim(), ignoreCase = true) }
  val routines = doc.routines.filter { r -> r.enabled && (r.kids.isEmpty() || (kid != null && kid.id in r.kids)) }.sortedBy { it.start }
  ApprovedHomeSurface(modifier) {
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${p.name}’s routines", color = ApprovedHomePalette.Secondary, fontSize = 23.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f))
        OwnerChip(p)
      }
      if (routines.isEmpty()) {
        ApprovedHomeWidgetMessage("Make a picture routine for ${p.name} in Portal Toolkit")
        return@Column
      }
      routines.take(3).forEach { r ->
        val status = RoutineSchedule.widgetStatus(r, runs, now)
        val run = runs[r.id]
        val key = if (kid != null && r.kids.size > 1) kid.id else RoutineRunLogic.ALL
        val done = run?.let { RoutineRunLogic.doneCount(r, it, key) } ?: 0
        val shape = RoundedCornerShape(22.dp)
        Row(Modifier.fillMaxWidth().heightIn(min = 84.dp).clip(shape).background(ApprovedHomePalette.Chrome)
            .tvFocusable(shape, focusScale = 1f, ringColor = ApprovedHomePalette.Accent) {
              if (RoutineSchedule.active(runs)?.rid == r.id) RoutineEngine.reopen()
            }.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text(r.name, color = ApprovedHomePalette.Text, fontSize = 27.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(status.text, color = if (status.tone == RoutineSchedule.Tone.LIVE) WtGreen else ApprovedHomePalette.Secondary,
                fontSize = 20.sp, maxLines = 1)
          }
          Text("$done of ${r.cards.size}", color = ApprovedHomePalette.Text, fontSize = 24.sp, fontWeight = FontWeight.Medium)
        }
      }
    }
  }
}
