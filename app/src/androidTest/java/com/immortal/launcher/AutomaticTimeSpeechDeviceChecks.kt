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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/** Public local settings and counter callbacks only; never construct TTS or read preferences. */
internal object AutomaticTimeSpeechDeviceChecks {
  fun run(): Bundle {
    val checks = JSONArray()
    var failureCount = 0
    fun expect(name: String, assertion: () -> Boolean) {
      // Exceptions expose only the fixed assertion name and a boolean.
      val passed = try { assertion() } catch (_: Throwable) { false }
      checks.put(JSONObject().put("name", name).put("passed", passed))
      if (!passed) failureCount++
    }

    expect("initially_disabled_has_no_playback_settings") {
      val f = Fixture(publicSettings().copy(spokenTimeOn = false))
      f.speech.playbackSettings() == null && f.loads == 1
    }
    expect("initially_zero_volume_has_no_playback_settings") {
      val f = Fixture(publicSettings().copy(volume = 0))
      f.speech.playbackSettings() == null && f.loads == 1
    }
    expect("initially_inside_overnight_quiet_window_is_blocked") {
      val f = Fixture(minute = 23 * 60)
      f.speech.playbackSettings() == null && f.loads == 1
    }
    expect("initially_enabled_returns_original_current_snapshot") {
      val f = Fixture()
      f.speech.playbackSettings() === f.current && f.loads == 1
    }
    expect("spoken_time_turned_off_after_initialization_cancels") {
      val f = Fixture()
      val initial = f.speech.playbackSettings()
      f.current = f.current.copy(spokenTimeOn = false)
      f.ready()
      initial != null && f.cancelledOnly() && f.loads == 2
    }
    expect("clock_entering_quiet_window_after_initialization_cancels") {
      val f = Fixture(minute = 22 * 60 - 1)
      val initial = f.speech.playbackSettings()
      f.minute = 22 * 60
      f.ready()
      initial != null && f.cancelledOnly() && f.loads == 2
    }
    expect("changed_quiet_settings_after_initialization_cancel") {
      val f = Fixture()
      val initial = f.speech.playbackSettings()
      f.current = f.current.copy(quietStartMin = 11 * 60, quietEndMin = 13 * 60)
      f.ready()
      initial != null && f.cancelledOnly() && f.loads == 2
    }
    expect("volume_zero_after_initialization_cancels") {
      val f = Fixture()
      val initial = f.speech.playbackSettings()
      f.current = f.current.copy(volume = 0)
      f.ready()
      initial != null && f.cancelledOnly() && f.loads == 2
    }
    expect("lower_current_volume_is_delivered_after_initialization") {
      val f = Fixture()
      val initial = f.speech.playbackSettings()
      f.current = f.current.copy(volume = 15)
      f.ready()
      initial?.volume == 70 && f.deliveredOnly() && f.delivered === f.current &&
          f.delivered?.volume == 15 && f.loads == 2
    }
    expect("current_voice_is_delivered_after_initialization") {
      val f = Fixture()
      val initial = f.speech.playbackSettings()
      f.current = f.current.copy(spokenVoice = "public_voice_b")
      f.ready()
      initial?.spokenVoice == "public_voice_a" && f.deliveredOnly() &&
          f.delivered === f.current && f.delivered?.spokenVoice == "public_voice_b" &&
          f.loads == 2
    }
    expect("each_playback_settings_call_rereads_complete_current_snapshot") {
      val f = Fixture()
      val first = f.speech.playbackSettings()
      f.current = f.current.copy(hourlyChimeOn = true, goldenHourOn = true,
          volume = 35, spokenVoice = "public_voice_b", sunriseVariant = 1)
      val second = f.speech.playbackSettings()
      second != null && first !== second && second === f.current && second.hourlyChimeOn &&
          second.goldenHourOn && second.volume == 35 &&
          second.spokenVoice == "public_voice_b" && second.sunriseVariant == 1 && f.loads == 2
    }
    expect("disabling_quiet_hours_allows_current_snapshot") {
      val f = Fixture(minute = 23 * 60)
      val initiallyBlocked = f.speech.playbackSettings() == null
      f.current = f.current.copy(quietHoursOn = false)
      f.ready()
      initiallyBlocked && f.deliveredOnly() && f.delivered === f.current && f.loads == 2
    }
    expect("clock_exiting_quiet_window_allows_current_snapshot") {
      val f = Fixture(minute = 8 * 60 - 1)
      val initiallyBlocked = f.speech.playbackSettings() == null
      f.minute = 8 * 60
      f.ready()
      initiallyBlocked && f.deliveredOnly() && f.delivered === f.current && f.loads == 2
    }
    expect("matching_quiet_endpoints_remain_a_disabled_window") {
      val f = Fixture(publicSettings().copy(quietStartMin = 12 * 60, quietEndMin = 12 * 60))
      val atEndpoint = f.speech.playbackSettings() === f.current
      f.minute = 23 * 60
      val awayFromEndpoint = f.speech.playbackSettings() === f.current
      atEndpoint && awayFromEndpoint && f.loads == 2
    }
    expect("valid_initial_snapshot_remains_immutable_after_current_settings_change") {
      val original = publicSettings()
      val f = Fixture(original)
      val initial = f.speech.playbackSettings()
      f.current = original.copy(spokenTimeOn = false, volume = 0,
          spokenVoice = "public_voice_b", quietHoursOn = false)
      f.ready()
      initial === original && original == publicSettings() && f.cancelledOnly() && f.loads == 2
    }
    expect("cancellation_callback_runs_once_and_delivery_never_runs") {
      val f = Fixture(publicSettings().copy(spokenTimeOn = false))
      f.ready()
      f.cancelledOnly() && f.delivered == null && f.loads == 1
    }
    expect("delivery_callback_runs_once_and_cancellation_never_runs") {
      val f = Fixture()
      f.ready()
      f.deliveredOnly() && f.delivered === f.current && f.loads == 1
    }
    expect("daytime_quiet_start_is_inclusive") {
      val f = Fixture(publicSettings().copy(quietStartMin = 11 * 60, quietEndMin = 13 * 60),
          minute = 11 * 60)
      f.speech.playbackSettings() == null && f.loads == 1
    }
    expect("daytime_quiet_end_is_exclusive") {
      val f = Fixture(publicSettings().copy(quietStartMin = 11 * 60, quietEndMin = 13 * 60),
          minute = 13 * 60)
      f.speech.playbackSettings() === f.current && f.loads == 1
    }
    expect("main_handler_queued_readiness_rereads_settings_and_cancels_without_delivery") {
      queuedReadinessCancels()
    }

    val passed = failureCount == 0
    val report = JSONObject().put("command", "automaticTimeSpeech").put("passed", passed)
        .put("completed", passed).put("checkCount", checks.length())
        .put("passedCount", checks.length() - failureCount).put("failureCount", failureCount)
        .put("fixturesPublic", true).put("networkUsed", false).put("stateMutations", false)
        .put("checks", checks)
    return Bundle().apply {
      putBoolean("automaticTimeSpeechCompleted", passed)
      putString("automaticTimeSpeechJson", report.toString())
      putInt("automaticTimeSpeechCheckCount", checks.length())
      putInt("automaticTimeSpeechPassedCount", checks.length() - failureCount)
      putInt("automaticTimeSpeechFailureCount", failureCount)
    }
  }

  private fun publicSettings() = ChimeConfig.Settings(
      hourlyChimeOn = false, spokenTimeOn = true, goldenHourOn = false,
      quietHoursOn = true, quietStartMin = 22 * 60, quietEndMin = 8 * 60,
      volume = 70, spokenVoice = "public_voice_a", sunriseVariant = 0)

  private class Fixture(settings: ChimeConfig.Settings = publicSettings(),
      var minute: Int = 12 * 60) {
    var current = settings
    var loads = 0
    var cancellations = 0
    var deliveries = 0
    var delivered: ChimeConfig.Settings? = null
    val speech = AutomaticTimeSpeech(loadSettings = { loads++; current }, nowMinute = { minute })

    fun ready() = speech.deliverOrCancel(cancel = { cancellations++ }, deliver = {
      deliveries++
      delivered = it
    })

    fun cancelledOnly() = cancellations == 1 && deliveries == 0
    fun deliveredOnly() = deliveries == 1 && cancellations == 0
  }

  private fun queuedReadinessCancels(): Boolean {
    val original = publicSettings()
    val current = AtomicReference(original)
    val loads = AtomicInteger()
    val cancellations = AtomicInteger()
    val deliveries = AtomicInteger()
    val queued = AtomicBoolean()
    val ranOnMain = AtomicBoolean()
    val callbackFailed = AtomicBoolean()
    val done = CountDownLatch(1)
    val speech = AutomaticTimeSpeech(loadSettings = { loads.incrementAndGet(); current.get() },
        nowMinute = { 12 * 60 })
    val initial = speech.playbackSettings()
    val handler = Handler(Looper.getMainLooper())
    val readiness = Runnable {
      try {
        ranOnMain.set(Looper.myLooper() === Looper.getMainLooper())
        speech.deliverOrCancel(cancel = { cancellations.incrementAndGet() },
            deliver = { deliveries.incrementAndGet() })
      } catch (_: Throwable) {
        callbackFailed.set(true)
      } finally {
        done.countDown()
      }
    }
    // Queue readiness from the main thread, then change only this public local fixture.
    // The queued callback cannot execute until this staging callback returns.
    val staged = handler.post {
      try {
        queued.set(handler.post(readiness))
        current.set(original.copy(spokenTimeOn = false))
        if (!queued.get()) done.countDown()
      } catch (_: Throwable) {
        callbackFailed.set(true)
        done.countDown()
      }
    }
    val completed = staged && done.await(5, TimeUnit.SECONDS)
    if (!completed) handler.removeCallbacks(readiness)
    return initial === original && completed && queued.get() && ranOnMain.get() &&
        !callbackFailed.get() && loads.get() == 2 && cancellations.get() == 1 &&
        deliveries.get() == 0
  }
}
