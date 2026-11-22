/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Hands a prepared deck's pixels to the GPU one step at a time before the deck is admitted.
 *
 * Admitting a Filmstrip deck builds every card's offscreen layer in one frame. Without this,
 * that frame also uploads every card photo (a household trace: one 310 ms frame, 147 ms
 * `prepareTree` + 96 ms `flush layers`, while the previous screen was still showing). Each step
 * here asks RenderThread to upload one photo and its backdrop ([prepare], `Bitmap.prepareToDraw`),
 * spaced [stepMillis] apart, so the admission frame finds the textures already uploaded.
 * Pixels and drawing are unchanged; only when the upload happens moves.
 *
 * [post] owns each step: a step it rejects or later discards (the queue closed) abandons the deck
 * exactly once through [onAbandon]. A deck that stops being [isCurrent] is abandoned at its next
 * step. [onReady] runs once, on the queue's thread, after the last step.
 */
internal class FilmstripTextureWarmup<T>(
    private val steps: List<List<T>>,
    private val prepare: (T) -> Unit,
    private val isCurrent: () -> Boolean,
    private val post: (Runnable, Long, () -> Unit) -> Boolean,
    private val onReady: () -> Unit,
    private val onAbandon: () -> Unit,
    private val stepMillis: Long = STEP_MILLIS,
) {
  private var next = 0
  private var finished = false

  fun start() = schedule(0L)

  private fun schedule(delay: Long) {
    val accepted = post(Runnable { step() }, delay) { abandon() }
    if (!accepted) abandon()
  }

  private fun step() {
    if (finished) return
    if (!isCurrent()) { abandon(); return }
    if (next >= steps.size) { finished = true; onReady(); return }
    try {
      steps[next++].forEach(prepare)
    } catch (_: RuntimeException) {
      // An upload hint that fails costs nothing visible: the admission frame uploads instead.
    }
    schedule(if (next >= steps.size) 0L else stepMillis)
  }

  private fun abandon() {
    if (finished) return
    finished = true
    onAbandon()
  }

  companion object {
    /** About two frames at 60 Hz: one upload lands in RenderThread's idle gap per step. */
    const val STEP_MILLIS = 34L
  }
}
