/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Routes the call state to its screen. Call screens are always dark, like the mock's `.vidscr`. */
@Composable
internal fun CallApp(motion: Boolean, lowMemory: Int, onClose: () -> Unit, onNeedPermissions: (camera: Boolean) -> Unit) {
  val context = LocalContext.current
  val state by Calling.state.collectAsState()
  val media by CallMediaHub.media.collectAsState()
  val toast = rememberCallToast()
  val call = state.call
  val ended = state.ended

  BackHandler {
    if (call != null) Calling.hangUp() else { Calling.dismissEnded(); onClose() }
  }
  // Ask for the mic (and camera for video) once, when this Portal is about to use them.
  val needsMedia = call != null && (call.direction == CallDirection.OUTGOING || !call.phase.beforeAnswer)
  LaunchedEffect(needsMedia, call?.id) {
    if (!needsMedia || call == null) return@LaunchedEffect
    val camera = call.video
    val missing = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
        (camera && context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
    if (missing) onNeedPermissions(camera)
  }
  LaunchedEffect(lowMemory) { if (lowMemory > 0) toast.show(CallingCopy.LOW_MEMORY, "info") }
  LaunchedEffect(call == null, ended == null) { if (call == null && ended == null) onClose() }

  Box(Modifier.fillMaxSize().background(CallColors.VideoBg)) {
    when {
      call == null && ended != null -> CallEndedScreen(ended, motion,
          onCallBack = { kind -> Calling.dismissEnded(); if (!Calling.dial(context, ended.peer.deviceId, kind, video = true)) onClose() },
          onDone = { Calling.dismissEnded(); onClose() })
      call == null -> Unit
      call.direction == CallDirection.INCOMING && call.phase.beforeAnswer ->
        if (call.kind == CallKind.DROP_IN) CallDropInIncoming(call, motion) else CallIncomingScreen(call, motion)
      call.direction == CallDirection.OUTGOING && call.phase.beforeAnswer -> CallOutgoingScreen(call, media, motion)
      else -> CallLiveScreen(call, media, motion, toast)
    }
    CallToastHost(toast, Modifier.align(Alignment.TopCenter).padding(top = 40.dp))
  }
}

/** Wall-clock now, re-read every [stepMs] while composed (countdowns and call timers). */
@Composable
internal fun rememberCallClock(stepMs: Long = CLOCK_STEP_MS): Long {
  var now by remember { mutableLongStateOf(Calling.now()) }
  LaunchedEffect(stepMs) {
    while (true) {
      now = Calling.now()
      delay(stepMs)
    }
  }
  return now
}

private const val CLOCK_STEP_MS = 250L
