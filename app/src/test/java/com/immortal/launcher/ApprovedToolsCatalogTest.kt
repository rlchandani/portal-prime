/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class ApprovedToolsCatalogTest {
    @Test fun advancedToolCannotBeOpenedOrFoundWithoutConsent() {
        assertEquals(11, ApprovedToolsCatalog.visible(false).size)
        assertNull(ApprovedToolsCatalog.resolve("speed", false))
        assertTrue(ApprovedToolsCatalog.visible(false, query = "speed").isEmpty())
        assertEquals("speed", ApprovedToolsCatalog.resolve("speed", true)?.id)
        assertEquals(12, ApprovedToolsCatalog.visible(true).size)
    }

    @Test fun searchTrimsWhitespaceAndKeepsTheCategoryBoundary() {
        assertEquals(listOf("camera"), ApprovedToolsCatalog.visible(false, query = "  CAMERA ").map { it.id })
        assertTrue(ApprovedToolsCatalog.visible(false, "Everyday", "camera").isEmpty())
        assertEquals(listOf("iss", "aurora"), ApprovedToolsCatalog.visible(false, "Discover").map { it.id })
        assertEquals(11, ApprovedToolsCatalog.visible(false, query = "  ").size)
    }

    @Test fun unknownRouteReturnsLibraryInsteadOfOpeningAnUnrelatedTool() {
        assertNull(ApprovedToolsCatalog.resolve("missing", true))
        assertNull(ApprovedToolsCatalog.resolve(null, true))
    }
    @Test fun hiddenAdvancedCategoryReturnsOrdinaryLibrary() {
        val group = ApprovedToolsCatalog.resolveGroup("Advanced", false)
        assertEquals("All tools", group)
        val entries = ApprovedToolsCatalog.visible(false, group)
        assertEquals(11, entries.size)
        assertFalse(entries.any { it.id == "speed" })
    }

    @Test fun validCategoriesArePreservedAcrossAdvancedChanges() {
        for (group in listOf("All tools", "Everyday", "Home & downtime", "Discover")) {
            assertEquals(group, ApprovedToolsCatalog.resolveGroup(group, false))
            assertEquals(group, ApprovedToolsCatalog.resolveGroup(group, true))
        }
        assertFalse("Advanced" in ApprovedToolsCatalog.categories(false))
        assertTrue("Advanced" in ApprovedToolsCatalog.categories(true))
        assertEquals("Advanced", ApprovedToolsCatalog.resolveGroup("Advanced", true))
    }

    @Test fun correctedCategoryStillRespectsSearchText() {
        val group = ApprovedToolsCatalog.resolveGroup("Advanced", false)
        assertEquals(listOf("lamp"), ApprovedToolsCatalog.visible(false, group, "  lamp  ").map { it.id })
        assertTrue(ApprovedToolsCatalog.visible(false, group, "missing-tool").isEmpty())
    }

    @Test fun enablingAdvancedAgainKeepsTheCorrectedSelection() {
        val corrected = ApprovedToolsCatalog.resolveGroup("Advanced", false)
        assertEquals("All tools", ApprovedToolsCatalog.resolveGroup(corrected, true))
        assertEquals(12, ApprovedToolsCatalog.visible(true, corrected).size)
    }

    @Test fun unknownRestoredCategoryFallsBackToVisibleAllTools() {
        for (advanced in listOf(false, true)) {
            assertEquals("All tools", ApprovedToolsCatalog.resolveGroup("Old category", advanced))
            assertEquals("All tools", ApprovedToolsCatalog.resolveGroup("", advanced))
        }
    }

}
