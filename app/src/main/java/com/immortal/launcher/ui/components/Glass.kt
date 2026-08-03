/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * THE glass design tokens — the single source of truth for every card surface in the launcher
 * (home widgets, dashboard cards, overlays). Values are the Dusk Aurora spec
 * (mocks/ipados/aurora.css --glass-*): change them here and every surface follows.
 */
object Glass {
  /** Card fill: cool dark frosted tint (mock --glass-bg rgba(22,26,38,.52)). */
  val Tint = Color(0x85161A26)
  /** Card rim (mock --glass-border rgba(255,255,255,.14)). */
  val Border = Color(0x24FFFFFF)
  /** Card corner radius — every top-level widget/card. */
  val Radius: Dp = 22.dp
  /** Inner tile radius (sub-tiles inside a card: HA tiles, quick-control chips). */
  val SubRadius: Dp = 14.dp
  /** Quiet inner-tile fill (sub-tiles that sit ON glass — lighter, additive white). */
  val SubTint = Color(0x17FFFFFF)
  /** Chrome-button fill (circular header buttons, slide tracks). */
  val Chrome = Color(0x33FFFFFF)
  /** Full-screen overlay scrim behind dialogs/pickers. */
  val Scrim = Color(0xE6000000)
  /** Muted/empty-state text on glass. */
  val TextMuted = Color(0xFF9A9A9A)
  /** Secondary text on glass (~80% white). */
  val TextSecondary = Color(0xCCFFFFFF)
}

/**
 * The one glass card surface (Dusk Aurora). Every widget/card surface in the launcher goes
 * through here — do NOT hand-roll `.background(...).border(...)` card surfaces.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = Glass.Radius,
    tint: Color = Glass.Tint,
    content: @Composable BoxScope.() -> Unit = {},
) {
  val shape = RoundedCornerShape(cornerRadius)
  Box(
      modifier = modifier
          .clip(shape)
          .background(tint)
          .border(1.dp, Glass.Border, shape),
  ) {
    content()
  }
}

/**
 * THE dark dialog/panel surface — every floating dialog on the home surface (add-widget
 * gallery, pair dialog, custom timer, widget picker) uses this one tone + radius, replacing
 * four ad-hoc color/radius pairs.
 */
@Composable
fun DarkDialogSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
  androidx.compose.material3.Surface(
      color = Color(0xFF1C202E),
      shape = RoundedCornerShape(24.dp),
      content = content,
      modifier = modifier,
  )
}
