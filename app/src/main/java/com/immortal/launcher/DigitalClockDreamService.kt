package com.immortal.launcher

import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.util.Log
import android.view.MotionEvent

class DigitalClockDreamService : DreamService() {
  private val handler = Handler(Looper.getMainLooper())
  private var settings = DigitalClockConfig.Settings()
  private var controller: DigitalClockView.Controller? = null

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
    tick.run()
  }

  override fun onDreamingStarted() {
    super.onDreamingStarted()
    Log.i(TAG, "onDreamingStarted")
    SleepScheduler.onScreensaverStarted(this)
  }

  override fun onDetachedFromWindow() {
    Log.i(TAG, "detached: digital clock dream ending")
    handler.removeCallbacksAndMessages(null)
    super.onDetachedFromWindow()
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
      controller?.let {
        DigitalClockView.update(it, settings)
        // Anti-burn-in: drift the whole clock along a slow, invisible path so the lit
        // pixels of an always-on screen don't ghost in over time.
        val now = System.currentTimeMillis()
        val shift = AntiBurnIn.shift(now, 14f)
        it.root.translationX = shift.x
        it.root.translationY = shift.y
      }
      handler.postDelayed(this, if (settings.showSeconds) 500L else 1_000L)
    }
  }

  private companion object {
    const val TAG = "ImmortalDigitalClock"
  }
}
