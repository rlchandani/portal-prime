/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.immortal.launcher.jarvis.wake.HearthpaneWake
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The listening dot on [PhotoFramePreviewActivity]: a [WakeListeningDotView] over the frame, following
 * [HearthpaneWake.listening] while the frame is started, dimmed under the night clock, drifting for burn-in. It sits
 * in the Activity's content frame, outside [PhotoFrameController]'s views, so the frame itself is unchanged.
 */
internal class FrameListeningDot(private val activity: ComponentActivity, night: Boolean) {
  private val view = WakeListeningDotView(activity)
  private var listening = false
  private var night = night

  init {
    activity.addContentView(view, WakeListeningDotView.layoutParams(activity))
    activity.lifecycleScope.launch {
      activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
        launch { HearthpaneWake.listening.collect { listening = it; render() } }
        while (true) {
          view.drift(System.currentTimeMillis(), ScreensaverConfig.load(activity).antiBurnIn)
          delay(WakeListeningDotStyle.DRIFT_TICK_MS)
        }
      }
    }
  }

  /** The frame entered or left its night clock ([PhotoFrameController.onNightClockChanged]). */
  fun setNight(on: Boolean) {
    night = on
    render()
  }

  private fun render() = view.show(listening, night)
}
