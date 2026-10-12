/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

class CalendarFeedReaderTest {
  private val now = 1_700_000_000_000L
  private val url = "https://fixture.invalid/calendar.ics"
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

  @Test fun eightConcurrentReadersShareOneDownload() {
    val calls = AtomicInteger()
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val barrier = CyclicBarrier(8)
    val pool = Executors.newFixedThreadPool(8)
    val reader = CalendarFeedReader({ calls.incrementAndGet(); entered.countDown()
      check(release.await(3, TimeUnit.SECONDS)); feed })
    try {
      val results = (1..8).map { pool.submit<List<CalendarFeed.Event>> {
        barrier.await(2, TimeUnit.SECONDS); reader.fetch(url, now, 45)
      } }
      assertTrue(entered.await(2, TimeUnit.SECONDS))
      Thread.sleep(80)
      release.countDown()
      for (result in results) assertEquals(listOf("Soon", "Later"),
          result.get(3, TimeUnit.SECONDS).map { it.title })
      assertEquals("eight consumers must download once", 1, calls.get())
    } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS)) }
  }

  @Test fun twentyWarmReadsReuseFeedUntilThirtySecondExpiry() {
    val calls = AtomicInteger(); val clock = AtomicLong()
    val reader = CalendarFeedReader({ calls.incrementAndGet(); feed }, clock::get)
    repeat(20) { assertEquals(2, reader.fetch(url, now, 45).size) }
    assertEquals(1, calls.get())
    clock.set(29_999); reader.fetch(url, now, 45); assertEquals(1, calls.get())
    clock.set(30_000); reader.fetch(url, now, 45); assertEquals(2, calls.get())
  }

  @Test fun webcalAndHttpsShareTheNormalizedFeedKey() {
    val urls = mutableListOf<String>()
    val reader = CalendarFeedReader({ urls.add(it); feed })
    reader.fetch("  webcal://fixture.invalid/calendar.ics  ", now, 45)
    reader.fetch(url, now, 45)
    assertEquals(listOf(url), urls)
  }

  @Test fun distinctFeedPathsAndQueriesNeverShareContent() {
    val urls = mutableListOf<String>()
    val reader = CalendarFeedReader({ urls.add(it); feed.replace("Soon", it) })
    for (key in listOf(url, "$url?feed=a", "$url?feed=b")) {
      assertEquals(key, reader.fetch(key, now, 45).first().title)
      assertEquals(key, reader.fetch(key, now, 45).first().title)
    }
    assertEquals(listOf(url, "$url?feed=a", "$url?feed=b"), urls)
  }

  @Test fun everyCallerFiltersExpiredEventsAgainstItsOwnTime() {
    val calls = AtomicInteger()
    val reader = CalendarFeedReader({ calls.incrementAndGet(); feed })
    assertEquals(listOf("Soon", "Later"), reader.fetch(url, now, 45).map { it.title })
    val afterSoon = 1_700_063_401_000L
    assertEquals(listOf("Later"), reader.fetch(url, afterSoon, 45).map { it.title })
    assertEquals(1, calls.get())
  }

  @Test fun everyCallerUsesItsOwnRequestedHorizon() {
    val calls = AtomicInteger()
    val reader = CalendarFeedReader({ calls.incrementAndGet(); feed })
    assertEquals(listOf("Soon"), reader.fetch(url, now, 1).map { it.title })
    assertEquals(listOf("Soon", "Later"), reader.fetch(url, now, 45).map { it.title })
    assertEquals(1, calls.get())
  }

  @Test fun unavailableFeedIsNotCachedAndCanRecoverImmediately() {
    val calls = AtomicInteger()
    val reader = CalendarFeedReader({ if (calls.incrementAndGet() == 1) null else feed })
    assertTrue(reader.fetch(url, now, 45).isEmpty())
    assertEquals(2, reader.fetch(url, now, 45).size)
    assertEquals(2, reader.fetch(url, now, 45).size)
    assertEquals(2, calls.get())
  }

  @Test fun failedDownloadIsNotCachedAndPreservesItsException() {
    val calls = AtomicInteger(); val failed = IOException("fixture failure")
    val reader = CalendarFeedReader({ if (calls.incrementAndGet() == 1) throw failed else feed })
    try { reader.fetch(url, now, 45); fail("download failure must propagate") }
    catch (error: IOException) { assertSame(failed, error) }
    assertEquals(2, reader.fetch(url, now, 45).size)
    assertEquals(2, reader.fetch(url, now, 45).size)
    assertEquals(2, calls.get())
  }

  @Test fun largeFeedsRemainSupportedWithoutLongLivedRetention() {
    val calls = AtomicInteger(); val large = feed + "\n" + " ".repeat(262_144)
    val reader = CalendarFeedReader({ calls.incrementAndGet(); large })
    repeat(2) { assertEquals(2, reader.fetch(url, now, 45).size) }
    assertEquals(2, calls.get())
  }

  @Test fun onlyFourFeedValuesAreRetained() {
    val calls = AtomicInteger()
    val reader = CalendarFeedReader({ calls.incrementAndGet(); feed })
    repeat(5) { reader.fetch("$url?id=$it", now, 45) }
    reader.fetch("$url?id=4", now, 45); assertEquals(5, calls.get())
    reader.fetch("$url?id=0", now, 45); assertEquals(6, calls.get())
  }
}
