/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale
import java.util.concurrent.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

/** Real Config readers and JSON parsers run; only Android preference storage is replaced. */
class ApprovedToolsSummariesTest {
  private var previousLocale = Locale.getDefault()

  @Before fun useStableLocale() {
    previousLocale = Locale.getDefault()
    Locale.setDefault(Locale.US)
  }

  @After fun restoreLocale() {
    Locale.setDefault(previousLocale)
  }

  @Test fun missingPreferencesReturnAllFiveEmptySummaries() {
    assertEquals(
        mapOf(
            "alarm" to "Off",
            "timers" to "Ready when you are",
            "countdowns" to "0 saved",
            "camera" to "0 saved",
            "notes" to "Leave a message",
        ),
        load(Fixture()),
    )
  }

  @Test fun populatedPreferencesRespectDeadlinesPluralityAlarmDaysAndBlankNotes() {
    val cases = listOf(
        HealthyCase("populated", {}, POPULATED_SUMMARIES),
        HealthyCase("repeating alarm", {
          `when`(it.alarm.getStringSet("days", null)).thenReturn(setOf("2", "6", "invalid"))
        }, POPULATED_SUMMARIES + ("alarm" to "07:05 · Repeating")),
        HealthyCase("unparseable repeat days", {
          `when`(it.alarm.getStringSet("days", null)).thenReturn(setOf("invalid"))
        }, POPULATED_SUMMARIES),
        HealthyCase("disabled saved alarm", {
          `when`(it.alarm.getBoolean("enabled", false)).thenReturn(false)
        }, POPULATED_SUMMARIES + ("alarm" to "Off")),
        HealthyCase("two future timers", {
          `when`(it.timers.getString("timers", null)).thenReturn(TWO_RUNNING_TIMERS)
        }, POPULATED_SUMMARIES + ("timers" to "2 timers running")),
        HealthyCase("past and exactly due timers", {
          `when`(it.timers.getString("timers", null)).thenReturn(NO_RUNNING_TIMERS)
        }, POPULATED_SUMMARIES + ("timers" to "Ready when you are")),
        HealthyCase("whitespace note", {
          `when`(it.notes.getString("text_note", "")).thenReturn(" \t\n ")
        }, POPULATED_SUMMARIES + ("notes" to "Leave a message")),
        HealthyCase("null note", {
          `when`(it.notes.getString("text_note", "")).thenReturn(null)
        }, POPULATED_SUMMARIES + ("notes" to "Leave a message")),
    )
    cases.forEach { case ->
      val fixture = Fixture(populated = true)
      case.configure(fixture)
      assertEquals(case.name, case.expected, load(fixture))
    }
  }

  @Test fun wrongTypedCountdownEventsMakeOnlyCountdownsUnavailable() {
    val fixture = Fixture(populated = true)
    doThrow(ClassCastException("events contains an integer"))
        .`when`(fixture.countdowns).getString("events", null)

    assertEquals(POPULATED_SUMMARIES + ("countdowns" to "Unavailable"), load(fixture))
  }

  @Test fun wrongTypedAlarmDaysDoNotPreventOtherFeaturesFromLoading() {
    val fixture = Fixture(populated = true)
    doThrow(ClassCastException("days contains a string"))
        .`when`(fixture.alarm).getStringSet("days", null)

    assertEquals(POPULATED_SUMMARIES + ("alarm" to "Unavailable"), load(fixture))
  }

  @Test fun wrongTypedTimersMakeOnlyTimersUnavailable() {
    val fixture = Fixture(populated = true)
    doThrow(ClassCastException("timers contains a boolean"))
        .`when`(fixture.timers).getString("timers", null)

    assertEquals(POPULATED_SUMMARIES + ("timers" to "Unavailable"), load(fixture))
  }

  @Test fun wrongTypedCamerasMakeOnlyCameraUnavailable() {
    val fixture = Fixture(populated = true)
    doThrow(ClassCastException("cameras contains an integer"))
        .`when`(fixture.cameras).getString("cameras", null)

    assertEquals(POPULATED_SUMMARIES + ("camera" to "Unavailable"), load(fixture))
  }

  @Test fun notesReadOrPreferenceAcquisitionFailurePreservesEarlierSummaries() {
    val readFailure = Fixture(populated = true)
    doThrow(ClassCastException("text_note contains an integer"))
        .`when`(readFailure.notes).getString("text_note", "")
    assertEquals(POPULATED_SUMMARIES + ("notes" to "Unavailable"), load(readFailure))

    val acquisitionFailure = Fixture(populated = true)
    doThrow(SecurityException("Notes preferences unavailable"))
        .`when`(acquisitionFailure.context)
        .getSharedPreferences("immortal_notes", Context.MODE_PRIVATE)
    assertEquals(POPULATED_SUMMARIES + ("notes" to "Unavailable"), load(acquisitionFailure))
  }

  @Test fun malformedJsonRetainsExistingConfigEmptyFallbacks() {
    val timers = Fixture(populated = true)
    `when`(timers.timers.getString("timers", null)).thenReturn("not-json")
    assertEquals(POPULATED_SUMMARIES + ("timers" to "Ready when you are"), load(timers))

    val countdowns = Fixture(populated = true)
    `when`(countdowns.countdowns.getString("events", null)).thenReturn("not-json")
    assertEquals(POPULATED_SUMMARIES + ("countdowns" to "0 saved"), load(countdowns))

    val cameras = Fixture(populated = true)
    `when`(cameras.cameras.getString("cameras", null)).thenReturn("not-json")
    assertEquals(POPULATED_SUMMARIES + ("camera" to "0 saved"), load(cameras))
  }

  @Test fun multipleReadFailuresPreserveEverySuccessfulFeature() {
    val fixture = Fixture(populated = true)
    doThrow(ClassCastException("events contains an integer"))
        .`when`(fixture.countdowns).getString("events", null)
    doThrow(ClassCastException("text_note contains an integer"))
        .`when`(fixture.notes).getString("text_note", "")

    assertEquals(
        mapOf(
            "alarm" to "07:05 · Once",
            "timers" to "1 timer running",
            "countdowns" to "Unavailable",
            "camera" to "1 saved",
            "notes" to "Unavailable",
        ),
        load(fixture),
    )
  }

  @Test fun cancellationFromEveryFeatureEscapesWithItsIdentityIntact() {
    val boundaries: List<Pair<String, (Fixture, CancellationException) -> Unit>> = listOf(
        "alarm days" to { fixture, cancelled ->
          doThrow(cancelled).`when`(fixture.alarm).getStringSet("days", null)
          Unit
        },
        "timers" to { fixture, cancelled ->
          doThrow(cancelled).`when`(fixture.timers).getString("timers", null)
          Unit
        },
        "countdown events" to { fixture, cancelled ->
          doThrow(cancelled).`when`(fixture.countdowns).getString("events", null)
          Unit
        },
        "cameras" to { fixture, cancelled ->
          doThrow(cancelled).`when`(fixture.cameras).getString("cameras", null)
          Unit
        },
        "note text" to { fixture, cancelled ->
          doThrow(cancelled).`when`(fixture.notes).getString("text_note", "")
          Unit
        },
        "preference acquisition" to { fixture, cancelled ->
          doThrow(cancelled).`when`(fixture.context)
              .getSharedPreferences("immortal_countdown", Context.MODE_PRIVATE)
          Unit
        },
    )
    boundaries.forEach { (name, inject) ->
      val fixture = Fixture(populated = true)
      val cancelled = CancellationException("Cancelled during $name")
      inject(fixture, cancelled)
      try {
        load(fixture)
        fail("$name cancellation must escape instead of returning summaries")
      } catch (actual: CancellationException) {
        assertSame(name, cancelled, actual)
      }
    }
  }

  private fun load(fixture: Fixture): Map<String, String> =
      try {
        ApprovedToolsSummaries.load(fixture.context, nowMillis = 1_000L)
      } finally {
        fixture.verifyNoPreferenceEdits()
      }

  private data class HealthyCase(
      val name: String,
      val configure: (Fixture) -> Unit,
      val expected: Map<String, String>,
  )

  private class Fixture(populated: Boolean = false) {
    val context = mock(Context::class.java)
    val alarm = mock(SharedPreferences::class.java)
    val timers = mock(SharedPreferences::class.java)
    val countdowns = mock(SharedPreferences::class.java)
    val cameras = mock(SharedPreferences::class.java)
    val notes = mock(SharedPreferences::class.java)

    init {
      `when`(context.getSharedPreferences("immortal_alarm", Context.MODE_PRIVATE)).thenReturn(alarm)
      `when`(context.getSharedPreferences("immortal_timers", Context.MODE_PRIVATE)).thenReturn(timers)
      `when`(context.getSharedPreferences("immortal_countdown", Context.MODE_PRIVATE)).thenReturn(countdowns)
      `when`(context.getSharedPreferences("immortal_cameras", Context.MODE_PRIVATE)).thenReturn(cameras)
      `when`(context.getSharedPreferences("immortal_notes", Context.MODE_PRIVATE)).thenReturn(notes)

      // Exact getters/defaults used by the real Config APIs, including fields not displayed.
      `when`(alarm.getStringSet("days", null)).thenReturn(null)
      `when`(alarm.getBoolean("enabled", false)).thenReturn(populated)
      `when`(alarm.getInt("hour", 7)).thenReturn(7)
      `when`(alarm.getInt("minute", 0)).thenReturn(if (populated) 5 else 0)
      `when`(alarm.getString("label", "Alarm")).thenReturn("Alarm")
      `when`(timers.getString("timers", null)).thenReturn(if (populated) ONE_RUNNING_TIMER else null)
      `when`(countdowns.getString("events", null)).thenReturn(if (populated) SAVED_EVENTS else null)
      `when`(cameras.getString("cameras", null)).thenReturn(if (populated) SAVED_CAMERAS else null)
      `when`(notes.getString("text_note", "")).thenReturn(if (populated) "Buy milk" else "")
    }

    fun verifyNoPreferenceEdits() {
      listOf(alarm, timers, countdowns, cameras, notes).forEach { verify(it, never()).edit() }
    }
  }

  private companion object {
    val POPULATED_SUMMARIES = mapOf(
        "alarm" to "07:05 · Once",
        "timers" to "1 timer running",
        "countdowns" to "2 saved",
        "camera" to "1 saved",
        "notes" to "A note is waiting",
    )
    const val ONE_RUNNING_TIMER = """[
      {"id":"past","label":"Past","dur":1000,"end":999},
      {"id":"due","label":"Due","dur":1000,"end":1000},
      {"id":"live","label":"Live","dur":1000,"end":1001}
    ]"""
    const val TWO_RUNNING_TIMERS = """[
      {"id":"past","label":"Past","dur":1000,"end":999},
      {"id":"due","label":"Due","dur":1000,"end":1000},
      {"id":"live","label":"Live","dur":1000,"end":1001},
      {"id":"other","label":"Other","dur":1000,"end":2000}
    ]"""
    const val NO_RUNNING_TIMERS = """[
      {"id":"past","label":"Past","dur":1000,"end":999},
      {"id":"due","label":"Due","dur":1000,"end":1000}
    ]"""
    const val SAVED_EVENTS = """[
      {"id":1,"label":"Birthday","emoji":"Cake","month":1,"day":2,"year":0},
      {"id":2,"label":"Trip","emoji":"Travel","month":10,"day":15,"year":2026}
    ]"""
    const val SAVED_CAMERAS = """[
      {"id":1,"name":"Door","url":"rtsp://camera.local/live"},
      {"id":2,"name":"Blank","url":"  "},
      {"id":3,"name":"Missing URL"}
    ]"""
  }
}
