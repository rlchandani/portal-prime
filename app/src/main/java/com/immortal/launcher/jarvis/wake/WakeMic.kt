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
 * time, gains it ([WakeGain]), runs it through a [MicroFrontend] and the [WakeWordModel], and forgets it. The mic is released the moment a
 * session ends: [stop] (main thread) unblocks the read. A wake ends detection at once and is reported; the recorder
 * then bridges (reads and discards) until Jarvis's own recording is live, so Jarvis starts on a warm input.
 *
 * Audio never leaves this class: nothing is kept past the frontend's 30 ms window, logged or stored.
 */
internal class WakeMic(
    private val model: WakeWordModel,
    private val events: Events,
) {
  interface Events {
    /**
     * Capture thread: "Hey Jarvis" was heard at [uptimeMs] with smoothed [score]. Detection has ended; the recorder
     * stays open, discarding everything, until [stop] (Jarvis is recording) or at most 1.5 s (the warm-mic bridge).
     */
    fun onWake(uptimeMs: Long, score: Float)

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
      rec.startRecording()
      if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) return Outcome.Ended("mic busy")
      try {
        while (live) {
          val n = rec.read(pcm, 0, READ_BYTES)
          if (n < 0) return Outcome.Ended("read error $n")
          WakeGain.apply(pcm, n)
          for (offset in 0..n - CHUNK_BYTES step CHUNK_BYTES) {
            if (!frontend.process(pcm, offset, features)) continue
            val q8 = model.push(features) ?: continue
            score = score.next(q8)
            if (score.triggered) {
              events.onWake(SystemClock.uptimeMillis(), score.mean)
              bridge(rec, pcm)
              return Outcome.Reported
            }
          }
        }
        return Outcome.Ended("stopped")
      } finally {
        pcm.fill(0)
      }
    }

    /**
     * The warm-mic bridge after a wake: keep the input running, reading and discarding everything (nothing reaches
     * the frontend, nothing is kept), until [stop] — Jarvis's own recording went live — or [BRIDGE_MS]. Jarvis's card
     * is the top app by then, so Android gives *it* the audio; its recorder attaches to a running input instead of
     * waking the audio HAL from standby (~250 ms on Family), which is most of wake-to-listening.
     */
    private fun bridge(rec: AudioRecord, pcm: ByteArray) {
      val end = SystemClock.uptimeMillis() + BRIDGE_MS
      while (live && SystemClock.uptimeMillis() < end && rec.read(pcm, 0, READ_BYTES) >= 0) pcm.fill(0)
    }

    private fun report(outcome: Outcome) {
      live = false
      when (outcome) {
        Outcome.Reported -> Unit
        is Outcome.Ended -> events.onEnded(outcome.reason)
      }
    }
  }

  private sealed interface Outcome {
    /** A wake, already reported from the capture thread. */
    data object Reported : Outcome
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
    const val BRIDGE_MS = 1_500L
  }
}
