/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.settings.SettingsRegistry
import org.junit.Assert.*
import org.junit.Test

class ApprovedClockSearchTest {
    private val index = approvedSettingsSearchIndex()

    private fun assertTime(query: String, title: String, id: String, page: String) {
        val result = approvedSettingsSearch(index, query).firstOrNull()
        assertNotNull("Missing visible time row for $query", result)
        assertEquals(title, result!!.title)
        assertEquals(id, result.id)
        assertEquals(page, result.page)
        assertFalse(result.advanced)
        assertFalse(result.automatic)
    }

    @Test fun visibleAlarmTimeIsSearchable() {
        assertTime("Alarm time", "Alarm time", "alarm.hour", "alarms")
    }

    @Test fun visibleWakeTimeIsSearchable() {
        assertTime("Wake time", "Wake time", "sunrise.hour", "alarms")
        assertEquals("Alarms & Wake", index.single { it.id == "sunrise.hour" }.crumb)
    }

    @Test fun oldAlarmHourAndMinuteQueriesReachOneVisibleEditor() {
        listOf("alarm hour", "alarm minute").forEach {
            assertTime(it, "Alarm time", "alarm.hour", "alarms")
        }
    }

    @Test fun oldSunriseHourAndMinuteQueriesReachOneVisibleEditor() {
        listOf("sunrise hour", "sunrise minute").forEach {
            assertTime(it, "Wake time", "sunrise.hour", "alarms")
        }
    }

    @Test fun originalTechnicalIdsRemainSearchableAliases() {
        listOf("alarm.hour", "alarm.minute").forEach {
            assertTime(it, "Alarm time", "alarm.hour", "alarms")
        }
        listOf("sunrise.hour", "sunrise.minute").forEach {
            assertTime(it, "Wake time", "sunrise.hour", "alarms")
        }
    }

    @Test fun groupedEditorsDoNotExposeDuplicateScalarResults() {
        assertEquals(listOf("Alarm time"), index.filter {
            it.id in setOf("alarm.hour", "alarm.minute")
        }.map { it.title })
        assertEquals(listOf("Wake time"), index.filter {
            it.id in setOf("sunrise.hour", "sunrise.minute")
        }.map { it.title })
    }

    @Test fun groupingSearchLeavesBothRegistryScalarsIntact() {
        val keys = SettingsRegistry.domain("sunrise")!!.specs.map { it.key }
        assertTrue(keys.containsAll(listOf("hour", "minute")))
        assertEquals("alarms", settingsControlHome("sunrise.hour"))
        assertEquals("alarms", settingsControlHome("sunrise.minute"))
    }

    @Test fun visibleNamesKeepUnorderedCaseAndWhitespaceMatching() {
        assertTime("  TIME \t aLaRm  ", "Alarm time", "alarm.hour", "alarms")
        assertTime("  time \t WAKE  ", "Wake time", "sunrise.hour", "alarms")
    }
}
