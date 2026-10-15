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

class AlarmConfigObservationTest {
  @Test fun allOwnedAlarmKeysAndBulkChangesNotifyTheSnapshot() {
    val f = Fixture(); var changes = 0
    val stop = AlarmConfig.observeSnapshot(f.context) { changes++ }
    listOf("enabled", "hour", "minute", "label", "days", null).forEach(f::notify)
    assertEquals(6, changes)
    stop()
  }
  @Test fun unrelatedSettingsNeverRefreshTheAlarmEditor() {
    val f = Fixture(); var changes = 0
    val stop = AlarmConfig.observeSnapshot(f.context) { changes++ }
    listOf("weather_unit", "volume", "unknown_key").forEach(f::notify)
    assertEquals(0, changes)
    stop()
  }
  @Test fun disposedListenerRejectsQueuedEventsAndUnregistersOnlyOnce() {
    val f = Fixture(); var changes = 0
    val stop = AlarmConfig.observeSnapshot(f.context) { changes++ }
    val queued = f.listeners.single()
    stop(); stop()
    queued.onSharedPreferenceChanged(f.store, "enabled")
    assertEquals(0, changes)
    Mockito.verify(f.store, Mockito.times(1))
        .unregisterOnSharedPreferenceChangeListener(Mockito.any(SharedPreferences.OnSharedPreferenceChangeListener::class.java))
  }
  @Test fun observationNeverMutatesTheSavedAlarm() {
    val f = Fixture()
    val stop = AlarmConfig.observeSnapshot(f.context) { }
    f.notify("enabled")
    stop()
    Mockito.verify(f.store, Mockito.never()).edit()
  }

  private class Fixture {
    val context = Mockito.mock(Context::class.java)
    val store = Mockito.mock(SharedPreferences::class.java)
    val listeners = linkedSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    init {
      Mockito.`when`(context.getSharedPreferences("immortal_alarm", Context.MODE_PRIVATE)).thenReturn(store)
      Mockito.doAnswer { call -> listeners.add(call.getArgument(0)); null }
          .`when`(store).registerOnSharedPreferenceChangeListener(Mockito.any(SharedPreferences.OnSharedPreferenceChangeListener::class.java))
      Mockito.doAnswer { call -> listeners.remove(call.getArgument(0)); null }
          .`when`(store).unregisterOnSharedPreferenceChangeListener(Mockito.any(SharedPreferences.OnSharedPreferenceChangeListener::class.java))
    }
    fun notify(key: String?) {
      listeners.toList().forEach { it.onSharedPreferenceChanged(store,key) }
    }
  }
}
