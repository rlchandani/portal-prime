/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
