/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.json.JSONObject

/** Actual Window-frame delivery and bounded collector lifecycle, using only an owned overlay. */
internal object FramePerformanceDeviceChecks {
  fun run(instrumentation: Instrumentation): Bundle {
    val assertions=JSONObject()
    val results=JSONObject()
    var activity: Activity?=null
    var overlay: View?=null
    var animation: ObjectAnimator?=null
    fun demand(label: String, valid: Boolean) {
      assertions.put(label,valid)
      check(valid) { label }
    }
    fun total(j: JSONObject): Long {
      val hosts=j.getJSONObject("hosts")
      return hosts.keys().asSequence().sumOf { hosts.getJSONObject(it).getLong("frames") }
    }
    fun capture(): JSONObject = FramePerformanceCapture.snapshot()
    fun startCapture(): Boolean = FramePerformanceCapture.start(checkNotNull(
        FramePerformanceRequest.parse(JSONObject().put("action","start").put("durationSec",10).put("phase","home"))))
    fun waitFor(label: String, ms: Long=4000, condition: () -> Boolean) {
      val deadline=SystemClock.elapsedRealtime()+ms
      while (SystemClock.elapsedRealtime()<deadline) {
        if(condition()) { demand(label,true);return }
        SystemClock.sleep(50)
      }
      demand(label,false)
    }
    try {
      demand("initial_stop",FramePerformanceCapture.stop())
      activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,HomeActivity::class.java)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      val host=checkNotNull(activity)
      instrumentation.runOnMainSync {
        // Replace Home's content only for this owned, finished test Activity. A static test
        // surface avoids background widget/clock renders and keeps the Window attached.
        val surface=FrameLayout(host)
        host.setContentView(surface)
        overlay=View(host).apply { setBackgroundColor(Color.WHITE) }
        surface.addView(overlay,FrameLayout.LayoutParams(40,40))
        animation=ObjectAnimator.ofFloat(overlay,"alpha",1f,.25f,1f).apply {
          duration=600;repeatCount=ValueAnimator.INFINITE;this.start()
        }
      }
      waitFor("owned_surface_attached_and_accelerated") {
        var ready=false
        instrumentation.runOnMainSync {
          ready=overlay?.isAttachedToWindow==true && overlay?.isHardwareAccelerated==true
        }
        ready
      }
      demand("capture_start",startCapture())
      waitFor("real_window_frames") { total(capture())>=5 }
      val first=capture()
      results.put("first",first)
      demand("listener_attached",first.getInt("activeListeners")>=1)
      demand("not_compositor_fps",!first.getBoolean("deliveredFpsMeasured"))
      instrumentation.runOnMainSync {
        animation?.pause()
      }
      instrumentation.waitForIdleSync()
      SystemClock.sleep(200) // Drain the final animation frame before resetting counters.
      demand("replacement_start",startCapture())
      val second=capture()
      demand("fresh_generation",second.getLong("sessionId")>first.getLong("sessionId"))
      demand("replacement_counters_reset",total(second)==0L)
      instrumentation.runOnMainSync {
        animation?.resume()
      }
      waitFor("replacement_receives_frames") { total(capture())>=5 }
      demand("explicit_stop",FramePerformanceCapture.stop())
      waitFor("explicit_stop_releases_listener_and_thread") {
        val j=capture();!j.getBoolean("active")&&j.getInt("activeListeners")==0&&!j.getBoolean("collectorRunning")
      }
      val held=total(capture())
      SystemClock.sleep(300)
      demand("no_frames_after_stop",held==total(capture()))
      demand("expiry_capture_start",startCapture())
      waitFor("expiry_has_live_frames") { total(capture())>=5 }
      instrumentation.runOnMainSync {
        animation?.cancel(); animation=null
      }
      // No snapshot reads or animated surface to trigger cleanup: the timer must release itself.
      SystemClock.sleep(11000)
      demand("deadline_releases_thread_before_first_read",Thread.getAllStackTraces().keys.none {
        it.isAlive && it.name == "immortal-perf"
      })
      val expired=capture()
      demand("deadline_releases_listener_without_external_stop",!expired.getBoolean("active")&&
          expired.getInt("activeListeners")==0&&!expired.getBoolean("collectorRunning"))
      results.put("expired",expired)
      demand("expiry_retains_actual_frames",total(expired)>=5)
      demand("expiry_caps_elapsed",expired.getLong("elapsedMs")==10000L)
      val expiredCount=total(expired)
      SystemClock.sleep(300)
      demand("no_frames_after_expiry",total(capture())==expiredCount)
      demand("destroy_capture_start",startCapture())
      instrumentation.runOnMainSync {
        animation?.cancel();animation=null
        (overlay?.parent as? ViewGroup)?.removeView(overlay);overlay=null
        host.finish()
      }
      waitFor("destroyed_window_listener_removed") { capture().getInt("activeListeners")==0 }
      demand("final_stop",FramePerformanceCapture.stop())
      demand("completed",true)
    } catch (failure: Throwable) {
      results.put("errorType",failure.javaClass.simpleName)
      results.put("lastCapture",capture())
      instrumentation.runOnMainSync {
        results.put("surfaceAttached",overlay?.isAttachedToWindow==true)
            .put("surfaceAccelerated",overlay?.isHardwareAccelerated==true)
            .put("surfaceShown",overlay?.isShown==true)
            .put("animatorRunning",animation?.isRunning==true)
      }
      assertions.put("completed",false)
    } finally {
      FramePerformanceCapture.stop()
      instrumentation.runOnMainSync {
        animation?.cancel()
        (overlay?.parent as? ViewGroup)?.removeView(overlay)
        activity?.finish()
      }
    }
    return Bundle().apply {
      putBoolean("framePerformanceCompleted",assertions.optBoolean("completed"))
      putString("framePerformanceAssertions",assertions.toString())
      putString("framePerformanceResults",results.toString())
    }
  }
}
