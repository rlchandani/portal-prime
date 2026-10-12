/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class ApprovedPhotoPreviewOwnershipTest {
    private fun bitmap(width: Int, height: Int) = mock(Bitmap::class.java).also {
        `when`(it.width).thenReturn(width); `when`(it.height).thenReturn(height)
    }

    @Test fun centeredCropTransfersOutputAndReleasesIntermediate() {
        val input = bitmap(528, 396); val output = bitmap(528, 352)
        val plan = ApprovedPhotoPreviewPlan(528, 396, 0, 22, 528, 352)
        assertSame(output, approvedCroppedPhotoPreview(input, plan) { bitmap, geometry ->
            assertSame(input, bitmap); assertEquals(plan, geometry); output
        })
        verify(input).recycle(); verify(output, never()).recycle()
    }

    @Test fun matchingAspectTransfersTheSameBitmapWithoutAllocationOrRecycle() {
        val input = bitmap(528, 352)
        val plan = ApprovedPhotoPreviewPlan(528, 352, 0, 0, 528, 352)
        assertSame(input, approvedCroppedPhotoPreview(input, plan) { _, _ -> error("unexpected_crop") })
        verify(input, never()).recycle()
    }

    @Test fun failedCropReleasesItsIntermediateAndPreservesFailure() {
        val input = bitmap(528, 396)
        val plan = ApprovedPhotoPreviewPlan(528, 396, 0, 22, 528, 352)
        val failure = IllegalStateException("fixture")
        val result = runCatching { approvedCroppedPhotoPreview(input, plan) { _, _ -> throw failure } }
        assertSame(failure, result.exceptionOrNull())
        verify(input).recycle()
    }
}
