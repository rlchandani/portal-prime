package com.immortal.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.dreams.DreamService
import android.util.Log
import android.view.MotionEvent
import androidx.core.content.ContextCompat

class DigitalClockDreamService : DreamService() {
  private val handler = Handler(Looper.getMainLooper())
  private var settings = DigitalClockConfig.Settings()
  private var controller: DigitalClockView.Controller? = null
  private var clockRunning = false
  private var receiverRegistered = false
  private var lastDriftUptimeMs: Long? = null
  private val timeChanged = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (clockRunning) refreshClock()
    }
  }

  private var downX = 0f
  private var downY = 0f

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    Log.i(TAG, "attached: digital clock dream starting")
    isInteractive = true
    isFullscreen = true
    isScreenBright = true
    settings = DigitalClockConfig.load(this)
    controller = DigitalClockView.build(this, settings)
    val root = controller!!.root
    root.setOnTouchListener { _, ev ->
      onTouch(ev)
      true
    }
    setContentView(root)
    startClock()
  }

  override fun onDreamingStarted() {
    super.onDreamingStarted()
    Log.i(TAG, "onDreamingStarted")
    SleepScheduler.onScreensaverStarted(this)
    startClock()
  }

  override fun onDreamingStopped() {
    stopClock()
    super.onDreamingStopped()
  }

  override fun onDetachedFromWindow() {
    Log.i(TAG, "detached: digital clock dream ending")
    stopClock()
    controller = null
    super.onDetachedFromWindow()
  }

  private fun startClock() {
    if (controller == null) return
    clockRunning = true
    if (!receiverRegistered) {
      ContextCompat.registerReceiver(this, timeChanged, IntentFilter().apply {
        addAction(Intent.ACTION_TIME_CHANGED)
        addAction(Intent.ACTION_TIMEZONE_CHANGED)
      }, ContextCompat.RECEIVER_NOT_EXPORTED)
      receiverRegistered = true
    }
    handler.removeCallbacks(drift)
    lastDriftUptimeMs = null
    refreshClock()
    if (!settings.showSeconds) drift.run()
  }

  private fun stopClock() {
    clockRunning = false
    handler.removeCallbacks(tick)
    handler.removeCallbacks(drift)
    if (receiverRegistered) {
      unregisterReceiver(timeChanged)
      receiverRegistered = false
    }
  }

  private fun refreshClock() {
    handler.removeCallbacks(tick)
    tick.run()
  }

  private fun onTouch(ev: MotionEvent) {
    when (ev.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        downX = ev.x
        downY = ev.y
      }
      MotionEvent.ACTION_UP -> {
        val dx = ev.x - downX
        val dy = ev.y - downY
        if (kotlin.math.abs(dx) < 48 && kotlin.math.abs(dy) < 48) {
          DreamPolicy.userExitAt = System.currentTimeMillis()
          finish()
        }
      }
    }
  }

  private val tick = object : Runnable {
    override fun run() {
      if (!clockRunning) return
      val nowUptimeMs = SystemClock.uptimeMillis()
      // Anchor before rendering so work crossing a minute boundary cannot skip it.
      val nextTickAt = nowUptimeMs +
          ClockCadence.nextClockDelayMs(System.currentTimeMillis(), settings.showSeconds)
      controller?.let { DigitalClockView.update(it, settings) }
      if (settings.showSeconds && ClockCadence.shouldApplyDrift(nowUptimeMs, lastDriftUptimeMs)) {
        applyDrift(nowUptimeMs)
      }
      handler.postAtTime(this, nextTickAt)
    }
  }

  private fun applyDrift(nowUptimeMs: Long) {
    controller?.let {
      // At 14px radius, a 3s step moves at most ~0.85px horizontally / ~0.63px
      // vertically along the same anti-burn-in path, without reformatting the clock.
      val shift = AntiBurnIn.shift(System.currentTimeMillis(), 14f)
      it.root.translationX = shift.x
      it.root.translationY = shift.y
      lastDriftUptimeMs = nowUptimeMs
    }
  }

  private val drift = object : Runnable {
    override fun run() {
      if (!clockRunning) return
      // No separate callback chain when the seconds tick can carry the drift.
      val delay = ClockCadence.nextDriftDelayMs(System.currentTimeMillis(), settings.showSeconds)
          ?: return
      applyDrift(SystemClock.uptimeMillis())
      handler.postDelayed(this, delay)
    }
  }

  private companion object {
    const val TAG = "ImmortalDigitalClock"
  }
}
