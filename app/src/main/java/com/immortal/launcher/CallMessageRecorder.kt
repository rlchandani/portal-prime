/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.File

/**
 * Camera 0 (Meta's AI-framed virtual camera) → visible self preview + MediaRecorder (H.264
 * 1280×720 ~1.6 Mbps, AAC mono), into one app-private temp file. The camera is open only while
 * [preview] is attached and the screen shows it (Portal closes a camera after ~7 s without a
 * visible preview). Measurement builds can swap the camera for [CallMessageTestFrames] and record
 * no audio, so night tests never capture a room. All work runs on one camera thread.
 */
internal class CallMessageRecorder(context: Context, private val testFrames: Boolean, private val events: Events) {
  interface Events {
    fun onCameraProblem()
    /** Recording ended (stop, the 2:00 cap or an error). [ok] false: the take is unusable. */
    fun onRecorded(ok: Boolean, durationMs: Long)
  }

  private val app = context.applicationContext
  private val thread = HandlerThread("hearthpane-vmsg-camera").apply { start() }
  private val handler = Handler(thread.looper)
  private var previewTexture: SurfaceTexture? = null
  private var previewSurface: Surface? = null
  private var camera: CameraDevice? = null
  private var session: CameraCaptureSession? = null
  private var recorder: MediaRecorder? = null
  private var frames: CallMessageTestFrames? = null
  private var startedAt = 0L
  private var wantCamera = false
  @Volatile private var released = false
  /** The take on disk while composing (deleted by [deleteTake]). */
  val take: File = File(CallMessageStore.outDir(app), "take-${SystemClock.elapsedRealtime()}.mp4")

  fun attachPreview(texture: SurfaceTexture?) = post {
    previewTexture = texture
    previewSurface?.release()
    previewSurface = texture?.let { it.setDefaultBufferSize(WIDTH, HEIGHT); Surface(it) }
    if (texture == null) { closeCamera(); return@post }
    if (wantCamera) openCamera()
  }

  fun camera(on: Boolean) = post {
    wantCamera = on
    if (on) openCamera() else closeCamera()
  }

  fun start(cameraOn: Boolean) = post {
    take.delete()
    val r = newRecorder(cameraOn) ?: run { events.onRecorded(false, 0); return@post }
    recorder = r
    if (cameraOn) {
      wantCamera = true
      if (testFrames) { frames?.recorder = r.surface; begin(r) } else rebuildSession(r.surface) { begin(r) }
    } else { closeCamera(); begin(r) }
  }

  fun stop() = post { finishRecording(ok = true) }

  fun deleteTake() = post { take.delete() }

  /** Everything off now: recording, camera, preview, the take on disk, the thread. Safe to call twice. */
  fun release() {
    if (released) return
    released = true
    // Not gated by [released]: queued work is skipped, teardown always runs.
    handler.post {
      runCatching { recorder?.stop() }
      runCatching { recorder?.release() }
      recorder = null
      closeCamera()
      previewSurface?.release(); previewSurface = null
      take.delete()
    }
    thread.quitSafely()
  }

  private fun post(block: () -> Unit) {
    if (released) return
    handler.post { if (!released) runCatching(block).onFailure { Log.w(TAG, "recorder task failed", it) } }
  }

  private fun begin(r: MediaRecorder) {
    runCatching { r.start() }.onFailure {
      Log.w(TAG, "recorder start failed", it); r.release(); recorder = null; events.onRecorded(false, 0); return
    }
    startedAt = SystemClock.elapsedRealtime()
  }

  private fun finishRecording(ok: Boolean) {
    val r = recorder ?: return
    recorder = null
    val stopped = runCatching { r.stop() }.isSuccess
    r.release()
    frames?.recorder = null
    val d = SystemClock.elapsedRealtime() - startedAt
    events.onRecorded(ok && stopped && take.length() > 0, d.coerceAtMost(CallMessageLimits.MAX_DURATION_MS))
  }

  private fun newRecorder(video: Boolean): MediaRecorder? = runCatching {
    @Suppress("DEPRECATION") val r = MediaRecorder()
    val audio = !testFrames && app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    if (audio) r.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
    if (video) r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
    r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
    r.setOutputFile(take.absolutePath)
    if (video) {
      r.setVideoEncodingBitRate(VIDEO_BPS); r.setVideoFrameRate(FPS); r.setVideoSize(WIDTH, HEIGHT)
      r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
    }
    if (audio) {
      r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC); r.setAudioChannels(1); r.setAudioSamplingRate(AUDIO_HZ)
      r.setAudioEncodingBitRate(AUDIO_BPS)
    }
    if (!audio && !video) error("nothing to record")
    r.setMaxDuration(CallMessageLimits.MAX_DURATION_MS.toInt())
    r.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) post { finishRecording(true) } }
    r.setOnErrorListener { _, _, _ -> post { finishRecording(false) } }
    r.prepare()
    r
  }.onFailure { Log.w(TAG, "recorder setup failed", it) }.getOrNull()

  @SuppressLint("MissingPermission")
  private fun openCamera() {
    if (camera != null || frames != null) return
    val surface = previewSurface ?: return
    if (testFrames) { frames = CallMessageTestFrames(surface, handler).also { it.start() }; return }
    if (app.checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { events.onCameraProblem(); return }
    val cm = app.getSystemService(CameraManager::class.java) ?: return events.onCameraProblem()
    val id = runCatching { cm.cameraIdList.firstOrNull { it == CallMedia.CAMERA_ID } ?: cm.cameraIdList.firstOrNull() }.getOrNull()
        ?: return events.onCameraProblem()
    runCatching {
      cm.openCamera(id, object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
          if (released || !wantCamera) { device.close(); return }
          camera = device
          rebuildSession(null) {}
        }
        override fun onDisconnected(device: CameraDevice) { device.close(); camera = null; events.onCameraProblem() }
        override fun onError(device: CameraDevice, error: Int) { device.close(); camera = null; events.onCameraProblem() }
      }, handler)
    }.onFailure { Log.w(TAG, "camera open failed", it); events.onCameraProblem() }
  }

  /** Preview only (Ready) or preview + recorder (Recording); both always include the visible preview. */
  private fun rebuildSession(recorderSurface: Surface?, then: () -> Unit) {
    val device = camera ?: run { if (recorderSurface != null) events.onRecorded(false, 0); return }
    val preview = previewSurface ?: return
    session?.close(); session = null
    val targets = listOfNotNull(preview, recorderSurface)
    @Suppress("DEPRECATION")
    device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
      override fun onConfigured(s: CameraCaptureSession) {
        if (released) { s.close(); return }
        session = s
        val req = device.createCaptureRequest(if (recorderSurface != null) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
        targets.forEach(req::addTarget)
        req.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        runCatching { s.setRepeatingRequest(req.build(), null, handler) }.onFailure { events.onCameraProblem() }
        then()
      }
      override fun onConfigureFailed(s: CameraCaptureSession) { events.onCameraProblem(); if (recorderSurface != null) events.onRecorded(false, 0) }
    }, handler)
  }

  private fun closeCamera() {
    frames?.stop(); frames = null
    runCatching { session?.close() }; session = null
    runCatching { camera?.close() }; camera = null
  }

  companion object {
    private const val TAG = "HearthpaneCallMsg"
    const val WIDTH = 1280
    const val HEIGHT = 720
    private const val FPS = 30
    private const val VIDEO_BPS = 1_600_000
    private const val AUDIO_HZ = 44_100
    private const val AUDIO_BPS = 64_000
  }
}
