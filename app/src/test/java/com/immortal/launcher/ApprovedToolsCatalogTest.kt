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
}
