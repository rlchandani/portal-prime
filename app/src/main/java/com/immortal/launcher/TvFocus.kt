/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

/**
 * Remote-control support for the launcher and its screens. The Portal TV is driven
 * by a D-pad, so every interactive element needs to be focusable and show a clear
 * highlight when selected. [tvFocusable] makes an element focusable + clickable by
 * the remote's center button, draws a bright focus ring, and gives it a subtle
 * scale so the selection is obvious from across a room. On touch models it behaves
 * like a normal clickable.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.tvFocusable(
    shape: Shape = RoundedCornerShape(16.dp),
    focusScale: Float = 1.06f,
    ringColor: Color = Color.White,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
  // Preserve the caller's existing disabled behavior (including Manage-mode badges).
  if (!enabled) return this
  val gate = LocalApprovedLauncherInputGate.current
  // Legacy screens retain disabled semantics; retained tabs keep their click nodes
  // stable and reject inactive actions at invocation.
  val clickEnabled = if (gate == null) LocalApprovedLauncherPageActive.current else true
  // Cached panes keep their interaction source and click chain across page transitions.
  val source = remember { MutableInteractionSource() }
  val focused by source.collectIsFocusedAsState()
  val showFocus = focused && (gate?.active ?: clickEnabled)
  // Touch cards use focusScale=1: avoid an identity graphics layer and animation
  // state for each visible card. Retain the animated return for TV callers.
  val scale = if (focusScale == 1f) 1f else {
    val animated by animateFloatAsState(if (showFocus) focusScale else 1f, label = "tvFocusScale")
    // Hide a retained pane's focus scale immediately, even while the animation settles.
    if (animated == 1f || (gate?.active ?: clickEnabled)) animated else 1f
  }
  val scaled = if (scale == 1f) this else this.scale(scale)
  val ring = if (showFocus) scaled.border(BorderStroke(3.dp, ringColor), shape) else scaled
  // Each child needs its own guard: a focusable widget parent stops traversal to
  // the page-level properties. Leave active canFocus at its existing default.
  val base = if (gate == null) ring else ring.focusProperties { if (!gate.active) canFocus = false }
  val click: () -> Unit = { if (gate?.acceptsAction() ?: clickEnabled) onClick() }
  val haptics = if (gate != null && onLongClick != null) LocalHapticFeedback.current else null
  return if (onLongClick != null) {
    base.combinedClickable(
        interactionSource = source, indication = null, enabled = clickEnabled,
        hapticFeedbackEnabled = gate == null,
        onClick = click, onLongClick = {
          if (gate?.acceptsAction() ?: clickEnabled) {
            onLongClick()
            // CombinedClickable otherwise requests feedback even for a rejected hold.
            haptics?.performHapticFeedback(HapticFeedbackType.LongPress)
          }
        })
  } else {
    base.clickable(interactionSource = source, indication = null, enabled = clickEnabled, onClick = click)
  }
}

/**
 * Focus treatment for full-width list rows: a filled highlight instead of a ring +
 * scale, so the row doesn't grow outside its container on the TV. Center clicks it.
 */
@Composable
fun Modifier.tvFocusableRow(
    focusFill: Color = Color(0x402E6BE6),
    onClick: () -> Unit,
): Modifier {
  val gate = LocalApprovedLauncherInputGate.current
  val clickEnabled = if (gate == null) LocalApprovedLauncherPageActive.current else true
  val source = remember { MutableInteractionSource() }
  val focused by source.collectIsFocusedAsState()
  val showFocus = focused && (gate?.active ?: clickEnabled)
  val base = this.background(if (showFocus) focusFill else Color.Transparent)
  val focusable = if (gate == null) base else base.focusProperties { if (!gate.active) canFocus = false }
  return focusable.clickable(interactionSource = source, indication = null, enabled = clickEnabled) {
    if (gate?.acceptsAction() ?: clickEnabled) onClick()
  }
}

/**
 * A focus ring for an element that's already focusable+clickable on its own (e.g. a
 * Material Button). Pass the element's [interactionSource] so the ring lights up when
 * the remote selects it, without adding a second click handler.
 */
@Composable
fun Modifier.focusRing(
    interactionSource: MutableInteractionSource,
    shape: Shape = RoundedCornerShape(14.dp),
    ringColor: Color = Color.White,
): Modifier {
  val focused by interactionSource.collectIsFocusedAsState()
  val scale by animateFloatAsState(if (focused) 1.04f else 1f, label = "focusRingScale")
  return this.scale(scale)
      .border(
          BorderStroke(if (focused) 3.dp else 0.dp, if (focused) ringColor else Color.Transparent),
          shape,
      )
}

/**
 * Remembers a [FocusRequester] and requests focus once the node is attached, so a
 * screen opens with something selected for the remote. Attach the returned modifier
 * to the element that should start focused.
 */
@Composable
fun rememberInitialFocus(): Pair<FocusRequester, Modifier> {
  val requester = remember { FocusRequester() }
  val modifier = Modifier.focusRequester(requester)
  DisposableEffect(Unit) {
    runCatching { requester.requestFocus() }
    onDispose {}
  }
  return requester to modifier
}
