/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
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
  private var clockRunning = false
  private val timeChanged = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (clockRunning) refreshClock()
    }
  }

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
  }

  override fun onResume() {
    super.onResume()
    clockRunning = true
    ContextCompat.registerReceiver(this, timeChanged, IntentFilter().apply {
      addAction(Intent.ACTION_TIME_CHANGED)
      addAction(Intent.ACTION_TIMEZONE_CHANGED)
    }, ContextCompat.RECEIVER_NOT_EXPORTED)
    refreshClock()
  }

  override fun onPause() {
    clockRunning = false
    handler.removeCallbacks(tick)
    unregisterReceiver(timeChanged)
    super.onPause()
  }

  private fun refreshClock() {
    handler.removeCallbacks(tick)
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
      if (!clockRunning) return
      // Anchor before rendering so work crossing a minute boundary cannot skip it.
      val nextTickAt = SystemClock.uptimeMillis() +
          ClockCadence.nextClockDelayMs(System.currentTimeMillis(), settings.showSeconds)
      controller?.let { DigitalClockView.update(it, settings) }
      handler.postAtTime(this, nextTickAt)
    }
  }
}
