/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPageLayoutTest {
    private fun kinds(page: String, advanced: Boolean = true, screensaverOn: Boolean = true) =
        settingsPageLayout(page, advanced, screensaverOn).entries.map { it.kind }

    @Test fun topLevelPagesOpenWithTheirLedeAndSubPagesDoNot() {
        assertEquals(SettingsEntryKind.LEDE, kinds("display").first())
        assertFalse(SettingsEntryKind.LEDE in kinds("w-weather"))
    }

    @Test fun advancedSectionsAndRowsHideBehindOneHint() {
        val off = settingsPageLayout("frame", advanced = false, screensaverOn = true)
        assertFalse("screensaver.ambientDashboard" in off.controlIndex)
        assertEquals(1, off.entries.count { it.kind == SettingsEntryKind.ADVANCED_HINT })
        val on = settingsPageLayout("frame", advanced = true, screensaverOn = true)
        assertTrue("screensaver.ambientDashboard" in on.controlIndex)
        assertFalse(SettingsEntryKind.ADVANCED_HINT in on.entries.map { it.kind })
        val header = on.entries.single { it.kind == SettingsEntryKind.HEADER && it.text == "Advanced" }
        assertTrue(header.advanced)
    }

    @Test fun pagesWithoutAdvancedContentShowNoHint() {
        assertFalse(SettingsEntryKind.ADVANCED_HINT in kinds("wallpaper", advanced = false))
    }

    @Test fun screensaverRowsHideWhileItIsOffWithOneNotice() {
        val frame = settingsPageLayout("frame", advanced = true, screensaverOn = false)
        assertTrue("screensaver.enabled" in frame.controlIndex)
        assertFalse("screensaver.mode" in frame.controlIndex)
        assertFalse("frame.preview" in frame.controlIndex)
        val notice = frame.entries.single { it.kind == SettingsEntryKind.NOTICE }
        assertEquals(FRAME_OFF_NOTICE, notice.text)
        // The notice sits right after the master switch.
        assertEquals(frame.controlIndex.getValue("screensaver.enabled") + 2, frame.entries.indexOf(notice))
        val night = settingsPageLayout("night", advanced = true, screensaverOn = false)
        assertEquals(SCREENSAVER_OFF_NOTICE, night.entries.single { it.kind == SettingsEntryKind.NOTICE }.text)
        // The sleep timer and the lamp do not depend on the screensaver.
        assertTrue("screensaver.sleepTimerEnabled" in night.controlIndex)
        assertTrue("ext.lamp" in night.controlIndex)
    }

    @Test fun controlIndexPointsAtTheEntryThatDrawsTheRow() {
        val layout = settingsPageLayout("night", advanced = true, screensaverOn = true)
        val rows = layout.entries[layout.controlIndex.getValue("screensaver.overnightEndMin")]
        assertEquals(SettingsEntryKind.ROWS, rows.kind)
        assertTrue((rows.item as SettingsDomainRows).keys.contains("overnightEndMin"))
        val block = layout.entries[layout.controlIndex.getValue("screensaver.sleepTimerMin")]
        assertEquals(SettingsBlockItem(SettingsBlock.SLEEP_TIMER), block.item)
        val lamp = layout.entries[layout.controlIndex.getValue("ext.lamp")]
        assertEquals(SettingsEntryKind.EXTERNAL, lamp.kind)
    }

    @Test fun entryKeysAreUniquePerPageSoLazyItemsAreStable() {
        SettingsTree.pages.forEach { page ->
            listOf(true, false).forEach { advanced ->
                val keys = settingsPageLayout(page.id, advanced, screensaverOn = advanced).entries.map { it.key }
                assertEquals("${page.id} has duplicate keys", keys.size, keys.toSet().size)
            }
        }
    }

    @Test fun cardEdgesWrapEachSectionsItems() {
        val widgets = settingsPageLayout("widgets", advanced = false, screensaverOn = true).entries
            .filter { it.kind == SettingsEntryKind.LINK }
        val section = widgets.drop(1)
        assertTrue(section.first().first)
        assertTrue(section.last().last)
        assertTrue(section.drop(1).dropLast(1).none { it.first || it.last })
    }

    @Test fun unknownPageIsEmpty() {
        val layout = settingsPageLayout("gone", advanced = true, screensaverOn = true)
        assertTrue(layout.entries.isEmpty())
        assertNull(layout.controlIndex["display.brightness"])
    }

    @Test fun pageDomainsListOnlyRegistryRowDomains() {
        assertEquals(listOf("screensaver"), settingsPageDomains("frame"))
        assertEquals(listOf("immortal"), settingsPageDomains("display"))
        assertTrue(settingsPageDomains("greeting").isEmpty())
    }
}
