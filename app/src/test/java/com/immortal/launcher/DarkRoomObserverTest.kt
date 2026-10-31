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
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

class DarkRoomObserverTest {
  private class Fixture(hasSensor: Boolean, registrationSucceeds: Boolean) {
    val context = Mockito.mock(Context::class.java)
    val manager = Mockito.mock(SensorManager::class.java)
    val sensor = Mockito.mock(Sensor::class.java)
    val changes = mutableListOf<Boolean>()
    val pending = linkedMapOf<Runnable, Long>()
    var now = 0L
    var registered = false
    lateinit var observer: DarkRoomObserver

    init {
      Mockito.`when`(context.getSystemService(Context.SENSOR_SERVICE)).thenReturn(manager)
      Mockito.`when`(manager.getDefaultSensor(Sensor.TYPE_LIGHT))
          .thenReturn(if (hasSensor) sensor else null)
      Mockito.`when`(sensor.type).thenReturn(Sensor.TYPE_LIGHT)
      Mockito.doAnswer {
        registered = registrationSucceeds
        registrationSucceeds
      }.`when`(manager).registerListener(Mockito.any(SensorEventListener::class.java),
          Mockito.eq(sensor), Mockito.anyInt(), Mockito.any(Handler::class.java))
      Mockito.doAnswer { registered = false; null }.`when`(manager)
          .unregisterListener(Mockito.any(SensorEventListener::class.java))
    }

    fun read(lux: Float) {
      val event = Mockito.mock(SensorEvent::class.java)
      event.sensor = sensor
      SensorEvent::class.java.getDeclaredField("values").apply {
        isAccessible = true
        set(event, floatArrayOf(lux))
      }
      observer.onSensorChanged(event)
    }

    fun advance(to: Long) {
      now = to
      pending.filterValues { it <= now }.keys.toList().forEach {
        pending.remove(it)
        it.run()
      }
    }
  }

  private fun withFixture(
      hasSensor: Boolean = true,
      registrationSucceeds: Boolean = true,
      block: (Fixture) -> Unit,
  ) {
    val f = Fixture(hasSensor, registrationSucceeds)
    Mockito.mockStatic(Looper::class.java).use { loops ->
      loops.`when`<Looper> { Looper.getMainLooper() }.thenReturn(Mockito.mock(Looper::class.java))
      Mockito.mockStatic(SystemClock::class.java).use { clock ->
        clock.`when`<Long> { SystemClock.uptimeMillis() }.thenAnswer { f.now }
        Mockito.mockConstruction(Handler::class.java) { handler, _ ->
          Mockito.doAnswer { call ->
            f.pending[call.getArgument(0)] = call.getArgument(1)
            true
          }.`when`(handler).postAtTime(Mockito.any(Runnable::class.java), Mockito.anyLong())
          Mockito.doAnswer { call ->
            f.pending.remove(call.getArgument<Runnable>(0))
            null
          }.`when`(handler).removeCallbacks(Mockito.any(Runnable::class.java))
        }.use {
          f.observer = DarkRoomObserver(f.context, 3f) { f.changes.add(it) }
          try { block(f) } finally { f.observer.setActive(false) }
        }
      }
    }
  }

  @Test fun singleOnChangeReadingEntersNightAtDwellWithoutSensorHeartbeat() = withFixture { f ->
    f.observer.setActive(true)
    f.read(0f)
    assertEquals(listOf(20_000L), f.pending.values.toList())
    f.advance(19_999)
    assertTrue(f.changes.isEmpty())
    f.advance(20_000)
    assertEquals(listOf(true), f.changes)
    assertTrue(f.pending.isEmpty())
  }

  @Test fun hidingCancelsRegistrationAndDwellEvenForAlreadyDispatchedCallback() = withFixture { f ->
    f.observer.setActive(true)
    f.read(0f)
    val dispatched = f.pending.keys.single()
    f.observer.setActive(false)
    assertFalse(f.registered)
    assertTrue(f.pending.isEmpty())
    f.now = 30_000
    dispatched.run()
    f.read(0f)
    assertTrue(f.changes.isEmpty())
    assertTrue(f.pending.isEmpty())
    f.observer.setActive(true)
    f.read(0f)
    assertEquals(listOf(50_000L), f.pending.values.toList())
    f.advance(50_000)
    assertEquals(listOf(true), f.changes)
  }

  @Test fun absentSensorDoesNotInventDarkness() = withFixture(hasSensor = false) { f ->
    f.observer.setActive(true)
    f.read(0f)
    f.advance(30_000)
    assertFalse(f.registered)
    assertTrue(f.changes.isEmpty())
    assertTrue(f.pending.isEmpty())
  }

  @Test fun failedRegistrationDoesNotAcceptSensorEvents() = withFixture(registrationSucceeds = false) { f ->
    f.observer.setActive(true)
    f.read(0f)
    f.advance(30_000)
    assertFalse(f.registered)
    assertTrue(f.changes.isEmpty())
    assertTrue(f.pending.isEmpty())
  }

  @Test fun invalidReadingRemovesPreviouslyScheduledDarkDecision() = withFixture { f ->
    f.observer.setActive(true)
    f.read(0f)
    f.now = 5_000
    f.read(Float.NaN)
    assertTrue(f.pending.isEmpty())
    f.advance(30_000)
    assertTrue(f.changes.isEmpty())
  }

  @Test fun passiveReadoutShowsActualSubscriptionReadingAndSettledNight() = withFixture { f ->
    f.observer.setActive(true)
    assertTrue(f.observer.diagnosticSnapshot().registered)
    assertEquals(0, f.observer.diagnosticSnapshot().readings)
    f.now = 100
    f.read(1f)
    val pending = f.observer.diagnosticSnapshot()
    assertEquals(1f, pending.lux)
    assertEquals(100L, pending.receiptUptimeMs)
    assertEquals(20_100L, pending.deadlineUptimeMs)
    assertFalse(pending.night)
    f.advance(20_100)
    assertTrue(f.observer.diagnosticSnapshot().night)
    assertNull(f.observer.diagnosticSnapshot().deadlineUptimeMs)
    assertTrue(f.pending.isEmpty())
  }

  @Test fun hiddenReadoutCannotPresentAnOldLuxAsAFreshSubscription() = withFixture { f ->
    f.observer.setActive(true)
    f.read(0f)
    f.observer.setActive(false)
    f.read(0f)
    val hidden = f.observer.diagnosticSnapshot()
    assertFalse(hidden.requested)
    assertFalse(hidden.registered)
    assertNull(hidden.lux)
    assertNull(hidden.receiptUptimeMs)
    f.observer.setActive(true)
    assertEquals(0, f.observer.diagnosticSnapshot().readings)
    assertNull(f.observer.diagnosticSnapshot().lux)
    assertTrue(f.pending.isEmpty())
  }

  @Test fun readoutDistinguishesUnavailableAndRejectedSensorRegistration() {
    withFixture(hasSensor = false) { f ->
      f.observer.setActive(true)
      assertFalse(f.observer.diagnosticSnapshot().available)
      assertTrue(f.observer.diagnosticSnapshot().requested)
      assertFalse(f.observer.diagnosticSnapshot().registered)
    }
    withFixture(registrationSucceeds = false) { f ->
      f.observer.setActive(true)
      assertTrue(f.observer.diagnosticSnapshot().available)
      assertFalse(f.observer.diagnosticSnapshot().registered)
      f.observer.setActive(false)
      assertFalse(f.observer.diagnosticSnapshot().requested)
    }
  }

  @Test fun unusedFramesCannotEvictALiveSensorReadout() = withFixture { f ->
    f.observer.setActive(true)
    val active = f.observer.diagnosticSnapshot()
    val unused = List(8) { DarkRoomObserver(f.context, 3f) {} }
    assertEquals(8, unused.size)
    assertTrue(DarkRoomObserver.diagnostics().any { it === active })
    assertTrue(DarkRoomObserver.diagnostics().size <= 8)
  }
}
