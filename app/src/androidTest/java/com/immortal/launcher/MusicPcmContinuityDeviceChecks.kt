/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Instrumentation
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicit test-only PCM continuity probe. No server playback, queue, volume or group commands. */
internal object MusicPcmContinuityDeviceChecks {
  fun run(instrumentation: Instrumentation, options: Bundle): Bundle {
    val result = Bundle()
    val context = instrumentation.targetContext
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    var client: MusicReceiverClient? = null
    var focus: AudioFocusRequest? = null
    var stage = "opt_in"
    var starts = 0
    var confirmedStops = 0
    var drainChecks = 0
    var noResumeChecks = 0
    var cleaned = false
    val began = SystemClock.elapsedRealtime()
    try {
      check(options.getString("pcmContinuity") == "1")
      val host = requireNotNull(options.getString("host"))
      check(host == "192.168.0.111") { "Unapproved test endpoint." }
      check(audio.mode == AudioManager.MODE_NORMAL && !audio.isMusicActive)
      val control = MusicReceiverClient(context, onChanged = {}, canStart = {
        it.stoppedConfirmed && audio.mode == AudioManager.MODE_NORMAL && !audio.isMusicActive
      })
      client = control
      fun command(action: MusicReceiverProtocol.Action): MusicReceiverProtocol.Observation {
        val latch = CountDownLatch(1)
        var response: MusicReceiverClient.Result? = null
        control.execute(action) { response = it; latch.countDown() }
        check(latch.await(2, TimeUnit.SECONDS)) { "Command deadline." }
        val reply = (response as? MusicReceiverClient.Result.Received)?.reply
            as? MusicReceiverProtocol.Reply.State
        check(reply != null && reply.error == null) { "Command rejected." }
        return reply.observation
      }
      fun awaitState(predicate: (MusicReceiverProtocol.Observation) -> Boolean): MusicReceiverProtocol.Observation {
        val deadline = SystemClock.elapsedRealtime() + 4_000
        do {
          // Every assertion consumes a fresh correlated Query, never a cached stop reply.
          if (control.view.connection == MusicReceiverClient.Connection.CONNECTED && control.view.pendingWhat == null) {
            val state = command(MusicReceiverProtocol.Action.Query)
            if (predicate(state)) return state
          }
          check(control.view.connection != MusicReceiverClient.Connection.UNTRUSTED)
          SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("State deadline.")
      }
      control.attach()
      stage = "initial_stopped"
      awaitState { it.stoppedConfirmed }
      for (kind in listOf("user_stop", "focus_transient")) {
        stage = "start_" + kind
        val before = awaitState { it.stoppedConfirmed }
        command(MusicReceiverProtocol.Action.UserStart(before, host))
        starts++
        val playing = awaitState {
          it.processAlive && it.rendererActive && it.focusHeld && it.playbackRequested &&
              it.pcmFramesDrained > before.pcmFramesDrained
        }
        stage = "suppress_" + kind
        if (kind == "user_stop") {
          command(MusicReceiverProtocol.Action.UserStop)
        } else {
          val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
              .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                  .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
              .setOnAudioFocusChangeListener { }.build()
          focus = request
          check(audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        }
        val stopped = awaitState { it.stoppedConfirmed && it.processAlive && !it.rendererActive }
        check(stopped.instance == playing.instance && stopped.clientId == playing.clientId)
        confirmedStops++
        stage = "drain_after_" + kind
        val drained = awaitState {
          it.stoppedConfirmed && it.processAlive && it.pcmFramesDrained > stopped.pcmFramesDrained
        }
        check(drained.instance == playing.instance)
        drainChecks++
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
        stage = "no_resume_" + kind
        repeat(10) {
          SystemClock.sleep(100)
          val state = command(MusicReceiverProtocol.Action.Query)
          check(state.stoppedConfirmed && state.processAlive && !state.rendererActive &&
              !state.focusHeld && !state.playbackRequested && state.instance == playing.instance)
        }
        noResumeChecks++
      }
      result.putBoolean("musicPcmContinuityPassed", true)
    } catch (_: Throwable) {
      result.putBoolean("musicPcmContinuityPassed", false)
      result.putString("failedStage", stage)
    } finally {
      focus?.let { audio.abandonAudioFocusRequest(it) }
      val control = client
      if (control != null) {
        val done = CountDownLatch(1)
        control.execute(MusicReceiverProtocol.Action.Suppress) { done.countDown() }
        cleaned = done.await(2, TimeUnit.SECONDS)
        control.detach()
      } else cleaned = true
      result.putBoolean("suppressionAcknowledged", cleaned)
      result.putInt("explicitStarts", starts)
      result.putInt("confirmedLocalStops", confirmedStops)
      result.putInt("continuedDrainChecks", drainChecks)
      result.putInt("noAutomaticResumeChecks", noResumeChecks)
      result.putBoolean("audiblePlaybackProven", false)
      result.putBoolean("serverWriteCommandsSent", false)
      result.putLong("elapsedMs", SystemClock.elapsedRealtime() - began)
    }
    return result
  }
}
