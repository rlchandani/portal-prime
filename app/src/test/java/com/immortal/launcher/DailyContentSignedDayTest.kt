/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class DailyContentSignedDayTest {
  @Test fun negativePartialUtcDayUsesPreviousDay() = withZone("UTC") {
    assertEquals(-1L, DailyContent.epochDay(-1L))
  }
  @Test fun negativePartialDayBeforeBoundaryFloorsAgain() = withZone("UTC") {
    assertEquals(-2L, DailyContent.epochDay(-DAY - 1))
  }
  @Test fun quoteBeforeEpochIsAvailable() = withZone("UTC") {
    assertEquals(LAST_QUOTE, DailyContent.quoteOfDay(-DAY).text)
  }
  @Test fun wordBeforeEpochIsAvailable() = withZone("UTC") {
    assertEquals("zenith", DailyContent.wordOfDay(-DAY).word)
  }
  @Test fun triviaBeforeEpochIsAvailable() = withZone("UTC") {
    assertEquals("Russia", DailyContent.triviaOfDay(-DAY).answer)
  }
  @Test fun westernZoneAtEpochUsesItsActualLocalDay() = withZone("America/Los_Angeles") {
    assertEquals(-1L, DailyContent.epochDay(0))
    assertEquals(LAST_QUOTE, DailyContent.quoteOfDay(0).text)
  }
  @Test fun westernLocalMidnightChangesTheDayAtTheBoundary() =
      withZone("America/Los_Angeles") {
        assertEquals(-1L, DailyContent.epochDay(8 * HOUR - 1))
        assertEquals(0L, DailyContent.epochDay(8 * HOUR))
      }
  @Test fun epochAndFirstContentsRemainUnchanged() = withZone("UTC") {
    assertEquals(0L, DailyContent.epochDay(0))
    assertEquals(FIRST_QUOTE, DailyContent.quoteOfDay(0).text)
    assertEquals("ephemeral", DailyContent.wordOfDay(0).word)
    assertEquals("Jupiter", DailyContent.triviaOfDay(0).answer)
  }
  @Test fun positiveDayBoundariesRemainUnchanged() = withZone("UTC") {
    assertEquals(0L, DailyContent.epochDay(DAY - 1))
    assertEquals(1L, DailyContent.epochDay(DAY))
    assertEquals(1L, DailyContent.epochDay(2 * DAY - 1))
  }
  @Test fun positiveContentWrapRemainsUnchanged() = withZone("UTC") {
    assertEquals(LAST_QUOTE, DailyContent.quoteOfDay(29 * DAY).text)
    assertEquals(FIRST_QUOTE, DailyContent.quoteOfDay(30 * DAY).text)
    assertEquals("ephemeral", DailyContent.wordOfDay(30 * DAY).word)
    assertEquals("Jupiter", DailyContent.triviaOfDay(30 * DAY).answer)
  }
  @Test fun negativeWholeContentCycleRetainsFirstItems() = withZone("UTC") {
    assertEquals(FIRST_QUOTE, DailyContent.quoteOfDay(-30 * DAY).text)
    assertEquals("ephemeral", DailyContent.wordOfDay(-30 * DAY).word)
    assertEquals("Jupiter", DailyContent.triviaOfDay(-30 * DAY).answer)
  }

  private fun withZone(id: String, block: () -> Unit) {
    val original = TimeZone.getDefault()
    try {
      TimeZone.setDefault(TimeZone.getTimeZone(id))
      block()
    } finally {
      TimeZone.setDefault(original)
    }
  }
  private companion object {
    const val HOUR = 60L * 60 * 1000
    const val DAY = 24 * HOUR
    const val FIRST_QUOTE = "The best way to predict the future is to invent it."
    const val LAST_QUOTE =
        "The harder you work for something, the greater you'll feel when you achieve it."
  }
}
