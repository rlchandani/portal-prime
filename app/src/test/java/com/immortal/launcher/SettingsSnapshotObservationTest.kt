/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

class SettingsSnapshotObservationTest {
  @Test fun appearanceAndWeatherSavedKeysRefreshOnlyTheirDomain() {
    val f = Fixture(); var app = 0; var calendar = 0
    val stopApp = ImmortalSettings.observeSnapshot(f.context) { app++ }
    val stopCalendar = ScreensaverConfig.observeCalendarSnapshot(f.context) { calendar++ }
    listOf("settings_theme", "weather_unit", "weather_widget", "tile_size",
        "clock_format", "show_mini_player", "hide_status_bar").forEach { f.notify(f.app, it) }
    assertEquals(7, app); assertEquals(0, calendar)
    stopApp(); stopCalendar()
  }
  @Test fun everyCalendarSavedKeyRefreshesOnlyTheCalendarDomain() {
    val f = Fixture(); var app = 0; var calendar = 0
    val stopApp = ImmortalSettings.observeSnapshot(f.context) { app++ }
    val stopCalendar = ScreensaverConfig.observeCalendarSnapshot(f.context) { calendar++ }
    listOf("calendar_url", "calendar_enabled", "calendar_range", "calendar_size", "calendar_side")
        .forEach { f.notify(f.calendar, it) }
    assertEquals(0, app); assertEquals(5, calendar)
    stopApp(); stopCalendar()
  }
  @Test fun connectionAndSlideshowWritesDoNotRefreshScalarRows() {
    val f = Fixture(); var callbacks = 0
    val stopApp = ImmortalSettings.observeSnapshot(f.context) { callbacks++ }
    val stopCalendar = ScreensaverConfig.observeCalendarSnapshot(f.context) { callbacks++ }
    listOf("ma_password", "snapcast_host", "multiroom_enabled", "advanced_settings")
        .forEach { f.notify(f.app, it) }
    listOf("cache_enabled", "mode", "interval_sec", "immich_key", "source")
        .forEach { f.notify(f.calendar, it) }
    assertEquals(0, callbacks); stopApp(); stopCalendar()
  }
  @Test fun nullNotificationRefreshesOnPlatformsThatEmitIt() {
    val f = Fixture(); var callbacks = 0
    val stop = ImmortalSettings.observeSnapshot(f.context) { callbacks++ }
    f.notify(f.app, null)
    assertEquals(1, callbacks); stop()
  }
  @Test fun disposalSuppressesNotificationsQueuedBeforeUnregister() {
    val f = Fixture(); var callbacks = 0
    val stop = ScreensaverConfig.observeCalendarSnapshot(f.context) { callbacks++ }
    val queued = f.listeners.getValue(f.calendar).single()
    stop(); queued.onSharedPreferenceChanged(f.calendar, "calendar_url")
    assertEquals(0, callbacks)
  }
  @Test fun repeatedDisposalUnregistersExactlyOnce() {
    val f = Fixture()
    val stop = ImmortalSettings.observeSnapshot(f.context) { fail("disposed") }
    stop(); stop()
    Mockito.verify(f.app, Mockito.times(1))
        .unregisterOnSharedPreferenceChangeListener(Mockito.any(SharedPreferences.OnSharedPreferenceChangeListener::class.java))
  }
  @Test fun independentSubscribersCanDisposeSeparately() {
    val f = Fixture(); var first = 0; var second = 0
    val stopFirst = ImmortalSettings.observeSnapshot(f.context) { first++ }
    val stopSecond = ImmortalSettings.observeSnapshot(f.context) { second++ }
    stopFirst(); f.notify(f.app, "weather_unit")
    assertEquals(0, first); assertEquals(1, second); stopSecond()
  }
  @Test fun subscribingAndDisposingNeverWritesPreferences() {
    val f = Fixture()
    val stopApp = ImmortalSettings.observeSnapshot(f.context) { }
    val stopCalendar = ScreensaverConfig.observeCalendarSnapshot(f.context) { }
    stopApp(); stopCalendar()
    Mockito.verify(f.app, Mockito.never()).edit()
    Mockito.verify(f.calendar, Mockito.never()).edit()
  }

  private class Fixture {
    val context = Mockito.mock(Context::class.java)
    val app = Mockito.mock(SharedPreferences::class.java)
    val calendar = Mockito.mock(SharedPreferences::class.java)
    val listeners = mapOf(app to linkedSetOf<SharedPreferences.OnSharedPreferenceChangeListener>(),
        calendar to linkedSetOf<SharedPreferences.OnSharedPreferenceChangeListener>())
    init {
      Mockito.`when`(context.getSharedPreferences("immortal_settings", Context.MODE_PRIVATE)).thenReturn(app)
      Mockito.`when`(context.getSharedPreferences("immortal_screensaver", Context.MODE_PRIVATE)).thenReturn(calendar)
      listeners.forEach { (store, owners) ->
        Mockito.doAnswer { call -> owners.add(call.getArgument(0)); null }
            .`when`(store).registerOnSharedPreferenceChangeListener(Mockito.any(SharedPreferences.OnSharedPreferenceChangeListener::class.java))
        Mockito.doAnswer { call -> owners.remove(call.getArgument(0)); null }
            .`when`(store).unregisterOnSharedPreferenceChangeListener(Mockito.any(SharedPreferences.OnSharedPreferenceChangeListener::class.java))
      }
    }
    fun notify(store: SharedPreferences, key: String?) {
      listeners.getValue(store).toList().forEach { it.onSharedPreferenceChanged(store, key) }
    }
  }
}
