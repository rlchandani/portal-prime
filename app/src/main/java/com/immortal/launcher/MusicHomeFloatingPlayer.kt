/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Margins and feel of the draggable card (dp). */
internal object MusicHomeFloatDims {
  /** From the content area's top: below the header bar. */
  val TOP = 16.dp
  /** Past the bounds a drag may stretch before the release settles it (stays inside [TOP]). */
  val OVERSHOOT = 12.dp
  /** A release this close to an edge snaps onto it. */
  val SNAP = 32.dp
  /** Home's bottom toasts' band, and their gap beside the card ([MusicCardAvoidance]). */
  val TOAST_BAND = 220.dp
  val TOAST_GAP = 24.dp
  /** The narrowest a bottom toast is placed beside the card; narrower, it rises above the card. */
  val TOAST_MIN_WIDTH = 480.dp
}

/**
 * Home's Now Playing card, or its minimized tile, floating over Home's content area ([modifier]
 * fills it: below the header, above the dock) and draggable anywhere inside it, never onto the
 * header, the favourites strip ([bottomInset]) or the dock. A release settles it inside the bounds
 * (edge-snapped); the card and the tile each remember their own spot on this Portal
 * ([MusicFloatPositionStore]). Dragging only moves the one player (no re-attach, no reload). Drags
 * start on the card's chrome; the player itself keeps its own touches.
 */
@Composable
internal fun MusicHomeFloatingPlayer(
    room: String,
    onOpenMusic: () -> Unit,
    sideInset: Dp,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
  val flow = remember(room) { engineSlice { MusicV3NowPlaying.from(it, room) } }
  val now by flow.collectAsState(initial = MusicV3NowPlaying.from(YouTubeMusicEngine.state.value, room))
  val track = now.track ?: return
  LaunchedEffect(now.playing) {
    if (!MusicHomeCardRules.autoHides(now.playing)) return@LaunchedEffect
    delay(MusicHomeCardRules.PAUSED_HIDE_MS)
    MusicHomeCardState.dismiss()
  }
  val minimized by MusicHomeCardState.minimized.collectAsState()
  val mode = MusicHomeCardRules.mode(minimized)
  DisposableEffect(Unit) { onDispose { MusicHomeCardState.place(null) } }
  BoxWithConstraints(modifier) {
    val density = LocalDensity.current
    val area = with(density) {
      MusicFloatArea(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), sideInset.toPx(),
          MusicHomeFloatDims.TOP.toPx(), sideInset.toPx(), bottomInset.toPx())
    }
    val sideW = MusicHomeCardDims.sideWidth(maxWidth.value.toInt(), sideInset.value.toInt())
    val size = with(density) {
      MusicFloatSize(MusicHomeCardDims.widthOf(mode, sideW).dp.toPx(), MusicHomeCardDims.heightOf(mode).dp.toPx())
    }
    val position = rememberFloatPosition(mode, size, area)
    MusicHomeCardBody(mode, track, now, room, onOpenMusic,
        Modifier.offset { position.value.let { IntOffset(it.x.roundToInt(), it.y.roundToInt()) } }
            .floatDrag(mode, size, area, position), sideW = sideW)
  }
}

/** The card's animated top-left; re-placed from the saved fraction when the mode or area changes. */
@Composable
private fun rememberFloatPosition(
    mode: MusicHomeCardMode,
    size: MusicFloatSize,
    area: MusicFloatArea,
): Animatable<Offset, *> {
  val context = LocalContext.current
  val saved = remember(mode) { MusicFloatPositionStore.load(context, mode.kind) }
  val start = MusicFloatMath.toOffset(saved, size, area)
  val position = remember(mode) { Animatable(Offset(start.x, start.y), Offset.VectorConverter) }
  LaunchedEffect(mode, size, area) {
    val stored = MusicFloatPositionStore.load(context, mode.kind)
    val o = MusicFloatMath.toOffset(stored, size, area)
    position.snapTo(Offset(o.x, o.y))
    publish(o, size, area)
  }
  return position
}

/** Tells the toasts and notifications where the card rests (not every drag frame). */
private fun publish(o: MusicFloatOffset, size: MusicFloatSize, area: MusicFloatArea) =
    MusicHomeCardState.place(MusicFloatRect(o.x, o.y, size.width, size.height, area.width, area.height))

@Composable
private fun Modifier.floatDrag(
    mode: MusicHomeCardMode,
    size: MusicFloatSize,
    area: MusicFloatArea,
    position: Animatable<Offset, *>,
): Modifier {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val density = LocalDensity.current
  val overshoot = with(density) { MusicHomeFloatDims.OVERSHOOT.toPx() }
  val snap = with(density) { MusicHomeFloatDims.SNAP.toPx() }
  val bounds by rememberUpdatedState(size to area)
  return pointerInput(mode, position) {
    var current = MusicFloatOffset(0f, 0f)
    fun release(save: Boolean) {
      val (s, a) = bounds
      val target = MusicFloatMath.settle(current, s, a, snap)
      if (save) MusicFloatPositionStore.save(context, mode.kind, MusicFloatMath.toPosition(target, s, a))
      publish(target, s, a)
      scope.launch { position.animateTo(Offset(target.x, target.y), spring()) }
    }
    detectDragGestures(
        onDragStart = { current = position.value.let { MusicFloatOffset(it.x, it.y) } },
        onDragEnd = { release(save = true) },
        onDragCancel = { release(save = false) },
    ) { change, amount ->
      change.consume()
      val (s, a) = bounds
      current = MusicFloatMath.drag(current, MusicFloatOffset(amount.x, amount.y), s, a, overshoot)
      val next = current
      scope.launch { position.snapTo(Offset(next.x, next.y)) }
    }
  }
}
