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
import java.util.concurrent.atomic.AtomicReference

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
    val diagnostics = Diagnostics()
    val began = SystemClock.elapsedRealtime()
    try {
      check(options.getString("pcmContinuity") == "1")
      val host = requireNotNull(options.getString("host"))
      check(host == "192.168.0.111") { "Unapproved test endpoint." }
      check(audio.mode == AudioManager.MODE_NORMAL && !audio.isMusicActive)
      val control = MusicReceiverClient(context, onChanged = diagnostics::recordView, canStart = {
        it.stoppedConfirmed && audio.mode == AudioManager.MODE_NORMAL && !audio.isMusicActive
      })
      client = control
      fun command(action: MusicReceiverProtocol.Action): MusicReceiverProtocol.Observation {
        val latch = CountDownLatch(1)
        var response: MusicReceiverClient.Result? = null
        control.execute(action) {
          diagnostics.recordResult(it)
          response = it
          latch.countDown()
        }
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
    } catch (failure: Throwable) {
      result.putBoolean("musicPcmContinuityPassed", false)
      result.putString("failedStage", stage)
      // Never include exception messages: they can contain endpoint or credential material.
      result.putString("failureClass", failure.javaClass.simpleName)
    } finally {
      // Capture the failing state before cleanup intentionally changes receiver ownership.
      diagnostics.writeTo(result, SystemClock.elapsedRealtime())
      focus?.let { audio.abandonAudioFocusRequest(it) }
      val control = client
      if (control != null) {
        val done = CountDownLatch(1)
        val cleanupReply = AtomicReference<MusicReceiverClient.Result?>(null)
        control.execute(MusicReceiverProtocol.Action.Suppress) {
          cleanupReply.set(it)
          done.countDown()
        }
        cleaned = done.await(2, TimeUnit.SECONDS) &&
            ((cleanupReply.get() as? MusicReceiverClient.Result.Received)?.reply
                as? MusicReceiverProtocol.Reply.State)?.let { it.error == null } == true
        control.detach()
      } else cleaned = false
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

  /** Keeps only typed, protocol-validated evidence; never emits identities or raw Binder data. */
  private class Diagnostics {
    private val lastView = AtomicReference<MusicReceiverClient.View?>(null)
    private val lastState = AtomicReference<MusicReceiverProtocol.Observation?>(null)
    private val lastClientFailure = AtomicReference<MusicReceiverClient.Failure?>(null)
    private val lastCommandFailure = AtomicReference<MusicReceiverClient.Failure?>(null)
    private val lastReceiverError = AtomicReference<String?>(null)

    fun recordView(view: MusicReceiverClient.View) {
      lastView.set(view)
      view.observation?.let(lastState::set)
      view.failure?.let(lastClientFailure::set)
    }

    fun recordResult(result: MusicReceiverClient.Result) {
      when (result) {
        is MusicReceiverClient.Result.Failed -> lastCommandFailure.set(result.failure)
        is MusicReceiverClient.Result.Received -> when (val reply = result.reply) {
          is MusicReceiverProtocol.Reply.State -> {
            lastState.set(reply.observation)
            reply.error?.let(lastReceiverError::set)
          }
          is MusicReceiverProtocol.Reply.Error -> lastReceiverError.set(reply.error)
        }
      }
    }

    fun writeTo(result: Bundle, nowElapsedMs: Long) {
      lastView.get()?.let { view ->
        result.putString("receiverConnection", view.connection.name)
        view.pendingWhat?.let { result.putInt("receiverPendingWhat", it) }
      }
      lastClientFailure.get()?.let { result.putString("lastClientFailure", it.name) }
      lastCommandFailure.get()?.let { result.putString("lastCommandFailure", it.name) }
      lastReceiverError.get()?.let { result.putString("lastReceiverError", it) }
      val state = lastState.get()
      result.putBoolean("lastValidatedStateAvailable", state != null)
      state?.let {
        result.putString("lastValidatedOutputState", it.outputState)
        result.putString("lastValidatedReason", it.reason)
        result.putLong("lastValidatedEpoch", it.epoch)
        result.putLong("lastValidatedStateAgeMs", (nowElapsedMs - it.observedAtElapsedMs).coerceAtLeast(0))
        result.putLong("lastValidatedPcmFramesDrained", it.pcmFramesDrained)
        result.putBoolean("lastValidatedProcessAlive", it.processAlive)
        result.putBoolean("lastValidatedRendererActive", it.rendererActive)
        result.putBoolean("lastValidatedFocusHeld", it.focusHeld)
        result.putBoolean("lastValidatedPlaybackRequested", it.playbackRequested)
        result.putBoolean("lastValidatedStoppedConfirmed", it.stoppedConfirmed)
      }
    }
  }
}
