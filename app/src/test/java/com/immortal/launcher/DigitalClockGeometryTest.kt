/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.ui.clock.DigitalClockGeometry
import org.junit.Assert.*
import org.junit.Test

class DigitalClockGeometryTest {
    private fun size(scale: Int, width: Int = 2040, height: Int = 1320) =
        DigitalClockGeometry.measure(1.5f, scale, 2.8f, 1.2f, width, height, 3f)

    @Test fun bigOptionsProduceDistinctProportionalLayouts() {
        val small = size(280)
        val medium = size(380)
        val large = size(460)
        assertTrue(small.width < medium.width && medium.width < large.width)
        assertTrue(small.height < medium.height && medium.height < large.height)
        assertEquals(380f / 280f, medium.textSize / small.textSize, 0.0001f)
        assertEquals(460f / 380f, large.textSize / medium.textSize, 0.0001f)
    }

    @Test fun boldSizesRemainDistinctOnNarrowPortraitPanels() {
        val options = listOf(320, 430, 500).map { size(it, 1320, 2040) }
        options.zipWithNext().forEach { (a, b) ->
            assertTrue(a.width < b.width && a.height < b.height)
        }
        options.forEach { assertTrue(it.width <= 1320 && it.height <= 2040) }
    }

    @Test fun fontMetricsAndPaddingFitInBothDimensions() {
        for (width in listOf(60, 320, 1320, 2040)) {
            for (height in listOf(20, 180, 1320)) {
                for (scale in listOf(90, 100, 280, 380, 460, 500)) {
                    val result = size(scale, width, height)
                    assertTrue(result.width <= width && result.height <= height)
                    assertTrue(result.textSize >= 0f)
                    assertTrue(result.textSize * 2.8f + 6f <= result.width + 0.001f)
                    assertTrue(result.textSize * 1.2f + 6f <= result.height + 0.001f)
                }
            }
        }
    }

    @Test fun classicKeepsNominalSizeWhenThereIsRoom() {
        assertEquals(127.5f, size(100).textSize, 0.0001f)
    }

    @Test fun emptyParentDoesNotProduceNegativeOrNonfiniteGeometry() {
        val result = size(500, 0, 0)
        assertEquals(0, result.width)
        assertEquals(0, result.height)
        assertEquals(0f, result.textSize, 0f)
    }

    @Test fun oversizedCustomScaleStaysWithinParent() {
        val result = size(Int.MAX_VALUE)
        assertTrue(result.textSize.isFinite())
        assertTrue(result.width <= 2040 && result.height <= 1320)
    }
}
