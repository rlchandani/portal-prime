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
import java.lang.ref.WeakReference

/** No camera, permission request, wake lock, polling loop, or retained background listener. */
internal class DarkRoomObserver(
    context: Context,
    private val darkLux: Float,
    initialNight: Boolean = false,
    private val onNightChanged: (Boolean) -> Unit,
) : SensorEventListener {
  private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
  private val sensor = manager?.getDefaultSensor(Sensor.TYPE_LIGHT)
  private val main = Handler(Looper.getMainLooper())
  private val policy = DarkRoomPolicy(darkLux, initialNight = initialNight)
  private var active = false
  internal data class Diagnostic(
      val host: String, val available: Boolean, val requested: Boolean = false,
      val registered: Boolean = false, val lux: Float? = null, val readings: Int = 0,
      val receiptUptimeMs: Long? = null, val night: Boolean = false,
      val deadlineUptimeMs: Long? = null, val darkLux: Float,
  )
  @Volatile private var diagnostic = Diagnostic(
      context.javaClass.simpleName, sensor != null, night = initialNight, darkLux = darkLux)
  private var queuedDeadline: Long? = null
  private val settle = Runnable {
    queuedDeadline = null
    if (active && policy.settle(SystemClock.uptimeMillis())) onNightChanged(policy.night)
    publishPolicy()
    scheduleDeadline()
  }

  init { rememberObserver() }

  private fun rememberObserver() {
    synchronized(observers) {
      observers.removeAll { it.get() == null }
      if (observers.any { it.get() === this }) return
      if (observers.size == MAX_OBSERVERS) {
        val inactive = observers.indexOfFirst { it.get()?.diagnosticSnapshot()?.registered != true }
        if (inactive >= 0) observers.removeAt(inactive)
        else if (diagnostic.requested) observers.removeAt(0)
        else return // An unused new frame must not displace a running sensor.
      }
      observers.add(WeakReference(this))
    }
  }

  /** Passive immutable snapshot; reading it starts no sensor or callback. */
  internal fun diagnosticSnapshot(): Diagnostic = diagnostic

  fun setActive(value: Boolean) {
    diagnostic = diagnostic.copy(requested = value)
    if (value) rememberObserver()
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
    diagnostic = diagnostic.copy(registered = active, lux = null, readings = 0,
        receiptUptimeMs = null, night = policy.night, deadlineUptimeMs = policy.deadlineMs)
  }

  override fun onSensorChanged(event: SensorEvent) {
    if (!active || event.sensor.type != Sensor.TYPE_LIGHT) return
    val now = SystemClock.uptimeMillis()
    val lux = event.values.firstOrNull() ?: Float.NaN
    if (policy.read(lux, now))
      onNightChanged(policy.night)
    diagnostic = diagnostic.copy(lux = lux.takeIf { it.isFinite() && it >= 0f },
        readings = if (diagnostic.readings < Int.MAX_VALUE) diagnostic.readings + 1 else Int.MAX_VALUE,
        receiptUptimeMs = now, night = policy.night, deadlineUptimeMs = policy.deadlineMs)
    scheduleDeadline()
  }

  private fun publishPolicy() {
    diagnostic = diagnostic.copy(night = policy.night, deadlineUptimeMs = policy.deadlineMs)
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

  private fun scheduleDeadline() {
    val deadline = if (active) policy.deadlineMs else null
    if (deadline == queuedDeadline) return
    main.removeCallbacks(settle)
    queuedDeadline = deadline
    if (deadline != null) main.postAtTime(settle, deadline)
  }

  companion object {
    private const val MAX_OBSERVERS = 8
    private val observers = mutableListOf<WeakReference<DarkRoomObserver>>()
    /** Bounded weak ownership: diagnostics cannot retain a destroyed Activity or Dream. */
    internal fun diagnostics(): List<Diagnostic> = synchronized(observers) {
      observers.removeAll { it.get() == null }
      observers.mapNotNull { it.get()?.diagnosticSnapshot() }
    }
  }
}
