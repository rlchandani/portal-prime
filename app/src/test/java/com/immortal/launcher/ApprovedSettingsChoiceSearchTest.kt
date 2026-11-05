/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class ApprovedSettingsChoiceSearchTest {
    private val index = approvedSettingsSearchIndex()

    private fun find(query: String, id: String, page: String) {
        val result = approvedSettingsSearch(index, query).singleOrNull { it.id == id }
        assertNotNull("Missing setting choice for $query", result)
        assertEquals(page, result!!.page)
    }

    @Test fun celsiusFindsTemperatureUnit() {
        find("Celsius", "immortal.weatherUnit", "weather")
    }

    @Test fun fahrenheitFindsTemperatureUnit() {
        find("Fahrenheit", "immortal.weatherUnit", "weather")
    }

    @Test fun clockFormatChoiceFindsAppearance() {
        find("24-hour", "immortal.clockFormat", "appearance")
    }

    @Test fun photoStyleChoiceFindsStylePicker() {
        find("Polaroid Scatter", "screensaver.mode", "styles")
    }

    @Test fun choiceTermsCombineWithControlTitle() {
        find("temperature Celsius", "immortal.weatherUnit", "weather")
    }

    @Test fun choiceMatchingKeepsCaseAndWhitespaceRules() {
        find(" \t cELsiUs  temperature ", "immortal.weatherUnit", "weather")
    }

    @Test fun directTitleStillRanksAheadOfChoiceAlias() {
        val choice = index.single { it.id == "immortal.weatherUnit" }
        val direct = ApprovedSettingsSearchResult("Celsius", "weather")
        assertEquals(listOf(direct, choice),
            approvedSettingsSearch(listOf(choice, direct), "Celsius"))
    }

    @Test fun unmatchedChoiceTermStillRejectsResult() {
        assertTrue(approvedSettingsSearch(index, "Celsius nonexistent").isEmpty())
    }

    @Test fun existingGroupedTimeAliasesRemain() {
        find("sunrise.minute", "sunrise.hour", "sunrise")
        find("alarm.minute", "alarm.hour", "alarm")
    }
}
