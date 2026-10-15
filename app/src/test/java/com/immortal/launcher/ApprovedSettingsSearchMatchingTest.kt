/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class ApprovedSettingsSearchMatchingTest {
    private fun result(title: String, key: String = title, help: String = "", page: String = "display") =
        ApprovedSettingsSearchResult(title, page, "test", key, help)

    @Test fun reversedWordsFindTheSameSetting() {
        val voice = result("Greeting voice")
        assertEquals(listOf(voice), approvedSettingsSearch(listOf(voice), "voice greeting"))
    }
    @Test fun repeatedWhitespaceDoesNotChangeTheQuery() {
        val clock = result("Clock face")
        assertEquals(listOf(clock), approvedSettingsSearch(listOf(clock), "  clock  \t face  "))
    }
    @Test fun termsCanSpanTheTitleAndItsHelp() {
        val setting = result("Brightness", help = "Reduce glare at night")
        assertEquals(listOf(setting), approvedSettingsSearch(listOf(setting), "night brightness"))
    }
    @Test fun everyTermIsRequired() {
        val setting = result("Brightness")
        assertTrue(approvedSettingsSearch(listOf(setting), "brightness nonexistent").isEmpty())
    }
    @Test fun exactTitleSurvivesTheTwelveResultLimit() {
        val incidental = (1..15).map { result("Other $it", help = "Brightness adjustment") }
        val direct = result("Brightness")
        val found = approvedSettingsSearch(incidental + direct, "brightness")
        assertEquals(direct, found.first())
        assertEquals(12, found.size)
    }
    @Test fun directTitlesComeBeforeIncidentalHelpMatches() {
        val metadata = result("Wallpaper", help = "Clock settings")
        val contains = result("Photo clock")
        val prefix = result("Clock face")
        val exact = result("Clock")
        assertEquals(listOf(exact, prefix, contains, metadata),
            approvedSettingsSearch(listOf(metadata, contains, prefix, exact), "clock"))
    }
    @Test fun equalRelevanceKeepsRegistryOrder() {
        val first = result("Other one", help = "Brightness settings")
        val second = result("Other two", help = "Brightness settings")
        assertEquals(listOf(first, second), approvedSettingsSearch(listOf(first, second), "brightness"))
    }
    @Test fun blankInputHasNoResults() {
        assertTrue(approvedSettingsSearch(listOf(result("Brightness")), " \t ").isEmpty())
    }
    @Test fun technicalSettingIdentifiersRemainSearchable() {
        val field = ApprovedSettingsSearchResult("Brightness", "display", "display", "brightness")
        assertEquals(listOf(field), approvedSettingsSearch(listOf(field), "display.brightness"))
    }
    @Test fun matchingRemainsCaseInsensitive() {
        val voice = result("Greeting voice")
        assertEquals(listOf(voice), approvedSettingsSearch(listOf(voice), "VOICE GREETING"))
    }
    @Test fun reorderedTermsRetainAdvancedAndManagedMetadata() {
        val field = ApprovedSettingsSearchResult("Long-lived token", "ha", "ha", "token", "Connect native controls", automatic = true)
        val found = approvedSettingsSearch(listOf(field), "token long-lived").single()
        assertSame(field, found)
        assertTrue(found.advanced)
        assertTrue(found.automatic)
    }
    @Test fun pageTitlesParticipateInMatching() {
        val field = result("Brightness")
        assertEquals(listOf(field), approvedSettingsSearch(listOf(field), "display brightness"))
    }
    @Test fun realRegistryFindsGreetingVoiceWithReversedWords() {
        assertEquals("welcome.voice", approvedSettingsSearch(approvedSettingsSearchIndex(), "voice greeting").first().id)
    }
    @Test fun realRegistryPutsTheDirectNightShiftControlFirst() {
        assertEquals("display.nightShift", approvedSettingsSearch(approvedSettingsSearchIndex(), "night shift").first().id)
    }
}
