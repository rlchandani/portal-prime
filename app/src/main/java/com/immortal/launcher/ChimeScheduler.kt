/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * Schedules the ambient audio cues via AlarmManager:
 *  - a top-of-the-hour alarm for the hourly chime and/or spoken time, which
 *    re-arms itself for the next hour each time it fires;
 *  - one-shot alarms at today's sunrise and sunset for the golden-hour tone,
 *    re-armed (for tomorrow) when they fire.
 *
 * Call [reschedule] on boot, app start, and whenever the chime settings change;
 * it arms exactly the alarms the current config needs and cancels the rest.
 */
object ChimeScheduler {
  private const val TAG = "ImmortalChime"
  private val goldenWork = GoldenHourWork()
  private val goldenExecutor by lazy {
    Executors.newSingleThreadExecutor { task ->
      Thread(task, "ImmortalSolarChime").apply { isDaemon = true }
    }
  }

  const val ACTION_HOURLY = "com.immortal.launcher.CHIME_HOURLY"
  const val ACTION_GOLDEN_SUNRISE = "com.immortal.launcher.CHIME_SUNRISE"
  const val ACTION_GOLDEN_SUNSET = "com.immortal.launcher.CHIME_SUNSET"

  private const val RC_HOURLY = 2001
  private const val RC_SUNRISE = 2002
  private const val RC_SUNSET = 2003

  private fun alarms(c: Context) = c.getSystemService(AlarmManager::class.java)

  private fun pi(c: Context, action: String, rc: Int, create: Boolean): PendingIntent? {
    val flags =
        (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE) or
            PendingIntent.FLAG_IMMUTABLE
    return PendingIntent.getBroadcast(
        c, rc, Intent(c, ChimeReceiver::class.java).setAction(action), flags)
  }

  private fun setAlarm(c: Context, atMs: Long, action: String, rc: Int) {
    val p = pi(c, action, rc, create = true) ?: return
    runCatching { alarms(c).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, p) }
        .onFailure {
          runCatching { alarms(c).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, p) }
        }
  }

  private fun cancel(c: Context, action: String, rc: Int) {
    pi(c, action, rc, create = false)?.let { alarms(c).cancel(it); it.cancel() }
  }

  /** Arm/cancel every cue alarm to match the current [ChimeConfig]. */
  fun reschedule(context: Context) {
    val cfg = ChimeConfig.load(context)

    if (cfg.hourlyChimeOn || cfg.spokenTimeOn) {
      setAlarm(context, nextTopOfHour(), ACTION_HOURLY, RC_HOURLY)
    } else {
      cancel(context, ACTION_HOURLY, RC_HOURLY)
    }

    if (cfg.goldenHourOn) {
      // Allocate the ticket before queueing; obsolete work skips its network request.
      requestGoldenHour(context)
    } else {
      goldenWork.cancel {
        cancel(context, ACTION_GOLDEN_SUNRISE, RC_SUNRISE)
        cancel(context, ACTION_GOLDEN_SUNSET, RC_SUNSET)
      }
    }
  }

  /** Re-arm the hourly alarm for the next top of the hour (called from the receiver). */
  fun rearmHourly(context: Context) {
    val cfg = ChimeConfig.load(context)
    if (cfg.hourlyChimeOn || cfg.spokenTimeOn) {
      setAlarm(context, nextTopOfHour(), ACTION_HOURLY, RC_HOURLY)
    }
  }

  /** Fetch once off the main thread; always release a receiver's pending result. */
  fun requestGoldenHour(context: Context, onFinished: () -> Unit = {}) {
    val app = context.applicationContext ?: context
    val token = goldenWork.begin { ChimeConfig.load(app).goldenHourOn }
    if (token == null) { onFinished(); return }
    goldenExecutor.execute {
      try {
        if (goldenWork.isCurrent(token) && ChimeConfig.load(app).goldenHourOn) {
          scheduleGoldenHour(app, token)
        }
      } catch (error: Exception) {
        Log.w(TAG, "Solar chime scheduling unavailable: ${error.javaClass.simpleName}")
      } finally {
        onFinished()
      }
    }
  }

  private fun scheduleGoldenHour(context: Context, token: Long) {
    val days = Weather.fetchSunDays(context) ?: return // Retain already-armed times on fetch failure.
    val next = nextGoldenHourTimes(days, System.currentTimeMillis())
    goldenWork.commit(token, { ChimeConfig.load(context).goldenHourOn }) {
      next.sunriseMillis?.let { setAlarm(context, it, ACTION_GOLDEN_SUNRISE, RC_SUNRISE) }
          ?: cancel(context, ACTION_GOLDEN_SUNRISE, RC_SUNRISE)
      next.sunsetMillis?.let { setAlarm(context, it, ACTION_GOLDEN_SUNSET, RC_SUNSET) }
          ?: cancel(context, ACTION_GOLDEN_SUNSET, RC_SUNSET)
      Log.i(TAG, "Next solar chimes armed")
    }
  }

  internal data class GoldenHourTimes(val sunriseMillis: Long?, val sunsetMillis: Long?)

  internal fun nextGoldenHourTimes(days: List<Weather.SunTimes>, now: Long): GoldenHourTimes =
      GoldenHourTimes(
          days.map { it.sunriseMillis }.filter { it > now }.minOrNull(),
          days.map { it.sunsetMillis }.filter { it > now }.minOrNull(),
      )

  private fun nextTopOfHour(): Long {
    val c = Calendar.getInstance()
    c.add(Calendar.HOUR_OF_DAY, 1)
    c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
  }
}
