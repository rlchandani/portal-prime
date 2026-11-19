/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Measurement-only probe (BuildConfig.PORTAL_CALL_LOOPBACK, off in every normal build): two
 * [CallMedia] peers in one process exchange SDP directly and stream over the LAN interface, so a
 * single Portal exercises the full call path (camera 0 → HW encode → DTLS-SRTP → decode → render)
 * for fps, bitrate, gfxinfo and memory numbers. Mics stay off; nothing is recorded.
 */
internal class CallLoopback private constructor(context: Context, onStats: (String) -> Unit) {
  private val main = Handler(Looper.getMainLooper())
  private var send: CallStats? = null
  private var recv: CallStats? = null
  val caller = CallMedia(context, CallQuality.AUTO, events("send") { send = it; report(onStats) })
  val callee = CallMedia(context, CallQuality.AUTO, events("recv") { recv = it; report(onStats) })

  private fun events(tag: String, sink: (CallStats) -> Unit) = object : CallMedia.Events {
    override fun onConnected() { Log.i(TAG, "$tag connected") }
    override fun onDisconnected() { Log.i(TAG, "$tag disconnected") }
    override fun onFailed() { Log.w(TAG, "$tag failed") }
    override fun onCameraIssue(issue: CameraIssue) { Log.w(TAG, "$tag camera issue $issue") }
    override fun onStats(stats: CallStats) { main.post { sink(stats) } }
  }

  private fun report(onStats: (String) -> Unit) {
    val s = send ?: return
    val r = recv
    val line = "send ${s.sendWidth}x${s.sendHeight} @ ${"%.1f".format(s.sendFps)} fps · ${s.sendKbps} kbps · ${s.encoder} ${s.codec}" +
        " · limit ${s.limitedBy}\nrecv @ ${"%.1f".format(r?.recvFps ?: 0.0)} fps · ${r?.recvKbps ?: 0} kbps · rtt ${s.rttMs ?: -1} ms" +
        " · jitter buffer ${r?.jitterBufferMs ?: -1} ms · e2e ~${r?.copy(rttMs = s.rttMs)?.latencyEstimateMs ?: -1} ms"
    Log.i("HearthpaneCallStats", line.replace('\n', ' '))
    onStats(line)
  }

  private fun connect() {
    caller.camera(true)
    caller.mic(false)
    callee.mic(false)
    caller.createOffer { offer ->
      if (offer == null) { Log.w(TAG, "no offer"); return@createOffer }
      callee.answer(offer) { answer ->
        if (answer == null) { Log.w(TAG, "no answer"); return@answer }
        caller.applyAnswer(answer) { ok -> Log.i(TAG, "loopback negotiated=$ok") }
      }
    }
  }

  fun stop() {
    caller.release()
    callee.release()
  }

  companion object {
    private const val TAG = "HearthpaneCallLoopback"
    fun start(context: Context, onStats: (String) -> Unit) = CallLoopback(context, onStats).also { it.connect() }
  }
}

/** Full-screen probe UI: the same remote renderer, self view and live bar as a real call. */
@Composable
internal fun CallLoopbackScreen(onClose: () -> Unit) {
  val context = LocalContext.current
  val owner = LocalLifecycleOwner.current
  var stats by remember { mutableStateOf("Connecting…") }
  var probe by remember { mutableStateOf<CallLoopback?>(null) }
  DisposableEffect(Unit) {
    val p = CallLoopback.start(context) { stats = it }
    probe = p
    val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) onClose() }
    owner.lifecycle.addObserver(observer)
    onDispose { owner.lifecycle.removeObserver(observer); p.stop() }
  }
  val p = probe ?: return
  Box(Modifier.fillMaxSize().background(CallColors.VideoBg)) {
    CallRemoteVideo(p.callee, Modifier.fillMaxSize())
    CallLiveBar("Loopback test · Camera on · Mic off", live = true, motion = true, modifier = Modifier.align(Alignment.TopStart).padding(40.dp))
    CallSelfFrame(p.caller, true, CallingCopy.SELF_LABEL, Modifier.align(Alignment.TopEnd).padding(40.dp))
    Column(Modifier.align(Alignment.BottomStart).padding(40.dp).background(CallColors.Bar, RoundedCornerShape(24.dp)).padding(24.dp)) {
      Text(stats, color = Color.White, fontSize = 22.sp)
    }
    CallPill("End test", onClose, icon = "end", style = CallButtonStyle.BAD, modifier = Modifier.align(Alignment.BottomEnd).padding(40.dp))
  }
}
