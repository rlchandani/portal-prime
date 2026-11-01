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

    @Test fun secondsIndicatorGetsItsOwnSpaceWithoutChangingTheNominalFontOrHeight() {
        val plain = size(100)
        val seconds = DigitalClockGeometry.measure(1.5f, 100, 2.8f, 1.2f, 2040, 1320, 3f,
            showSeconds = true)
        assertEquals(plain.textSize, seconds.textSize, 0f)
        assertEquals(plain.height, seconds.height)
        assertTrue("Indicator and gap need a separate trailing slot",
            seconds.width - plain.width >= seconds.textSize * 0.8f)
    }

    @Test fun textAndSecondsSlotFitTogetherInTightAndLargeParents() {
        for (width in listOf(60, 240, 1320, 2040)) {
            for (height in listOf(20, 180, 1320)) {
                for (scale in listOf(90, 100, 280, 380, 460, 500)) {
                    val seconds = DigitalClockGeometry.measure(1.5f, scale, 2.8f, 1.2f,
                        width, height, 3f, showSeconds = true)
                    assertTrue(seconds.width <= width && seconds.height <= height)
                    // Independent lower bound: text plus the visible ring and a gap.
                    assertTrue("A ring cannot share the text's reserved width",
                        seconds.textSize * 3.6f + 6f <= seconds.width + 0.001f)
                    assertTrue(seconds.textSize * 1.2f + 6f <= seconds.height + 0.001f)
                }
            }
        }
    }

    @Test fun explicitSecondsOffPreservesTheOriginalGeometryExactly() {
        for (scale in listOf(90, 100, 280, 380, 460, 500)) {
            assertEquals(size(scale), DigitalClockGeometry.measure(1.5f, scale, 2.8f, 1.2f,
                2040, 1320, 3f, showSeconds = false))
        }
    }

    @Test fun secondsKeepTheLargeFamilySizesDistinctInPortrait() {
        val options = listOf(320, 430, 500).map {
            DigitalClockGeometry.measure(1.5f, it, 2.8f, 1.2f, 1320, 2040, 3f,
                showSeconds = true)
        }
        options.zipWithNext().forEach { (a, b) ->
            assertTrue(a.width < b.width && a.height < b.height)
        }
        assertEquals(430f / 320f, options[1].textSize / options[0].textSize, 0.0001f)
    }

    @Test fun secondsRespectEmptyAndOversizedParents() {
        assertEquals(DigitalClockGeometry.Size(0f, 0, 0),
            DigitalClockGeometry.measure(1.5f, 500, 2.8f, 1.2f, 0, 0, 3f, showSeconds = true))
        val result = DigitalClockGeometry.measure(1.5f, Int.MAX_VALUE, 2.8f, 1.2f,
            2040, 1320, 3f, showSeconds = true)
        assertTrue(result.textSize.isFinite() && result.width <= 2040 && result.height <= 1320)
        assertTrue(result.textSize * 3.6f + 6f <= result.width + 0.001f)
    }
}
