/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live preview follows the photo-fit setting for every style that honours it
 * ([PhotoFitPolicy.honoursFit]) and ignores it for the rest, matching the real stage.
 */
class SlideshowPreviewFitTest {
    private val fill = ScreensaverConfig.FIT_FILL
    private val fit = ScreensaverConfig.FIT_FIT

    private fun slides(mode: String, fitValue: String) =
        SlideshowPreview.spec(ScreensaverConfig.Settings(enabled = true, mode = mode, fit = fitValue)).slides

    @Test fun smartFrameFillsOrShowsWhole() {
        assertEquals(List(SlideshowPreview.SLIDE_COUNT) { SlideLayout.FILL }, slides(ScreensaverConfig.MODE_SMART, fill))
        assertEquals(List(SlideshowPreview.SLIDE_COUNT) { SlideLayout.WHOLE }, slides(ScreensaverConfig.MODE_SMART, fit))
    }

    @Test fun portraitPairCoversOrShrinksCardsToThePhoto() {
        assertEquals(List(SlideshowPreview.SLIDE_COUNT) { SlideLayout.PAIR }, slides(ScreensaverConfig.MODE_PORTRAITS, fill))
        assertEquals(List(SlideshowPreview.SLIDE_COUNT) { SlideLayout.PAIR_WHOLE }, slides(ScreensaverConfig.MODE_PORTRAITS, fit))
    }

    @Test fun classicCropsOrLetterboxes() {
        assertEquals(List(SlideshowPreview.SLIDE_COUNT) { SlideLayout.FILL }, slides(ScreensaverConfig.MODE_CLASSIC, fill))
        assertEquals(List(SlideshowPreview.SLIDE_COUNT) { SlideLayout.WHOLE }, slides(ScreensaverConfig.MODE_CLASSIC, fit))
    }

    @Test fun everyHonouringStyleChangesWithFitAndNoOtherDoes() {
        ScreensaverConfig.MODES.forEach { mode ->
            val changes = slides(mode, fill) != slides(mode, fit)
            assertEquals(mode, PhotoFitPolicy.honoursFit(mode), changes)
        }
    }

    @Test fun fitMatchesTheRealStagePresentation() {
        // Wherever fit is honoured, the preview fills exactly when the real stage fills.
        listOf(ScreensaverConfig.MODE_SMART, ScreensaverConfig.MODE_PORTRAITS, ScreensaverConfig.MODE_CLASSIC).forEach { mode ->
            listOf(fill, fit).forEach { value ->
                val settings = ScreensaverConfig.Settings(mode = mode, fit = value)
                val stageFills = PhotoFrameStagePolicy.presentation(settings, nightPhoto = false, splitGlance = false).fill
                val previewFills = SlideshowPreview.layoutFor(mode, value) in setOf(SlideLayout.FILL, SlideLayout.PAIR)
                assertEquals("$mode $value", stageFills, previewFills)
            }
        }
    }

    @Test fun pairCardHugsItsWholePhotoCentredInTheSlot() {
        val card = SlideshowPreview.pairCard(981, 1472, 400, 500)
        assertEquals(CompactStagePlan.contain(981, 1472, ScreensaverLayout.Box(0, 0, 400, 500)), card)
        assertEquals(500, card.height)
        assertTrue(card.width < 400)
        assertTrue("centred", kotlin.math.abs((400 - card.right) - card.left) <= 1)
        // A landscape in a tall slot spans the width and sits mid-height.
        val wide = SlideshowPreview.pairCard(1472, 981, 400, 500)
        assertEquals(400, wide.width)
        assertTrue("mid-height", kotlin.math.abs((500 - wide.bottom) - wide.top) <= 1)
    }

    @Test fun pairCardFallsBackToTheSlotForAnUnknownPhoto() {
        assertEquals(ScreensaverLayout.Box(0, 0, 400, 500), SlideshowPreview.pairCard(0, 0, 400, 500))
        assertEquals(ScreensaverLayout.Box(0, 0, 0, 0), SlideshowPreview.pairCard(981, 1472, 0, 0))
    }
}
