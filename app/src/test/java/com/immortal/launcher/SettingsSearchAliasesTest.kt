/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Old names still find their setting, and every row of every page is indexed (not just composed ones). */
class SettingsSearchAliasesTest {
    private val index = approvedSettingsSearchIndex()

    @Test fun everyAliasPointsAtARealPageAndRow() {
        SettingsSearchAliases.forEach { alias ->
            assertTrue(alias.oldName, SettingsTree.contains(alias.page))
            alias.control?.let { control ->
                val layout = settingsPageLayout(alias.page, advanced = true, screensaverOn = true)
                assertTrue("${alias.oldName} → ${alias.page} lacks $control", control in layout.controlIndex)
            }
        }
    }

    @Test fun oldNamesOpenTheirNewHome() {
        fun first(query: String) = approvedSettingsSearch(index, query).first()
        assertEquals("frame", first("Slideshow").page)
        assertEquals("fleet.name", first("Device name").control)
        assertEquals("about", first("Device name").page)
        assertEquals("phone", first("Phone remote").page)
        assertEquals("w-calendar", first("Almanac").page)
        assertEquals("night", first("Lamp").page)
    }

    @Test fun aliasesExplainWhereTheSettingWent() {
        val slideshow = index.first { it.alias && it.title == "Slideshow" }
        assertEquals("Was Photos & screensaver → Photo Frame", slideshow.crumb)
    }

    @Test fun everyRowOfEveryPageIsIndexed() {
        SettingsTree.pages.forEach { page ->
            settingsPageLayout(page.id, advanced = true, screensaverOn = true).controlIndex.keys.forEach { control ->
                // The minute half of the Wake time editor is searched as Wake time.
                if (control == "sunrise.minute") return@forEach
                assertTrue("${page.id}: $control is not searchable",
                    index.any { !it.alias && it.page == page.id && it.control == control })
            }
            assertTrue(index.any { it.page == page.id && it.control == null && it.title == page.title })
        }
    }

    @Test fun advancedOnlyResultsAreMarked() {
        assertTrue(index.single { it.control == "ha.token" }.advanced)
        assertTrue(index.single { it.control == "mqtt.host" }.advanced)
        assertTrue(index.single { it.control == "link.mqtt" }.advanced)
        assertTrue(index.single { it.control == "ha.entities" }.advanced)
        assertTrue(!index.single { it.control == "home_dashboard.url" }.advanced)
        assertTrue(!index.single { it.control == "display.brightness" }.advanced)
    }
}
