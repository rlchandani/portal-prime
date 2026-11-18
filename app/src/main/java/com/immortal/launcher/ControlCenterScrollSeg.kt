/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * A segmented control in a slot that may be narrower than it (the editor's floor switch on a home
 * with many or long floor names): it scrolls sideways, softly fades whichever edge has more, and
 * keeps the selected option in view whenever [selection] changes. [content] receives the modifier
 * for the selected option ([CcSeg]'s `selectedModifier`). When everything fits it looks exactly
 * like a plain [CcSeg].
 */
@Composable
internal fun CcScrollingSeg(selection: Any?, modifier: Modifier = Modifier, content: @Composable (selectedModifier: Modifier) -> Unit) {
  val scroll = rememberScrollState()
  val requester = remember { BringIntoViewRequester() }
  LaunchedEffect(selection) {
    withFrameNanos { } // placed first, so there is something to bring into view
    requester.bringIntoView()
  }
  Box(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
    drawContent()
    val fade = FADE_DP.dp.toPx()
    val start = scroll.value > 0
    val end = scroll.value < scroll.maxValue
    if (start || end) drawRect(edgeMask(start, end, fade / size.width), blendMode = BlendMode.DstIn)
  }.horizontalScroll(scroll)) {
    content(Modifier.bringIntoViewRequester(requester))
  }
}

/** Opaque in the middle, fading to clear over [frac] of the width at each edge that has more to show. */
private fun edgeMask(start: Boolean, end: Boolean, frac: Float): Brush {
  val f = frac.coerceIn(0f, HALF)
  return Brush.horizontalGradient(
      0f to if (start) Color.Transparent else Color.Black,
      f to Color.Black,
      1f - f to Color.Black,
      1f to if (end) Color.Transparent else Color.Black,
  )
}

private const val FADE_DP = 36
private const val HALF = 0.5f
