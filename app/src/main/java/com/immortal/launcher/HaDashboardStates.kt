/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** A button under an empty state. */
internal data class HaStateAction(val text: String, val icon: String?, val primary: Boolean, val onClick: () -> Unit)

/** Dashed-outline full-area message (no rooms, empty room, sign-in, unreachable). */
@Composable
internal fun ColumnScope.HaEmptyState(icon: String, title: String, body: String, red: Boolean = false,
    actions: List<HaStateAction> = emptyList()) {
  val d = LocalHaDims.current
  val c = LocalHaColors.current
  Column(Modifier.weight(1f).fillMaxWidth().dashedOutline(c.outline).padding(40.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically)) {
    HaBigWell(icon, red)
    HaText(title, d.empty, c.text, serif = true, lines = 2, lineHeight = 1.1f, align = TextAlign.Center)
    HaText(body, d.body, c.sec, Modifier.widthIn(max = 640.dp), lines = 4, lineHeight = 1.4f, align = TextAlign.Center)
    if (actions.isNotEmpty()) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
      actions.forEach { HaPill(it.text, it.onClick, icon = it.icon, primary = it.primary) }
    }
  }
}

@Composable
private fun HaBigWell(icon: String, red: Boolean) {
  val c = LocalHaColors.current
  Box(Modifier.size(100.dp).clip(RoundedCornerShape(30.dp)).background(if (red) c.redBg else c.surface2),
      contentAlignment = Alignment.Center) { HaIcon(icon, if (red) c.red else c.sec, 52.dp) }
}

private fun Modifier.dashedOutline(color: Color) = drawBehind {
  val w = HaShape.Outline.toPx()
  drawRoundRect(color, cornerRadius = CornerRadius(DASH_RADIUS.dp.toPx()),
      style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH.dp.toPx(), DASH.dp.toPx()))))
}

/** Amber "Can't reach Home Assistant" banner over last-known tiles. */
@Composable
internal fun HaOfflineBanner(ageText: String, onRetry: () -> Unit) {
  val d = LocalHaDims.current
  val c = LocalHaColors.current
  val shape = RoundedCornerShape(24.dp)
  Row(Modifier.fillMaxWidth().clip(shape).background(c.amberBg).border(HaShape.Outline, c.amberLine, shape)
      .semantics { liveRegion = LiveRegionMode.Polite }.padding(start = 22.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
    Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(c.amber.copy(alpha = BANNER_WELL)),
        contentAlignment = Alignment.Center) { HaIcon("wifioff", c.amber, 30.dp) }
    Column(Modifier.weight(1f)) {
      HaText("Can't reach Home Assistant", d.name, c.text, weight = FontWeight.Bold)
      HaText("$ageText · controls pause until it's back", d.status, c.sec, lines = 2)
    }
    HaPill("Try again", onRetry, icon = "refresh")
  }
}

/** The not-set-up card (CC dialog pattern). */
@Composable
internal fun HaSetupCard(onSetUp: () -> Unit, onLater: () -> Unit) {
  val d = LocalHaDims.current
  val c = LocalHaColors.current
  Box(Modifier.fillMaxSize().padding(d.pad.dp), contentAlignment = Alignment.Center) {
    Column(Modifier.widthIn(max = 820.dp).fillMaxWidth().haCard(c, RoundedCornerShape(26.dp)).padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
      HaBigWell("home", red = false)
      HaText("Connect Home Assistant", d.modal, c.text, serif = true, lineHeight = 1.08f)
      HaText("Add your Home Assistant address and a long-lived access token. Rooms, lights, climate and scenes " +
          "then appear here and in Control Center.", d.body, c.sec, lines = 4, lineHeight = 1.4f)
      Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        HaPill("Set up", onSetUp, icon = "gear", primary = true)
        HaPill("Not now", onLater)
      }
    }
  }
}

/** Shimmering placeholder block. */
@Composable
internal fun Modifier.haSkeleton(radius: Int = SKELETON_RADIUS): Modifier {
  val c = LocalHaColors.current
  val phase by rememberInfiniteTransition(label = "skeleton").animateFloat(0f, 1f,
      infiniteRepeatable(tween(SHIMMER_MS, easing = LinearEasing), RepeatMode.Restart), label = "shimmer")
  val shape = RoundedCornerShape(radius.dp)
  return clip(shape).drawBehind {
    drawRect(c.surface)
    drawRect(c.surface2.copy(alpha = SHIMMER_PEAK * (1f - kotlin.math.abs(phase * 2f - 1f))))
  }.border(HaShape.Outline, c.outline, shape)
}

/** Loading: five room rows. */
@Composable
internal fun HaSideSkeleton() {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    repeat(SKELETON_ROOMS) { Box(Modifier.fillMaxWidth().height(LocalHaDims.current.roomRow.dp).haSkeleton(22)) }
  }
}

/** Loading: the room title bar and the mock's six tile shapes. */
@Composable
internal fun ColumnScope.HaGridSkeleton() {
  Box(Modifier.size(width = 280.dp, height = 40.dp).clip(RoundedCornerShape(11.dp)).background(LocalHaColors.current.surface2))
  val spans = listOf(HaDashboardModel.Span(2, 1), HaDashboardModel.Span(2, 2), HaDashboardModel.Span(2, 1),
      HaDashboardModel.Span(1, 1), HaDashboardModel.Span(1, 1), HaDashboardModel.Span(1, 1))
  HaGrid(spans, Modifier.weight(1f)) { repeat(spans.size) { Box(Modifier.haSkeleton()) } }
}

/** Bottom toast pill; "Try again" when the action can be repeated. */
@Composable
internal fun HaToast(toast: HaDashboardSession.Toast, onRetry: () -> Unit, modifier: Modifier) {
  val d = LocalHaDims.current
  val c = LocalHaColors.current
  Row(modifier.heightIn(min = 72.dp).clip(HaShape.Pill).background(c.text)
      .semantics { liveRegion = LiveRegionMode.Polite }.padding(start = 24.dp, end = 12.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    HaIcon(toast.icon, c.bg, 26.dp)
    HaText(toast.text, d.status, c.bg, weight = FontWeight.SemiBold)
    if (toast.retry != null) {
      Row(Modifier.height(56.dp).clip(HaShape.Pill).border(HaShape.Outline, c.bg.copy(alpha = TOAST_LINE), HaShape.Pill)
          .clickable(role = Role.Button, onClickLabel = "Try again", onClick = onRetry).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        HaText("Try again", d.button, c.bg, weight = FontWeight.SemiBold)
      }
    } else Box(Modifier.size(12.dp))
  }
}

private const val DASH = 8
private const val DASH_RADIUS = 28
private const val BANNER_WELL = 0.22f
private const val TOAST_LINE = 0.4f
private const val SKELETON_RADIUS = 26
private const val SKELETON_ROOMS = 5
private const val SHIMMER_MS = 1400
private const val SHIMMER_PEAK = 0.9f
