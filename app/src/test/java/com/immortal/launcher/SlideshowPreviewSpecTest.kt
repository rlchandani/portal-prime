/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideshowPreviewSpecTest {
    private val base = ScreensaverConfig.Settings()

    @Test fun eachNativeStyleHasItsOwnSlides() {
        assertEquals(SlideLayout.FILL, SlideshowPreview.layoutFor(ScreensaverConfig.MODE_SMART, ScreensaverConfig.FIT_FILL))
        assertEquals(SlideLayout.PAIR, SlideshowPreview.layoutFor(ScreensaverConfig.MODE_PORTRAITS, ScreensaverConfig.FIT_FILL))
        assertEquals(SlideLayout.WHOLE, SlideshowPreview.layoutFor(ScreensaverConfig.MODE_WHOLE, ScreensaverConfig.FIT_FILL))
        assertEquals(SlideLayout.PRINT, SlideshowPreview.layoutFor(ScreensaverConfig.MODE_GALLERY_PRINT, ScreensaverConfig.FIT_FILL))
        assertEquals(SlideLayout.PAIR, SlideshowPreview.layoutFor(ScreensaverConfig.MODE_GALLERY_SPREAD, ScreensaverConfig.FIT_FILL))
    }

    @Test fun classicFollowsFitAndShuffleMixesStyles() {
        assertEquals(SlideLayout.WHOLE, SlideshowPreview.layoutFor(ScreensaverConfig.MODE_CLASSIC, ScreensaverConfig.FIT_FIT))
        assertEquals(SlideLayout.FILL, SlideshowPreview.layoutFor(ScreensaverConfig.MODE_CLASSIC, ScreensaverConfig.FIT_FILL))
        assertEquals(listOf(SlideLayout.FILL, SlideLayout.PAIR, SlideLayout.PRINT),
            SlideshowPreview.slidesFor(ScreensaverConfig.MODE_SHUFFLE, ScreensaverConfig.FIT_FILL))
        ScreensaverConfig.MODES.forEach { assertEquals(it, SlideshowPreview.SLIDE_COUNT, SlideshowPreview.slidesFor(it, "fill").size) }
    }

    @Test fun pushStylesSlideAndTheRestCrossfade() {
        assertEquals(SlideTransition.PUSH, SlideshowPreview.transitionFor("push"))
        assertEquals(SlideTransition.PUSH, SlideshowPreview.transitionFor("filmstrip"))
        assertEquals(SlideTransition.CROSSFADE, SlideshowPreview.transitionFor(ScreensaverConfig.MODE_SMART))
    }

    @Test fun motionNeedsBothTheSwitchAndAStyleThatMoves() {
        assertTrue(SlideshowPreview.spec(base.copy(mode = ScreensaverConfig.MODE_SMART, photoMotion = true)).motion)
        assertFalse(SlideshowPreview.spec(base.copy(mode = ScreensaverConfig.MODE_SMART, photoMotion = false)).motion)
        assertFalse(SlideshowPreview.spec(base.copy(mode = ScreensaverConfig.MODE_GALLERY_PRINT, photoMotion = true)).motion)
    }

    @Test fun captionNamesStyleMotionAndInterval() {
        val spec = SlideshowPreview.spec(base.copy(enabled = true, mode = ScreensaverConfig.MODE_SMART, photoMotion = true, intervalSec = 30))
        assertEquals("Smart frame · motion · every 30 s (preview: 4 s)", spec.caption)
        val classic = SlideshowPreview.spec(base.copy(enabled = true, mode = ScreensaverConfig.MODE_CLASSIC, intervalSec = 120))
        assertEquals("Classic · still · every 2 min (preview: 4 s)", classic.caption)
        assertEquals("Photo frame is off", SlideshowPreview.spec(base.copy(enabled = false)).caption)
    }

    @Test fun overlaysFollowTheirSwitches() {
        val spec = SlideshowPreview.spec(base.copy(facesEnabled = false, showNowPlaying = true))
        assertFalse(spec.showClock)
        assertTrue(spec.showNowPlaying)
    }

    @Test fun slidesWrap() {
        assertEquals(1, SlideshowPreview.next(0))
        assertEquals(0, SlideshowPreview.next(2))
        assertEquals(0, SlideshowPreview.next(5, 0))
    }
}
