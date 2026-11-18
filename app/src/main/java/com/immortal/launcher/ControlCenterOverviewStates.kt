/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The overview's designed states: connecting (faint last plan, one shimmer sweep and the
 * engine's honest step list — mock `loading`), no plan yet (build-your-plan call to action,
 * or a note for kids mode), and the side column skeleton.
 */
@Composable
internal fun BoxScope.CcOverviewLoading(s: CcUiState) {
  val c = LocalCc.current
  val plan = s.plan
  val floor = plan?.takeIf { !it.isEmpty }?.floors?.firstOrNull()
  if (floor != null) {
    val model = remember(floor, s.entities, s.settings) { CcPlanModel.build(floor, s.entities, s.settings, s.now) }
    CcPlanCanvas(model, CcLens.LIVE, null, null, CcMapSources.NONE, stale = true, fahrenheit = s.fahrenheit, onTap = {},
        modifier = Modifier.fillMaxSize().padding(top = 96.dp, bottom = 84.dp).alpha(SKELETON_ALPHA))
  }
  CcShimmer()
  val rows = remember(s.connection, s.registry, s.entities.size) { CcOverviewText.loading(s.connection, s.registry, s.entities.size) }
  Column(Modifier.align(Alignment.Center).width(900.dp).clip(CcStyle.Modal).background(c.glass).padding(horizontal = 50.dp, vertical = 46.dp)) {
    Box(Modifier.size(96.dp).clip(STATE_ICON).background(c.sel), contentAlignment = Alignment.Center) { CcIcon("home", c.text, 52.dp) }
    Box(Modifier.height(20.dp))
    Text("Connecting to your home…", color = c.text, fontSize = 46.sp, fontFamily = CcStyle.Serif, lineHeight = 52.sp)
    Text("Usually under two seconds on Wi-Fi.", color = c.sec, fontSize = 24.sp, modifier = Modifier.padding(top = 12.dp, bottom = 20.dp))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { rows.forEach { LoadRow(it) } }
  }
}

@Composable
private fun LoadRow(row: CcOverviewText.LoadRow) {
  val c = LocalCc.current
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
      when (row.step) {
        CcOverviewText.Step.DONE -> CcIcon("check", c.green, 32.dp)
        CcOverviewText.Step.NOW -> if (LocalCcMotion.current) CircularProgressIndicator(Modifier.size(28.dp), color = CcStyle.Ember, strokeWidth = 4.dp, trackColor = c.div)
        else CircularProgressIndicator({ SPINNER_STILL }, Modifier.size(28.dp), color = CcStyle.Ember, strokeWidth = 4.dp, trackColor = c.div)
        CcOverviewText.Step.LATER -> CcIcon("clock", c.text.copy(alpha = LATER_ICON_ALPHA), 32.dp)
      }
    }
    Text(row.text, color = c.text.copy(alpha = if (row.step == CcOverviewText.Step.LATER) LATER_ALPHA else 1f), fontSize = 23.sp)
  }
}

/** The single loading sweep (moved by layer translation, so no per-frame brushes). */
@Composable
private fun BoxScope.CcShimmer() {
  if (!LocalCcMotion.current) return
  val c = LocalCc.current
  val t = rememberInfiniteTransition(label = "shimmer").animateFloat(-1f, 1f, infiniteRepeatable(tween(SHIMMER_MS, easing = LinearEasing)), label = "sweep")
  val brush = remember(c) { Brush.horizontalGradient(listOf(Color.Transparent, c.text.copy(alpha = SHIMMER_ALPHA), Color.Transparent)) }
  Box(Modifier.matchParentSize().graphicsLayer { translationX = t.value * size.width }.background(brush))
}

/** No plan yet: Home Assistant has rooms but no geometry, so the household draws it once. */
@Composable
internal fun BoxScope.CcPlanEmpty(s: CcUiState, ui: CcUi) {
  if (CcPolicy.screenHidden(CcScreen.EDIT, s.settings)) {
    CcCenteredMessage("plan", "No floor plan yet",
        "Home Assistant knows the rooms but not where they are, so the map has to be drawn once. " +
            "Kids mode can't edit it — ask a grown-up to build the floor plan from Edit plan.")
    return
  }
  CcCenteredMessage("plan", "Build your floor plan",
      "Home Assistant knows your rooms and devices but not where they are. Draw the rooms once on this Portal; " +
          "every Portal on the same Home Assistant user shares the plan.",
      primary = "Build the floor plan" to { ui.go(CcScreen.EDIT) })
}

/** Side column placeholders while connecting (mock heights). */
@Composable
internal fun CcSideSkeleton(modifier: Modifier = Modifier) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
    SKELETON_HEIGHTS.forEach { h -> Box(Modifier.fillMaxWidth().height(h.dp).ccCard()) }
  }
}

private val STATE_ICON = androidx.compose.foundation.shape.RoundedCornerShape(30.dp)
private val SKELETON_HEIGHTS = listOf(260, 300, 200, 240)
private const val SKELETON_ALPHA = 0.25f
private const val LATER_ALPHA = 0.45f
private const val LATER_ICON_ALPHA = 0.3f
private const val SPINNER_STILL = 0.3f
private const val SHIMMER_MS = 1_800
private const val SHIMMER_ALPHA = 0.06f
