/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.settings.SettingsDomain
import com.immortal.launcher.settings.SettingsDomains
import com.immortal.launcher.settings.StringSpec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Registry validation through the real calendar specs and ScreensaverConfig persistence. */
class CalendarUrlRegistryTest {
    private val oldUrl = "https://example.com/previous.ics"

    @Suppress("UNCHECKED_CAST")
    private val urlSpec: StringSpec<ScreensaverConfig.Settings>
        get() = SettingsDomains.calendar.specs.single { it.key == "url" } as
            StringSpec<ScreensaverConfig.Settings>

    /** Preserve registered loading and setters, recording the batch hook without hardware effects. */
    private fun calendarDomain(batches: MutableList<Set<String>>) = SettingsDomain(
        id = SettingsDomains.calendar.id,
        title = SettingsDomains.calendar.title,
        load = SettingsDomains.calendar.load,
        specs = SettingsDomains.calendar.specs,
        defaults = SettingsDomains.calendar.defaults,
        onApplied = { _, keys -> batches.add(keys.toSet()) },
    )

    @Test
    fun invalidLinks_keepOldUrlAndPerformZeroWrites() {
        val prefs = ScreensaverTestPrefs(mapOf("calendar_url" to oldUrl))
        val batches = mutableListOf<Set<String>>()
        val domain = calendarDomain(batches)
        val before = domain.load(prefs.context)
        listOf(
            "not a url",
            "https://example.com/photos/album",
            "ftp://example.com/calendar.ics",
            "https:///calendar.ics",
            "https://calendar.google.com.evil.example/calendar",
            "https://example.com:70000/calendar.ics",
        ).forEach { raw ->
            assertFalse(urlSpec.applyWhen(raw))
            assertTrue(domain.apply(prefs.context, JSONObject().put("url", raw)).isEmpty())
            assertEquals(before, ScreensaverConfig.load(prefs.context))
        }
        assertEquals(oldUrl, urlSpec.get(domain.load(prefs.context)))
        assertEquals(0, prefs.writeCount)
        assertTrue(batches.isEmpty())
    }

    @Test
    fun wrongTypes_areRejectedWithoutChangingSettings() {
        val prefs = ScreensaverTestPrefs(mapOf("calendar_url" to oldUrl, "calendar_enabled" to false))
        val batches = mutableListOf<Set<String>>()
        val domain = calendarDomain(batches)
        val before = domain.load(prefs.context)
        listOf<Any>(
            JSONObject.NULL,
            true,
            42,
            1.5,
            JSONObject().put("value", oldUrl),
            JSONArray().put(oldUrl),
        ).forEach { value ->
            assertTrue(domain.apply(prefs.context, JSONObject().put("url", value)).isEmpty())
            assertEquals(before, ScreensaverConfig.load(prefs.context))
            assertEquals(0, prefs.writeCount)
        }
        assertTrue(batches.isEmpty())
    }

    @Test
    fun validProviders_areAcceptedThroughRegisteredSetters() {
        listOf(
            "https://calendar.google.com/calendar/ical/fixture/basic.ics",
            "https://www.google.com/calendar/feeds/fixture/public/basic",
            "webcal://p01-caldav.icloud.com/published/2/fixture",
            "https://p01-caldav.icloud.com/published/2/fixture",
            "https://example.com/calendar.ics",
        ).forEach { raw ->
            val prefs = ScreensaverTestPrefs(mapOf("calendar_url" to oldUrl))
            val batches = mutableListOf<Set<String>>()
            val domain = calendarDomain(batches)
            assertTrue(urlSpec.applyWhen(raw))
            assertEquals(setOf("url"), domain.apply(prefs.context, JSONObject().put("url", raw)))
            val saved = ScreensaverConfig.load(prefs.context)
            assertEquals(raw, saved.calendarUrl)
            assertEquals(raw, urlSpec.get(saved))
            assertTrue(saved.hasCalendarLink)
            assertEquals(1, prefs.writeCount)
            assertEquals(listOf(setOf("url")), batches)
        }
    }

    @Test
    fun blankAndWhitespace_clearUrlAndPreserveWidgetEnabledChoice() {
        listOf(true, false).forEach { enabled ->
            listOf("", " ", "\t\r\n ").forEach { raw ->
                val prefs = ScreensaverTestPrefs(mapOf(
                    "calendar_url" to oldUrl,
                    "calendar_enabled" to enabled,
                    "calendar_range" to CalendarFeed.RANGE_WEEK,
                ))
                val batches = mutableListOf<Set<String>>()
                val domain = calendarDomain(batches)
                val before = domain.load(prefs.context)
                assertEquals(setOf("url"), domain.apply(prefs.context, JSONObject().put("url", raw)))
                val saved = ScreensaverConfig.load(prefs.context)
                assertNull(saved.calendarUrl)
                assertFalse(prefs.contains("calendar_url"))
                assertEquals("", urlSpec.get(saved))
                assertEquals(enabled, saved.calendarEnabled)
                assertFalse(saved.hasCalendarLink)
                assertFalse(saved.usesCalendar)
                assertEquals(before.copy(calendarUrl = null), saved)
                assertEquals(1, prefs.writeCount)
                assertEquals(listOf(setOf("url")), batches)
            }
        }
    }

    @Test
    fun acceptedUrls_areOnlyTrimmed_preservingWebcalAndEncodedQuery() {
        listOf(
            "webcal://p01-caldav.icloud.com/published/2/fixture?value=a%2Fb%2Bc%3D&label=two+words",
            "https://example.com/Team%20Calendar.ics?value=%252F%26%3D&label=two+words",
        ).forEach { expected ->
            val prefs = ScreensaverTestPrefs(mapOf("calendar_url" to oldUrl, "calendar_enabled" to false))
            val batches = mutableListOf<Set<String>>()
            val domain = calendarDomain(batches)
            val before = domain.load(prefs.context)
            val raw = " \t$expected\r\n "
            assertEquals(setOf("url"), domain.apply(prefs.context, JSONObject().put("url", raw)))
            val saved = ScreensaverConfig.load(prefs.context)
            assertEquals(expected, saved.calendarUrl)
            assertEquals(expected, urlSpec.get(saved))
            assertEquals(before.copy(calendarUrl = expected), saved)
            assertEquals(1, prefs.writeCount)
            assertEquals(listOf(setOf("url")), batches)
        }
    }

    @Test
    fun invalidUrlWithValidRange_appliesOnlyRangeAndCallsOnAppliedOnceWithTruthfulKeys() {
        val prefs = ScreensaverTestPrefs(mapOf(
            "calendar_url" to oldUrl,
            "calendar_range" to CalendarFeed.RANGE_DAY,
            "calendar_enabled" to false,
        ))
        val batches = mutableListOf<Set<String>>()
        val domain = calendarDomain(batches)
        val before = domain.load(prefs.context)
        val applied = domain.apply(prefs.context, JSONObject()
            .put("url", "https://example.com/photos/album")
            .put("range", CalendarFeed.RANGE_WEEK))
        assertEquals(setOf("range"), applied)
        val saved = ScreensaverConfig.load(prefs.context)
        assertEquals(oldUrl, saved.calendarUrl)
        assertEquals(CalendarFeed.RANGE_WEEK, saved.calendarRange)
        assertEquals(before.copy(calendarRange = CalendarFeed.RANGE_WEEK), saved)
        assertEquals(1, prefs.writeCount)
        assertEquals(listOf(setOf("range")), batches)
    }
}
