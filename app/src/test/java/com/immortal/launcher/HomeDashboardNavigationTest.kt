/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeDashboardNavigationTest {
    @Test fun dashboardAddressSearchOpensThePageThatRendersItsRegistryRow() {
        assertEquals("ha", approvedPageForControl("home_dashboard", "url"))
        val result = approvedSettingsSearchIndex().single { it.id == "home_dashboard.url" }
        assertEquals("ha", result.page)
        assertEquals("Dashboard address", result.title)
    }
}
