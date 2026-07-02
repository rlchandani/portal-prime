/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Full-screen preview of the digital clock with the current settings. Opened
 * from the "Preview" button in Immortal Settings, so the user can see what the
 * clock looks like before waiting for the screensaver to activate.
 *
 * Tap anywhere to exit.
 */
class DigitalClockPreviewActivity : ComponentActivity() {
  private val handler = Handler(Looper.getMainLooper())
  private var controller: DigitalClockView.Controller? = null
  private var settings = DigitalClockConfig.Settings()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Immersive fullscreen
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).apply {
      hide(WindowInsetsCompat.Type.systemBars())
      systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
    // Keep screen on while previewing
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    settings = DigitalClockConfig.load(this)
    controller = DigitalClockView.build(this, settings)
    setContentView(controller!!.root)
    tick.run()
  }

  override fun onTouchEvent(event: MotionEvent?): Boolean {
    if (event?.action == MotionEvent.ACTION_UP) {
      finish()
      return true
    }
    return super.onTouchEvent(event)
  }

  override fun onDestroy() {
    handler.removeCallbacksAndMessages(null)
    super.onDestroy()
  }

  private val tick = object : Runnable {
    override fun run() {
      controller?.let { DigitalClockView.update(it, settings) }
      handler.postDelayed(this, if (settings.showSeconds) 500L else 1_000L)
    }
  }
}
