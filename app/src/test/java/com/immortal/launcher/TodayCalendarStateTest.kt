/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.time.ZonedDateTime
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class TodayCalendarStateTest {
  private val utc = TimeZone.getTimeZone("UTC")
  private fun ms(s: String) = ZonedDateTime.parse(s).toInstant().toEpochMilli()
  private val now = ms("2026-10-18T08:00:00Z")
  private fun event(start: String, end: String, allDay: Boolean = false) =
      CalendarFeed.Event(ms(start), ms(end), "Public event", allDay)
  private fun state(event: CalendarFeed.Event?, unavailable: Boolean = false, use24: Boolean = true,
      url: String? = "https://example.test/calendar.ics") = prepareTodayCalendarState(url,
          CalendarFeed.Result(listOfNotNull(event), unavailable), now, use24, utc, Locale.US)

  @Test fun todayIncludesDateAndHonoursBothClockFormats() {
    val e = event("2026-10-18T09:00:00Z", "2026-10-18T10:00:00Z")
    assertEquals("Today · 09:00", state(e).kicker)
    assertEquals("Until 10:00", state(e).detail)
    assertEquals("Today · 9:00 AM", state(e, use24 = false).kicker)
    assertEquals("Until 10:00 AM", state(e, use24 = false).detail)
  }
  @Test fun tomorrowCannotLookLikeTodaysEvent() {
    val e = event("2026-10-19T09:00:00Z", "2026-10-19T10:00:00Z")
    assertEquals("Tomorrow · 09:00", state(e).kicker)
  }
  @Test fun allDayUsesDateOnceAndAllDayDetailOnce() {
    val e = event("2026-10-19T00:00:00Z", "2026-10-20T00:00:00Z", true)
    assertEquals("Tomorrow", state(e).kicker)
    assertEquals("All-day", state(e).detail)
  }
  @Test fun distantFutureIncludesCalendarDateAndYearWhenNeeded() {
    val e = event("2027-01-04T09:00:00Z", "2027-01-04T10:00:00Z")
    assertEquals("Mon, Jan 4, 2027 · 09:00", prepareTodayCalendarState(
        "https://example.test/calendar.ics", CalendarFeed.Result(listOf(e)),
        ms("2026-12-18T08:00:00Z"), true, utc, Locale.US).kicker)
  }
  @Test fun overnightRunningEventHasTruthfulCurrentDayContext() {
    val e = event("2026-10-17T23:00:00Z", "2026-10-18T09:00:00Z")
    assertEquals("Today · In progress", state(e).kicker)
    assertEquals("Until 09:00", state(e).detail)
  }
  @Test fun eventEndingOnADifferentDayNamesThatDay() {
    val e = event("2026-10-18T23:00:00Z", "2026-10-19T09:00:00Z")
    assertEquals("Until Tomorrow · 09:00", state(e).detail)
  }
  @Test fun unavailableEmptyFeedDoesNotClaimNothingIsScheduled() {
    val s = state(null, unavailable = true)
    assertTrue(s.unavailable)
    assertEquals("Calendar unavailable", s.message)
  }
  @Test fun successfulEmptyFeedHasNormalEmptyMessage() {
    assertEquals("No upcoming events", state(null).message)
    assertFalse(state(null).unavailable)
  }
  @Test fun unavailableFeedKeepsUsableEventAndMarksItSaved() {
    val e = event("2026-10-18T09:00:00Z", "2026-10-18T10:00:00Z")
    val s = state(e, unavailable = true)
    assertEquals(e, s.event)
    assertTrue(s.unavailable)
    assertEquals("Saved event · Until 10:00", s.detail)
  }
  @Test fun changedSourceClearsPriorRowsAndShowsLoading() {
    val e = event("2026-10-18T09:00:00Z", "2026-10-18T10:00:00Z")
    val s = state(e).forSource("https://example.test/new.ics")
    assertNull(s.event)
    assertTrue(s.refreshing)
    assertEquals("Loading calendar…", s.message)
    assertEquals("https://example.test/new.ics", s.url)
  }
  @Test fun sameSourceRefreshRetainsEventButProgressIsTruthful() {
    val e = event("2026-10-18T09:00:00Z", "2026-10-18T10:00:00Z")
    val s = state(e, unavailable = true)
    assertEquals(s, s.forSource(s.url))
    assertEquals(e, s.copy(refreshing = true).event)
    assertTrue(s.copy(refreshing = true).refreshing)
  }
  @Test fun removingSourceShowsSetupEvenIfAnOldResultExists() {
    val e = event("2026-10-18T09:00:00Z", "2026-10-18T10:00:00Z")
    val s = state(e, url = null)
    assertNull(s.event)
    assertEquals("Add your calendar", s.message)
  }
  @Test fun exclusiveEndAndExpiredRowsDoNotBecomeNextEvent() {
    val ended = event("2026-10-17T23:00:00Z", "2026-10-18T08:00:00Z")
    assertNull(state(ended).event)
  }
  @Test fun retryCoalescesAndItsForceFlagCanOnlyBeConsumedOnce() {
    val s = state(null, unavailable = true)
    val gate = TodayCalendarRefreshGate()
    assertTrue(gate.request(s, s.url))
    assertFalse(gate.request(s, s.url))
    assertEquals(1, gate.revision)
    assertTrue(gate.pending(s.url))
    assertTrue(gate.consume(s.url))
    assertFalse(gate.consume(s.url))
    assertFalse(gate.pending(s.url))
  }
  @Test fun changedSourceRetiresAnOldPendingForceFlag() {
    val s = state(null, unavailable = true)
    val gate = TodayCalendarRefreshGate()
    assertTrue(gate.request(s, s.url))
    assertFalse(gate.consume("https://example.test/new.ics"))
    assertFalse(gate.consume(s.url))
  }
  @Test fun healthyLoadingMissingAndMismatchedSourcesCannotRetry() {
    val gate = TodayCalendarRefreshGate()
    assertFalse(gate.request(state(null), "https://example.test/calendar.ics"))
    val failed = state(null, unavailable = true)
    assertFalse(gate.request(failed.copy(refreshing = true), failed.url))
    assertFalse(gate.request(failed, null))
    assertFalse(gate.request(failed, "https://example.test/new.ics"))
    assertEquals(0, gate.revision)
  }
  @Test fun completedRequestAllowsOneSubsequentRetryWithoutPersistingAnything() {
    val s = state(null, unavailable = true)
    val gate = TodayCalendarRefreshGate()
    assertTrue(gate.request(s, s.url))
    assertTrue(gate.consume(s.url))
    assertTrue(gate.request(s, s.url))
    assertEquals(2, gate.revision)
    assertTrue(gate.consume(s.url))
  }

}
