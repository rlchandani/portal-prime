/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class CalendarFeedRecoveryTest {
  private val now = 1_700_000_000_000L
  private val url = "https://fixture.invalid/calendar.ics"
  private val day = 86_400_000L
  private val feed = """
    BEGIN:VCALENDAR
    BEGIN:VEVENT
    SUMMARY:Soon
    DTSTART:20231115T140000Z
    DTEND:20231115T150000Z
    END:VEVENT
    BEGIN:VEVENT
    SUMMARY:Later
    DTSTART:20231120T140000Z
    DTEND:20231120T150000Z
    END:VEVENT
    END:VCALENDAR
  """.trimIndent()

  private fun titles(result: CalendarFeed.Result) = result.events.map { it.title }

  @Test fun firstNullAndTransportErrorReportUnavailableWithoutEvents() {
    for (throws in listOf(false, true)) {
      val reader = CalendarFeedReader({ if (throws) throw IOException("fixture") else null })
      val result = reader.read(url, now, 45)
      assertTrue(result.unavailable)
      assertTrue(result.events.isEmpty())
    }
  }

  @Test fun failedReadCanRecoverImmediatelyAndShareTheSuccessfulCache() {
    val calls = AtomicInteger()
    val reader = CalendarFeedReader({ if (calls.incrementAndGet() == 1) null else feed })
    assertTrue(reader.read(url, now, 45).unavailable)
    repeat(2) {
      val result = reader.read(url, now, 45)
      assertFalse(result.unavailable)
      assertEquals(listOf("Soon", "Later"), titles(result))
    }
    assertEquals(2, calls.get())
  }

  @Test fun forcedNullRefreshBypassesWarmCacheAndRetainsEvents() {
    val calls = AtomicInteger()
    val reader = CalendarFeedReader({ if (calls.incrementAndGet() == 1) feed else null })
    assertFalse(reader.read(url, now, 45).unavailable)
    val result = reader.read(url, now, 45, forceRefresh = true)
    assertTrue(result.unavailable)
    assertEquals(listOf("Soon", "Later"), titles(result))
    assertTrue(reader.read(url, now, 45).unavailable)
    assertEquals(3, calls.get())
  }

  @Test fun transportFailureReparsesSavedRawFeedForCurrentTimeAndHorizon() {
    var fail = false
    val reader = CalendarFeedReader({ if (fail) throw IOException("fixture") else feed })
    assertEquals(listOf("Soon"), titles(reader.read(url, now, 1)))
    fail = true
    val wider = reader.read(url, now, 45, forceRefresh = true)
    assertTrue(wider.unavailable)
    assertEquals(listOf("Soon", "Later"), titles(wider))
    val later = reader.read(url, 1_700_063_401_000L, 45)
    assertTrue(later.unavailable)
    assertEquals(listOf("Later"), titles(later))
    assertTrue(reader.read(url, 1_700_063_401_000L, 1).events.isEmpty())
  }

  @Test fun successfulEmptyCalendarReplacesPreviouslySavedEvents() {
    var response: String? = feed
    val reader = CalendarFeedReader({ response })
    reader.read(url, now, 45)
    response = "BEGIN:VCALENDAR\nEND:VCALENDAR"
    val empty = reader.read(url, now, 45, forceRefresh = true)
    assertFalse(empty.unavailable)
    assertTrue(empty.events.isEmpty())
    response = null
    val failure = reader.read(url, now, 45, forceRefresh = true)
    assertTrue(failure.unavailable)
    assertTrue(failure.events.isEmpty())
  }

  @Test fun htmlRefreshRetainsValidFeedAndIsNotCached() {
    var response = feed
    val calls = AtomicInteger()
    val reader = CalendarFeedReader({ calls.incrementAndGet(); response })
    reader.read(url, now, 45)
    response = "<html><body>Sign in</body></html>"
    val failure = reader.read(url, now, 45, forceRefresh = true)
    assertTrue(failure.unavailable)
    assertEquals(listOf("Soon", "Later"), titles(failure))
    response = feed.replace("Soon", "Recovered")
    val recovery = reader.read(url, now, 45)
    assertFalse(recovery.unavailable)
    assertEquals(listOf("Recovered", "Later"), titles(recovery))
    assertEquals(3, calls.get())
  }

  @Test fun incompleteCalendarCannotReplaceSavedFeed() {
    var response = feed
    val reader = CalendarFeedReader({ response })
    reader.read(url, now, 45)
    for (invalid in listOf(
        feed.replace("Soon", "Incomplete").substringBefore("END:VCALENDAR"),
        "BEGIN:VCALENDAR\nBEGIN:VEVENT\nSUMMARY:Missing end",
        "BEGIN:VCALENDAR\nBEGIN:VEVENT\nSUMMARY:Missing end\nEND:VCALENDAR",
        feed.replace("END:VEVENT", "END:VTODO"),
        feed.replace("END:VCALENDAR", "END:VEVENT\nEND:VCALENDAR"),
        feed.replace("BEGIN:VEVENT", "BEGIN:VCALENDAR\nBEGIN:VEVENT"),
        "$feed\nBEGIN:VCALENDAR\nEND:VCALENDAR",
        "END:VEVENT\n$feed",
    )) {
      response = invalid
      val result = reader.read(url, now, 45, forceRefresh = true)
      assertTrue(result.unavailable)
      assertEquals(listOf("Soon", "Later"), titles(result))
    }
    // Nested alarms and folded property text are valid; event parsing stays permissive.
    response = feed.replace("SUMMARY:Soon",
        "BEGIN:VALARM\nDESCRIPTION:Reminder\n END:VEVENT\nEND:VALARM\nSUMMARY:Soon")
    val nested = reader.read(url, now, 45)
    assertFalse(nested.unavailable)
    assertEquals(listOf("Soon", "Later"), titles(nested))
  }

  @Test fun documentBoundariesAllowBomCaseWhitespaceAndCrLfButRejectOutsideContent() {
    var response = "\uFEFF  " + feed.replace("BEGIN:VCALENDAR", "begin:vcalendar")
        .replace("END:VCALENDAR", "end:vcalendar").replace("\n", "\r\n") + "  \r\n\r\n"
    val reader = CalendarFeedReader({ response })
    val valid = reader.read(url, now, 45)
    assertFalse(valid.unavailable)
    assertEquals(listOf("Soon", "Later"), titles(valid))
    for (invalid in listOf("<html>\n$feed", "$feed\n</html>")) {
      response = invalid
      val result = reader.read(url, now, 45, forceRefresh = true)
      assertTrue(result.unavailable)
      assertEquals(listOf("Soon", "Later"), titles(result))
    }
  }

  @Test fun savedFeedNeverLeaksAcrossSourceHosts() {
    var response: String? = feed
    val reader = CalendarFeedReader({ response })
    reader.read(url, now, 45)
    response = null
    val other = reader.read("https://other.invalid/calendar.ics", now, 45)
    assertTrue(other.unavailable)
    assertTrue(other.events.isEmpty())
    assertEquals(listOf("Soon", "Later"), titles(reader.read(url, now, 45, true)))
  }

  @Test fun savedFeedNeverLeaksAcrossPaths() {
    var response: String? = feed
    val reader = CalendarFeedReader({ response })
    reader.read(url, now, 45)
    response = null
    val other = reader.read("https://fixture.invalid/other.ics", now, 45)
    assertTrue(other.unavailable)
    assertTrue(other.events.isEmpty())
  }

  @Test fun savedFeedNeverLeaksAcrossQueryValues() {
    var response: String? = feed
    val reader = CalendarFeedReader({ response })
    reader.read("$url?source=a", now, 45)
    response = null
    val other = reader.read("$url?source=b", now, 45)
    assertTrue(other.unavailable)
    assertTrue(other.events.isEmpty())
    assertEquals(listOf("Soon", "Later"), titles(reader.read("$url?source=a", now, 45, true)))
  }

  @Test fun webcalAndHttpsShareBothWarmAndSavedNormalizedFeed() {
    var response: String? = feed
    val loaded = mutableListOf<String>()
    val reader = CalendarFeedReader({ loaded.add(it); response })
    assertFalse(reader.read("  WEBCAL://fixture.invalid/calendar.ics  ", now, 45).unavailable)
    assertEquals(listOf("Soon", "Later"), titles(reader.read(url, now, 45)))
    assertEquals(listOf(url), loaded)
    response = null
    val result = reader.read(url, now, 45, true)
    assertTrue(result.unavailable)
    assertEquals(listOf("Soon", "Later"), titles(result))
    assertEquals(listOf(url, url), loaded)
  }

  @Test fun savedFeedsUseFourEntryLruAndFallbackAccessKeepsEntryRecent() {
    var response: String? = feed
    val reader = CalendarFeedReader({ response })
    repeat(4) { reader.read("$url?id=$it", now, 45) }
    response = null
    assertEquals(2, reader.read("$url?id=0", now, 45, true).events.size)
    response = feed
    reader.read("$url?id=4", now, 45)
    response = null
    assertTrue(reader.read("$url?id=1", now, 45, true).events.isEmpty())
    for (id in listOf(0, 2, 3, 4)) {
      val result = reader.read("$url?id=$id", now, 45, true)
      assertTrue(result.unavailable)
      assertEquals(listOf("Soon", "Later"), titles(result))
    }
  }

  @Test fun sizeBoundaryRetainsExactLimitButOversizedSuccessIsNeverRetained() {
    val exact = feed + "\n" + " ".repeat(131_072 - feed.length - 1)
    var response: String? = exact
    val calls = AtomicInteger()
    val reader = CalendarFeedReader({ calls.incrementAndGet(); response })
    assertFalse(reader.read(url, now, 45).unavailable)
    response = null
    assertFalse(reader.read(url, now, 45).unavailable)
    assertEquals(2, reader.read(url, now, 45, true).events.size)
    response = exact + " "
    repeat(2) {
      val result = reader.read(url, now, 45)
      assertFalse(result.unavailable)
      assertEquals(2, result.events.size)
    }
    response = null
    val failure = reader.read(url, now, 45)
    assertTrue(failure.unavailable)
    assertTrue(failure.events.isEmpty())
    assertEquals(5, calls.get())

    // Fits the UTF-16 bound, but 90,000 CJK characters exceed 262,144 UTF-8 bytes.
    response = feed.replace("BEGIN:VCALENDAR",
        "BEGIN:VCALENDAR\nX-PADDING:" + "\u754C".repeat(90_000))
    repeat(2) {
      val result = reader.read(url, now, 45)
      assertFalse(result.unavailable)
      assertEquals(2, result.events.size)
    }
    response = null
    val unicodeFailure = reader.read(url, now, 45)
    assertTrue(unicodeFailure.unavailable)
    assertTrue(unicodeFailure.events.isEmpty())
    assertEquals(8, calls.get())
  }

  @Test fun savedFeedExpiresAtTwentyFourHoursWithoutWarmOrFallbackRenewal() {
    val clock = AtomicLong()
    var response: String? = feed
    val reader = CalendarFeedReader({ response }, clock::get)
    reader.read(url, now, 45)
    clock.set(29_999)
    assertFalse(reader.read(url, now, 45).unavailable)
    response = null
    clock.set(day - 1)
    assertEquals(2, reader.read(url, now, 45).events.size)
    clock.set(day)
    val expired = reader.read(url, now, 45)
    assertTrue(expired.unavailable)
    assertTrue(expired.events.isEmpty())
  }

  @Test fun backwardsMonotonicClockInvalidatesSavedFeed() {
    val clock = AtomicLong(100)
    var response: String? = feed
    val reader = CalendarFeedReader({ response }, clock::get)
    reader.read(url, now, 45)
    response = null
    clock.set(99)
    val result = reader.read(url, now, 45)
    assertTrue(result.unavailable)
    assertTrue(result.events.isEmpty())
    clock.set(101)
    assertTrue(reader.read(url, now, 45).events.isEmpty())
  }

  private fun awaitInCache(threads: List<Thread>) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
    while (threads.any { thread ->
      thread.state !in listOf(Thread.State.WAITING, Thread.State.TIMED_WAITING) ||
          thread.stackTrace.none { it.className.endsWith("SingleFlightValueCache") && it.methodName == "get" }
    }) {
      if (System.nanoTime() >= deadline) fail("readers did not join the active load")
      Thread.yield()
    }
  }

  @Test fun eightForcedConcurrentReadsSkipWarmValueAndJoinOneActiveRefresh() {
    val calls = AtomicInteger()
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val start = CountDownLatch(1)
    val results = ConcurrentLinkedQueue<CalendarFeed.Result>()
    val errors = ConcurrentLinkedQueue<Throwable>()
    val reader = CalendarFeedReader({
      if (calls.incrementAndGet() > 1) {
        entered.countDown()
        check(release.await(5, TimeUnit.SECONDS))
        feed.replace("Soon", "Refreshed")
      } else feed
    })
    reader.read(url, now, 45)
    val threads = (1..8).map {
      Thread {
        try { start.await(); results.add(reader.read(url, now, 45, true)) }
        catch (error: Throwable) { errors.add(error) }
      }
    }
    try {
      threads.forEach { it.start() }
      start.countDown()
      assertTrue(entered.await(3, TimeUnit.SECONDS))
      awaitInCache(threads)
      release.countDown()
      threads.forEach { it.join(3_000); assertFalse(it.isAlive) }
      assertTrue(errors.toString(), errors.isEmpty())
      assertEquals(8, results.size)
      results.forEach {
        assertFalse(it.unavailable)
        assertEquals(listOf("Refreshed", "Later"), titles(it))
      }
      assertEquals(2, calls.get())
    } finally {
      start.countDown(); release.countDown()
      threads.forEach { it.interrupt(); it.join(3_000) }
    }
  }

  @Test fun interruptionPropagatesAndRestoresFlagForLoaderAndWaitingReader() {
    val interruption = InterruptedException("fixture")
    val throwing = CalendarFeedReader({ throw interruption })
    try {
      throwing.read(url, now, 45)
      fail("loader interruption must propagate")
    } catch (error: InterruptedException) {
      assertSame(interruption, error)
      assertTrue(Thread.currentThread().isInterrupted)
    } finally { Thread.interrupted() }

    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val failure = AtomicReference<Throwable>()
    val ownerFailure = AtomicReference<Throwable>()
    val interrupted = AtomicBoolean()
    val calls = AtomicInteger()
    val reader = CalendarFeedReader({
      calls.incrementAndGet()
      entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); feed
    })
    val owner = Thread {
      try { reader.read(url, now, 45) } catch (error: Throwable) { ownerFailure.set(error) }
    }
    val waiter = Thread {
      try { reader.read(url, now, 45, true); failure.set(AssertionError("interruption swallowed")) }
      catch (error: Throwable) { failure.set(error); interrupted.set(Thread.currentThread().isInterrupted) }
    }
    try {
      owner.start()
      assertTrue(entered.await(3, TimeUnit.SECONDS))
      waiter.start()
      awaitInCache(listOf(waiter))
      waiter.interrupt(); waiter.join(3_000)
      assertFalse(waiter.isAlive)
      assertTrue(failure.get() is InterruptedException)
      assertTrue(interrupted.get())
      release.countDown(); owner.join(3_000)
      assertFalse(owner.isAlive)
      assertNull(ownerFailure.get())
      assertFalse(reader.read(url, now, 45).unavailable)
      assertEquals(1, calls.get())
    } finally {
      release.countDown(); owner.interrupt(); waiter.interrupt()
      owner.join(3_000); waiter.join(3_000)
    }
  }
}
