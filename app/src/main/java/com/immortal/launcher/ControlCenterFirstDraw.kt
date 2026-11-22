/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos

/**
 * The Control Center's first frame, built over a few frames nobody sees. On Family Portal+ (1.326)
 * an open was one 90 ms frame on the main thread (attach, the whole shell and Map composed and
 * measured, then all of it recorded) and Home's own frame queued behind it. Here the window's
 * first draw is held (a cancelled pre-draw: no frame is drawn or shown, Home stays on screen) while
 * the shell composes on the first frame and the screen body ([bodyComposed]) on the next; the
 * first frame that is drawn then only records what is already laid out. The first drawn frame is
 * exactly what an unheld open drew, so nothing on screen changes; the window just appears a couple
 * of vsyncs after its work instead of in one long frame.
 *
 * Never holds for more than [maxHeldFrames] cancelled draws, so a stalled composition still shows.
 * Main thread only.
 */
internal class CcFirstDraw(private val maxHeldFrames: Int = MAX_HELD_FRAMES) {
  /** False on the first frame: only the rail and header compose; the body joins on the next frame. */
  var bodyComposed by mutableStateOf(false)
    private set
  private var released = false
  private var held = 0

  /** The pre-draw decision: hold (false) until [release] or until [maxHeldFrames] draws were held. */
  fun allowDraw(): Boolean {
    if (released) return true
    held++
    if (held > maxHeldFrames) release()
    return released
  }

  /** Lets the next frame draw. */
  fun release() { released = true; bodyComposed = true }

  /**
   * Run from the composition: the body composes one frame after the shell, and the draw is
   * released one frame after that, so the drawn frame composes and measures nothing new.
   */
  suspend fun stage() {
    withFrameNanos { }
    bodyComposed = true
    withFrameNanos { }
    release()
  }

  /** Holds [root]'s window draws through [allowDraw]; the listener removes itself once drawing is allowed. */
  fun hold(root: View) {
    val observer = root.viewTreeObserver
    observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
      override fun onPreDraw(): Boolean {
        val draw = allowDraw()
        if (draw) root.viewTreeObserver.removeOnPreDrawListener(this)
        return draw
      }
    })
  }

  companion object {
    /** About 130 ms at 60 Hz: far more than the two staged frames, short enough to never feel stuck. */
    const val MAX_HELD_FRAMES = 8
  }
}
