/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import java.util.Calendar
import org.mockito.Mockito
import org.junit.Assert.*
import org.junit.Test

class AlarmSettingsEditorTest {
  @Test fun hourEditDoesNotReenableAnAlarmThatFiredAfterTheEditorOpened() {
    val f = Fixture()
    val editor = AlarmSettingsEditor(f)
    f.value = f.value.copy(enabled = false, label = "Updated label", days = emptySet())
    val saved = editor.stepHour(1)
    assertFalse(f.value.enabled)
    assertEquals("Updated label", f.value.label)
    assertTrue(f.value.days.isEmpty())
    assertEquals(f.value, saved)
    assertEquals(listOf("hour", "reschedule"), f.trace)
  }

  @Test fun relativeTimeEditsUseTheLatestStoredTimeAndRetainOtherFields() {
    val f = Fixture(); val editor = AlarmSettingsEditor(f)
    f.value = f.value.copy(hour = 4, minute = 35, label = "Changed")
    editor.stepHour(1); editor.stepMinute(5)
    assertEquals(5, f.value.hour)
    assertEquals(40, f.value.minute)
    assertEquals("Changed", f.value.label)
  }

  @Test fun dayToggleUsesTheLatestSelectionAndPreservesExternalDisabledState() {
    val f = Fixture(); val editor = AlarmSettingsEditor(f)
    f.value = f.value.copy(enabled = false, days = setOf(Calendar.TUESDAY))
    editor.toggleDay(Calendar.TUESDAY)
    assertFalse(f.value.enabled)
    assertTrue(f.value.days.isEmpty())
    assertEquals(listOf("days", "reschedule"), f.trace)
  }

  @Test fun explicitEnableChangesOnlyEnabledAndRetainsNewerTimeLabelAndDays() {
    val f = Fixture(); val editor = AlarmSettingsEditor(f)
    f.value = f.value.copy(enabled = false, hour = 23, minute = 57,
        label = "New label", days = setOf(Calendar.FRIDAY))
    val latest = f.value
    editor.setEnabled(true)
    assertEquals(latest.copy(enabled = true), f.value)
    assertEquals(listOf("enabled", "reschedule"), f.trace)
  }

  @Test fun hourWrapRemainsBidirectional() {
    val f = Fixture(); f.value = f.value.copy(hour = 23)
    val editor = AlarmSettingsEditor(f)
    assertEquals(0, editor.stepHour(1).hour)
    assertEquals(23, editor.stepHour(-1).hour)
  }

  @Test fun minuteWrapRetainsFiveMinuteStepsFromAnUnalignedTime() {
    val f = Fixture(); f.value = f.value.copy(minute = 57)
    val editor = AlarmSettingsEditor(f)
    assertEquals(2, editor.stepMinute(5).minute)
    assertEquals(57, editor.stepMinute(-5).minute)
  }

  @Test fun refreshReadsExternalChangesWithoutWritingOrRescheduling() {
    val f = Fixture(); val editor = AlarmSettingsEditor(f)
    f.value = f.value.copy(enabled = false, label = "Fired")
    assertEquals(f.value, editor.refresh())
    assertEquals(f.value, editor.snapshot)
    assertTrue(f.trace.isEmpty())
  }

  @Test fun schedulerSeesTheUpdatedSingleFieldBeforeReadback() {
    val f = Fixture(); val editor = AlarmSettingsEditor(f)
    val result = editor.stepMinute(5)
    assertEquals(listOf("minute", "reschedule"), f.trace)
    assertEquals(1, f.scheduled.size)
    assertEquals(result, f.scheduled.single())
    assertEquals(result, editor.snapshot)
  }

  @Test fun exactTimePreservesLatestOtherFieldsAndSchedulesTheFinalPairOnce() {
    val f = Fixture(); val editor = AlarmSettingsEditor(f)
    f.value = f.value.copy(enabled = false, label = "Updated label", days = setOf(Calendar.FRIDAY))
    val result = editor.setTime(8, 17)
    val expected = AlarmConfig.Config(false, 8, 17, "Updated label", setOf(Calendar.FRIDAY))
    assertEquals(expected, f.value)
    assertEquals(expected, result)
    assertEquals(expected, editor.snapshot)
    assertEquals(listOf("time", "reschedule"), f.trace)
    assertEquals(listOf(expected), f.scheduled)
  }

  @Test fun invalidTimeRejectsBothFieldsBeforeAnyBackendSideEffect() {
    listOf(-1 to 17, 24 to 17, 8 to -1, 8 to 60, -1 to 60, 24 to -1).forEach { (hour, minute) ->
      val f = Fixture(); val editor = AlarmSettingsEditor(f)
      val before = f.value
      f.readCount = 0
      assertThrows(IllegalArgumentException::class.java) { editor.setTime(hour, minute) }
      assertEquals(before, f.value)
      assertEquals(before, editor.snapshot)
      assertTrue(f.trace.isEmpty())
      assertTrue(f.scheduled.isEmpty())
      assertEquals(0, f.readCount)
    }
  }

  @Test fun unchangedLatestTimeRefreshesStaleSnapshotWithoutWritingOrRescheduling() {
    val f = Fixture(); val editor = AlarmSettingsEditor(f)
    f.value = f.value.copy(enabled = false, hour = 8, minute = 17, label = "Fired", days = emptySet())
    val expected = f.value
    assertEquals(expected, editor.setTime(8, 17))
    assertEquals(expected, editor.snapshot)
    assertTrue(f.trace.isEmpty())
    assertTrue(f.scheduled.isEmpty())
  }

  @Test fun matchingStaleSnapshotStillWritesWhenLatestPersistedTimeDiffers() {
    val f = Fixture(); val editor = AlarmSettingsEditor(f)
    f.value = f.value.copy(hour = 8, minute = 17)
    val result = editor.setTime(7, 30)
    assertEquals(7, result.hour)
    assertEquals(30, result.minute)
    assertEquals(listOf("time", "reschedule"), f.trace)
    assertEquals(listOf(result), f.scheduled)
  }

  @Test fun readbackIncludesChangesMadeWhileRescheduling() {
    val f = Fixture(); val editor = AlarmSettingsEditor(f)
    f.afterReschedule = { f.value = f.value.copy(enabled = false, label = "Changed during schedule") }
    val result = editor.setTime(23, 59)
    assertEquals(AlarmConfig.Config(true, 23, 59, "Alarm", setOf(Calendar.MONDAY)), f.scheduled.single())
    assertEquals(AlarmConfig.Config(false, 23, 59, "Changed during schedule", setOf(Calendar.MONDAY)), result)
    assertEquals(result, editor.snapshot)
    assertEquals(listOf("time", "reschedule"), f.trace)
  }

  @Test fun exactTimeAcceptsBothDailyBoundaries() {
    val f = Fixture(); val editor = AlarmSettingsEditor(f)
    assertEquals(0 to 0, editor.setTime(0, 0).let { it.hour to it.minute })
    assertEquals(23 to 59, editor.setTime(23, 59).let { it.hour to it.minute })
    assertEquals(listOf("time", "reschedule", "time", "reschedule"), f.trace)
  }

  @Test fun groupedPersistenceAppliesOnceAndPreservesAllOtherKeys() {
    val prefs = alarmPrefs()
    AlarmConfig.setTime(prefs.context, 8, 17)
    assertEquals(1, prefs.writeCount)
    assertEquals(mapOf("enabled" to false, "hour" to 8, "minute" to 17,
        "label" to "Latest label", "days" to setOf("6"), "unrelated" to "retained"), prefs.all)
  }

  @Test fun groupedPersistenceRejectsInvalidPairWithoutPartialWrite() {
    listOf(-1 to 17, 24 to 17, 8 to -1, 8 to 60).forEach { (hour, minute) ->
      val prefs = alarmPrefs()
      val before = prefs.all
      assertThrows(IllegalArgumentException::class.java) { AlarmConfig.setTime(prefs.context, hour, minute) }
      assertEquals(0, prefs.writeCount)
      assertEquals(before, prefs.all)
    }
  }

  private fun alarmPrefs() = ScreensaverTestPrefs(mapOf("enabled" to false, "hour" to 7,
      "minute" to 30, "label" to "Latest label", "days" to setOf("6"), "unrelated" to "retained")).also {
    Mockito.`when`(it.context.getSharedPreferences("immortal_alarm", Context.MODE_PRIVATE)).thenReturn(it)
  }

  private class Fixture : AlarmSettingsEditorBackend {
    var value = AlarmConfig.Config(true, 7, 30, "Alarm", setOf(Calendar.MONDAY))
    val trace = mutableListOf<String>()
    val scheduled = mutableListOf<AlarmConfig.Config>()
    var readCount = 0
    var afterReschedule: (() -> Unit)? = null
    override fun read(): AlarmConfig.Config { readCount++; return value }
    override fun setEnabled(value: Boolean) { trace += "enabled"; this.value = this.value.copy(enabled = value) }
    override fun setHour(value: Int) { trace += "hour"; this.value = this.value.copy(hour = value) }
    override fun setMinute(value: Int) { trace += "minute"; this.value = this.value.copy(minute = value) }
    override fun setTime(hour: Int, minute: Int) { trace += "time"; value = value.copy(hour = hour, minute = minute) }
    override fun setDays(value: Set<Int>) { trace += "days"; this.value = this.value.copy(days = value) }
    override fun reschedule() { trace += "reschedule"; scheduled += value; afterReschedule?.invoke() }
  }
}
