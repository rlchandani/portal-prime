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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The Hearthpane ember: same colours and shape as the launcher icon. */
internal object HearthpaneMark {
  val Ember = Color(0xFFD9773A)
  val EmberLight = Color(0xFFF0A65A)
  /** Square-ish top, round bottom, like the icon glyph (27% / 50% corners). */
  val GlyphShape = RoundedCornerShape(topStartPercent = 27, topEndPercent = 27, bottomStartPercent = 50,
      bottomEndPercent = 50)
  /** Soft glow behind the glyph, as a share of the glyph size. */
  const val GLOW_ALPHA = 0.35f
  const val GLOW_REACH = 1.4f
}

@Composable
internal fun HearthpaneGlyph(size: Dp, modifier: Modifier = Modifier) {
  Box(modifier.size(size)
      .drawBehind {
        val r = this.size.minDimension * HearthpaneMark.GLOW_REACH
        drawCircle(Brush.radialGradient(listOf(HearthpaneMark.EmberLight.copy(alpha = HearthpaneMark.GLOW_ALPHA),
            Color.Transparent), center = center, radius = r), radius = r, center = center)
      }
      .background(Brush.verticalGradient(listOf(HearthpaneMark.EmberLight, HearthpaneMark.Ember)),
          HearthpaneMark.GlyphShape))
}

/** Header wordmark: ember glyph + serif name. One semantic label for screen readers. */
@Composable
internal fun HearthpaneWordmark(color: Color, fontSize: TextUnit = 30.sp, modifier: Modifier = Modifier) {
  Row(modifier.clearAndSetSemantics { contentDescription = Brand.NAME },
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    HearthpaneGlyph((fontSize.value * 0.7f).dp)
    Text(Brand.WORDMARK, color = color, fontSize = fontSize, fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal)
  }
}
