/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
  if (phase !in setOf(CcPhase.UNREACHABLE, CcPhase.AUTH_FAILED, CcPhase.NOT_CONFIGURED, CcPhase.OFFLINE)) return
  val c = LocalCc.current
  Box(Modifier.fillMaxSize().padding(start = CcStyle.RailWidth, top = CcStyle.HeaderHeight).background(c.scrim.copy(alpha = 0.35f))) {
    val retry = rememberCountdown(s.connection.retryAt)
    val last = s.connection.lastLiveAt.takeIf { it > 0 }?.let { "Showing last known state from ${ccTime(it)}. " } ?: ""
    when (phase) {
      CcPhase.UNREACHABLE -> CcCenteredMessage("warn", "Can't reach Home Assistant",
          "The Portal is online but the Home Assistant server isn't answering. ${last}Locks, garage and alarm stay disabled until it's back." +
              (if (retry > 0) "\n\nRetrying in $retry s (backs off to 60 s)." else ""),
          primary = "Retry now" to { ControlCenter.retryNow(context) },
          secondary = "Connection settings" to { context.openApprovedHomeSettings("ha") }, tone = CcTone.RED)
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
  LaunchedEffect(toast?.id) { if (toast != null) { delay(TOAST_MS); ControlCenter.dismissToast(context, toast.id) } }
  Box(Modifier.fillMaxSize().padding(bottom = 36.dp), contentAlignment = Alignment.BottomCenter) {
    AnimatedVisibility(toast != null, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
      val t = toast ?: return@AnimatedVisibility
      Row(Modifier.widthIn(max = 1100.dp).clip(CcStyle.Pill).background(if (c.dark) c.surface2 else c.text)
          .padding(start = 30.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        if (t.error) CcIcon("warn", c.red, 30.dp) else CcIcon("check", c.green, 30.dp)
        Text(t.text, color = if (c.dark) c.text else c.bg, fontSize = 23.sp, maxLines = 2, modifier = Modifier.padding(vertical = 14.dp))
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
private const val TOAST_MS = 5_000L
