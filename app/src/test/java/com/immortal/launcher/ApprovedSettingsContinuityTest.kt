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

class ApprovedSettingsContinuityTest {
    @Test fun ordinarySettingsDoNotRequireAnotherActivity() {
        listOf("screensaver", "clock", "chime", "welcome", "sunrise", "alarm", "sleep",
            "world", "health", "multiroom", "mqtt", "boot", "sources", "styles", "faces",
            "countdowns", "crypto", "todo", "news", "stocks", "options", "alpaca")
            .forEach { page ->
                assertNull("$page must render in the Settings pane", approvedSettingsPage(page)?.activity)
            }
    }

    @Test fun fullscreenActionsKeepTheirOwnDestination() {
        assertEquals(HomeActivity::class.java, approvedSettingsPage("widgets")?.activity)
        assertEquals(LampActivity::class.java, approvedSettingsPage("lamp")?.activity)
        assertEquals(CameraViewerActivity::class.java, approvedSettingsPage("cameras")?.activity)
    }
}
