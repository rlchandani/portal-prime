/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

/** Count writes at the real tick's TextView boundary, while preserving clock/timer updates. */
class FaceRendererMetadataUpdatesTest {
  @Test fun returningClockRefreshesAcrossMidnightWithoutResumingHiddenWork() = fixture { f ->
    f.tick()
    f.renderer.setHostActive(false)
    Mockito.clearInvocations(f.clock, f.handler)
    f.now += 86_400_000L
    val refresh = f.renderer.javaClass.methods.singleOrNull { it.name == "refreshRetainedTime" }
    assertNotNull("The retained clock must refresh before reveal without resuming media work", refresh)
    refresh!!.invoke(f.renderer, Date(f.now))
    assertEquals(listOf("2030-01-01", "2030-01-02"), f.date.writes)
    val clockCalls = Mockito.mockingDetails(f.clock).invocations
    val publication = clockCalls.single { it.method.name == "refreshForReveal" }
    assertEquals(f.now, (publication.arguments[0] as Date).time)
    assertFalse((field(f.renderer, "work").get(f.renderer) as FaceRendererWorkState).isActive)
    Mockito.verify(f.clock, Mockito.never()).setHostActive(true)
    Mockito.verify(f.handler, Mockito.never()).postDelayed(Mockito.any(Runnable::class.java), Mockito.anyLong())
  }

  @Test fun sixtyUnchangedTicksWriteMetadataOnceButStillUpdateTheClockEverySecond() = fixture { f ->
    f.weather("Clear 21°")
    repeat(60) { f.tick() }
    assertEquals(listOf("2030-01-01"), f.date.writes)
    assertEquals(listOf("75% ⚡"), f.battery.writes)
    assertEquals(listOf("Clear 21°"), f.weather.writes)
    val updates = Mockito.mockingDetails(f.clock).invocations.filter { it.method.name == "update" }
    assertEquals(60, updates.size)
    assertEquals(List(60) { it % 2 != 0 }, updates.map { it.arguments[1] })
    Mockito.verify(f.handler, Mockito.times(60)).postDelayed(f.runnable, 1_000L)
  }

  @Test fun midnightAndDateFormatChangesArePublishedOnTheNextTick() = fixture { f ->
    f.tick()
    f.now += 86_400_000L
    f.tick()
    f.face(ClockSpec(showDate = true, dateFormat = DateFormat.NUMERIC_MDY))
    f.tick()
    assertEquals(listOf("2030-01-01", "2030-01-02", "01/02/2030"), f.date.writes)
  }

  @Test fun batteryAndWeatherChangesIncludingDisappearanceStayLive() = fixture { f ->
    f.weather("Clear 21°")
    f.tick()
    f.battery(41, false)
    f.weather("Rain 18°")
    f.tick()
    f.battery(-1, false)
    f.weather("")
    f.tick()
    f.tick()
    assertEquals(listOf("75% ⚡", "41%", ""), f.battery.writes)
    assertEquals(listOf("Clear 21°", "Rain 18°", ""), f.weather.writes)
    assertEquals(View.GONE, f.battery.visibility)
    assertEquals(View.GONE, f.weather.visibility)
    f.battery(50, true)
    f.weather("Clear 20°")
    f.tick()
    assertEquals("50% ⚡", f.battery.writes.last())
    assertEquals("Clear 20°", f.weather.writes.last())
    assertEquals(View.VISIBLE, f.battery.visibility)
    assertEquals(View.VISIBLE, f.weather.visibility)
  }

  @Test fun pausedTimerSkipsDuplicatesAndRunningCountdownStillChangesEachSecond() = fixture { f ->
    f.remaining = 62_000L
    repeat(60) { f.tick() }
    assertEquals(listOf("⏱ 1:02"), f.timer.writes)
    f.running = true
    f.endsAt = f.now + 62_000L
    f.now += 1_000L
    f.tick()
    f.now += 1_000L
    f.tick()
    assertEquals(listOf("⏱ 1:02", "⏱ 1:01", "⏱ 1:00"), f.timer.writes)
    f.now = f.endsAt
    f.tick()
    Mockito.verify(f.timer.view).setVisibility(View.GONE)
  }

  private class Label {
    val view = Mockito.mock(TextView::class.java)
    val writes = mutableListOf<String>()
    var visibility = View.VISIBLE
      private set
    private var current: CharSequence = ""
    init {
      Mockito.`when`(view.text).thenAnswer { current }
      Mockito.doAnswer { call ->
        current = call.getArgument<CharSequence>(0)
        writes.add(current.toString())
        null
      }.`when`(view).setText(Mockito.any(CharSequence::class.java))
      Mockito.doAnswer { call ->
        visibility = call.getArgument(0)
        null
      }.`when`(view).setVisibility(Mockito.anyInt())
    }
  }

  private class Fixture(val renderer: FaceRenderer, val handler: Handler, prefs: SharedPreferences) {
    val runnable = field(renderer, "tick").get(renderer) as Runnable
    val date = Label()
    val battery = Label()
    val weather = Label()
    val timer = Label()
    val clock = Mockito.mock(ClockFaceView::class.java)
    // Midday UTC makes the fixture independent of daylight-saving boundaries.
    var now = 1_893_499_200_000L
    var remaining = 0L
    var running = false
    var endsAt = 0L
    init {
      field(renderer, "dateView").set(renderer, date.view)
      field(renderer, "batteryView").set(renderer, battery.view)
      field(renderer, "weatherView").set(renderer, weather.view)
      field(renderer, "timerView").set(renderer, timer.view)
      field(renderer, "clockFace").set(renderer, clock)
      field(renderer, "antiBurnInOn").setBoolean(renderer, false)
      face(ClockSpec(showDate = true, dateFormat = DateFormat.ISO))
      battery(75, true)
      // Mark an already registered listener; receiver ownership is tested separately.
      field(renderer, "batteryReceiver").set(renderer, Mockito.mock(android.content.BroadcastReceiver::class.java))
      Mockito.`when`(prefs.getLong("remaining_ms", 0L)).thenAnswer { remaining }
      Mockito.`when`(prefs.getLong("ends_at", 0L)).thenAnswer { endsAt }
      Mockito.`when`(prefs.getBoolean("running", false)).thenAnswer { running }
      (field(renderer, "work").get(renderer) as FaceRendererWorkState).start()
    }
    fun face(clock: ClockSpec) = field(renderer, "face").set(renderer, Face("test", clock = clock))
    fun battery(percent: Int, charging: Boolean) = field(renderer, "batteryStatus").set(renderer, percent to charging)
    fun weather(value: String) = field(renderer, "weatherText").set(renderer, value)
    fun tick() {
      Mockito.mockConstruction(Date::class.java) { date, _ ->
        Mockito.`when`(date.time).thenReturn(now)
      }.use { runnable.run() }
    }
  }

  private fun fixture(test: (Fixture) -> Unit) {
    val originalZone = TimeZone.getDefault()
    val originalLocale = Locale.getDefault()
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    Locale.setDefault(Locale.US)
    try {
      val context = Mockito.mock(Context::class.java)
      val resources = Mockito.mock(Resources::class.java)
      val metrics = Mockito.mock(DisplayMetrics::class.java).apply { density = 1f }
      val prefs = Mockito.mock(SharedPreferences::class.java)
      Mockito.`when`(context.resources).thenReturn(resources)
      Mockito.`when`(resources.displayMetrics).thenReturn(metrics)
      Mockito.`when`(context.getSharedPreferences(Mockito.anyString(), Mockito.anyInt())).thenReturn(prefs)
      Mockito.mockStatic(Looper::class.java).use { loops ->
        loops.`when`<Looper> { Looper.getMainLooper() }.thenReturn(Mockito.mock(Looper::class.java))
        Mockito.mockConstruction(Handler::class.java).use { handlers ->
          Mockito.mockConstruction(FrameLayout::class.java).use {
            Mockito.mockConstruction(FrameLayout.LayoutParams::class.java).use {
              val renderer = FaceRenderer(context)
              try { test(Fixture(renderer, handlers.constructed().single(), prefs)) }
              finally { renderer.stop() }
            }
          }
        }
      }
    } finally {
      TimeZone.setDefault(originalZone)
      Locale.setDefault(originalLocale)
    }
  }

  private companion object {
    fun field(value: Any, name: String) = value.javaClass.getDeclaredField(name).apply { isAccessible = true }
  }
}
