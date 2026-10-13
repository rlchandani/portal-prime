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
import android.content.SharedPreferences
import android.content.res.Resources
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

/** Runs the shipped renderer at its Android receiver boundary, without a network or device. */
class FaceRendererBatteryUpdatesTest {
  @Test fun sixtyIdleReadsRegisterOnce() = fixture { f ->
    repeat(60) { assertEquals(75 to true, f.read()) }
    assertEquals(1, f.registrations)
  }

  @Test fun registrationUsesCurrentStickyStateBeforeAnyCallback() = fixture { f ->
    assertEquals(75 to true, f.read())
    assertEquals(1, f.registrations)
  }

  @Test fun broadcastChangesStayFreshWithoutPollingAndroidAgain() = fixture { f ->
    assertEquals(75 to true, f.read())
    f.emit(f.intent(41, 100, BatteryManager.BATTERY_STATUS_DISCHARGING))
    assertEquals(41 to false, f.read())
    assertEquals(1, f.registrations)
  }

  @Test fun hidingReleasesListenerAndLateCallbacksCannotRestoreBattery() = fixture { f ->
    f.read()
    val old = f.receivers.lastOrNull()
    f.renderer.setHostActive(false)
    assertTrue(f.registered.isEmpty())
    old?.onReceive(f.context, f.intent(99, 100))
    assertEquals(-1 to false, f.read())
    assertEquals(1, f.registrations)
  }

  @Test fun resumeReadsFreshStickyStateAndRejectsPreviousListener() = fixture { f ->
    f.read()
    val old = f.receivers.lastOrNull()
    f.renderer.setHostActive(false)
    f.sticky = f.intent(20, 40, BatteryManager.BATTERY_STATUS_FULL)
    f.work.setHostActive(true) // Admit the same read used by the resumed tick, without weather IO.
    assertEquals(50 to true, f.read())
    old?.onReceive(f.context, f.intent(99, 100))
    assertEquals(50 to true, f.read())
    assertEquals(2, f.registrations)
  }

  @Test fun absentOrInvalidBatteryKeepsTheOriginalHiddenRepresentation() = fixture { f ->
    f.sticky = f.intent(20, 40, present = false)
    assertEquals(-1 to false, f.read())
    f.emit(f.intent(-1, 100, BatteryManager.BATTERY_STATUS_DISCHARGING))
    assertEquals(-1 to false, f.read())
    f.emit(f.intent(30, 0, BatteryManager.BATTERY_STATUS_DISCHARGING))
    assertEquals(-1 to false, f.read())
  }

  @Test fun tickWithoutBatteryWidgetsDoesNotQueryAndroid() = fixture { f ->
    field(f.renderer, "face").set(f.renderer,
        Face("generated-no-battery", clock = ClockSpec(showDate = false),
            battery = BatterySpec(enabled = false)))
    (field(f.renderer, "tick").get(f.renderer) as Runnable).run()
    assertEquals(0, f.registrations)
  }

  private class Fixture(val context: Context, val renderer: FaceRenderer) {
    val work = field(renderer, "work").get(renderer) as FaceRendererWorkState
    var registrations = 0
    val receivers = mutableListOf<BroadcastReceiver>()
    val registered = mutableSetOf<BroadcastReceiver>()
    var sticky: Intent? = intent(75, 100)
    init {
      Mockito.doAnswer { call ->
        registrations++
        val receiver = call.getArgument<BroadcastReceiver?>(0)
        if (receiver != null) { receivers.add(receiver); registered.add(receiver) }
        sticky
      }.`when`(context).registerReceiver(Mockito.nullable(BroadcastReceiver::class.java),
          Mockito.any(IntentFilter::class.java))
      Mockito.doAnswer { call -> registered.remove(call.getArgument<BroadcastReceiver>(0)); null }
          .`when`(context).unregisterReceiver(Mockito.any(BroadcastReceiver::class.java))
      work.start()
    }
    @Suppress("UNCHECKED_CAST")
    fun read(): Pair<Int, Boolean> = renderer.javaClass.getDeclaredMethod("batteryInfo")
        .apply { isAccessible = true }.invoke(renderer) as Pair<Int, Boolean>
    fun emit(value: Intent) {
      sticky = value
      registered.toList().forEach { it.onReceive(context, value) }
    }
    fun intent(level: Int, scale: Int, status: Int = BatteryManager.BATTERY_STATUS_CHARGING,
        present: Boolean = true): Intent = Mockito.mock(Intent::class.java).also {
      Mockito.`when`(it.action).thenReturn(Intent.ACTION_BATTERY_CHANGED)
      Mockito.`when`(it.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)).thenReturn(present)
      Mockito.`when`(it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)).thenReturn(level)
      Mockito.`when`(it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)).thenReturn(scale)
      Mockito.`when`(it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)).thenReturn(status)
    }
  }

  private fun fixture(test: (Fixture) -> Unit) {
    val context = Mockito.mock(Context::class.java)
    val resources = Mockito.mock(Resources::class.java)
    val metrics = Mockito.mock(DisplayMetrics::class.java).apply { density = 1f }
    val prefs = Mockito.mock(SharedPreferences::class.java)
    Mockito.`when`(context.resources).thenReturn(resources)
    Mockito.`when`(resources.displayMetrics).thenReturn(metrics)
    Mockito.`when`(context.getSharedPreferences(Mockito.anyString(), Mockito.anyInt())).thenReturn(prefs)
    val main = Mockito.mock(Looper::class.java)
    Mockito.mockStatic(Looper::class.java).use { loops ->
      loops.`when`<Looper> { Looper.getMainLooper() }.thenReturn(main)
      Mockito.mockConstruction(Handler::class.java).use {
        Mockito.mockConstruction(IntentFilter::class.java).use {
          Mockito.mockConstruction(FrameLayout::class.java).use {
            Mockito.mockConstruction(FrameLayout.LayoutParams::class.java).use {
              val renderer = FaceRenderer(context)
              try { test(Fixture(context, renderer)) } finally { renderer.stop() }
            }
          }
        }
      }
    }
  }

  private companion object {
    fun field(value: Any, name: String) = value.javaClass.getDeclaredField(name).apply { isAccessible = true }
  }
}
