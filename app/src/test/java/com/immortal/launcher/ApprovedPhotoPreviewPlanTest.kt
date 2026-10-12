/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class ApprovedPhotoPreviewPlanTest {
    private val cell = ApprovedSourcePreviewSize(528, 352)
    @Test fun landscapeUsesCellWidthAndCentersTheVerticalCrop() {
        assertEquals(ApprovedPhotoPreviewPlan(528, 396, 0, 22, 528, 352),
            approvedPhotoPreviewPlan(4000, 3000, cell))
    }
    @Test fun portraitKeepsEnoughPixelsForTheSameVisibleCell() {
        assertEquals(ApprovedPhotoPreviewPlan(528, 704, 0, 176, 528, 352),
            approvedPhotoPreviewPlan(3000, 4000, cell))
    }
    @Test fun widePhotoCentersTheHorizontalCrop() {
        assertEquals(ApprovedPhotoPreviewPlan(704, 352, 88, 0, 528, 352),
            approvedPhotoPreviewPlan(6000, 3000, cell))
    }
    @Test fun matchingAspectNeedsNoAdditionalCrop() {
        assertEquals(ApprovedPhotoPreviewPlan(528, 352, 0, 0, 528, 352),
            approvedPhotoPreviewPlan(6000, 4000, cell))
    }
    @Test fun measuredCellSizeChangesTheOutputWithoutUsingDisplayDpAsPixels() {
        assertEquals(ApprovedPhotoPreviewPlan(600, 450, 0, 25, 600, 400),
            approvedPhotoPreviewPlan(4000, 3000, ApprovedSourcePreviewSize(600, 400)))
    }
    @Test fun smallOriginalIsNeverUpscaledInsideTheDecoder() {
        val plan = approvedPhotoPreviewPlan(128, 128, cell)!!
        assertEquals(128, plan.width); assertEquals(128, plan.height)
        assertEquals(128, plan.cropWidth); assertEquals(85, plan.cropHeight)
        assertEquals(0, plan.left); assertEquals(21, plan.top)
    }
    @Test fun invalidBoundsOrUnboundedCellAreRejected() {
        for ((w, h) in listOf(0 to 5, 5 to 0, -1 to 5)) assertNull(approvedPhotoPreviewPlan(w, h, cell))
        for (size in listOf(ApprovedSourcePreviewSize(0, 5), ApprovedSourcePreviewSize(5, 0),
            ApprovedSourcePreviewSize(2049, 5))) assertNull(approvedPhotoPreviewPlan(4000, 3000, size))
    }
    @Test fun extremeAspectHasBoundedIntermediatePixelsAndContainedCrop() {
        for ((w, h) in listOf(Int.MAX_VALUE to 1, 1 to Int.MAX_VALUE, 100000 to 100000)) {
            val p = approvedPhotoPreviewPlan(w, h, cell)!!
            assertTrue(p.width in 1..2400 && p.height in 1..2400)
            assertTrue(p.width.toLong() * p.height <= SlideshowDecodeBudget.MAX_PIXELS)
            assertTrue(p.cropWidth in 1..p.width && p.cropHeight in 1..p.height)
            assertTrue(p.left >= 0 && p.left + p.cropWidth <= p.width)
            assertTrue(p.top >= 0 && p.top + p.cropHeight <= p.height)
        }
    }
    @Test fun landscapeReturnedPixelBudgetDropsWithoutReducingCellResolution() {
        val p = approvedPhotoPreviewPlan(4000, 3000, cell)!!
        assertEquals(743424L, p.cropWidth.toLong() * p.cropHeight * 4)
        assertTrue(p.cropWidth.toLong() * p.cropHeight * 4 < 3000000L / 4)
    }
}
