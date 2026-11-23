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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.immortal.launcher.memory.InterestWidgets
import com.immortal.launcher.memory.InterestWidgetsHome
import com.immortal.launcher.memory.SportsCard
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The Sports widget (Jarvis personalization, slice 3; mock `mocks/jarvis-personalization-20261122`, Home "Sports"):
 * the followed teams' game tonight, live score or last result, read through [SportsFeed][com.immortal.launcher.jarvis.SportsFeed]
 * (team lists cached, game pages never served stale) and re-read every 30 s while a game is live. A live score older
 * than a minute reads "Live · updating" with no score ([SportsCard]). Everything in a card (type, logos, gaps) is
 * multiplied by [SportsCard.layout]'s scale for the slot, and bigger slots show more teams. Offered in the picker
 * only while "learn from conversations" is on; with it off, or for guests and children, the card says where scores
 * show instead (the mock's household copy). Tap opens Jarvis, which already knows the team.
 */
@Composable
internal fun SportsHomeWidget(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val snap = rememberInterestWidgets()
  val teams = snap?.picks?.teams.orEmpty()
  val read by produceHomeState<InterestWidgetsHome.SportsRead?>(initialValue = null, snap?.scope, teams.map { it.id }) {
    val s = snap ?: return@produceHomeState
    if (s.picks.teams.isEmpty()) return@produceHomeState
    while (true) {
      val next = withContext(Dispatchers.IO) { runCatching { InterestWidgetsHome.sports(context, s) }.getOrNull() }
      if (next != null) value = next
      val at = System.currentTimeMillis()
      val games = value?.let { SportsCard.games(it.teams, it.atMs, at, ZoneId.systemDefault(), false) }.orEmpty()
      homeRefreshDelay(if (next == null) SportsCard.LIVE_REFRESH_MS else SportsCard.refreshMs(games, at))
    }
  }
  // Re-check staleness between reads: a live score is never shown once its read is a minute old.
  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(read) { while (true) { now = System.currentTimeMillis(); delay(STALE_CHECK_MS) } }
  val use24 = remember { ImmortalSettings.use24HourClock(context) }
  ImmortalWidgetShell(title = "Sports", openPage = "sports", modifier = modifier, scrollContent = false,
      onOpen = { triggerJarvis(context) }) {
    val r = read
    when {
      snap == null -> SportsMessage("Scores for the teams people here follow show up on their own Home.")
      teams.isEmpty() -> SportsMessage(if (snap.household) "Teams people here talk about with Jarvis show up here."
          else "Teams you talk about with Jarvis show up here.")
      r == null -> ApprovedHomeWidgetMessage("Loading…")
      r.teams.isEmpty() -> ApprovedHomeWidgetMessage("Couldn't reach the scores")
      else -> {
        val games = SportsCard.games(r.teams, r.atMs, now, ZoneId.systemDefault(), use24)
        val provenance = InterestWidgets.provenance(InterestWidgets.Kind.TEAM, snap.household)
        BoxWithConstraints(Modifier.fillMaxSize().tvFocusableRow { triggerJarvis(context) }.ccSpeak("Ask Jarvis about your teams")) {
          val layout = SportsCard.layout(maxWidth.value, maxHeight.value, games.size)
          val gap = SportsCard.CELL_GAP_DP.dp
          Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
            games.take(layout.cells).chunked(layout.columns).forEach { line ->
              Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                line.forEach { g -> SportsGameCard(g, provenance, layout.scale, layout.details, Modifier.weight(1f)) }
                repeat(layout.columns - line.size) { Spacer(Modifier.weight(1f)) }
              }
            }
          }
        }
      }
    }
  }
}

private const val STALE_CHECK_MS = 15_000L

@Composable
private fun SportsMessage(text: String) {
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Text(text, color = ApprovedHomePalette.Secondary, fontSize = 18.sp, lineHeight = 24.sp, textAlign = TextAlign.Center)
  }
}

/**
 * One team's game, the mock's card body at [scale]: the status line ("TONIGHT · 7:30", "FINAL") with "✦ Your team",
 * the two sides as colour discs with their names, "vs" or the score between them, and the secondary line.
 */
@Composable
private fun SportsGameCard(g: SportsCard.Game, provenance: String, scale: Float, details: Boolean, modifier: Modifier) {
  val s = scale
  Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
    Row(Modifier.fillMaxWidth().height((SportsCard.STATUS_DP * s).dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy((12 * s).dp)) {
      Text(g.status.uppercase(), color = if (g.phase == SportsCard.Phase.LIVE) LearnedPalette.Ink else ApprovedHomePalette.Secondary,
          fontSize = (21 * s).sp, lineHeight = (28 * s).sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.08.em,
          maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
      LearnedProvenance(provenance, scale = s)
    }
    Spacer(Modifier.height((SportsCard.STATUS_GAP_DP * s).dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy((18 * s).dp)) {
      SportsSide(g.us, s, Modifier.weight(1f))
      val them = g.them
      if (them != null) {
        val score = if (g.us.score != null && them.score != null) "${g.us.score}–${them.score}" else "vs"
        Text(score, color = if (score == "vs") ApprovedHomePalette.Secondary else ApprovedHomePalette.Text,
            fontSize = (46 * s).sp, lineHeight = (52 * s).sp, fontFamily = FontFamily.Serif, maxLines = 1,
            modifier = Modifier.padding(bottom = ((SportsCard.NAME_DP + SportsCard.LOGO_GAP_DP) * s / 2).dp))
        SportsSide(them, s, Modifier.weight(1f))
      }
    }
    val detail = g.detail
    if (details && detail != null) {
      Spacer(Modifier.height((SportsCard.DETAIL_GAP_DP * s).dp))
      Text(detail, color = ApprovedHomePalette.Secondary, fontSize = (18 * s).sp, lineHeight = (SportsCard.DETAIL_DP * s).sp,
          maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
  }
}

/** A team: its colour disc with the abbreviation (mock `.lg`) over its short name. */
@Composable
private fun SportsSide(side: SportsCard.Side, s: Float, modifier: Modifier) {
  val disc = side.color.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
  Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Box(Modifier.size((SportsCard.LOGO_DP * s).dp).clip(CircleShape).background(disc ?: ApprovedHomePalette.Chrome),
        contentAlignment = Alignment.Center) {
      Text(side.abbreviation.take(4), color = if (disc != null) Color.White else ApprovedHomePalette.Text,
          fontSize = (22 * s).sp, lineHeight = (26 * s).sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
    Spacer(Modifier.height((SportsCard.LOGO_GAP_DP * s).dp))
    Text(side.name, color = ApprovedHomePalette.Text, fontSize = (23 * s).sp, lineHeight = (SportsCard.NAME_DP * s).sp,
        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
  }
}
