/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class ApprovedSettingsLazyProjectionTest {
    @Test fun deepLinkIndicesIncludeHeadersAndGaps() {
        val result = approvedSettingsLazyProjection("frame", linkedMapOf(
            null to listOf("enabled"), "Photos" to listOf("fit", "shuffle"),
            "Advanced options" to listOf("cache"),
        )) { it }
        assertEquals(mapOf("frame.enabled" to 0, "frame.fit" to 3,
            "frame.shuffle" to 4, "frame.cache" to 7), result.controlIndices)
        result.controlIndices.forEach { (id, index) ->
            assertEquals(id, result.items[index].id)
            assertEquals(ApprovedSettingsLazyKind.CONTROL, result.items[index].kind)
        }
        assertEquals(result.items.size, result.items.map { it.id }.toSet().size)
    }

    @Test fun hidingRowsUpdatesCardEdgesAndIndicesWithoutChangingSurvivingIds() {
        val before = approvedSettingsLazyProjection("clock",
            linkedMapOf("Face" to listOf("enabled", "face", "size"))) { it }
        val after = approvedSettingsLazyProjection("clock",
            linkedMapOf("Face" to listOf("enabled"))) { it }
        assertEquals(before.items[1].id, after.items[1].id)
        assertTrue(after.items[1].firstRow)
        assertTrue(after.items[1].lastRow)
        assertFalse(before.items[1].lastRow)
        assertNull(after.controlIndices["clock.face"])
        assertEquals(1, after.controlIndices["clock.enabled"])
    }

    @Test fun emptyGroupsDoNotCreateOrphanHeadersOrBreakFirstSectionSpacing() {
        val result = approvedSettingsLazyProjection("test",
            linkedMapOf("Hidden" to emptyList(), "Visible" to listOf("value"))) { it }
        assertEquals(3, result.items.size)
        assertTrue(result.items.first().firstGroup)
        assertEquals("Visible", result.items.first().section)
        assertEquals(1, result.controlIndices["test.value"])
        assertTrue(approvedSettingsLazyProjection<String>("test", emptyMap()) { it }.items.isEmpty())
    }
}
