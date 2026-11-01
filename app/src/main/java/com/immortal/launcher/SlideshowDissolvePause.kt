/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Pause admission for a finite dissolve, separate from continuous photo motion. */
internal class SlideshowDissolvePause(private val onRunningChanged: (Boolean) -> Unit) {
  var paused = false
    private set
  var hostActive = true
    private set
  private var active = false
  private var manualWhilePaused = false

  fun begin(automatic: Boolean) {
    active = true
    manualWhilePaused = paused && !automatic
    sync()
  }

  fun end() { active = false; manualWhilePaused = false }

  fun setPaused(value: Boolean) {
    // Reaffirming Pause after a manual show preserves its finite transition. A new
    // explicit Pause, including one after Resume, freezes the blend already in flight.
    if (value && !paused) manualWhilePaused = false
    paused = value
    sync()
  }

  fun setHostActive(value: Boolean) {
    hostActive = value
    sync()
  }

  private fun sync() {
    if (active) onRunningChanged(hostActive && (!paused || manualWhilePaused))
  }
}
