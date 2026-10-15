/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.Calendar
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

  private class Fixture : AlarmSettingsEditorBackend {
    var value = AlarmConfig.Config(true, 7, 30, "Alarm", setOf(Calendar.MONDAY))
    val trace = mutableListOf<String>()
    val scheduled = mutableListOf<AlarmConfig.Config>()
    override fun read() = value
    override fun setEnabled(value: Boolean) { trace += "enabled"; this.value = this.value.copy(enabled = value) }
    override fun setHour(value: Int) { trace += "hour"; this.value = this.value.copy(hour = value) }
    override fun setMinute(value: Int) { trace += "minute"; this.value = this.value.copy(minute = value) }
    override fun setDays(value: Set<Int>) { trace += "days"; this.value = this.value.copy(days = value) }
    override fun reschedule() { trace += "reschedule"; scheduled += value }
  }
}
