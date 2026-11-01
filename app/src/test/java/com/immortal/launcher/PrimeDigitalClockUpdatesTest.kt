/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.view.View
import com.immortal.launcher.ui.clock.PrimeDigitalClockFaceView
import com.immortal.launcher.ui.clock.DigitalClockTimeFormat
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.SimpleTimeZone
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

/** Runs the shipped update method; only the Android drawing boundary is replaced. */
class PrimeDigitalClockUpdatesTest {
  @Test fun configuredSeparatorsAndLeadingZeroAreRenderedByTheActualUpdatePath() {
    for (format in listOf("12h", "24h")) {
      for (zero in listOf(false, true)) {
        for ((separator, character) in listOf(
            Separator.COLON to ":", Separator.DOT to ".", Separator.NONE to "", Separator.BLINK to ":",
        )) {
          fixture(ClockSpec(format = format, leadingZero = zero, separator = separator)) { f ->
            f.at("2030-01-01T01:07:00Z")
            assertEquals((if (zero) "01" else "1") + character + "07", f.displayed())
          }
        }
      }
    }
  }

  @Test fun optionalMeridiemFollowsNoonMidnightAndRetainedTimezoneChanges() =
      fixture(ClockSpec(showAmPm = true)) { f ->
        for ((instant, expected) in listOf(
            "2030-01-01T00:07:00Z" to "12:07 AM",
            "2030-01-01T12:07:00Z" to "12:07 PM",
            "2030-01-01T13:07:00Z" to "1:07 PM",
        )) {
          f.at(instant)
          assertEquals(expected, f.displayed())
        }
        TimeZone.setDefault(TimeZone.getTimeZone("GMT-02:00"))
        f.at("2030-01-01T13:07:00Z")
        assertEquals("11:07 AM", f.displayed())
      }

  @Test fun blinkingMeridiemKeepsAccessibilityStableWhileItsColonChanges() =
      fixture(ClockSpec(showAmPm = true, leadingZero = false, separator = Separator.BLINK)) { f ->
        f.at("2030-01-01T01:07:00Z", true)
        assertEquals("1:07 AM", f.displayed())
        f.at("2030-01-01T01:07:01Z", false)
        assertEquals("1 07 AM", f.displayed())
        assertEquals("1:07 AM", f.get("accessibleTime"))
        assertEquals(2, f.invalidations)
      }

  @Test fun formatOptionsPreserveLocalizedDigitsAndMeridiemOnLocaleChange() =
      fixture(ClockSpec(showAmPm = true, separator = Separator.DOT)) { f ->
        f.at("2030-01-01T13:07:00Z")
        assertEquals("1.07 PM", f.displayed())
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
        f.at("2030-01-01T13:07:00Z")
        assertEquals("١.٠٧ م", f.displayed())
        assertEquals(2, f.invalidations)
      }

  @Test fun meridiemKeepsMinuteCadenceAndTheSameCachedFormatter() =
      fixture(ClockSpec(showAmPm = true)) { f ->
        val formatter = f.get("hourMinFmt")
        repeat(60) { f.update(it) }
        assertEquals("12:34 PM", f.displayed())
        assertSame(formatter, f.get("hourMinFmt"))
        assertEquals(1, f.invalidations)
        f.update(60)
        assertEquals("12:35 PM", f.displayed())
        assertEquals(2, f.invalidations)
      }

  @Test fun secondsRemainAnArcWhenMeridiemIsEnabled() =
      fixture(ClockSpec(showSeconds = true, showAmPm = true, separator = Separator.DOT)) { f ->
        f.update(37)
        assertEquals("12.34 PM", f.displayed())
        assertEquals(37, f.get("currentSeconds"))
      }

  @Test fun steadyMinuteClockDrawsOnceAcrossSixtyTicksAndStillAdvancesAtTheMinute() = fixture { f ->
    repeat(60) { f.update(it) }
    assertEquals("12:34", f.displayed())
    assertEquals(1, f.invalidations)
    f.update(60)
    assertEquals("12:35", f.displayed())
    assertEquals(2, f.invalidations)
  }

  @Test fun hiddenSecondsAreNotFormatted() = fixture { f ->
    f.update(0)
    val seconds = Mockito.mock(SimpleDateFormat::class.java)
    Mockito.`when`(seconds.format(Mockito.any(Date::class.java))).thenReturn("00")
    f.set("secondsFmt", seconds)
    repeat(59) { f.update(it + 1) }
    Mockito.verifyNoInteractions(seconds)
    assertEquals(1, f.invalidations)
  }

  @Test fun enabledSecondsKeepTheArcUpdatingEachSecond() = fixture(ClockSpec(showSeconds = true)) { f ->
    repeat(60) { f.update(it) }
    assertEquals(60, f.invalidations)
    assertEquals(59, f.get("currentSeconds"))
    f.update(59)
    assertEquals(60, f.invalidations) // Repeated delivery of the same second needs no new draw.
  }

  @Test fun blinkingSeparatorStillChangesEveryTick() = fixture(ClockSpec(separator = Separator.BLINK)) { f ->
    repeat(60) { second ->
      f.update(second)
      assertEquals(if (second % 2 == 0) "12:34" else "12 34", f.displayed())
    }
    assertEquals(60, f.invalidations)
  }

  @Test fun accessibleTimeUpdatesAtTheMinuteRatherThanOnEveryBlink() =
      fixture(ClockSpec(separator = Separator.BLINK)) { f ->
        repeat(60) { f.update(it) }
        assertEquals("12:34", f.get("accessibleTime"))
        f.update(60)
        assertEquals("12:35", f.get("accessibleTime"))
      }

  @Test fun timezoneChangesRefreshTheRetainedClockWithoutRecreatingItsView() = fixture { f ->
    f.update(0)
    TimeZone.setDefault(TimeZone.getTimeZone("GMT+05:30"))
    f.update(0)
    assertEquals("6:04", f.displayed())
    assertEquals(2, f.invalidations)
  }

  @Test fun localeChangesRefreshDigitsWithoutRecreatingItsView() = fixture { f ->
    f.update(0)
    Locale.setDefault(Locale.forLanguageTag("ar-EG"))
    f.update(0)
    assertEquals("١٢:٣٤", f.displayed())
    assertEquals(2, f.invalidations)
  }

  @Test fun twentyFourHourClockPreservesItsExistingFormat() =
      fixture(ClockSpec(format = "24h")) { f ->
        TimeZone.setDefault(TimeZone.getTimeZone("GMT+05:30"))
        f.update(0)
        assertEquals("18:04", f.displayed())
      }

  @Test fun retainedClockTracksBothDstTransitions() = fixture { f ->
    TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
    val cases = listOf(
        "2026-03-08T06:59:59Z" to "1:59",
        "2026-03-08T07:00:00Z" to "3:00",
        "2026-11-01T05:59:59Z" to "1:59",
        "2026-11-01T06:00:00Z" to "1:00",
    )
    cases.forEach { (time, expected) ->
      f.clock.update(Date.from(Instant.parse(time)), true)
      assertEquals(expected, f.displayed())
    }
    assertEquals(4, f.invalidations)
  }

  @Test fun sameIdAndRawOffsetWithDifferentDstRulesRefreshTheRetainedClock() =
      fixture(ClockSpec(format = "24h")) { f ->
        val now = Date.from(Instant.parse("2026-07-01T12:00:00Z"))
        listOf(Calendar.MARCH to "13:00", Calendar.AUGUST to "12:00").forEach { (month, expected) ->
          TimeZone.setDefault(SimpleTimeZone(
              0, "SameId", month, 1, 0, 0, Calendar.NOVEMBER, 1, 0, 0))
          f.clock.update(now, true)
          assertEquals(expected, f.displayed())
        }
        assertEquals(2, f.invalidations)
      }

  private class Fixture(spec: ClockSpec) {
    val clock = Mockito.mock(PrimeDigitalClockFaceView::class.java, Mockito.CALLS_REAL_METHODS)
    private val view = Mockito.mock(View::class.java)
    var invalidations = 0
      private set
    private val start = 1_893_501_240_000L // 2030-01-01 12:34:00 UTC.

    init {
      set("spec", spec)
      set("hourMinFmt", SimpleDateFormat(DigitalClockTimeFormat.pattern(spec), Locale.US))
      set("secondsFmt", SimpleDateFormat("ss", Locale.US))
      set("currentHourMin", "--:--")
      // Available after the fix; the baseline has no live-locale state.
      PrimeDigitalClockFaceView::class.java.declaredFields.firstOrNull {
        it.name == "formatterLocale"
      }?.apply { isAccessible = true; set(clock, Locale.US) }
      Mockito.doReturn(view).`when`(clock).view
      Mockito.doAnswer { invalidations++; null }.`when`(view).invalidate()
    }

    fun update(seconds: Int) = clock.update(Date(start + seconds * 1_000L), seconds % 2 == 0)
    fun at(instant: String, blink: Boolean = true) = clock.update(Date.from(Instant.parse(instant)), blink)
    fun displayed(): String = get("currentHourMin") as String
    fun get(name: String): Any? = field(name).get(clock)
    fun set(name: String, value: Any) = field(name).set(clock, value)
    private fun field(name: String) =
        PrimeDigitalClockFaceView::class.java.getDeclaredField(name).apply { isAccessible = true }
  }

  private fun fixture(spec: ClockSpec = ClockSpec(), block: (Fixture) -> Unit) {
    val zone = TimeZone.getDefault()
    val locale = Locale.getDefault()
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    Locale.setDefault(Locale.US)
    try { block(Fixture(spec)) }
    finally {
      TimeZone.setDefault(zone)
      Locale.setDefault(locale)
    }
  }
}
