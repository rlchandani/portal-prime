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

class QuickBarConfigObservationTest {
  @Test fun enabledSavePublishesTheNewImmutableSnapshot() {
    val fixture = Fixture()
    var observed = QuickBarConfig.load(fixture.context)
    val stop = QuickBarConfig.observe(fixture.context) { observed = QuickBarConfig.load(fixture.context) }
    QuickBarConfig.setEnabled(fixture.context, true)
    assertEquals(QuickBarConfig.Settings(enabled = true), observed)
    stop()
  }
  @Test fun alwaysShowSavePublishesTheNewValue() {
    val fixture = Fixture()
    QuickBarConfig.setEnabled(fixture.context, true)
    var observed = QuickBarConfig.load(fixture.context)
    val stop = QuickBarConfig.observe(fixture.context) { observed = QuickBarConfig.load(fixture.context) }
    QuickBarConfig.setAlwaysShow(fixture.context, true)
    assertEquals(QuickBarConfig.Settings(enabled = true, alwaysShow = true), observed)
    stop()
  }
  @Test fun unrelatedSavedKeysDoNotRefreshThePage() {
    val fixture = Fixture(); var callbacks = 0
    val stop = QuickBarConfig.observe(fixture.context) { callbacks++ }
    fixture.store.edit().putBoolean("unrelated", true).apply()
    assertEquals(0, callbacks); stop()
  }
  @Test fun nullClearNotificationRefreshesOnPlatformsThatEmitIt() {
    val fixture = Fixture(); var callbacks = 0
    val stop = QuickBarConfig.observe(fixture.context) { callbacks++ }
    fixture.listeners.single().onSharedPreferenceChanged(fixture.store, null)
    assertEquals(1, callbacks); stop()
  }
  @Test fun disposalRejectsAnAlreadyQueuedNotification() {
    val fixture = Fixture(); var callbacks = 0
    val stop = QuickBarConfig.observe(fixture.context) { callbacks++ }
    val queuedListener = fixture.listeners.single()
    stop(); queuedListener.onSharedPreferenceChanged(fixture.store, "enabled")
    assertEquals(0, callbacks); assertTrue(fixture.listeners.isEmpty())
  }
  @Test fun repeatedDisposalUnregistersOnlyOnce() {
    val fixture = Fixture()
    val stop = QuickBarConfig.observe(fixture.context) { fail("Disposed observer called") }
    stop(); stop()
    assertEquals(1, fixture.unregisters)
  }
  @Test fun disposingOneObserverDoesNotStopAnother() {
    val fixture = Fixture(); var first = 0; var second = 0
    val stopFirst = QuickBarConfig.observe(fixture.context) { first++ }
    val stopSecond = QuickBarConfig.observe(fixture.context) { second++ }
    stopFirst(); QuickBarConfig.setEnabled(fixture.context, true)
    assertEquals(0, first); assertEquals(1, second); stopSecond()
  }
  @Test fun observingDoesNotWriteOrResetStoredValues() {
    val fixture = Fixture()
    QuickBarConfig.setAlwaysShow(fixture.context, true)
    Mockito.clearInvocations(fixture.store)
    val stop = QuickBarConfig.observe(fixture.context) { }
    Mockito.verify(fixture.store, Mockito.never()).edit()
    assertEquals(QuickBarConfig.Settings(alwaysShow = true), QuickBarConfig.load(fixture.context))
    stop()
  }

  private class Fixture {
    val context = Mockito.mock(Context::class.java)
    val store = Mockito.mock(SharedPreferences::class.java)
    private val editor = Mockito.mock(SharedPreferences.Editor::class.java)
    val listeners = linkedSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    private val values = mutableMapOf<String, Boolean>()
    private val pending = mutableMapOf<String, Boolean>()
    var unregisters = 0
    init {
      Mockito.`when`(context.getSharedPreferences("quick_bar", Context.MODE_PRIVATE)).thenReturn(store)
      Mockito.`when`(store.edit()).thenReturn(editor)
      Mockito.doAnswer { call -> values[call.getArgument<String>(0)] ?: call.getArgument<Boolean>(1) }
          .`when`(store).getBoolean(Mockito.anyString(), Mockito.anyBoolean())
      Mockito.doAnswer { call ->
        pending[call.getArgument(0)] = call.getArgument(1); editor
      }.`when`(editor).putBoolean(Mockito.anyString(), Mockito.anyBoolean())
      Mockito.doAnswer {
        val changed = pending.keys.filter { values[it] != pending[it] }
        values.putAll(pending); pending.clear()
        changed.forEach { key -> listeners.toList().forEach { it.onSharedPreferenceChanged(store, key) } }
        null
      }.`when`(editor).apply()
      Mockito.doAnswer { call -> listeners.add(call.getArgument(0)); null }
          .`when`(store).registerOnSharedPreferenceChangeListener(Mockito.any(SharedPreferences.OnSharedPreferenceChangeListener::class.java))
      Mockito.doAnswer { call -> listeners.remove(call.getArgument(0)); unregisters++; null }
          .`when`(store).unregisterOnSharedPreferenceChangeListener(Mockito.any(SharedPreferences.OnSharedPreferenceChangeListener::class.java))
    }
  }
}
