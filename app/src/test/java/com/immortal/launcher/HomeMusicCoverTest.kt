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

class HomeMusicCoverTest {
    @Test fun aJustOpenedFullScreenMusicLeavesHomeAsItWas() {
        assertNull(HomeMusicCover.homeLayout(MusicPlayerLayout.FULL, covered = false))
    }

    @Test fun homeGoesInactiveOnceMusicHasSettled() {
        assertEquals(MusicPlayerLayout.FULL, HomeMusicCover.homeLayout(MusicPlayerLayout.FULL, covered = true))
    }

    @Test fun closingMusicRestoresHomeAtOnceEvenBeforeTheCoverClears() {
        assertNull(HomeMusicCover.homeLayout(null, covered = true))
        assertNull(HomeMusicCover.homeLayout(null, covered = false))
    }

    @Test fun inlineLayoutsShowOverHomeImmediately() {
        assertEquals(MusicPlayerLayout.COMPACT, HomeMusicCover.homeLayout(MusicPlayerLayout.COMPACT, covered = false))
        assertEquals(MusicPlayerLayout.PHOTOS, HomeMusicCover.homeLayout(MusicPlayerLayout.PHOTOS, covered = false))
    }

    @Test fun leavingAnInactiveHomeTakesThreeFramesAndOnlyTheLayerSwapIsSeen() {
        assertEquals(listOf(MusicCloseStep.UNCOVER_HOME, MusicCloseStep.SETTLE, MusicCloseStep.SHOW_HOME, MusicCloseStep.REMOVE_MUSIC),
            HomeMusicCover.closePlan(MusicPlayerLayout.FULL, covered = true))
    }

    @Test fun musicClosedBeforeHomeWentInactiveJustGoes() {
        assertEquals(listOf(MusicCloseStep.REMOVE_MUSIC), HomeMusicCover.closePlan(MusicPlayerLayout.FULL, covered = false))
        assertEquals(listOf(MusicCloseStep.REMOVE_MUSIC), HomeMusicCover.closePlan(MusicPlayerLayout.COMPACT, covered = true))
        assertEquals(listOf(MusicCloseStep.REMOVE_MUSIC), HomeMusicCover.closePlan(null, covered = false))
    }

    @Test fun homeIsAZeroAlphaLayerUnderFullScreenMusicUntilShown() {
        assertEquals(0f, HomeMusicCover.homeAlpha(MusicPlayerLayout.FULL, shownOverMusic = false), 0f)
        assertEquals(1f, HomeMusicCover.homeAlpha(MusicPlayerLayout.FULL, shownOverMusic = true), 0f)
        assertEquals(1f, HomeMusicCover.homeAlpha(null, shownOverMusic = false), 0f)
        assertEquals(1f, HomeMusicCover.homeAlpha(MusicPlayerLayout.COMPACT, shownOverMusic = false), 0f)
        assertEquals(1f, HomeMusicCover.homeAlpha(MusicPlayerLayout.PHOTOS, shownOverMusic = false), 0f)
    }

    @Test fun musicHidesInTheSameFrameHomeShows() {
        assertEquals(1f, HomeMusicCover.musicAlpha(homeShown = false), 0f)
        assertEquals(0f, HomeMusicCover.musicAlpha(homeShown = true), 0f)
    }

    @Test fun theNowPlayingCardWaitsUntilMusicHasGone() {
        assertTrue(HomeMusicCover.cardSeesMusicOpen(homeLayout = null, closing = true))
        assertFalse(HomeMusicCover.cardSeesMusicOpen(homeLayout = null, closing = false))
        assertTrue(HomeMusicCover.cardSeesMusicOpen(MusicPlayerLayout.FULL, closing = false))
    }
}
