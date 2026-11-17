/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource

/**
 * The drift time of the stage's wash ([MusicStageBackdrop]). While any list on the stage scrolls,
 * and for [quietMs] after its last delta, the drift holds still: a drift step repaints the whole
 * window (the wash is full-screen and shows through the sheet), so during a fling it would turn
 * every third frame into a full-screen frame. The held time is skipped, so the drift resumes from
 * exactly where it stopped (no jump); at 34–46 s per sweep a held second is invisible.
 *
 * Times are in the [System.nanoTime] base, the same base as Choreographer frame times. Main thread
 * only (nested scroll callbacks and the frame loop both run there); plain fields, so reporting a
 * scroll never invalidates anything.
 */
@Stable
internal class MusicWashClock(
    private val quietMs: Long = QUIET_MS,
    private val nowMs: () -> Long = { System.nanoTime() / NANOS_PER_MS },
) {
  private var startMs = UNSET
  private var heldMs = 0L
  private var heldFromMs = UNSET
  private var lastScrollMs = UNSET

  /** Attach to the stage root: every scroll delta of a descendant list (drag or fling) lands here. */
  val connection: NestedScrollConnection = object : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
      onScroll(nowMs())
      return Offset.Zero
    }
  }

  fun onScroll(atMs: Long) {
    lastScrollMs = atMs
  }

  /** Drift seconds at frame time [frameMs], or null while held (keep drawing the last value). */
  fun secondsAt(frameMs: Long): Float? {
    if (startMs == UNSET) startMs = frameMs
    val held = lastScrollMs != UNSET && frameMs - lastScrollMs < quietMs
    if (held) {
      if (heldFromMs == UNSET) heldFromMs = frameMs
      return null
    }
    if (heldFromMs != UNSET) {
      heldMs += frameMs - heldFromMs
      heldFromMs = UNSET
    }
    return (frameMs - startMs - heldMs) / MS_PER_SECOND
  }

  companion object {
    /** A fling reports a delta every frame; this much silence means the list has settled. */
    const val QUIET_MS = 250L
    private const val UNSET = Long.MIN_VALUE
    private const val NANOS_PER_MS = 1_000_000L
    private const val MS_PER_SECOND = 1_000f
  }
}
