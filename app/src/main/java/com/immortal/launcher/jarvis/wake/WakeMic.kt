/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis.wake

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import android.util.Log

/**
 * Listening sessions on the microphone. Each [start] runs one capture thread that reads 30 ms of 16 kHz PCM at a
 * time, runs it through a [MicroFrontend] and the [WakeWordModel], and forgets it. The mic is released the moment a
 * session ends: [stop] (main thread) unblocks the read, and a wake stops *and releases* the recorder on the capture
 * thread before it is reported, so Jarvis finds the mic free when its card opens.
 *
 * Audio never leaves this class: nothing is kept past the frontend's 30 ms window, logged or stored.
 */
internal class WakeMic(
    private val model: WakeWordModel,
    private val events: Events,
) {
  interface Events {
    /** Capture thread, mic already released: "Hey Jarvis" was heard at [uptimeMs] with smoothed [score]. */
    fun onWake(uptimeMs: Long, score: Float)

    /**
     * Capture thread, just before recording starts: [sessionId] identifies our own capture among the device's
     * recordings (reported first, so our own start never reads as "another app took the mic").
     */
    fun onOpened(sessionId: Int)

    /** Capture thread: a session ended without a wake ([reason] is for the log). */
    fun onEnded(reason: String)
  }

  /**
   * One capture: its own flag, so a quick stop→start can never revive the previous thread. It waits for the
   * [previous] capture thread to finish first: the model and the mic belong to one session at a time.
   */
  private inner class Session(private val previous: Thread?) : Runnable {
    @Volatile var live = true
    @Volatile var record: AudioRecord? = null

    override fun run() {
      Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
      previous?.join(JOIN_MS)
      if (previous?.isAlive == true) return report(Outcome.Ended("previous capture still closing"))
      val rec = open() ?: return report(Outcome.Ended("mic unavailable"))
      record = rec
      val outcome = try {
        if (live) listen(rec) else Outcome.Ended("stopped")
      } catch (e: RuntimeException) {
        Log.w(TAG, "wake capture failed: ${e.javaClass.simpleName}")
        Outcome.Ended("capture error")
      } finally {
        record = null
        runCatching { rec.stop() }
        rec.release()
      }
      report(outcome)
    }

    private fun listen(rec: AudioRecord): Outcome {
      model.reset()
      val frontend = MicroFrontend()
      val pcm = ByteArray(READ_BYTES)
      val features = IntArray(MicroFilterbank.CHANNELS)
      var score = WakeScore()
      events.onOpened(rec.audioSessionId)
      rec.startRecording()
      if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) return Outcome.Ended("mic busy")
      try {
        while (live) {
          val n = rec.read(pcm, 0, READ_BYTES)
          if (n < 0) return Outcome.Ended("read error $n")
          for (offset in 0..n - CHUNK_BYTES step CHUNK_BYTES) {
            if (!frontend.process(pcm, offset, features)) continue
            val q8 = model.push(features) ?: continue
            score = score.next(q8)
            if (score.triggered) return Outcome.Woke(SystemClock.uptimeMillis(), score.mean)
          }
        }
        return Outcome.Ended("stopped")
      } finally {
        pcm.fill(0)
      }
    }

    private fun report(outcome: Outcome) {
      live = false
      when (outcome) {
        is Outcome.Woke -> events.onWake(outcome.uptimeMs, outcome.score)
        is Outcome.Ended -> events.onEnded(outcome.reason)
      }
    }
  }

  private sealed interface Outcome {
    data class Woke(val uptimeMs: Long, val score: Float) : Outcome
    data class Ended(val reason: String) : Outcome
  }

  private var current: Session? = null
  private var thread: Thread? = null

  /** Main thread: true while a session is capturing (or about to). */
  val active: Boolean get() = current?.live == true

  /** Main thread. Starts listening unless a session is already live. */
  fun start() {
    if (active) return
    val session = Session(thread)
    current = session
    thread = Thread(session, "hearthpane-wake").apply { isDaemon = true; start() }
  }

  /** Main thread. Stops at once: the blocked read returns and the capture thread releases the mic. */
  fun stop() {
    val session = current ?: return
    current = null
    session.live = false
    runCatching { session.record?.stop() }
  }

  @SuppressLint("MissingPermission") // WakePolicy only listens with RECORD_AUDIO granted.
  private fun open(): AudioRecord? {
    val min = AudioRecord.getMinBufferSize(MicroFrontend.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT)
    val rec = runCatching {
      AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, MicroFrontend.SAMPLE_RATE,
          AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, BUFFER_BYTES))
    }.getOrNull() ?: return null
    if (rec.state == AudioRecord.STATE_INITIALIZED) return rec
    rec.release()
    return null
  }

  private companion object {
    const val TAG = "HearthpaneWake"
    const val CHUNK_BYTES = 2 * MicroFrontend.STEP // 10 ms
    const val READ_BYTES = 3 * CHUNK_BYTES // 30 ms: one model step per read, ~33 wakeups a second
    const val BUFFER_BYTES = 8 * READ_BYTES // 240 ms of slack so a busy moment never drops audio
    const val JOIN_MS = 1_000L
  }
}
