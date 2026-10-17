/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class ClockTimeDraftTest {
    @Test fun acceptsMidnightNoonAndLastMinute() {
        assertEquals(0, ClockTimeDraft("00", "00").totalMinutesOrNull())
        assertEquals(720, ClockTimeDraft("12", "00").totalMinutesOrNull())
        assertEquals(1439, ClockTimeDraft("23", "59").totalMinutesOrNull())
        assertEquals(487, ClockTimeDraft("8", "7").totalMinutesOrNull())
    }

    @Test fun rejectsHoursOutsideThe24HourDay() {
        listOf("24", "-1", "99999999999999999999").forEach {
            val draft = ClockTimeDraft(it, "30")
            assertNull(draft.totalMinutesOrNull())
            assertTrue(draft.errorOrNull()?.contains("0 to 23") == true)
            assertEquals(it, draft.hour)
        }
    }

    @Test fun rejectsMinutesOutsideAnHour() {
        listOf("60", "-1", "99999999999999999999").forEach {
            val draft = ClockTimeDraft("12", it)
            assertNull(draft.totalMinutesOrNull())
            assertTrue(draft.errorOrNull()?.contains("0 to 59") == true)
            assertEquals(it, draft.minute)
        }
    }

    @Test fun rejectsEmptySignedWhitespaceAndNonDecimalInput() {
        listOf("", " ", " 1", "1 ", "+1", "1.0", "1e1", "ab", "١").forEach {
            assertNull("hour: $it", ClockTimeDraft(it, "00").totalMinutesOrNull())
            assertNull("minute: $it", ClockTimeDraft("00", it).totalMinutesOrNull())
        }
    }

    @Test fun savedMinutesInitializeAnIndependentDraft() {
        val draft = ClockTimeDraft.fromMinutes(1439)
        assertEquals("23", draft.hour)
        assertEquals("59", draft.minute)
        assertNull(draft.errorOrNull())
        assertEquals(1439, draft.totalMinutesOrNull())
        assertEquals("00", ClockTimeDraft.fromMinutes(0).hour)
        assertEquals("00", ClockTimeDraft.fromMinutes(0).minute)
    }
}
