/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.SimpleTimeZone
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

class DateFormatterTest {
    private lateinit var originalLocale: Locale
    private lateinit var originalZone: TimeZone
    private val pattern = "yyyy-MM-dd HH:mm:ss z"
    private val date = Date.from(Instant.parse("2026-10-30T23:30:00Z"))

    @Before fun saveDefaults() {
        originalLocale = Locale.getDefault()
        originalZone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After fun restoreDefaults() {
        Locale.setDefault(originalLocale)
        TimeZone.setDefault(originalZone)
    }

    @Test fun repeatedCallsQueryIcuOnceAndKeepDatesLive() = onFreshThread {
        Mockito.mockStatic(DateFormat::class.java).use { icu ->
            icu.`when`<String> { DateFormat.getBestDateTimePattern(Locale.US, "yMdHms") }
                .thenReturn(pattern)
            repeat(100) { index ->
                val next = Date(date.time + index * 86_400_000L)
                assertEquals(reference(next), DateFormatter.format(next, "yMdHms", Locale.US))
            }
            icu.verify({ DateFormat.getBestDateTimePattern(Locale.US, "yMdHms") }, Mockito.times(1))
        }
    }

    @Test fun localeAndSkeletonHaveSeparateCachedPatterns() = onFreshThread {
        Mockito.mockStatic(DateFormat::class.java).use { icu ->
            val cases = listOf(
                Triple(Locale.US, "EEEMMMd", "EEE, MMM d"),
                Triple(Locale.FRANCE, "EEEMMMd", "EEE d MMM"),
                Triple(Locale.FRANCE, "EEEEMMMMd", "EEEE d MMMM")
            )
            cases.forEach { (locale, skeleton, resolved) ->
                icu.`when`<String> { DateFormat.getBestDateTimePattern(locale, skeleton) }
                    .thenReturn(resolved)
            }
            repeat(3) {
                cases.forEach { (locale, skeleton, resolved) ->
                    assertEquals(reference(date, resolved, locale), DateFormatter.format(date, skeleton, locale))
                }
            }
            cases.forEach { (locale, skeleton, _) ->
                icu.verify({ DateFormat.getBestDateTimePattern(locale, skeleton) }, Mockito.times(1))
            }
            assertEquals("Vendredi 30 Octobre", DateFormatter.format(date, "EEEEMMMMd", Locale.FRANCE))
        }
    }

    @Test fun defaultLocaleChangesAreObserved() = onFreshThread {
        Mockito.mockStatic(DateFormat::class.java).use { icu ->
            for (locale in listOf(Locale.US, Locale.FRANCE)) {
                icu.`when`<String> { DateFormat.getBestDateTimePattern(locale, "MMMM") }
                    .thenReturn("MMMM")
            }
            assertEquals("October", DateFormatter.format(date, "MMMM"))
            Locale.setDefault(Locale.FRANCE)
            assertEquals("Octobre", DateFormatter.format(date, "MMMM"))
            Locale.setDefault(Locale.US)
            assertEquals("October", DateFormatter.format(date, "MMMM"))
            icu.verify({ DateFormat.getBestDateTimePattern(Locale.US, "MMMM") }, Mockito.times(1))
            icu.verify({ DateFormat.getBestDateTimePattern(Locale.FRANCE, "MMMM") }, Mockito.times(1))
        }
    }

    @Test fun timezoneChangesRefreshDateAndZoneNameWithoutAnotherIcuQuery() = onFreshThread {
        Mockito.mockStatic(DateFormat::class.java).use { icu ->
            icu.`when`<String> { DateFormat.getBestDateTimePattern(Locale.US, "yMdHmsz") }
                .thenReturn(pattern)
            for (id in listOf("UTC", "Asia/Tokyo", "America/Los_Angeles", "UTC")) {
                TimeZone.setDefault(TimeZone.getTimeZone(id))
                assertEquals(reference(date), DateFormatter.format(date, "yMdHmsz", Locale.US))
            }
            icu.verify({ DateFormat.getBestDateTimePattern(Locale.US, "yMdHmsz") }, Mockito.times(1))
        }
    }

    @Test fun sameTimezoneIdWithDifferentOffsetsRefreshes() = onFreshThread {
        Mockito.mockStatic(DateFormat::class.java).use { icu ->
            icu.`when`<String> { DateFormat.getBestDateTimePattern(Locale.US, "yMdHms") }
                .thenReturn(pattern)
            for (offset in listOf(0, 7_200_000, -18_000_000, 0)) {
                TimeZone.setDefault(SimpleTimeZone(offset, "SameId"))
                assertEquals(reference(date), DateFormatter.format(date, "yMdHms", Locale.US))
            }
            icu.verify({ DateFormat.getBestDateTimePattern(Locale.US, "yMdHms") }, Mockito.times(1))
        }
    }

    @Test fun sameTimezoneIdAndOffsetWithDifferentDstRulesRefreshes() = onFreshThread {
        Mockito.mockStatic(DateFormat::class.java).use { icu ->
            icu.`when`<String> { DateFormat.getBestDateTimePattern(Locale.US, "yMdHms") }
                .thenReturn(pattern)
            val summer = Date.from(Instant.parse("2026-07-01T23:30:00Z"))
            // Both zones observe DST and share ID/raw offset; only the transition rules differ.
            for (startMonth in listOf(Calendar.MARCH, Calendar.AUGUST, Calendar.MARCH)) {
                TimeZone.setDefault(SimpleTimeZone(
                    0, "SameId", startMonth, 1, 0, 0, Calendar.NOVEMBER, 1, 0, 0
                ))
                assertEquals(reference(summer), DateFormatter.format(summer, "yMdHms", Locale.US))
            }
            icu.verify({ DateFormat.getBestDateTimePattern(Locale.US, "yMdHms") }, Mockito.times(1))
        }
    }

    @Test fun cachedFormatterTracksBothDstTransitions() = onFreshThread {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        Mockito.mockStatic(DateFormat::class.java).use { icu ->
            icu.`when`<String> { DateFormat.getBestDateTimePattern(Locale.US, "yMdHmsz") }
                .thenReturn(pattern)
            val cases = listOf(
                "2026-03-08T06:59:59Z" to "2026-03-08 01:59:59 EST",
                "2026-03-08T07:00:00Z" to "2026-03-08 03:00:00 EDT",
                "2026-11-01T05:59:59Z" to "2026-11-01 01:59:59 EDT",
                "2026-11-01T06:00:00Z" to "2026-11-01 01:00:00 EST"
            )
            for ((instant, expected) in cases) {
                assertEquals(expected, DateFormatter.format(Date.from(Instant.parse(instant)), "yMdHmsz", Locale.US))
            }
            icu.verify({ DateFormat.getBestDateTimePattern(Locale.US, "yMdHmsz") }, Mockito.times(1))
        }
    }

    @Test fun timezoneIdChangesRefreshEvenWhenRulesAreIdentical() = onFreshThread {
        Mockito.mockStatic(DateFormat::class.java).use { icu ->
            icu.`when`<String> { DateFormat.getBestDateTimePattern(Locale.US, "z") }.thenReturn("z")
            for (id in listOf("UTC", "GMT", "UTC")) {
                TimeZone.setDefault(TimeZone.getTimeZone(id))
                assertEquals(reference(date, "z"), DateFormatter.format(date, "z", Locale.US))
            }
        }
    }

    @Test fun cacheEvictsLeastRecentlyUsedEntryAfterEightPatterns() = onFreshThread {
        Mockito.mockStatic(DateFormat::class.java).use { icu ->
            for (index in 0..8) {
                icu.`when`<String> { DateFormat.getBestDateTimePattern(Locale.US, "s$index") }
                    .thenReturn(pattern)
            }
            for (index in 0..7) DateFormatter.format(date, "s$index", Locale.US)
            DateFormatter.format(date, "s0", Locale.US) // Keep the oldest entry hot.
            DateFormatter.format(date, "s8", Locale.US)
            DateFormatter.format(date, "s0", Locale.US)
            DateFormatter.format(date, "s1", Locale.US) // Evicted entry must resolve again.
            icu.verify({ DateFormat.getBestDateTimePattern(Locale.US, "s0") }, Mockito.times(1))
            icu.verify({ DateFormat.getBestDateTimePattern(Locale.US, "s1") }, Mockito.times(2))
            for (index in 2..8) {
                icu.verify({ DateFormat.getBestDateTimePattern(Locale.US, "s$index") }, Mockito.times(1))
            }
        }
    }

    @Test fun concurrentCallersHaveIndependentFormattersAndIcuCaches() {
        val executor = Executors.newFixedThreadPool(4)
        val ready = CountDownLatch(4)
        val start = CountDownLatch(1)
        try {
            val futures = (0..3).map { worker ->
                executor.submit(Callable {
                    Mockito.mockStatic(DateFormat::class.java).use { icu ->
                        icu.`when`<String> { DateFormat.getBestDateTimePattern(Locale.US, "yMdHms") }
                            .thenReturn(pattern)
                        val expected = SimpleDateFormat(pattern, Locale.US)
                        ready.countDown()
                        check(start.await(10, TimeUnit.SECONDS))
                        repeat(1_000) { index ->
                            val next = Date(date.time + worker * 31_536_000_000L + index * 1_000L)
                            assertEquals(expected.format(next), DateFormatter.format(next, "yMdHms", Locale.US))
                        }
                        icu.verify({ DateFormat.getBestDateTimePattern(Locale.US, "yMdHms") }, Mockito.times(1))
                    }
                })
            }
            check(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    private fun reference(value: Date, resolved: String = pattern, locale: Locale = Locale.US): String =
        SimpleDateFormat(resolved, locale).format(value).split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
        }

    private fun onFreshThread(block: () -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit(Callable { block() }).get(20, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }
}
