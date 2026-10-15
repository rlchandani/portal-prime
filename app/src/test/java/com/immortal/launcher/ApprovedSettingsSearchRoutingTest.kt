/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovedSettingsSearchRoutingTest {
    @Test fun slideshowStyleSearchReachesTheStylePicker() {
        assertEquals("styles", approvedPageForControl("screensaver", "mode"))
        val style = approvedSettingsSearchIndex().single { it.id == "screensaver.mode" }
        assertEquals("styles", style.page)
    }

    @Test fun sleepControlsOpenTheirOwnScreen() {
        listOf("sleepTimerEnabled", "sleepTimerMin", "pauseAudioOnSleep", "closeAppOnSleep")
            .forEach { assertEquals("sleep", approvedPageForControl("screensaver", it)) }
    }

    @Test fun atomicFeedAndAudioSettingsReachTheirEditors() {
        assertEquals("calendar-url", approvedPageForControl("calendar", "url"))
        assertEquals("sources", approvedPageForControl("screensaver", "albumRefreshMin"))
        listOf("multiRoomEnabled", "snapcastHost", "maPort", "maUsername", "maPassword")
            .forEach { assertEquals("multiroom", approvedPageForControl("immortal", it)) }
        assertEquals("appearance", approvedPageForControl("immortal", "hideStatusBar"))
        assertNull(approvedPageForControl("immortal", "unrecognized-field"))
    }

    @Test fun registryNavigationUsesItsDeclaredActivity() {
        val index = approvedSettingsSearchIndex()
        assertEquals("calendar-url", index.single { it.id == "calendar.url" }.page)
        assertEquals("sleep", index.single { it.id == "screensaver.sleepTimerMin" }.page)
        val refresh = index.single { it.id == "screensaver.albumRefreshMin" }
        assertEquals("sources", refresh.page)
        assertTrue(!refresh.automatic)
        assertTrue(index.filter { it.id.isNotBlank() }.all {
            approvedSettingsPage(it.page) != null ||
                ApprovedSettingsCategories.any { category -> category.id == it.page }
        })
    }
    @Test fun nativeHomeAssistantSetupRequiresAdvancedWhileWebAddressStaysOrdinary() {
        val index = approvedSettingsSearchIndex()
        val web = approvedSettingsSearch(index, "Dashboard address")
            .single { it.id == "home_dashboard.url" }
        assertEquals("ha", web.page)
        assertTrue(!web.advanced)
        val server = approvedSettingsSearch(index, "Base URL").single { it.id == "ha.url" }
        val token = approvedSettingsSearch(index, "Long-lived token").single { it.id == "ha.token" }
        assertEquals("ha", server.page)
        assertEquals("ha", token.page)
        assertTrue(server.advanced)
        assertTrue(token.advanced)
        val entities = approvedSettingsSearch(index, "Choose Home Assistant entities")
            .single { it.page == "ha-entities" && it.id.isBlank() }
        assertTrue(entities.advanced)
    }
}
