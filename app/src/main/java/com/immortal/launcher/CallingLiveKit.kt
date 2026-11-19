/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * `.ind`: the live bar, top left on every call screen. A red pulsing dot while camera or mic is
 * live (grey and still when both are off), the camera/mic state in words, then "🔒 E2E".
 */
@Composable
internal fun CallLiveBar(text: String, live: Boolean, motion: Boolean, modifier: Modifier = Modifier, e2e: Boolean = true) {
  Row(modifier.height(72.dp).background(CallColors.Bar, RoundedCornerShape(50)).padding(start = 26.dp, end = 30.dp)
      .semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(18.dp)) {
    LiveDot(live, motion)
    // Compact (the Mini): a narrow bar ellipsizes the state words before it drops the E2E mark.
    if (currentLayoutClass().compact) Text(text, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
    else Text(text, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    if (e2e) {
      Box(Modifier.width(1.5.dp).height(30.dp).background(CallColors.BarDivider))
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CallIcon("lock", Color.White, 26.dp)
        Text(CallingCopy.E2E, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
      }
    }
  }
}

@Composable
private fun LiveDot(live: Boolean, motion: Boolean) {
  val color = if (live) CallColors.LiveRed else CallColors.DotIdle
  val pulse = if (live && motion) {
    val t = rememberInfiniteTransition(label = "live")
    t.animateFloat(0f, 1f, infiniteRepeatable(tween(PULSE_MS)), label = "pulse").value
  } else null
  Box(Modifier.size(18.dp).drawBehind {
    pulse?.let { p -> drawCircle(color.copy(alpha = PULSE_ALPHA * (1f - p)), radius = size.minDimension / 2 + PULSE_REACH.toPx() * p) }
    drawCircle(color)
  })
}

/** The self view frame: 420×280 (compact: see [CallsCompactLayout.selfView]), radius 28, 3 dp glass ring, label under the picture. */
@Composable
internal fun CallSelfFrame(media: CallMedia?, cameraOn: Boolean, label: String, modifier: Modifier = Modifier,
                           width: androidx.compose.ui.unit.Dp = 420.dp, height: androidx.compose.ui.unit.Dp = width * 2 / 3) {
  val shape = RoundedCornerShape(28.dp)
  Box(modifier.width(width).height(height).clip(shape).background(CallColors.VideoBg)
      .border(3.dp, CallColors.GlassEdge, shape)) {
    if (media != null && cameraOn) CallSelfVideo(media, Modifier.fillMaxSize())
    AnimatedVisibility(!cameraOn, enter = slideInHorizontally(spring(Spring.DampingRatioLowBouncy)) { -it } + fadeIn(),
        exit = fadeOut()) { CallShutter(Modifier.fillMaxSize(), compact = width < SHUTTER_COMPACT) }
    Text(if (cameraOn) label else CallingCopy.CAMERA_OFF, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)
            .background(CallColors.Bar, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp))
  }
}

/** The shutter visual: diagonal stripes, a lens circle, "Camera off / Nobody can see you". */
@Composable
internal fun CallShutter(modifier: Modifier = Modifier, compact: Boolean = true) {
  Box(modifier.drawBehind {
    drawRect(CallColors.ShutterA)
    val step = STRIPE.toPx()
    var x = -size.height
    while (x < size.width) {
      drawLine(CallColors.ShutterB, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = step / 2)
      x += step
    }
  }, contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Box(Modifier.size(if (compact) 90.dp else 160.dp).border(4.dp, Color.White.copy(alpha = 0.3f), CircleShape),
          contentAlignment = Alignment.Center) { CallIcon("videoOff", Color.White.copy(alpha = 0.85f), if (compact) 40.dp else 70.dp) }
      Text(CallingCopy.CAMERA_OFF, color = Color.White, fontSize = if (compact) 22.sp else 34.sp, fontWeight = FontWeight.Bold)
      Text(CallingCopy.NOBODY_SEES, color = Color.White.copy(alpha = 0.7f), fontSize = if (compact) 19.sp else 26.sp)
    }
  }
}

@Immutable
internal data class CallToastMessage(val text: String, val icon: String?, val id: Long)

/** A toast state: [show] replaces the current one; it disappears after 2.8 s (mock). */
internal class CallToastState {
  var current by mutableStateOf<CallToastMessage?>(null)
    private set
  private var next = 0L

  fun show(text: String, icon: String? = null) { current = CallToastMessage(text, icon, ++next) }
  fun clear(id: Long) { if (current?.id == id) current = null }
}

@Composable
internal fun CallToastHost(state: CallToastState, modifier: Modifier = Modifier) {
  val message = state.current
  LaunchedEffect(message?.id) {
    val id = message?.id ?: return@LaunchedEffect
    delay(TOAST_MS)
    state.clear(id)
  }
  AnimatedVisibility(message != null, modifier, enter = slideInVertically(spring(Spring.DampingRatioMediumBouncy)) { -it / 2 } + fadeIn(),
      exit = fadeOut()) {
    val m = message ?: return@AnimatedVisibility
    Row(Modifier.height(80.dp).background(CallColors.Toast, RoundedCornerShape(50)).padding(horizontal = 34.dp)
        .semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
      m.icon?.let { CallIcon(it, Color.White, 32.dp) }
      Text(m.text, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
  }
}

@Composable
internal fun rememberCallToast(): CallToastState = remember { CallToastState() }

private const val PULSE_MS = 1_600
private const val PULSE_ALPHA = 0.6f
private val PULSE_REACH = 14.dp
private val STRIPE = 28.dp
private val SHUTTER_COMPACT = 600.dp
private const val TOAST_MS = 2_800L
