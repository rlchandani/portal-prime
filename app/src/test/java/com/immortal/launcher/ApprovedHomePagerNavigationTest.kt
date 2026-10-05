/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class ApprovedHomePagerNavigationTest {
    @Test fun olderProgrammaticProgressCannotOverwriteANewerRequest() {
        val navigation = ApprovedHomePagerNavigation()
        navigation.request(1)
        navigation.request(2)
        assertNull(navigation.observe(1))
        assertNull(navigation.observe(2))
        assertEquals(3, navigation.observe(3))
        assertNull(navigation.observe(3))
    }

    @Test fun samePageRequestsNeedNoFlowEventBeforeFocusNavigationCanResume() {
        val navigation = ApprovedHomePagerNavigation(1)
        navigation.request(1)
        navigation.acknowledge(1)
        assertEquals(0, navigation.observe(0))
    }

    @Test fun parentClampingSuppressesOldPagerEventsUntilTheClampedTargetArrives() {
        val navigation = ApprovedHomePagerNavigation(2)
        navigation.reflectParent(0)
        navigation.reflectParent(0)
        navigation.acknowledge(1)
        assertNull(navigation.observe(2))
        assertNull(navigation.observe(0))
        assertEquals(1, navigation.observe(1))
    }
}
