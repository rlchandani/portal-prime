/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The two springs of the stage: [progress] (sheet closed 0 → open 1) and [focus] (Search squeeze).
 * Every moving part reads them in layout or draw lambdas, so a transition never recomposes.
 */
@Stable
internal class MusicStageMotion(private val scope: CoroutineScope, private val reduced: Boolean, open: Boolean) {
  val progress = Animatable(if (open) 1f else 0f)
  val focus = Animatable(0f)
  private var target = progress.value

  /** Springs to open/closed carrying [velocity] (progress per second); reduce motion snaps. */
  fun settle(open: Boolean, velocity: Float = 0f) {
    target = if (open) 1f else 0f
    val to = target
    scope.launch {
      if (reduced) progress.snapTo(to)
      else progress.animateTo(to, spring(MusicSheetDrag.SPRING_DAMPING, MusicSheetDrag.SPRING_STIFFNESS,
          MusicSheetDrag.SPRING_THRESHOLD), initialVelocity = velocity)
    }
  }

  /** Follows the sheet state unless a drag release already aimed there. */
  fun follow(openTarget: Float) {
    if (openTarget != target) settle(openTarget >= 1f)
  }

  fun focusTo(to: Float) {
    scope.launch {
      if (reduced) focus.snapTo(to)
      else focus.animateTo(to, spring(MusicSheetDrag.SPRING_DAMPING, MusicSheetDrag.FOCUS_STIFFNESS,
          MusicSheetDrag.SPRING_THRESHOLD))
    }
  }

  fun dragTo(p: Float) {
    scope.launch { progress.snapTo(p) }
  }

  /** The frame right now (call only from layout/draw lambdas or event handlers). */
  fun frame(layout: MusicStageLayout): MusicStageFrame =
      MusicStageGeometry.frame(layout, progress.value, focus.value)
}

@Composable
internal fun rememberMusicStageMotion(reduced: Boolean, open: Boolean): MusicStageMotion {
  val scope = rememberCoroutineScope()
  return remember(scope, reduced) { MusicStageMotion(scope, reduced, open) }
}

/**
 * Drag on the bottom bar or the header strip: the sheet follows the finger (rubber-banded past the
 * ends) and a release flicks or settles ([MusicSheetDrag]). Velocity is measured on the finger's
 * own travel, not on the moving element.
 */
internal fun Modifier.musicSheetDrag(
    motion: MusicStageMotion,
    travelDp: Float,
    onStart: () -> Unit,
    onSettled: (open: Boolean, velocity: Float) -> Unit,
): Modifier = pointerInput(motion, travelDp) {
  val tracker = VelocityTracker()
  var start = 0f
  var dy = 0f
  fun release() {
    val v = MusicSheetDrag.velocity(tracker.calculateVelocity().y / density, travelDp)
    onSettled(MusicSheetDrag.settlesOpen(motion.progress.value, v), v)
  }
  detectVerticalDragGestures(
      onDragStart = {
        start = motion.progress.value
        dy = 0f
        tracker.resetTracking()
        onStart()
      },
      onVerticalDrag = { change, amount ->
        dy += amount
        tracker.addPosition(change.uptimeMillis, Offset(0f, dy))
        motion.dragTo(MusicSheetDrag.progress(start, dy / density, travelDp))
        change.consume()
      },
      onDragEnd = ::release,
      onDragCancel = ::release,
  )
}

/** Places an element at a dp position read in the layout phase (no recomposition while it moves). */
internal fun Modifier.stageOffset(position: () -> Pair<Float, Float>): Modifier = offset {
  val (x, y) = position()
  IntOffset(x.dp.roundToPx(), y.dp.roundToPx())
}
