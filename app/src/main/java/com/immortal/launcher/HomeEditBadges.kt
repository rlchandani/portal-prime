/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Add-flow ring after Add, and the bottom area the place-and-size bar covers. */
internal const val ADD_RING_MS = 1_200L
internal val ADD_BAR_AREA = 206.dp

/** Saves [widget] exactly at [slot] with [span] if the current saved layout still has room. */
internal fun addIntoSlot(context: Context, widget: HomeWidgetStore.HomeWidget, slot: HomeWidgetStore.Slot,
    span: Pair<Int, Int>): Boolean = synchronized(RemoteCompanionStore.mutationLock) {
  val current = HomeWidgetStore.load(context)
  val updated = HomeSlots.addAt(current, widget, slot, span) ?: return@synchronized false
  HomeWidgetStore.replaceIfUnchanged(context, current, updated)
}

/**
 * iOS-style "jiggle": a gentle continuous wobble applied to editable tiles while Manage mode is on.
 * Each tile is given a [seed] so they fall slightly out of phase (rather than wobbling in lockstep).
 * The animation only exists while [enabled]; it leaves composition when Manage mode ends so the
 * long-lived launcher isn't animating in the background.
 */
@Composable
internal fun Modifier.jiggle(enabled: Boolean, seed: Int): Modifier {
  if (!enabled) return this
  val transition = rememberInfiniteTransition(label = "jiggle")
  val angle by
      transition.animateFloat(
          initialValue = -1.0f,
          targetValue = 1.0f,
          animationSpec =
              infiniteRepeatable(
                  animation = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                  repeatMode = RepeatMode.Reverse,
                  initialStartOffset = StartOffset(0),
              ),
          label = "jiggle-angle",
      )
  val bob by
      transition.animateFloat(
          initialValue = -0.8f,
          targetValue = 0.8f,
          animationSpec =
              infiniteRepeatable(
                  animation = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                  repeatMode = RepeatMode.Reverse,
                  initialStartOffset = StartOffset(0),
              ),
          label = "jiggle-bob",
      )
  return this.graphicsLayer {
    rotationZ = angle
    translationY = bob
    compositingStrategy = CompositingStrategy.Offscreen
  }
}

/**
 * Round red delete affordance with a crisply-drawn, perfectly-centered ✕ (the unicode glyph
 * isn't vertically centered inside its line box, so it's drawn as two crossing strokes instead).
 */
@Composable
internal fun RemoveBadge(size: Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
  Surface(
      color = Color(0xFFE53935),
      shape = CircleShape,
      modifier = modifier.size(size).tvFocusable(CircleShape) { onClick() },
  ) {
    Canvas(Modifier.fillMaxSize()) {
      val cx = this.size.width / 2f
      val cy = this.size.height / 2f
      val arm = this.size.minDimension * 0.21f
      val stroke = this.size.minDimension * 0.11f
      drawLine(
          Color.White,
          Offset(cx - arm, cy - arm),
          Offset(cx + arm, cy + arm),
          strokeWidth = stroke,
          cap = StrokeCap.Round,
      )
      drawLine(
          Color.White,
          Offset(cx - arm, cy + arm),
          Offset(cx + arm, cy - arm),
          strokeWidth = stroke,
          cap = StrokeCap.Round,
      )
    }
  }
}

/**
 * Gray minus badge for iOS-style edit mode on app icons.
 */
@Composable
internal fun MinusBadge(modifier: Modifier = Modifier, onClick: () -> Unit) {
  Surface(
      color = Color(0xE6C7C7CC),
      shape = CircleShape,
      modifier = modifier
          .size(32.dp)
          .semantics { contentDescription = "Remove item" }
          .clickable(
              interactionSource = remember { MutableInteractionSource() },
              indication = null
          ) { onClick() },
  ) {
    Canvas(Modifier.fillMaxSize()) {
      val cx = this.size.width / 2f
      val cy = this.size.height / 2f
      val armWidth = this.size.minDimension * 0.30f
      val stroke = this.size.minDimension * 0.12f
      drawLine(
          Color.White,
          Offset(cx - armWidth, cy),
          Offset(cx + armWidth, cy),
          strokeWidth = stroke,
          cap = StrokeCap.Round,
      )
    }
  }
}

/**
 * Resize control for widgets: PRESS-AND-DRAG the bottom-right handle to resize (iOS/Android style).
 * Reports the accumulated drag delta continuously via [onResizeDrag]; the caller snaps to grid
 * sizes. [onResizeStart]/[onResizeEnd] bracket the gesture so the caller can capture the start span
 * and reset its accumulator. The drag consumes its pointer events so it never triggers the widget
 * body's reorder handle.
 */
@Composable
internal fun ResizeBadge(
    modifier: Modifier = Modifier,
    onResizeStart: () -> Unit,
    onResizeDrag: (dragDelta: Offset) -> Unit,
    onResizeEnd: () -> Unit,
    onResizeCancel: () -> Unit,
) {
  // Keep latest callbacks so the long-lived pointerInput lambda always calls the current versions.
  val startState = rememberUpdatedState(onResizeStart)
  val dragState = rememberUpdatedState(onResizeDrag)
  val endState = rememberUpdatedState(onResizeEnd)
  val cancelState = rememberUpdatedState(onResizeCancel)
  Surface(
      color = Color(0xE6C7C7CC),
      shape = CircleShape,
      modifier = modifier
          .size(48.dp)
          .semantics { contentDescription = "Resize widget" }
          .pointerInput(Unit) {
            var resizing = false
            try {
              detectDragGestures(
                  onDragStart = { resizing = true; startState.value() },
                  onDragEnd = { resizing = false; endState.value() },
                  onDragCancel = { resizing = false; cancelState.value() },
              ) { change, dragAmount ->
                // Consume so the reorderable grid's drag handle does not also react to this pointer.
                change.consume()
                dragState.value(dragAmount)
              }
            } finally {
              if (resizing) cancelState.value()
            }
          },
  ) {
    Canvas(Modifier.fillMaxSize().padding(8.dp)) {
      // Double-headed diagonal resize arrow (↖ ↘): a diagonal shaft with an arrowhead at each end.
      val d = this.size.minDimension
      val stroke = d * 0.09f
      val m = d * 0.30f                 // arrow tip inset from edges
      val tl = Offset(m, m)             // top-left tip
      val br = Offset(d - m, d - m)     // bottom-right tip
      val head = d * 0.14f              // arrowhead leg length
      // shaft
      drawLine(Color.White, tl, br, strokeWidth = stroke, cap = StrokeCap.Round)
      // top-left arrowhead (two legs)
      drawLine(Color.White, tl, Offset(tl.x + head, tl.y), strokeWidth = stroke, cap = StrokeCap.Round)
      drawLine(Color.White, tl, Offset(tl.x, tl.y + head), strokeWidth = stroke, cap = StrokeCap.Round)
      // bottom-right arrowhead (two legs)
      drawLine(Color.White, br, Offset(br.x - head, br.y), strokeWidth = stroke, cap = StrokeCap.Round)
      drawLine(Color.White, br, Offset(br.x, br.y - head), strokeWidth = stroke, cap = StrokeCap.Round)
    }
  }
}
