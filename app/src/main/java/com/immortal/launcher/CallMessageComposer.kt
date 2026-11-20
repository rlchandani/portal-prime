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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.Executors

/**
 * Runs one "Leave a message" screen: feeds taps, recorder callbacks and a 250 ms tick into the
 * pure [CallMessageCompose] reducer and performs its effects. Main thread, except the upload.
 */
internal class CallMessageComposer(context: Context, val peer: CallPeer, testFrames: Boolean, private val onFinish: () -> Unit,
                                   private val callId: String? = null) {
  private val app = context.applicationContext
  private val main = Handler(Looper.getMainLooper())
  private val net = Executors.newSingleThreadExecutor { r -> Thread(r, "hearthpane-vmsg-send").apply { isDaemon = true } }
  var state by mutableStateOf(ComposeState())
    private set
  var cameraProblem by mutableStateOf(false)
    private set
  val recorder = CallMessageRecorder(app, testFrames, object : CallMessageRecorder.Events {
    override fun onCameraProblem() = main.post { cameraProblem = true }.let { }
    override fun onRecorded(ok: Boolean, durationMs: Long) = main.post { dispatch(ComposeEvent.Recorded(ok, durationMs)) }.let { }
  })
  private var released = false
  /** The take's player while Review is on screen (Watch it toggles it). */
  var reviewPlayer: androidx.media3.exoplayer.ExoPlayer? = null

  fun watch() {
    val p = reviewPlayer ?: return
    if (p.isPlaying) p.pause() else { if (p.playbackState == androidx.media3.common.Player.STATE_ENDED) p.seekTo(0); p.play() }
  }

  private val tick = object : Runnable {
    override fun run() {
      if (released) return
      dispatch(ComposeEvent.Tick(Calling.now()))
      main.postDelayed(this, TICK_MS)
    }
  }

  init {
    recorder.camera(state.cameraOn)
    main.post(tick)
  }

  fun dispatch(e: ComposeEvent) {
    if (released) return
    val step = CallMessageCompose.reduce(state, e)
    state = step.state
    step.effects.forEach(::perform)
  }

  private fun perform(effect: ComposeEffect) {
    when (effect) {
      is ComposeEffect.OpenCamera -> { cameraProblem = false; recorder.camera(effect.on) }
      is ComposeEffect.StartRecording -> recorder.start(effect.cameraOn)
      ComposeEffect.StopRecording -> recorder.stop()
      ComposeEffect.CloseCamera -> recorder.camera(false)
      ComposeEffect.DeleteTake -> recorder.deleteTake()
      ComposeEffect.Upload -> { reviewPlayer?.pause(); upload() }
      ComposeEffect.Finish -> onFinish()
    }
  }

  private fun upload() {
    val take = recorder.take
    val duration = state.durationMs
    val audioOnly = !state.cameraOn
    net.execute {
      // A phone has no server: its message waits here, sealed, until Portal Toolkit collects it.
      val failure = if (CallPhoneId.isPhone(peer.id)) CallPhoneOutbox.put(app, take, peer, duration, audioOnly, callId)
      else CallMessageSender.send(app, take, peer, duration, audioOnly, callId)
      main.post { dispatch(if (failure == null) ComposeEvent.SendOk else ComposeEvent.SendFailed(failure)) }
    }
  }

  /** The screen went away: nothing is kept (camera, mic and the take all go). */
  fun release() {
    if (released) return
    dispatch(ComposeEvent.Discard)
    released = true
    main.removeCallbacks(tick)
    // Release deletes the take; an upload in flight keeps streaming from its open (now unlinked) file.
    recorder.release()
    net.shutdown()
    Log.i(TAG, "compose closed")
  }

  private companion object {
    const val TAG = "HearthpaneCallMsg"
    const val TICK_MS = 250L
  }
}
