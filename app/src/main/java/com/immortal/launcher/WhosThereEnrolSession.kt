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
import android.os.SystemClock
import android.view.Surface
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The guided capture: camera 0 with the full live preview on screen (needed past ~7 s), frames
 * analysed one at a time on the models' thread and folded into [WhosThereEnrolPolicy]. Only the
 * resulting embeddings leave this class; frames and aligned crops never do. Closes the camera
 * the moment the capture is done, cancelled or times out.
 */
internal class WhosThereEnrolSession(
    context: Context,
    preview: Surface,
    private val models: FaceModels,
    private val analysis: ExecutorService,
    private val rotations: List<Int>,
    private val onState: (EnrolState) -> Unit,
    private val onFinished: (List<Embedding>?, String?) -> Unit,
) : WhosThereCamera.Listener {
  private val main = Handler(Looper.getMainLooper())
  private val busy = AtomicBoolean(false)
  private val finished = AtomicBoolean(false)
  private val camera = WhosThereCamera(context, preview, this)
  private val startedAt = SystemClock.elapsedRealtime()
  private var state = WhosThereEnrolPolicy.start(SystemClock.elapsedRealtime())
  @Volatile private var rotation: Int? = null
  private var frames = 0

  fun start() {
    camera.open()
    main.postDelayed(::checkTimeout, TIMEOUT_MS)
  }

  fun cancel() = finish(null, null)

  override fun onOpened(openMs: Long) = Unit
  override fun wantFrame(): Boolean = !finished.get() && busy.compareAndSet(false, true)
  override fun onFailed(reason: String) = finish(null, "The camera isn’t available right now. Try again in a moment.")

  override fun onFrame(frame: YuvFrame) {
    analysis.execute {
      try {
        if (!finished.get()) analyse(frame)
      } finally {
        busy.set(false)
      }
    }
  }

  private fun analyse(frame: YuvFrame) {
    if (++frames <= WhosThereGlance.SETTLE_FRAMES) return
    val luma = frame.meanLuma()
    val faces = detect(frame)
    val sample = faces.singleOrNull()?.let { models.embed(frame, it) }
    val (yaw, pitch) = faces.firstOrNull()?.pose ?: (0f to 0f)
    val f = EnrolFrame(faces.size, faces.firstOrNull()?.box?.score ?: 0f, faces.firstOrNull()?.widthFraction ?: 0f, luma,
        sample?.sharpness ?: 0f, yaw, pitch, sample?.embedding, SystemClock.elapsedRealtime())
    state = WhosThereEnrolPolicy.onFrame(state, f)
    val snapshot = state
    main.post { if (!finished.get()) onState(snapshot) }
    if (snapshot.done) finish(snapshot.collected, null)
  }

  private fun detect(frame: YuvFrame): List<FrameFace> {
    rotation?.let { return models.detect(frame, it) }
    for (r in rotations) {
      val faces = models.detect(frame, r)
      if (faces.isNotEmpty()) { rotation = r; return faces }
    }
    return emptyList()
  }

  private fun checkTimeout() {
    if (finished.get()) return
    if (SystemClock.elapsedRealtime() - startedAt >= TIMEOUT_MS) {
      finish(null, "That took too long. Check the light and try again.")
    }
  }

  private fun finish(result: List<Embedding>?, error: String?) {
    if (!finished.compareAndSet(false, true)) return
    camera.close()
    main.removeCallbacksAndMessages(null)
    main.post { onFinished(result, error) }
  }

  companion object {
    const val TIMEOUT_MS = 60_000L
  }
}
