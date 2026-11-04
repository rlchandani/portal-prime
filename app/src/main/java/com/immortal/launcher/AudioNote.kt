/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Records and plays the single "leave a note" voice memo to [NotesConfig.audioFile].
 * Uses the standard handset mic (RECORD_AUDIO) — the far-field array needs a Meta-
 * signed permission Immortal can't hold, which is fine for a short up-close memo.
 * One instance owns one recorder/player at a time; call [release] when done.
 */
class AudioNote(private val context: Context) {
  private val TAG = "ImmortalNote"
  private val work = AudioNoteWork()
  private val main = Handler(Looper.getMainLooper())
  // Native handles are accessed only by the shared audio worker.
  private var recorder: MediaRecorder? = null
  private var recordingFile: File? = null
  private var player: MediaPlayer? = null
  private var recordStarted = false
  private var recordStartMs = 0L
  @Volatile private var recording = false
  @Volatile private var playing = false

  val isRecording: Boolean get() = recording
  val isPlaying: Boolean get() = playing

  /** Blocking compatibility API. New UI should use [startRecordingAsync]. */
  fun startRecording(): Boolean {
    playing = false
    return work.call(false) { request ->
      prepareRecorder(request) && startPreparedRecorder(request)
    }
  }

  /** Native preparation/start run off main; retiring a request prevents its queued start. */
  fun startRecordingAsync(onStarted: (Boolean) -> Unit) {
    playing = false
    work.submit { request ->
      if (!prepareRecorder(request)) {
        publish(request) { onStarted(false) }
      } else if (!work.resume(request) {
        val started = startPreparedRecorder(request)
        publish(request) { onStarted(started) }
      }) {
        stopRecorder()
      }
    }
  }

  private fun prepareRecorder(request: AudioNoteWork.Request): Boolean {
    stopPlayer()
    stopRecorder()
    if (!request.isCurrent()) return false
    var file: File? = null
    var prepared: MediaRecorder? = null
    return runCatching {
      val stage = File.createTempFile("voice-memo-", ".m4a.part", context.filesDir)
      file = stage
      @Suppress("DEPRECATION")
      val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
      prepared = rec
      rec.setAudioSource(MediaRecorder.AudioSource.MIC)
      rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
      rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
      rec.setAudioChannels(1)
      rec.setAudioSamplingRate(44100)
      rec.setAudioEncodingBitRate(128000)
      rec.setOutputFile(stage.absolutePath)
      rec.prepare()
      if (!request.isCurrent()) {
        runCatching { rec.release() }
        runCatching { stage.delete() }
        false
      } else {
        recorder = rec
        recordingFile = stage
        recordStarted = false
        true
      }
    }.getOrElse {
      Log.w(TAG, "prepare recording failed", it)
      runCatching { prepared?.release() }
      runCatching { file?.delete() }
      false
    }
  }

  private fun startPreparedRecorder(request: AudioNoteWork.Request): Boolean {
    val rec = recorder ?: return false
    if (!request.isCurrent()) {
      stopRecorder()
      return false
    }
    return runCatching {
      rec.start()
      recordStarted = true
      recordStartMs = SystemClock.elapsedRealtime()
      if (!request.isCurrent()) {
        stopRecorder()
        false
      } else {
        recording = true
        true
      }
    }.getOrElse {
      Log.w(TAG, "start recording failed", it)
      stopRecorder()
      false
    }
  }

  /** Peak input level since the last poll, 0..32767. ~0 means the mic is hearing
   *  near-silence (speaker too far from the device). Drives the UI meter. */
  fun peakLevel(): Int = work.read(0) {
    runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
  }

  /** Blocking compatibility API; new UI uses [stopAsync]. */
  fun stopRecording(): Boolean {
    recording = false
    return work.call(false) { stopRecorder() }
  }

  private fun stopRecorder(): Boolean {
    val r = recorder ?: return NotesConfig.hasAudioNote(context)
    val file = recordingFile
    recorder = null
    recordingFile = null
    recording = false
    val tooShort = !recordStarted || SystemClock.elapsedRealtime() - recordStartMs < 500
    val stopped = recordStarted && runCatching { r.stop() }.isSuccess
    recordStarted = false
    val released = runCatching { r.release() }.isSuccess
    try {
      if (!stopped || !released || tooShort || file == null || !file.isFile || file.length() == 0L) {
        Log.w(TAG, "recording incomplete — keeping the saved memo")
        return false
      }
      // Same-directory rename replaces the saved memo only after native finalization.
      // Never delete the destination or fall back to a partial copy.
      val saved = NotesConfig.audioFile(context)
      if (!file.renameTo(saved)) {
        Log.w(TAG, "recording replacement failed — keeping the saved memo")
        return false
      }
      Log.i(TAG, "recorded ${saved.length()} bytes to ${saved.absolutePath}")
      return true
    } catch (failure: Exception) {
      Log.w(TAG, "recording replacement failed", failure)
      return false
    } finally {
      runCatching { file?.delete() }
    }
  }

  /** Legacy completion callback continues to cover both completion and failure, on main. */
  fun play(onDone: () -> Unit = {}) {
    playAsync(onStarted = {}, onDone = onDone, onError = onDone)
  }

  /** File setup and prepareAsync run off main; prepared callbacks never start a retired player. */
  fun playAsync(onStarted: () -> Unit, onDone: () -> Unit, onError: () -> Unit) {
    playing = false
    recording = false
    work.submit { request ->
      stopPlayer()
      stopRecorder()
      if (!request.isCurrent()) return@submit
      val file = NotesConfig.audioFile(context)
      if (!file.exists() || file.length() == 0L) {
        publish(request, onError)
        return@submit
      }
      runCatching {
        val mp = MediaPlayer()
        player = mp // Publish ownership before any operation that can throw.
        mp.setAudioAttributes(
          AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_MEDIA)
              .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        java.io.FileInputStream(file).use { fis -> mp.setDataSource(fis.fd) }
        mp.setOnPreparedListener {
          work.resume(request) {
            if (player === mp && request.isCurrent()) {
              runCatching {
                mp.start()
                if (request.isCurrent()) {
                  playing = true
                  publish(request, onStarted)
                } else stopPlayer()
              }.onFailure {
                Log.w(TAG, "play start failed", it)
                stopPlayer()
                publish(request, onError)
              }
            }
          }
        }
        mp.setOnCompletionListener {
          work.resume(request) {
            if (player === mp) {
              stopPlayer()
              publish(request, onDone)
            }
          }
        }
        mp.setOnErrorListener { _, what, extra ->
          work.resume(request) {
            if (player === mp) {
              Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
              stopPlayer()
              publish(request, onError)
            }
          }
          true
        }
        if (request.isCurrent()) mp.prepareAsync() else stopPlayer()
      }.onFailure {
        Log.w(TAG, "play preparation failed", it)
        stopPlayer()
        publish(request, onError)
      }
    }
  }

  fun stopPlaying() {
    playing = false
    work.submit {
      stopPlayer()
      // Also release a cancelled recorder that had prepared but had not started.
      if (recorder != null && !recordStarted) stopRecorder()
    }
  }

  private fun stopPlayer() {
    val p = player
    player = null
    playing = false
    if (p != null) {
      runCatching { p.setOnPreparedListener(null) }
      runCatching { p.setOnCompletionListener(null) }
      runCatching { p.setOnErrorListener(null) }
      runCatching { p.stop() }
      runCatching { p.release() }
    }
  }

  /** Immediate logical cancellation; native finalization/release completes on the worker. */
  fun stopAsync(onStopped: (Boolean) -> Unit = {}) {
    recording = false
    playing = false
    work.submit { request ->
      stopPlayer()
      val saved = stopRecorder()
      publish(request) { onStopped(saved) }
    }
  }

  /** Serialize deletion after finalization so an old recorder cannot recreate the deleted memo. */
  fun deleteMemoAsync(onDeleted: (Boolean) -> Unit) {
    recording = false
    playing = false
    work.submit {} // Retire preparation immediately; the explicit file deletion survives hiding.
    work.commit {
      stopPlayer()
      stopRecorder()
      NotesConfig.clearAudio(context)
      val deleted = !NotesConfig.hasAudioNote(context)
      main.post { if (work.isOpen) onDeleted(deleted) }
    }
  }

  /** Initial/re-entry state waits behind an old owner's finalization on the shared worker. */
  fun hasMemoAsync(onResult: (Boolean) -> Unit) {
    work.readAsync(false, { NotesConfig.hasAudioNote(context) },
      { main.post { it() } }, onResult)
  }

  private fun publish(request: AudioNoteWork.Request, callback: () -> Unit) {
    request.dispatch({ main.post { it() } }, callback)
  }

  fun release() {
    recording = false
    playing = false
    work.close {
      stopPlayer()
      stopRecorder()
    }
  }
}

/** Serial native work with immediate request retirement and a single replaceable pending command. */
internal class AudioNoteWork(private val executor: Executor = sharedExecutor) {
  private val lock = Any()
  private var current: Request? = null
  private var pending: Command? = null
  private var committed: Command? = null
  private var draining = false
  private var closed = false
  val isOpen: Boolean get() = synchronized(lock) { !closed }

  inner class Request {
    fun isCurrent(): Boolean = synchronized(lock) { !closed && current === this }
    fun dispatch(post: (() -> Unit) -> Unit, callback: () -> Unit) {
      post { if (isCurrent()) callback() }
    }
  }

  private class Command(val request: Request?, val action: () -> Unit, val onDiscard: () -> Unit = {})

  fun submit(onDiscard: () -> Unit = {}, action: (Request) -> Unit): Request? {
    var replaced: Command? = null
    val request = synchronized(lock) {
      if (closed) null
      else Request().also { request ->
        current = request
        replaced = pending
        pending = Command(request, { action(request) }, onDiscard)
        scheduleLocked()
      }
    }
    replaced?.onDiscard?.invoke()
    if (request == null) onDiscard()
    return request
  }

  /** Continuations from native callbacks must not displace a newer command or final cleanup. */
  fun resume(request: Request, action: (Request) -> Unit): Boolean {
    var replaced: Command? = null
    val accepted = synchronized(lock) {
      if (closed || current !== request) false
      else {
        replaced = pending
        pending = Command(request, { action(request) })
        scheduleLocked()
        true
      }
    }
    replaced?.onDiscard?.invoke()
    return accepted
  }

  /** Only legacy APIs await this worker. Cancellation of pending work completes the waiter. */
  fun <T> call(default: T, action: (Request) -> T): T {
    val result = CompletableFuture<T>()
    submit(onDiscard = { result.complete(default) }) { request ->
      result.complete(runCatching { action(request) }.getOrDefault(default))
    }
    return runCatching { result.get() }.getOrDefault(default)
  }

  /** Legacy peak queries serialize native access without cancelling the audio request. */
  fun <T> read(default: T, action: () -> T): T {
    val result = CompletableFuture<T>()
    executor.execute { result.complete(runCatching(action).getOrDefault(default)) }
    return runCatching { result.get() }.getOrDefault(default)
  }

  fun <T> readAsync(default: T, action: () -> T, post: (() -> Unit) -> Unit, onRead: (T) -> Unit) {
    val snapshot = synchronized(lock) {
      if (closed) return
      current
    }
    executor.execute {
      if (synchronized(lock) { closed || current !== snapshot }) return@execute
      val result = runCatching(action).getOrDefault(default)
      post {
        if (synchronized(lock) { !closed && current === snapshot }) onRead(result)
      }
    }
  }

  /** One explicit memo deletion must finish even if lifecycle cleanup supersedes the audio request. */
  fun commit(action: () -> Unit) {
    synchronized(lock) {
      if (closed) return
      committed = Command(null, action)
      scheduleLocked()
    }
  }

  fun close(cleanup: () -> Unit) {
    var replaced: Command? = null
    synchronized(lock) {
      if (closed) return
      closed = true
      current = null
      replaced = pending
      pending = Command(null, cleanup)
      scheduleLocked()
    }
    replaced?.onDiscard?.invoke()
  }

  private fun scheduleLocked() {
    if (!draining) {
      draining = true
      executor.execute { drain() }
    }
  }

  private fun drain() {
    while (true) {
      val command = synchronized(lock) {
        (committed ?: pending).also {
          if (committed != null) committed = null else pending = null
          if (it == null) draining = false
        }
      } ?: return
      if (command.request == null || command.request.isCurrent()) {
        runCatching { command.action() }
      } else command.onDiscard()
    }
  }

  companion object {
    // Shared across owners: finishing an old recording cannot race a new owner writing the memo.
    private val sharedExecutor = Executors.newSingleThreadExecutor { task ->
      Thread(task, "ImmortalMemoAudio").apply { isDaemon = true }
    }
  }
}
