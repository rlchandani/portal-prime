/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.immortal.launcher.ui.theme.LocalApprovedPalette

/** The mock's ember: filled confidence bars. */
private val Ember = Color(0xFFD9773A)

/** The "still learning" outline (mock `.fact.tent`): a dashed 1.5 dp rounded border, no fill. */
internal fun Modifier.dashedBorder(color: Color, radius: Dp): Modifier = drawBehind {
  val stroke = 1.5.dp.toPx()
  drawRoundRect(
      color = color,
      topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
      size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
      cornerRadius = CornerRadius(radius.toPx()),
      style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 7.dp.toPx()))),
  )
}

/** 1–4 bars, filled from the left (mock `confBars`). */
@Composable
internal fun ConfidenceBars(filled: Int) {
  val p = LocalApprovedPalette.current
  Row(Modifier.semantics { contentDescription = "Confidence $filled of 4" },
      horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    (1..4).forEach { i ->
      Box(Modifier.size(width = 18.dp, height = 8.dp).clip(RoundedCornerShape(4.dp))
          .background(if (i <= filled) Ember else p.divider))
    }
  }
}
