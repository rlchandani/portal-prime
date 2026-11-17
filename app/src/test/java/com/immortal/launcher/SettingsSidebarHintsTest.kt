/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsSidebarHintsTest {
    private val facts = SettingsSidebarFacts(photoFrameOn = true, alarmOn = true, alarmMinuteOfDay = 7 * 60 + 5,
        wallpaper = "Dusk Aurora")

    @Test fun photoFrameShowsOnOrOff() {
        assertEquals("On", settingsSidebarHint(SettingsPageIds.FRAME, facts, advanced = false))
        assertEquals("Off", settingsSidebarHint(SettingsPageIds.FRAME, facts.copy(photoFrameOn = false), false))
    }

    @Test fun alarmShowsItsTimeOnlyWhenOn() {
        assertEquals("07:05", settingsSidebarHint(SettingsPageIds.ALARMS, facts, false))
        assertEquals("Off", settingsSidebarHint(SettingsPageIds.ALARMS, facts.copy(alarmOn = false), false))
    }

    @Test fun wallpaperAndAdvanced() {
        assertEquals("Dusk Aurora", settingsSidebarHint(SettingsPageIds.WALLPAPER, facts, false))
        assertEquals("On", settingsSidebarHint(SettingsPageIds.ADVANCED, facts, advanced = true))
        assertEquals("Off", settingsSidebarHint(SettingsPageIds.ADVANCED, facts, advanced = false))
    }

    @Test fun unknownFactsAndOtherPagesShowNothing() {
        val unknown = SettingsSidebarFacts()
        assertNull(settingsSidebarHint(SettingsPageIds.FRAME, unknown, false))
        assertNull(settingsSidebarHint(SettingsPageIds.ALARMS, unknown, false))
        assertNull(settingsSidebarHint(SettingsPageIds.SOUNDS, facts, false))
    }

    @Test fun clockLabelIs24Hour() {
        assertEquals("00:00", clockLabel(0))
        assertEquals("23:59", clockLabel(23 * 60 + 59))
    }

    @Test fun wallpaperLabelFallsBackToCustom() {
        val choices = listOf("dark" to "Dark")
        assertEquals("Dark", wallpaperHintLabel("dark", choices))
        assertEquals("Custom", wallpaperHintLabel("other", choices))
    }
}
