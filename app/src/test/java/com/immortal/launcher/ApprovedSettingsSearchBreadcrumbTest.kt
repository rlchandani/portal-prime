/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class ApprovedSettingsSearchBreadcrumbTest {
    @Test fun pageResultsExplainTheirSidebarGroupInsteadOfRepeatingTheTitle() {
        val index = approvedSettingsSearchIndex()
        assertEquals("Appearance", approvedSettingsSearchBreadcrumb(index.single { it.id.isEmpty() && it.page == "display" }))
        assertEquals("Home & widgets", approvedSettingsSearchBreadcrumb(index.single { it.id.isEmpty() && it.page == "weather" }))
        assertEquals("Photos & screensaver", approvedSettingsSearchBreadcrumb(index.single { it.id.isEmpty() && it.page == "sources" }))
        for (result in index.filter { it.id.isEmpty() }) {
            val expected = ApprovedSettingsCategories.single { it.id == approvedSettingsCategory(result.page) }.title
            assertEquals(result.page, expected, approvedSettingsSearchBreadcrumb(result))
        }
    }
    @Test fun sameNamedControlsUseTheParentGroupCaseInsensitively() {
        val result = ApprovedSettingsSearchResult("tOuCh SoUnDs", "touch", "display", "touchSounds")
        assertEquals("Sound & wake", approvedSettingsSearchBreadcrumb(result))
    }
    @Test fun ordinaryControlsStillExplainTheirPage() {
        val result = approvedSettingsSearchIndex().single { it.id == "display.brightness" }
        assertEquals("Display", approvedSettingsSearchBreadcrumb(result))
    }
    @Test fun meaningfulRegistrySectionsStayVisible() {
        val result = approvedSettingsSearchIndex().single { it.id == "calendar.side" }
        assertEquals("Calendar · Screensaver widget", approvedSettingsSearchBreadcrumb(result))
    }
    @Test fun duplicatePageAndCategorySectionsAreOmitted() {
        assertEquals("Home & widgets", approvedSettingsSearchBreadcrumb(
            ApprovedSettingsSearchResult("Calendar", "calendar", section = "cALendar")))
        assertEquals("Home & widgets", approvedSettingsSearchBreadcrumb(
            ApprovedSettingsSearchResult("Calendar", "calendar", section = "HOME & WIDGETS")))
    }
    @Test fun knownCategoryOnlyDestinationsHaveUsefulContext() {
        val result = ApprovedSettingsSearchResult("Advanced settings", "advanced", "immortal", "advancedSettings")
        assertEquals("Advanced", approvedSettingsSearchBreadcrumb(result))
    }
    @Test fun unknownDestinationsDoNotInventAnAppearanceGroup() {
        val result = ApprovedSettingsSearchResult("Something", "unknown-page", section = "Existing section")
        assertEquals("Existing section", approvedSettingsSearchBreadcrumb(result))
    }
    @Test fun advancedPageContextUsesItsCanonicalRouteAndRetainsItsSection() {
        val result = ApprovedSettingsSearchResult("Connect Immich", "immich", section = "Credentials")
        assertEquals("Advanced · Credentials", approvedSettingsSearchBreadcrumb(result))
        assertEquals("immich", result.page)
        assertTrue(result.advanced)
    }
}
