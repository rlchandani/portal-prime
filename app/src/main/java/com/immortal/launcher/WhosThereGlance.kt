/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.os.SystemClock
import android.view.Surface
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Timings for the perf table, all in ms. */
internal data class GlanceTimings(
    val openMs: Long = -1,
    val firstFrameMs: Long = -1,
    val previewMs: Long = -1,
    val framesSeen: Int = 0,
    val framesAnalysed: Int = 0,
    val detectAvgMs: Double = 0.0,
    val embedAvgMs: Double = 0.0,
    val closeMs: Long = -1,
    val totalMs: Long = -1,
) {
  fun log(): String = "open=${openMs}ms first=${firstFrameMs}ms preview=${previewMs}ms frames=$framesSeen " +
      "analysed=$framesAnalysed detect=${"%.1f".format(detectAvgMs)}ms embed=${"%.1f".format(embedAvgMs)}ms " +
      "close=${closeMs}ms total=${totalMs}ms"
}

internal data class GlanceResult(
    val outcome: GlanceOutcome,
    val meanLuma: Float,
    val failed: String?,
    val timings: GlanceTimings,
    /** Highest cosine seen against any template (a number only, for daytime threshold tuning). */
    val bestScore: Float = -1f,
    /** The rotation that found a face (Mini upright check), null if none was found. */
    val rotation: Int? = null,
) {
  /** No light at all (privacy shutter / covered lens: the virtual camera sends black frames). */
  val covered: Boolean get() = failed == null && timings.framesSeen > 0 && meanLuma < COVERED_LUMA
  val dark: Boolean get() = !covered && failed == null && timings.framesSeen > 0 && meanLuma < DARK_LUMA

  companion object {
    const val COVERED_LUMA = 12f
    const val DARK_LUMA = 40f
  }
}

/**
 * One visible glance: camera 0 open with the preview on screen for [PREVIEW_MS] of frames, the
 * frames analysed in memory on [analysis] (the thread that owns [models]), then the camera is
 * closed at once. Frames that arrive while a frame is being analysed are skipped, not queued.
 * Each frame's embeddings become matches straight away and the embeddings are dropped, so
 * unknown faces are never kept even in memory beyond their frame.
 */
internal class WhosThereGlance(
    private val context: Context,
    private val preview: Surface,
    private val models: FaceModels,
    private val templates: List<FaceTemplates>,
    private val analysis: ExecutorService,
    private val rotations: List<Int>,
    private val thresholds: MatchThresholds = MatchThresholds(),
    /** A confident single-person verdict before the look ends, so the greeting needn't wait. */
    private val onEarly: (GlanceOutcome) -> Unit = {},
    private val onDone: (GlanceResult) -> Unit,
) : WhosThereCamera.Listener {
  private val started = SystemClock.elapsedRealtime()
  private val busy = AtomicBoolean(false)
  private val finished = AtomicBoolean(false)
  private val seen = AtomicInteger(0)
  private val camera = WhosThereCamera(context, preview, this)
  @Volatile private var openMs = -1L
  @Volatile private var firstFrameAt = 0L
  @Volatile private var lockedRotation: Int? = null
  // Touched only on the analysis thread.
  private val matches = ArrayList<List<FaceMatch>>()
  private var lumaSum = 0f
  private var lumaFrames = 0
  private var detectNs = 0L
  private var embedNs = 0L
  private var embedded = 0
  private var analysed = 0
  private var earlySent = false
  private var bestScore = -1f

  fun start() {
    camera.open()
    analysis.execute { watchdog() }
  }

  /** Abort (Home left, call started, screen off). Delivers a result with what was seen so far. */
  fun cancel(reason: String) {
    camera.close() // release the camera now; the verdict is assembled on the analysis thread
    analysis.execute { finish(reason) }
  }

  override fun onOpened(openMs: Long) {
    this.openMs = openMs
  }

  override fun wantFrame(): Boolean {
    val n = seen.incrementAndGet()
    if (firstFrameAt == 0L) firstFrameAt = SystemClock.elapsedRealtime()
    if (SystemClock.elapsedRealtime() - firstFrameAt >= PREVIEW_MS) {
      analysis.execute { finish(null) }
      return false
    }
    // Skip the first frames while auto-exposure settles (luma 81 → 110 by frame ~6 in the spike).
    return n > SETTLE_FRAMES && !finished.get() && busy.compareAndSet(false, true)
  }

  override fun onFrame(frame: YuvFrame) {
    analysis.execute {
      try {
        if (!finished.get()) analyse(frame)
      } finally {
        busy.set(false)
      }
    }
  }

  override fun onFailed(reason: String) {
    analysis.execute { finish(reason) }
  }

  private fun analyse(frame: YuvFrame) {
    analysed++
    val luma = frame.meanLuma()
    lumaSum += luma
    lumaFrames++
    if (luma < GlanceResult.DARK_LUMA) return // too dark to guess; better none than wrong
    val t0 = SystemClock.elapsedRealtimeNanos()
    val faces = detectUpright(frame)
    detectNs += SystemClock.elapsedRealtimeNanos() - t0
    val frameMatches = faces.take(MAX_FACES).map { face ->
      val t1 = SystemClock.elapsedRealtimeNanos()
      val sample = models.embed(frame, face)
      embedNs += SystemClock.elapsedRealtimeNanos() - t1
      embedded++
      sample.embedding?.let { e ->
        WhosThereMatcher.scores(e, templates).values.maxOrNull()?.let { if (it > bestScore) bestScore = it }
        WhosThereMatcher.match(e, templates, thresholds)
      } ?: FaceMatch.Unknown
    }
    matches += frameMatches
    if (!earlySent) WhosThereMatcher.early(matches)?.let { earlySent = true; onEarly(it) }
  }

  /** Tries the expected rotation first; once a face is found that rotation is kept. */
  private fun detectUpright(frame: YuvFrame): List<FrameFace> {
    lockedRotation?.let { return models.detect(frame, it) }
    for (rotation in rotations) {
      val faces = models.detect(frame, rotation)
      if (faces.isNotEmpty()) {
        lockedRotation = rotation
        return faces
      }
    }
    return emptyList()
  }

  private fun watchdog() {
    // Runs on the analysis thread between frames; re-posts itself until the glance ends.
    if (finished.get()) return
    val elapsed = SystemClock.elapsedRealtime() - started
    val noFrames = firstFrameAt == 0L && elapsed > FIRST_FRAME_TIMEOUT_MS
    if (noFrames || elapsed > HARD_TIMEOUT_MS) {
      finish(if (noFrames) "no frames" else null)
      return
    }
    WATCHDOG.postDelayed({ if (!finished.get()) analysis.execute { watchdog() } }, WATCHDOG_MS)
  }

  private fun finish(failed: String?) {
    if (!finished.compareAndSet(false, true)) return
    val closeMs = camera.close()
    val outcome = WhosThereMatcher.vote(matches.toList())
    matches.clear()
    val now = SystemClock.elapsedRealtime()
    val first = if (firstFrameAt == 0L) -1L else firstFrameAt - started
    val timings = GlanceTimings(openMs, first, if (firstFrameAt == 0L) -1 else now - firstFrameAt, seen.get(), analysed,
        if (analysed == 0) 0.0 else detectNs / analysed / 1e6, if (embedded == 0) 0.0 else embedNs / embedded / 1e6,
        closeMs, now - started)
    onDone(GlanceResult(outcome, if (lumaFrames == 0) 0f else lumaSum / lumaFrames, failed, timings, bestScore, lockedRotation))
  }

  companion object {
    const val PREVIEW_MS = 1_500L
    const val SETTLE_FRAMES = 5
    const val MAX_FACES = 3
    const val FIRST_FRAME_TIMEOUT_MS = 3_000L
    const val HARD_TIMEOUT_MS = 4_000L
    private const val WATCHDOG_MS = 250L
    private val WATCHDOG = android.os.Handler(android.os.Looper.getMainLooper())
  }
}
