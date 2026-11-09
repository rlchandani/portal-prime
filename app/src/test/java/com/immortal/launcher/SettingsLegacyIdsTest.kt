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

/** Deep links and saved routes that use the previous 8-category page ids keep working. */
class SettingsLegacyIdsTest {
    /** Every page id of the previous Settings registry (ApprovedSettingsPages, build 1.239). */
    private val oldPageIds = listOf(
        "touch", "display", "appearance", "wallpaper", "screensaver", "sources", "styles", "faces", "clock",
        "dismiss", "folder", "album", "immich", "albums", "smb", "dav", "web", "widgets", "cameras", "weather",
        "calendar", "almanac", "calendar-url", "world", "countdowns", "guests", "quickbar", "alarm", "crypto",
        "todo", "news", "stocks", "alpaca", "options", "chime", "music", "welcome", "sunrise", "lamp",
        "multiroom", "mqtt", "ha", "ha-entities", "ha-dashboard", "phone", "intercom", "boot", "device", "sleep",
        "health", "apk", "help", "speed", "system", "managed", "debug", "advanced",
    )
    private val oldCategories = listOf("appearance", "photos", "home", "sound", "connections", "access", "device", "advanced")

    @Test fun everyOldPageIdOpensANewPageOrItsOwnActivity() {
        oldPageIds.forEach { id ->
            when (val destination = SettingsLegacyIds.resolve(id)) {
                is SettingsDestination.Page -> assertTrue("$id → ${destination.page}", SettingsTree.contains(destination.page))
                is SettingsDestination.Activity ->
                    assertTrue("$id has no Activity", destination.id in SettingsActivityDestinations)
                null -> throw AssertionError("$id has no destination")
            }
        }
        assertEquals(SettingsLegacyIds.activityPages, SettingsActivityDestinations.keys)
        assertEquals(SettingsLegacyIds.activityPages, SettingsLegacyIds.activityHomes.keys)
        SettingsLegacyIds.activityHomes.values.forEach { assertTrue(it, SettingsTree.contains(it)) }
    }

    @Test fun mappedHighlightsAreDrawnOnTheirTargetPage() {
        SettingsLegacyIds.pages.forEach { (old, destination) ->
            val control = destination.control ?: return@forEach
            val layout = settingsPageLayout(destination.page, advanced = true, screensaverOn = true)
            assertTrue("$old → ${destination.page} does not draw $control", control in layout.controlIndex)
        }
    }

    @Test fun everyOldCategoryOpensItsSuccessorPage() {
        oldCategories.forEach { category ->
            val destination = SettingsLegacyIds.resolve(null, category)
            assertTrue(category, destination is SettingsDestination.Page && SettingsTree.contains(destination.page))
        }
        assertNull(SettingsLegacyIds.resolve(null, null))
        assertNull(SettingsLegacyIds.resolve("no-such-page"))
    }

    @Test fun homeWidgetMusicAndGuestDeepLinksLandOnTheRightPage() {
        fun page(id: String, category: String? = null) =
            (SettingsLegacyIds.resolve(id, category) as SettingsDestination.Page).page
        assertEquals("w-weather", page("weather"))
        assertEquals("w-calendar", page("calendar"))
        assertEquals("w-world", page("world"))
        assertEquals("w-crypto", page("crypto"))
        assertEquals("w-news", page("news"))
        assertEquals("w-stocks", page("stocks"))
        assertEquals("options", page("options"))
        assertEquals("alarms", page("alarm"))
        assertEquals("home", page("quickbar"))
        assertEquals("sources", page("sources"))
        assertEquals("system", page("system"))
        assertEquals("ha", page("ha"))
        // Music's "Set up multi-room" passes the old Connections category with the old page.
        assertEquals("music", page("multiroom", "connections"))
        assertEquals("w-guest", page("guests"))
        assertEquals("frame", page("screensaver"))
    }

    @Test fun newIdsResolveToThemselvesAndKeepAnExplicitRow() {
        SettingsTree.pages.forEach { page ->
            assertEquals(SettingsDestination.Page(page.id), SettingsLegacyIds.resolve(page.id))
        }
        assertEquals(SettingsDestination.Page("alarms", "sunrise.hour"), SettingsLegacyIds.resolve("sunrise", control = "sunrise.hour"))
        assertEquals(SettingsDestination.Page("sounds", "display.touchSounds"), SettingsLegacyIds.resolve("touch"))
    }

    @Test fun fullScreenEditorsKeepTheirActivity() {
        assertEquals(SettingsDestination.Activity("lamp"), SettingsLegacyIds.resolve("lamp"))
        assertEquals(SettingsDestination.Activity("calendar-url"), SettingsLegacyIds.resolve("calendar-url"))
        assertEquals(SettingsDestination.Activity("ha-dashboard"), SettingsLegacyIds.resolve("ha-dashboard"))
        assertEquals(LampActivity::class.java, SettingsActivityDestinations["lamp"])
        assertEquals(CameraViewerActivity::class.java, SettingsActivityDestinations["cameras"])
    }

    @Test fun registryNavigationRowsOpenTheirPageInPlace() {
        assertEquals("clock", settingsPageForActivity(FacePickerActivity::class.java))
        assertEquals("sources", settingsPageForActivity(ScreensaverSourcesActivity::class.java))
        assertEquals("dismiss", settingsPageForActivity(ScreensaverDismissAppActivity::class.java))
        assertNull(settingsPageForActivity(CalendarUrlEntryActivity::class.java))
        SettingsStandalonePages.values.forEach { assertTrue(it, SettingsTree.contains(it)) }
    }
}
