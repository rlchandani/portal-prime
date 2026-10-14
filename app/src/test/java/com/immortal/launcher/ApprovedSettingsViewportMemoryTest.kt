/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class ApprovedSettingsViewportMemoryTest {
    @Test fun independentRoutesPreserveBothRowIndexAndPixelOffset() {
        val memory = ApprovedSettingsViewportMemory()
        memory.save("list:photos/screensaver/", ApprovedSettingsViewportMemory.Position(9, 41))
        memory.save("column:photos/faces/", ApprovedSettingsViewportMemory.Position(offset = 380))
        assertEquals(ApprovedSettingsViewportMemory.Position(9, 41), memory.read("list:photos/screensaver/"))
        assertEquals(ApprovedSettingsViewportMemory.Position(0, 380), memory.read("column:photos/faces/"))
        assertNull(memory.read("list:photos/screensaver/screensaver.sound"))
    }

    @Test fun defaultBoundMatchesHistoryWithoutRetainingOldestRoutes() {
        val memory = ApprovedSettingsViewportMemory()
        repeat(33) { memory.save("page:$it", ApprovedSettingsViewportMemory.Position(it, it * 11)) }
        assertNull(memory.read("page:0"))
        for (i in 1..32) assertEquals(ApprovedSettingsViewportMemory.Position(i, i * 11), memory.read("page:$i"))
    }

    @Test fun updatedRouteIsNotEvictedBeforeOlderUnchangedRoute() {
        val memory = ApprovedSettingsViewportMemory(2)
        memory.save("first", ApprovedSettingsViewportMemory.Position(offset = 10))
        memory.save("second", ApprovedSettingsViewportMemory.Position(offset = 20))
        memory.save("first", ApprovedSettingsViewportMemory.Position(offset = 30))
        memory.save("third", ApprovedSettingsViewportMemory.Position(offset = 40))
        assertEquals(ApprovedSettingsViewportMemory.Position(offset = 30), memory.read("first"))
        assertNull(memory.read("second"))
    }

    @Test fun invalidPositionDoesNotOverwriteValidNavigationMemory() {
        val memory = ApprovedSettingsViewportMemory()
        val valid = ApprovedSettingsViewportMemory.Position(4, 7)
        memory.save("page", valid)
        for (invalid in listOf(ApprovedSettingsViewportMemory.Position(-1, 0),
                ApprovedSettingsViewportMemory.Position(0, -1))) {
            try { memory.save("page", invalid); fail("Negative viewport must be rejected") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(valid, memory.read("page"))
    }
}
