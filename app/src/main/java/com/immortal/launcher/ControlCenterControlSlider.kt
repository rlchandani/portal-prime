/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Enter/exit progress for sheets and modals. [progress] is meant to be read only in
 * `graphicsLayer`/draw lambdas; [visible] stays true until the exit animation has finished, so
 * hosts can return early (and cost nothing) once it is fully closed.
 */
@Stable
internal class CcPresence(val progress: Animatable<Float, AnimationVector1D>, private val mounted: State<Boolean>) {
  fun visible(open: Boolean): Boolean = open || mounted.value
}

@Composable
internal fun rememberCcPresence(open: Boolean, durationMs: Int, easing: Easing): CcPresence {
  val anim = remember { Animatable(if (open) 1f else 0f) }
  val mounted = remember { mutableStateOf(open) }
  LaunchedEffect(open) {
    val target = if (open) 1f else 0f
    if (open) mounted.value = true
    if (durationMs <= 0) anim.snapTo(target) else anim.animateTo(target, tween(durationMs, easing = easing))
    if (!open) mounted.value = false
  }
  return remember { CcPresence(anim, mounted) }
}

/**
 * `.hslider`: a 76 dp pill you drag or tap. [value] is 0…100; [onLive] fires while dragging (for
 * throttled sends) and [onCommit] once on release. The drag lives in [drag] (hoistable so a page
 * can show it elsewhere); the fill is drawn from it and only the tiny label recomposes per step.
 */
@Composable
internal fun CcCtlHSlider(value: Int, label: (Int) -> String, onCommit: (Int) -> Unit, modifier: Modifier = Modifier,
                          enabled: Boolean = true, onLive: ((Int) -> Unit)? = null, description: String = "Slider",
                          drag: MutableState<Int?> = remember { mutableStateOf(null) }, idleLabel: String? = null) {
  val c = LocalCc.current
  val commit by rememberUpdatedState(onCommit)
  val live by rememberUpdatedState(onLive)
  val current = rememberUpdatedState(value)
  val shown = remember(drag) { derivedStateOf { drag.value ?: current.value } }
  val fill = c.text.copy(alpha = FILL_ALPHA)
  Box(modifier.height(76.dp).fillMaxWidth().alpha(if (enabled) 1f else CC_DISABLED_ALPHA).clip(CcStyle.Pill).background(c.surface2)
      .drawBehind { drawRect(fill, size = Size(size.width * shown.value / PCT, size.height)) }
      .semantics { contentDescription = "$description ${label(value)}" }
      .then(if (!enabled) Modifier else Modifier
          .pointerInput(Unit) { detectTapGestures { o -> commit(CcControlMath.pctFromHorizontal(o.x, size.width.toFloat())) } }
          .pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { o -> drag.value = CcControlMath.pctFromHorizontal(o.x, size.width.toFloat()) },
                onDragEnd = { drag.value?.let { commit(it) }; drag.value = null },
                onDragCancel = { drag.value = null },
            ) { change, _ ->
              val p = CcControlMath.pctFromHorizontal(change.position.x, size.width.toFloat())
              if (p != drag.value) { drag.value = p; live?.invoke(p) }
              change.consume()
            }
          })) {
    SliderLabel(shown, drag, label, idleLabel)
  }
}

/** Only this text recomposes while the slider moves. */
@Composable
private fun BoxScope.SliderLabel(shown: State<Int>, drag: State<Int?>, label: (Int) -> String, idleLabel: String?) {
  val c = LocalCc.current
  val v = shown.value
  val text = if (idleLabel != null && drag.value == null) idleLabel else label(v)
  Text(text, color = if (v > LABEL_ON_FILL) c.bg else c.text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold,
      modifier = Modifier.align(Alignment.CenterStart).padding(start = 24.dp))
}

private const val PCT = 100f
private const val FILL_ALPHA = 0.85f
private const val LABEL_ON_FILL = 30

/** The last page a host showed, kept (without snapshot state) so it stays drawn while closing. */
internal class CcLast<T : Any> { var value: T? = null }
