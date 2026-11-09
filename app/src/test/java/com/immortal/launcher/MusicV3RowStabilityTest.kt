/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import com.immortal.launcher.youtube.YtTrack as YtDataTrack

/** Lazy-list keys, row models and thumbnail decode sizes for the music lists (perf fixes). */
class MusicV3RowStabilityTest {
  @Test fun keysAreVideoIdsWithASuffixOnlyOnRepeats() {
    assertEquals(listOf("a", "b", "a#2", "c", "a#3"), MusicV3RowKeys.unique(listOf("a", "b", "a", "c", "a")))
  }

  @Test fun keysDoNotShiftWhenARowIsInsertedAbove() {
    val before = MusicV3RowKeys.unique(listOf("x", "y", "z"))
    val after = MusicV3RowKeys.unique(listOf("new", "x", "y", "z"))
    assertEquals(before, after.drop(1))
    assertTrue(after.none { it.contains(":") })
  }

  @Test fun theSameTrackGivesAnEqualRowModelSoRowsCanSkip() {
    val t = YtDataTrack("vid12345", "Salt & Cedar", "Marigold Hours", "https://i.ytimg.com/vi/vid12345/mqdefault.jpg")
    val a = trackRowModel(t, "Liked songs:vid12345")
    val b = trackRowModel(t.copy(), "Liked songs:vid12345")
    assertEquals(a, b)
    assertEquals(a.hashCode(), b.hashCode())
    assertNotEquals(a, trackRowModel(t.copy(title = "Other"), "Liked songs:vid12345"))
  }

  @Test fun thumbnailsDecodeNearTheirDisplaySize() {
    assertEquals(96, MediaArt.edgeFor(60))
    assertEquals(96, MediaArt.edgeFor(95))
    assertEquals(128, MediaArt.edgeFor(100))
    assertEquals(256, MediaArt.edgeFor(234))
    assertEquals(MediaArt.MAX_EDGE, MediaArt.edgeFor(2000))
  }

  @Test fun theRequestedEdgeReachesTheLoaderCappedAtTheDefault() {
    val cover = Mockito.mock(Bitmap::class.java)
    val edges = mutableListOf<Int>()
    assertSame(cover, MediaArt.resolveUri("https://fixture/a", 128, { _, e -> edges += e; cover }, { _, _ -> null }))
    MediaArt.resolveUri("https://fixture/a", 9_000, { _, e -> edges += e; cover }, { _, _ -> null })
    assertEquals(listOf(128, MediaArt.MAX_EDGE), edges)
  }

  @Test fun chipSliceIgnoresPositionTicks() {
    val s = YtEngineState(positionMs = 1_000L)
    assertEquals(MusicV3ChipSlice.of(s), MusicV3ChipSlice.of(s.copy(positionMs = 2_000L, positionAtElapsedMs = 9L)))
    assertEquals(MusicNowSlice.of(s, "Kitchen"), MusicNowSlice.of(s.copy(positionMs = 5_000L), "Kitchen"))
    assertFalse(MusicProgressSlice.of(s) == MusicProgressSlice.of(s.copy(positionMs = 5_000L)))
  }

  @Test fun repeatCyclesOffAllOne() {
    assertEquals(YtRepeat.ALL, MusicV3StageCopy.nextRepeat(YtRepeat.OFF))
    assertEquals(YtRepeat.ONE, MusicV3StageCopy.nextRepeat(YtRepeat.ALL))
    assertEquals(YtRepeat.OFF, MusicV3StageCopy.nextRepeat(YtRepeat.ONE))
  }
}
