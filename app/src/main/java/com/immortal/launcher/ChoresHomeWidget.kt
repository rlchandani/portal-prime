/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MINUTE_MS = 60_000L

/**
 * Home "Chores" widget: each kid with today's chores as big pictures, and their stars this week
 * toward the goal. A tap ticks a chore (stars wait for a parent's OK when the chore asks for one);
 * tapping it again takes a mis-tap back. The header opens this week and the last four.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChoresHomeWidget(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { ChoreStore.ensureLoaded(context.applicationContext) } } }
  val state by ChoreStore.state.collectAsState()
  // The day only changes at midnight (and the week on Monday): re-check once a minute.
  val day by produceState(ChoreStore.today()) {
    while (true) { delay(MINUTE_MS - ChoreStore.clock() % MINUTE_MS); value = ChoreStore.today() }
  }
  val board = remember(state, day) { ChoreBoard.today(state, day) }
  var showWeek by remember { mutableStateOf(false) }
  ImmortalWidgetShell(title = "Chores", openPage = "chores", modifier = modifier, onOpen = { showWeek = true }) {
    if (board.isEmpty()) {
      Text("No chores yet", color = ApprovedHomePalette.Text, fontSize = 26.sp, lineHeight = 34.sp)
      ApprovedHomeWidgetMessage("Add kids and chores in Portal Toolkit on your phone")
    }
    Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
      board.forEach { row ->
        KidToday(row) { item ->
          scope.launch(Dispatchers.IO) { runCatching { ChoresFeature.toggle(context.applicationContext, row.kid.id, item) } }
        }
      }
    }
  }
  if (showWeek) ChoresWeekDialog(state, day) { showWeek = false }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KidToday(row: ChoreBoard.KidToday, onTap: (ChoreBoard.Item) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(ChoreTokens.Gap)) {
    KidHeader(row.kid, row.week)
    ChoreGoalBar(row.week)
    when {
      row.items.isEmpty() -> ApprovedHomeWidgetMessage("No chores today. Free day!")
      else -> {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(ChoreTokens.Gap), verticalArrangement = Arrangement.spacedBy(ChoreTokens.Gap)) {
          row.items.forEach { item -> ChoreTile(item) { onTap(item) } }
        }
        if (row.items.all { it.done }) ApprovedHomeWidgetMessage("All done today. Great job!")
      }
    }
  }
}

@Composable
private fun KidHeader(kid: ChoreKid, week: ChoreBoard.Week) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    ChoreKidAvatar(kid.avatar, kid.color, ChoreTokens.AvatarSmall)
    Column(Modifier.weight(1f)) {
      Text(kid.name, color = ApprovedHomePalette.Text, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold,
          maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(ChoreBoard.goalLine(week) + if (week.pending > 0) " · ${week.pending} waiting" else "",
          color = ApprovedHomePalette.Secondary, fontSize = 22.sp, lineHeight = 30.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
  }
}

/** This week's stars per kid and the last four weeks (the widget header). Edits live on the phone. */
@Composable
internal fun ChoresWeekDialog(state: ChoreState, day: Int, onDismiss: () -> Unit) {
  val weeks = remember(state, day) { ChoreBoard.week(state, day) }
  val history = remember(state, day) { ChoreBoard.history(state, day) }
  ApprovedHomeDialog("Stars this week", onDismiss) {
    if (state.liveKids.isEmpty()) ApprovedHomeWidgetMessage("Add kids and chores in Portal Toolkit on your phone.")
    state.liveKids.forEach { kid ->
      val week = weeks.firstOrNull { it.kid == kid.id } ?: return@forEach
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        KidHeader(kid, week)
        ChoreGoalBar(week)
      }
    }
    if (history.any { w -> w.any { it.stars > 0 } }) {
      Text("Last 4 weeks", color = ApprovedHomePalette.Text, fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium,
          modifier = Modifier.padding(top = 12.dp))
      history.forEachIndexed { back, kids ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
          Text(weekLabel(back + 1), color = ApprovedHomePalette.Secondary, fontSize = 22.sp, lineHeight = 30.sp,
              modifier = Modifier.weight(0.35f))
          Text(kids.joinToString("   ") { w ->
            val kid = state.kids[w.kid]
            "${kid?.avatar.orEmpty()} ${w.stars} ★" + if (w.reached) " ✓" else ""
          }, color = ApprovedHomePalette.Text, fontSize = 22.sp, lineHeight = 30.sp, modifier = Modifier.weight(0.65f))
        }
      }
    }
    ApprovedHomeWidgetMessage("Stars start fresh every Monday.")
  }
}

private fun weekLabel(back: Int): String = if (back == 1) "Last week" else "$back weeks ago"
