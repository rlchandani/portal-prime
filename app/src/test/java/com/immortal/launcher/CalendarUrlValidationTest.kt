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

class CalendarUrlValidationTest {

  @Test
  fun googleHosts_requireCalendarPathOnlyForGeneralGoogleDomain() {
    listOf(
        "https://calendar.google.com/feed",
        "https://shared.calendar.google.com/feed",
        "https://google.com/calendar",
        "https://www.google.com/calendar/events",
        "https://calendar.google.com./feed",
    ).forEach { assertSupported(it, "Google Calendar") }

    listOf(
        "https://google.com/",
        "https://google.com/calendarish",
        "https://google.com/Calendar/events",
        "https://www.google.com/CALENDAR",
    ).forEach { assertUnsupported(it) }
    // A generic ICS endpoint remains usable even when it is not a Google calendar route.
    assertSupported("https://google.com/Calendar/feed.ics", "Calendar feed")
  }

  @Test
  fun appleHosts_acceptExtensionlessPublishedCalendarIdentifiers() {
    listOf(
        "webcal://p01-caldav.icloud.com/published/2/synthetic-calendar-id",
        "https://p01-caldav.icloud.com/published/2/synthetic-calendar-id",
        "https://icloud.com/published/calendar",
        "https://apple.com/calendar/published",
        "https://calendar.apple.com/published/calendar",
        "https://icloud.com./published/calendar",
    ).forEach { assertSupported(it, "Apple iCloud") }
  }

  @Test
  fun genericIcs_requiresPathOrQueryMarkerRatherThanFragment() {
    assertSupported("https://example.com/calendars/events.ics", "Calendar feed")
    assertSupported("http://example.com/feed.ICS/download", "Calendar feed")
    assertUnsupported("https://example.com/feed#events.ics")
    assertUnsupported("https://example.com/feed#https://calendar.google.com/calendar/events.ics")
    assertUnsupported("https://example.com/feed?format=calendar#events.ics")
  }

  @Test
  fun queryIcs_acceptsDecodedMarkerWithoutAnIcsPath() {
    assertSupported("https://example.com/export?file=events.ics", "Calendar feed")
    assertSupported("https://example.com/export?file=events%2E%69%63%73", "Calendar feed")
    assertSupported("http://example.com/export?file=EVENTS.ICS&download=1", "Calendar feed")
    assertUnsupported("https://example.com/export?format=ical")
  }

  @Test
  fun encodedPathIcs_isRecognizedAfterDecoding() {
    assertSupported("https://example.com/calendar/events%2Eics", "Calendar feed")
    assertSupported("https://example.com/calendar/events.%69%63%73", "Calendar feed")
    assertSupported("https://example.com/calendar%20name/events%2EICS", "Calendar feed")
    assertUnsupported("https://example.com/calendar/events%252Eics")
  }

  @Test
  fun localAddresses_acceptIpv4LocalhostIpv6AndValidPortBoundaries() {
    listOf(
        "http://192.168.1.42/calendar.ics",
        "http://192.168.1.42:8080/calendar.ics",
        "http://localhost/calendar.ics",
        "http://localhost:1/calendar.ics",
        "https://localhost:65535/calendar.ics",
        "http://[::1]/calendar.ics",
        "http://[2001:db8::1]:8080/calendar.ics",
    ).forEach { assertSupported(it, "Calendar feed") }
  }

  @Test
  fun trimAndCase_acceptOuterWhitespaceAndCaseInsensitiveSchemeHostAndIcs() {
    assertSupported(" \tHTTPS://CALENDAR.GOOGLE.COM/feed\r\n", "Google Calendar")
    assertSupported("  HTTPS://P01-CALDAV.ICLOUD.COM/published/calendar  ", "Apple iCloud")
    assertSupported(" \nHTTP://EXAMPLE.COM/EVENTS.ICS\t ", "Calendar feed")
    assertSupported("  WeBcAl://EXAMPLE.COM/extensionless  ", "Calendar feed")
  }

  @Test
  fun malformedUrls_rejectMissingHostInnerWhitespaceControlsAndInvalidEscapes() {
    listOf(
        "",
        "   ",
        "https:///calendar.ics",
        "https://?file=calendar.ics",
        "webcal:///published/calendar",
        "https://exa mple.com/calendar.ics",
        "https://example.com/calendar feed.ics",
        "https://example.com/feed.ics?name=two words",
        "https://example.com/cal\tendar.ics",
        "https://example.com/cal\nendar.ics",
        "https://example.com/cal\rendar.ics",
        "https://example.com/cal\u0000endar.ics",
        "https://example.com/cal\u007Fendar.ics",
        "https://example.com/calendar.ics%",
        "https://example.com/calendar.ics%2",
        "https://example.com/calendar.ics%GG",
        "https://example.com/feed.ics?name=%ZZ",
    ).forEach { assertUnsupported(it) }
  }

  @Test
  fun schemes_rejectUnsupportedRelativeAndOpaqueUrisEvenWithCalendarMarkers() {
    listOf(
        "ftp://example.com/calendar.ics",
        "file://localhost/calendar.ics",
        "javascript://calendar.google.com/calendar",
        "mailto:calendar@example.com?file=events.ics",
        "https:calendar.google.com/calendar.ics",
        "http:example.com/calendar.ics",
        "webcal:example.com/calendar.ics",
        "//example.com/calendar.ics",
        "/calendar.ics",
        "example.com/calendar.ics",
    ).forEach { assertUnsupported(it) }
  }

  @Test
  fun ports_rejectZeroNegativeOutOfRangeAndNonNumericAuthorities() {
    listOf(
        "https://example.com:0/calendar.ics",
        "https://example.com:-1/calendar.ics",
        "https://example.com:65536/calendar.ics",
        "https://example.com:999999999999/calendar.ics",
        "https://example.com:abc/calendar.ics",
        "webcal://p01-caldav.icloud.com:0/published/calendar",
        "https://calendar.google.com:65536/feed",
        "http://[::1]:65536/calendar.ics",
    ).forEach { assertUnsupported(it) }
  }

  @Test
  fun providerSpoofs_cannotSupplySupportOrLabelsThroughUnrelatedUrlComponents() {
    listOf(
        "https://calendar.google.com.evil.example/feed",
        "https://evilgoogle.com/calendar",
        "https://evilcalendar.google.com/feed",
        "https://icloud.com.evil.example/published/calendar",
        "https://evilicloud.com/published/calendar",
        "https://apple.com.evil.example/published/calendar",
        "https://evilapple.com/published/calendar",
        "https://example.com/calendar.google.com/calendar",
        "https://example.com/icloud.com/published/calendar",
        "https://example.com/feed?provider=calendar.google.com/calendar",
        "https://example.com/feed?provider=icloud.com",
        "https://calendar.google.com@example.com/feed",
        "https://icloud.com@example.com/feed",
        "https://events.ics@example.com/feed",
    ).forEach { assertUnsupported(it) }

    // Independently supported feeds on spoof hosts must retain the generic label.
    listOf(
        "https://calendar.google.com.evil.example/events.ics",
        "https://icloud.com.evil.example/events.ics",
        "https://apple.com.evil.example/events.ics",
        "https://example.com/calendar.google.com/events.ics",
        "https://example.com/events.ics?provider=icloud.com",
        "https://calendar.google.com@example.com/events.ics",
        "https://icloud.com@example.com/events.ics",
        "webcal://apple.com.evil.example/published/calendar",
    ).forEach { assertSupported(it, "Calendar feed") }
    assertSupported("https://example.com@calendar.google.com/feed", "Google Calendar")
  }

  @Test
  fun normalizeUrl_preservesEncodedPathAndPrivateQueryExactly() {
    // Synthetic values only; failure messages deliberately omit URL contents.
    val webcal =
        "  WeBcAl://P01-CALDAV.ICLOUD.COM/published/synthetic%2Fid%2Eics" +
            "?private=synthetic%2Bvalue%3D&name=Case+Sensitive&next=%2Fcalendar#keep%20me  "
    val expected =
        "https://P01-CALDAV.ICLOUD.COM/published/synthetic%2Fid%2Eics" +
            "?private=synthetic%2Bvalue%3D&name=Case+Sensitive&next=%2Fcalendar#keep%20me"
    val normalized = CalendarFeed.normalizeUrl(webcal)
    assertTrue("Normalization must preserve encoded data", normalized == expected)
    assertTrue("Normalization must be idempotent", CalendarFeed.normalizeUrl(normalized) == expected)

    val https = "HTTPS://example.com/private%2Fcalendar.ics?private=synthetic%2Bvalue%3D"
    val http = "http://localhost:8080/feed%2Eics?private=synthetic%2Fvalue"
    assertTrue("HTTPS must only be trimmed", CalendarFeed.normalizeUrl(" \t$https\n ") == https)
    assertTrue("HTTP must only be trimmed", CalendarFeed.normalizeUrl("  $http  ") == http)
  }

  private fun assertSupported(url: String, provider: String) {
    assertTrue("Expected a supported calendar URL", CalendarFeed.isSupported(url))
    assertEquals(provider, CalendarFeed.providerName(url))
  }

  private fun assertUnsupported(url: String) {
    assertFalse("Expected an unsupported calendar URL", CalendarFeed.isSupported(url))
    assertEquals("Calendar feed", CalendarFeed.providerName(url))
  }
}
