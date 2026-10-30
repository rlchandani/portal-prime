/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** No camera, permission request, wake lock, polling loop, or retained background listener. */
internal class DarkRoomObserver(
    context: Context,
    darkLux: Float,
    private val onNightChanged: (Boolean) -> Unit,
) : SensorEventListener {
  private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
  private val sensor = manager?.getDefaultSensor(Sensor.TYPE_LIGHT)
  private val main = Handler(Looper.getMainLooper())
  private val policy = DarkRoomPolicy(darkLux)
  private var active = false
  private var queuedDeadline: Long? = null
  private val settle = Runnable {
    queuedDeadline = null
    if (active && policy.settle(SystemClock.uptimeMillis())) onNightChanged(policy.night)
    scheduleDeadline()
  }

  fun setActive(value: Boolean) {
    if (active == value) return
    if (!value) {
      active = false
      manager?.unregisterListener(this)
      main.removeCallbacks(settle)
      queuedDeadline = null
      policy.cancelPending()
    } else if (sensor != null) {
      active = runCatching {
        manager?.registerListener(this, sensor, 1_000_000, main) == true
      }.getOrDefault(false)
    }
  }

  override fun onSensorChanged(event: SensorEvent) {
    if (!active || event.sensor.type != Sensor.TYPE_LIGHT) return
    if (policy.read(event.values.firstOrNull() ?: Float.NaN, SystemClock.uptimeMillis()))
      onNightChanged(policy.night)
    scheduleDeadline()
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

  private fun scheduleDeadline() {
    val deadline = if (active) policy.deadlineMs else null
    if (deadline == queuedDeadline) return
    main.removeCallbacks(settle)
    queuedDeadline = deadline
    if (deadline != null) main.postAtTime(settle, deadline)
  }
}
