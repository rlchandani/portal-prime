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
import org.junit.Test

class ApprovedSettingsContinuityTest {
    @Test fun ordinarySettingsDoNotRequireAnotherActivity() {
        listOf("screensaver", "clock", "chime", "welcome", "sunrise", "alarm", "sleep",
            "world", "health", "multiroom", "mqtt", "boot", "sources", "styles", "faces",
            "countdowns", "crypto", "todo", "news", "stocks", "options", "alpaca", "widgets")
            .forEach { page ->
                assertNull("$page must render in the Settings pane", approvedSettingsPage(page)?.activity)
            }
    }

    @Test fun fullscreenActionsKeepTheirOwnDestination() {
        assertEquals(LampActivity::class.java, approvedSettingsPage("lamp")?.activity)
        assertEquals(CameraViewerActivity::class.java, approvedSettingsPage("cameras")?.activity)
    }

    @Test fun submenuNamesDescribeSpecificEditorsRatherThanRepeatingCategories() {
        assertEquals("Slideshow", approvedSettingsPage("screensaver")?.title)
        assertEquals("Home dashboard", approvedSettingsPage("widgets")?.title)
        assertEquals("Home Assistant dashboard", approvedSettingsPage("ha")?.title)
        for (category in ApprovedSettingsCategories) {
            assertFalse(category.pages.any { approvedSettingsPage(it)?.title == category.title })
        }
    }
}
