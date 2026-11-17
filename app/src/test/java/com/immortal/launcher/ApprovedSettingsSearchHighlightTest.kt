/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovedSettingsSearchHighlightTest {
    @Test fun marksTheFirstCaseInsensitiveMatch() {
        assertEquals(6..10, approvedSettingsSearchMatch("Photo frame screensaver", " FRAME "))
    }

    @Test fun noMarkWhenTheTitleDoesNotContainTheQuery() {
        assertNull(approvedSettingsSearchMatch("Brightness", "volume"))
        assertNull(approvedSettingsSearchMatch("Brightness", "  "))
    }

    @Test fun searchShowsTheShorterTitleAndStillFindsTheOldOne() {
        val index = approvedSettingsSearchIndex()
        val byNew = approvedSettingsSearch(index, "time format").firstOrNull { it.control == "immortal.clockFormat" }
        assertNotNull(byNew)
        assertEquals("Time format", byNew!!.title)
        assertTrue("Clock" in byNew.keywords.split(' '))
    }
}
