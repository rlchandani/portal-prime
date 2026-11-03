/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarCancelledStatusTest {
  private val now = 1_700_000_000_000L // 2023-11-14T22:13:20Z

  private fun event(title: String, properties: String = "",
      dates: String = "DTSTART:20231115T140000Z\nDTEND:20231115T150000Z") =
      "BEGIN:VEVENT\nUID:$title@example.test\nDTSTAMP:20231114T000000Z\n" +
          "$dates\nSUMMARY:$title\n$properties\nEND:VEVENT\n"

  private fun feed(events: String) =
      "BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//Immortal//Fixture//EN\n${events}END:VCALENDAR"

  private fun titles(source: String) = CalendarFeed.parse(source, now).map { it.title }

  @Test fun cancelledTimedEventIsOmittedWithoutHidingItsActiveNeighbour() {
    assertEquals(listOf("Active"), titles(feed(
        event("Cancelled", "STATUS:CANCELLED") + event("Active", "STATUS:CONFIRMED"))))
  }

  @Test fun cancelledAllDayEventIsOmitted() {
    assertTrue(CalendarFeed.parse(feed(event("Cancelled all day", "STATUS:CANCELLED",
        "DTSTART;VALUE=DATE:20231115\nDTEND;VALUE=DATE:20231116")), now).isEmpty())
  }

  @Test fun cancelledRecurringMasterDoesNotGenerateOccurrences() {
    assertEquals(listOf("Active"), titles(feed(event("Cancelled series",
        "RRULE:FREQ=DAILY;COUNT=3\nSTATUS:CANCELLED") + event("Active"))))
  }

  @Test fun cancellationHonoursCaseParametersAndLineUnfolding() {
    val events = event("Lowercase", "status:cancelled") +
        event("Parameter", "STATUS;X-FIXTURE=1:CANCELLED") +
        event("Folded", "STATUS:CAN\r\n CELLED") +
        event("Status first").replace("BEGIN:VEVENT\n", "BEGIN:VEVENT\nSTATUS:CANCELLED\n") +
        event("Active")
    assertEquals(listOf("Active"), titles(feed(events)))
  }

  @Test fun absentConfirmedTentativeAndUnknownStatusesRetainTheirEvents() {
    val events = event("Absent") + event("Confirmed", "STATUS:CONFIRMED") +
        event("Tentative", "STATUS:TENTATIVE") + event("Unknown", "STATUS:X-FIXTURE")
    // Scheduling METHOD and mentions in text are not VEVENT status properties.
    assertEquals(listOf("Absent", "Confirmed", "Tentative", "Unknown", "STATUS:CANCELLED"),
        titles(feed(events + event("STATUS:CANCELLED")).replace("VERSION:2.0",
            "VERSION:2.0\nMETHOD:CANCEL")))
  }

  @Test fun cancelledEventsCannotConsumeTheOccurrenceCap() {
    val events = (1..501).joinToString("") { event("Cancelled $it", "STATUS:CANCELLED") }
    assertEquals(listOf("Active"), titles(feed(events + event("Active"))))
  }

  @Test fun changedStatusAndWarmPreparedReadsStayCorrect() {
    val active = feed(event("Appointment", "STATUS:CONFIRMED"))
    val cancelled = active.replace("STATUS:CONFIRMED", "STATUS:CANCELLED")
    assertEquals(listOf("Appointment"), titles(active))
    repeat(2) { assertTrue(CalendarFeed.parse(cancelled, now).isEmpty()) }
    assertEquals(listOf("Appointment"), titles(active))
  }

  @Test fun successfulCancellationReplacesActiveRecoveryData() {
    var response: String? = feed(event("Appointment", "STATUS:CONFIRMED"))
    val reader = CalendarFeedReader({ response })
    val url = "https://example.test/cancelled.ics"
    assertEquals(listOf("Appointment"), reader.read(url, now, 45).events.map { it.title })
    response = feed(event("Appointment", "STATUS:CANCELLED"))
    val cancelled = reader.read(url, now, 45, forceRefresh = true)
    assertFalse(cancelled.unavailable)
    assertTrue(cancelled.events.isEmpty())
    response = null
    val recovery = reader.read(url, now, 45, forceRefresh = true)
    assertTrue(recovery.unavailable)
    assertTrue(recovery.events.isEmpty())
  }
}
