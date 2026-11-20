/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Connection states from the mock's States group, drawn over the last known map: Home Assistant
 * unreachable (retry with back-off, last known time), token expired (send a new one from the
 * phone), not set up, and Portal offline. Loading and stale are shown in place by the overview
 * (skeleton plan, dimmed values + the header pill).
 */
@Composable
internal fun CcConnectionOverlay(s: CcUiState, ui: CcUi) {
  val context = LocalContext.current
  if (ui.screen == CcScreen.SETTINGS) return
  val phase = s.connection.phase
  if (!phase.blocksHome) return
  val c = LocalCc.current
  // Portrait (Mini upright): the header is on top and the bar at the bottom instead of the rail.
  val inset = if (currentLayoutClass().compactPortrait)
    Modifier.padding(top = CcCompactChrome.HeaderHeight, bottom = CcCompactChrome.NavHeight)
  else Modifier.padding(start = CcStyle.RailWidth, top = CcStyle.HeaderHeight)
  Box(Modifier.fillMaxSize().then(inset).background(c.scrim.copy(alpha = 0.35f))) {
    val retry = rememberCountdown(s.connection.retryAt)
    val last = s.connection.lastLiveAt.takeIf { it > 0 }?.let { "Showing last known state from ${ccTime(it)}. " } ?: ""
    when (phase) {
      CcPhase.UNREACHABLE -> {
        val server = remember(context) { HaConfig.baseUrl(context) }
        CcCenteredMessage("warn", "Can't reach Home Assistant",
            "The Portal is online but the Home Assistant server isn't answering. ${last}Locks, garage and alarm stay disabled until it's back.",
            primary = "Retry now" to { ControlCenter.retryNow(context) }, primaryIcon = "refresh",
            secondary = "Connection settings" to { context.openApprovedHomeSettings("ha") }, tone = CcTone.RED,
            extra = { CcChecklist(CcUnreachable.lines(server, retry)) })
      }
      CcPhase.AUTH_FAILED -> CcCenteredMessage("key", "Home Assistant token expired",
          "Home Assistant refused this Portal's token. Send a new long-lived token from Portal Toolkit on your phone, or enter one in Settings › Home Assistant. Nothing was changed in your home.",
          primary = "Connection settings" to { context.openApprovedHomeSettings("ha") },
          secondary = "Try again" to { ControlCenter.retryNow(context) }, tone = CcTone.AMBER)
      CcPhase.OFFLINE -> CcCenteredMessage("wifioff", "This Portal is offline",
          "${last}Controls are paused and nothing is queued — they come back as soon as Wi-Fi does.",
          primary = "Retry now" to { ControlCenter.retryNow(context) }, tone = CcTone.AMBER)
      else -> CcCenteredMessage("link", "Connect Home Assistant",
          "The Control Center shows your home live from Home Assistant. Add its address and a long-lived token first.",
          primary = "Set up Home Assistant" to { context.openApprovedHomeSettings("ha") })
    }
  }
}

/** One line of a state card's checklist (mock `.cklist`): passed, failed, or still trying. */
internal enum class CcCheckMark { OK, FAIL, WAIT }

@Immutable
internal data class CcStateCheck(val mark: CcCheckMark, val text: String)

/** The unreachable card's checklist, as the mock: Portal Wi-Fi ✓, the server ✗ no response, the retry. Pure. */
internal object CcUnreachable {
  fun lines(baseUrl: String, retrySec: Long): List<CcStateCheck> = listOf(
      CcStateCheck(CcCheckMark.OK, "Portal Wi-Fi"),
      CcStateCheck(CcCheckMark.FAIL, "${server(baseUrl)} — no response"),
      CcStateCheck(CcCheckMark.WAIT, if (retrySec > 0) "Retrying in $retrySec s (backs off to 60 s)" else "Retrying now"),
  )

  /** The saved address as typed (the mock shows the URL); "Home Assistant" when none is saved. */
  fun server(baseUrl: String): String = baseUrl.trim().trimEnd('/').ifEmpty { "Home Assistant" }
}

/** ✓ / ✗ / spinner rows; the spinner turns in the draw phase only (no recomposition per frame). */
@Composable
private fun CcChecklist(lines: List<CcStateCheck>) {
  val c = LocalCc.current
  Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
    lines.forEach { line ->
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        when (line.mark) {
          CcCheckMark.OK -> CcIcon("check", c.green, 28.dp)
          CcCheckMark.FAIL -> CcIcon("x", c.red, 28.dp)
          CcCheckMark.WAIT -> CcSpinner(c.sec, 28.dp)
        }
        Text(line.text, color = c.text, fontSize = 23.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
  }
}

@Composable
private fun CcSpinner(color: Color, size: Dp) {
  val turn = rememberInfiniteTransition(label = "spin")
  val angle by turn.animateFloat(0f, FULL_TURN, infiniteRepeatable(tween(SPIN_MS, easing = LinearEasing)), label = "angle")
  Canvas(Modifier.size(size).padding(3.dp).graphicsLayer { rotationZ = angle }) {
    val stroke = SPIN_STROKE.dp.toPx()
    drawCircle(color.copy(alpha = SPIN_TRACK), style = Stroke(stroke))
    drawArc(color, -90f, SPIN_SWEEP, useCenter = false, style = Stroke(stroke, cap = StrokeCap.Round))
  }
}

/** Whole seconds until [at] (0 when past), ticking once a second. */
@Composable
internal fun rememberCountdown(at: Long): Long {
  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(at) { while (at > now) { delay(TICK_MS); now = System.currentTimeMillis() } }
  return ((at - now + TICK_MS - 1) / TICK_MS).coerceAtLeast(0)
}

/** Bottom toast with Undo (`.toast`); disappears after [TOAST_MS]. */
@Composable
internal fun CcToastHost(toast: CcToast?) {
  val context = LocalContext.current
  val c = LocalCc.current
  val layout = currentLayoutClass()
  LaunchedEffect(toast?.id) { if (toast != null) { delay(TOAST_MS); ControlCenter.dismissToast(context, toast.id) } }
  // Portrait: above the bottom bar, inset from the screen edges, the text wrapping before Undo.
  val frame = if (layout.compactPortrait) Modifier.padding(start = 24.dp, end = 24.dp, bottom = CcCompactChrome.NavHeight + 24.dp)
  else Modifier.padding(bottom = 36.dp)
  Box(Modifier.fillMaxSize().then(frame), contentAlignment = Alignment.BottomCenter) {
    AnimatedVisibility(toast != null, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
      val t = toast ?: return@AnimatedVisibility
      Row(Modifier.widthIn(max = 1100.dp).clip(CcStyle.Pill).background(if (c.dark) c.surface2 else c.text)
          .padding(start = 30.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        if (t.error) CcIcon("warn", c.red, 30.dp) else CcIcon("check", c.green, 30.dp)
        Text(t.text, color = if (c.dark) c.text else c.bg, fontSize = 23.sp, maxLines = 2,
            modifier = (if (layout.compact) Modifier.weight(1f, fill = false) else Modifier).padding(vertical = 14.dp))
        if (t.undo) Row(Modifier.clip(CcStyle.Pill).background(c.bg.copy(alpha = 0.18f)).clickable {
          ControlCenter.undo(context); ControlCenter.dismissToast(context, t.id)
        }.padding(horizontal = 26.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          CcIcon("undo", CcStyle.EmberLight, 26.dp)
          Text("Undo", color = CcStyle.EmberLight, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        } else Box(Modifier.size(12.dp))
      }
    }
  }
}

private const val TICK_MS = 1_000L
private const val FULL_TURN = 360f
private const val SPIN_MS = 900
private const val SPIN_SWEEP = 100f
private const val SPIN_TRACK = .25f
private const val SPIN_STROKE = 3
private const val TOAST_MS = 5_000L
