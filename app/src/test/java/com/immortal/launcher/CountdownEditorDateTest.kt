/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

/** Actual compiled Add callback, with generated preferences and no mounted Compose screen. */
class CountdownEditorDateTest {
  @Test fun february31RetainsTheDraftWithoutSaving() = rejected(2, 31, 2026)
  @Test fun april31RetainsTheDraftWithoutSaving() = rejected(4, 31, 2026)
  @Test fun commonYearFebruary29RetainsTheDraftWithoutSaving() = rejected(2, 29, 2026)
  @Test fun nonLeapCenturyFebruary29RetainsTheDraftWithoutSaving() = rejected(2, 29, 2100)
  @Test fun yearlyFebruary30RetainsTheDraftWithoutSaving() = rejected(2, 30, 2026, yearly = true)
  @Test fun blankNameKeepsItsExistingError() =
      rejected(2, 28, 2026, name = "  ", expectedError = "Enter a name.")
  @Test fun ordinaryFebruary28Saves() = accepted(2, 28, 2026)
  @Test fun leapYearFebruary29Saves() = accepted(2, 29, 2028)
  @Test fun yearlyFebruary29Saves() = accepted(2, 29, 2026, yearly = true)
  @Test fun existingLongNamePolicyRemains() = accepted(6, 1, 2026, name = "x".repeat(81))

  private fun rejected(
      month: Int, day: Int, year: Int, yearly: Boolean = false, name: String = "Draft",
      expectedError: String = "Choose a valid date for this month and year.",
  ) {
    val fixture = Fixture(month, day, year, yearly, name)
    val events = fixture.events.value
    fixture.add()
    assertEquals(0, fixture.writes)
    assertEquals(SAVED, fixture.raw)
    assertEquals(name, fixture.label.value)
    assertEquals("🎂", fixture.emoji.value)
    assertEquals(month, fixture.month.intValue)
    assertEquals(day, fixture.day.intValue)
    assertEquals(year, fixture.year.intValue)
    assertEquals(yearly, fixture.yearly.value)
    assertSame(events, fixture.events.value)
    assertEquals(expectedError, fixture.error.value)
    Mockito.verify(fixture.prefs, Mockito.never()).edit()
  }

  private fun accepted(
      month: Int, day: Int, year: Int, yearly: Boolean = false, name: String = "Draft",
  ) {
    val fixture = Fixture(month, day, year, yearly, name)
    val existing = CountdownConfig.load(fixture.context)
    fixture.add()
    val saved = CountdownConfig.load(fixture.context)
    assertEquals(1, fixture.writes)
    assertEquals(existing, saved.dropLast(1))
    val added = saved.last()
    assertEquals(CountdownConfig.Event(added.id, name, "🎂", month, day,
        if (yearly) 0 else year), added)
    assertEquals("", fixture.label.value)
    assertNull(fixture.error.value)
    assertEquals(CountdownConfig.loadSorted(fixture.context), fixture.events.value)
  }

  private class Fixture(m: Int, d: Int, y: Int, repeat: Boolean, name: String) {
    val context = Mockito.mock(Context::class.java)
    val prefs = Mockito.mock(SharedPreferences::class.java)
    var raw = SAVED
    var writes = 0
    val label = mutableStateOf(name)
    val error = mutableStateOf<String?>("Previous error")
    val emoji = mutableStateOf("🎂")
    val month = mutableIntStateOf(m)
    val day = mutableIntStateOf(d)
    val yearly = mutableStateOf(repeat)
    val year = mutableIntStateOf(y)
    val events: androidx.compose.runtime.MutableState<List<CountdownConfig.Event>>

    init {
      Mockito.`when`(context.getSharedPreferences("immortal_countdown", Context.MODE_PRIVATE))
          .thenReturn(prefs)
      Mockito.`when`(prefs.getString("events", null)).thenAnswer { raw }
      Mockito.`when`(prefs.edit()).thenAnswer {
        val editor = Mockito.mock(SharedPreferences.Editor::class.java, Mockito.RETURNS_SELF)
        var staged: String? = null
        Mockito.`when`(editor.putString(Mockito.eq("events"), Mockito.anyString())).thenAnswer {
          staged = it.getArgument(1)
          editor
        }
        Mockito.doAnswer { raw = checkNotNull(staged); writes++; null }.`when`(editor).apply()
        editor
      }
      events = mutableStateOf(CountdownConfig.loadSorted(context))
    }

    fun add() {
      // The unique Context + eight state parameters identify the actual Add callback.
      // Do not invoke CountdownConfig.add directly or reproduce the editor's validation.
      val callback = Class.forName("com.immortal.launcher.CountdownSettingsActivityKt")
          .declaredMethods.single {
            it.parameterCount == 9 && it.parameterTypes.first() == Context::class.java
          }
      callback.isAccessible = true
      // Kotlin captures states in first-use order; the date guard moves emoji after the date.
      // Bind the two verified signatures explicitly without changing any behavioral assertion.
      val state = MutableState::class.java
      val intState = MutableIntState::class.java
      when (callback.parameterTypes.drop(1)) {
        listOf(state, state, state, intState, intState, state, intState, state) ->
            callback.invoke(null, context, label, error, emoji, month, day, yearly, year, events)
        listOf(state, state, intState, intState, state, intState, state, state) ->
            callback.invoke(null, context, label, error, month, day, yearly, year, emoji, events)
        else -> error("Unexpected compiled countdown Add callback signature")
      }
    }
  }

  private companion object {
    const val SAVED = """[
      {"id":11,"label":"Saved","emoji":"A","month":12,"day":31,"year":2032},
      {"id":13,"label":"Legacy invalid date","emoji":"L","month":4,"day":31,"year":2026}
    ]"""
  }
}
