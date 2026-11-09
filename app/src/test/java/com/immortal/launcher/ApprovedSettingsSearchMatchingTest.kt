/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ranking and matching of the split-view search (pure, over hand-made and real indexes). */
class ApprovedSettingsSearchMatchingTest {
    private val index = approvedSettingsSearchIndex()

    private fun result(title: String, control: String? = title, keywords: String = "", crumb: String = "Display & Brightness") =
        ApprovedSettingsSearchResult(title, "display", control, crumb, keywords)

    @Test fun reversedWordsFindTheSameSetting() {
        val voice = result("Greeting voice")
        assertEquals(listOf(voice), approvedSettingsSearch(listOf(voice), "voice greeting"))
    }

    @Test fun repeatedWhitespaceAndCaseDoNotChangeTheQuery() {
        val clock = result("Clock face")
        assertEquals(listOf(clock), approvedSettingsSearch(listOf(clock), "  CLOCK  \t face  "))
    }

    @Test fun termsCanSpanTheTitleKeywordsAndBreadcrumb() {
        val setting = result("Brightness", keywords = "Reduce glare at night")
        assertEquals(listOf(setting), approvedSettingsSearch(listOf(setting), "night brightness"))
        assertEquals(listOf(setting), approvedSettingsSearch(listOf(setting), "display brightness"))
    }

    @Test fun everyTermIsRequiredAndBlankFindsNothing() {
        val setting = result("Brightness")
        assertTrue(approvedSettingsSearch(listOf(setting), "brightness nonexistent").isEmpty())
        assertTrue(approvedSettingsSearch(listOf(setting), " \t ").isEmpty())
    }

    @Test fun hyphensAreIgnoredSoDashedNamesMatch() {
        val token = result("Long-lived token")
        assertSame(token, approvedSettingsSearch(listOf(token), "token longlived").single())
        assertSame(token, approvedSettingsSearch(listOf(token), "long-lived").single())
    }

    @Test fun exactThenPrefixThenContainsThenOtherMatches() {
        val metadata = result("Wallpaper", keywords = "Clock settings")
        val contains = result("Photo clock")
        val prefix = result("Clock face")
        val exact = result("Clock")
        assertEquals(listOf(exact, prefix, contains, metadata),
            approvedSettingsSearch(listOf(metadata, contains, prefix, exact), "clock"))
    }

    @Test fun equalRelevanceKeepsIndexOrder() {
        val first = result("Other one", keywords = "Brightness settings")
        val second = result("Other two", keywords = "Brightness settings")
        assertEquals(listOf(first, second), approvedSettingsSearch(listOf(first, second), "brightness"))
    }

    @Test fun resultsAreCappedButTheExactTitleSurvives() {
        val incidental = (1..40).map { result("Other $it", control = "c$it", keywords = "Brightness adjustment") }
        val direct = result("Brightness")
        val found = approvedSettingsSearch(incidental + direct, "brightness")
        assertEquals(direct, found.first())
        assertEquals(24, found.size)
    }

    @Test fun pagesRankJustAheadOfRowsWithTheSameTitleMatch() {
        val row = result("Sounds", control = "x.sounds")
        val page = ApprovedSettingsSearchResult("Sounds", "sounds", null, "Page")
        assertEquals(listOf(page, row), approvedSettingsSearch(listOf(row, page), "sounds"))
    }

    @Test fun aliasesRankAfterRealRowsAndAreDroppedWhenTheRowIsListed() {
        val real = ApprovedSettingsSearchResult("Sleep timer", "night", "screensaver.sleepTimerEnabled", "Night & Sleep")
        val alias = ApprovedSettingsSearchResult("Sleep timer", "night", "screensaver.sleepTimerEnabled",
            "Was Device → Night & Sleep", alias = true)
        assertEquals(listOf(real), approvedSettingsSearch(listOf(alias, real), "sleep timer"))
        val otherAlias = ApprovedSettingsSearchResult("Slideshow", "frame", null, "Was Photos → Photo Frame", alias = true)
        val row = ApprovedSettingsSearchResult("Slideshow speed", "frame", "frame.speed", "Photo Frame")
        assertEquals(listOf(row, otherAlias), approvedSettingsSearch(listOf(otherAlias, row), "slideshow"))
    }

    @Test fun matchingKeepsAdvancedAndManagedMetadata() {
        val field = ApprovedSettingsSearchResult("Long-lived token", "ha", "ha.token", "Home Assistant",
            advanced = true, automatic = true)
        val found = approvedSettingsSearch(listOf(field), "token long-lived").single()
        assertSame(field, found)
        assertTrue(found.advanced)
        assertTrue(found.automatic)
        assertEquals("Home Assistant · Automatic", approvedSettingsSearchBreadcrumb(found))
    }

    @Test fun realIndexFindsDirectControlsFirst() {
        assertEquals("display.nightShift", approvedSettingsSearch(index, "night shift").first().id)
        assertEquals("welcome.voice", approvedSettingsSearch(index, "voice greeting").first().id)
        assertEquals("display.brightness", approvedSettingsSearch(index, "brightness").first().id)
    }

    @Test fun realIndexCombinesSectionAndTitle() {
        val position = approvedSettingsSearch(index, "photo frame position").map { it.id }
        assertTrue(position.toString(), "calendar.side" in position)
        val calendar = index.single { it.id == "calendar.side" }
        assertEquals("Widgets & Calendar › Calendar › Photo frame", calendar.crumb)
        assertEquals("w-calendar", calendar.page)
        assertFalse(calendar.advanced)
    }

    @Test fun technicalIdsStaySearchable() {
        assertEquals("display.brightness", approvedSettingsSearch(index, "display.brightness").first().id)
    }
}
