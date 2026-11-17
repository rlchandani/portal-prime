/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockFacePreviewSpecTest {
    @Test fun everyCatalogFaceHasItsOwnPlacement() {
        val placements = FaceCatalog.entries.associate { it.id to ClockFacePreview.spec(true, it.id, 1).placement }
        assertEquals(FacePlacement.CORNER_CLASSIC, placements[FaceCatalog.DEFAULT_ID])
        assertEquals(FacePlacement.FLIP, placements["flip"])
        assertEquals(FacePlacement.CENTER, placements["big"])
        assertEquals(FacePlacement.CENTER, placements["bold"])
        assertEquals(FacePlacement.CORNER_SMALL, placements["minimal"])
    }

    @Test fun sizesScaleRelativeToMedium() {
        val big = FaceCatalog.entryFor("big")
        assertEquals(1f, ClockFacePreview.sizeFactor(big, 1), 0f)
        assertEquals(280f / 380f, ClockFacePreview.sizeFactor(big, 0), 1e-4f)
        assertEquals(460f / 380f, ClockFacePreview.sizeFactor(big, 9), 1e-4f)
        assertEquals(1f, ClockFacePreview.sizeFactor(FaceCatalog.entryFor("minimal"), 2), 0f)
        assertTrue(ClockFacePreview.spec(true, "big", 2).timeSp > ClockFacePreview.spec(true, "big", 0).timeSp)
    }

    @Test fun captionNamesFaceAndSize() {
        assertEquals("Big clock · Large · live", ClockFacePreview.spec(true, "big", 2).caption)
        assertEquals("Minimal · live", ClockFacePreview.spec(true, "minimal", 2).caption)
        assertEquals("${Brand.NAME} · live", ClockFacePreview.spec(true, null, 0).caption)
        assertEquals("Clock face hidden", ClockFacePreview.spec(false, "flip", 1).caption)
        assertFalse(ClockFacePreview.spec(false, "flip", 1).shown)
    }

    @Test fun onlyDatedFacesShowADate() {
        assertNull(ClockFacePreview.spec(true, "big", 1).dateSp)
        assertTrue(ClockFacePreview.spec(true, "bold", 1).condensed)
        assertTrue((ClockFacePreview.spec(true, FaceCatalog.DEFAULT_ID, 1).dateSp ?: 0f) > 0f)
    }

    @Test fun unknownFacesFallBackToTheDefault() {
        assertEquals(ClockFacePreview.spec(true, FaceCatalog.DEFAULT_ID, 1), ClockFacePreview.spec(true, "retired-face", 1))
    }
}
