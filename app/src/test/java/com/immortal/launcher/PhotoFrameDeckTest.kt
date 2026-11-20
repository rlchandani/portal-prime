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

/** Deck building and walking, with the shuffle and header decode passed in. */
class PhotoFrameDeckTest {
  @Test fun orderIsKeptUnlessShuffledOnce() {
    val items = listOf("a", "b", "c")
    assertEquals(items, PhotoFrameDeck.ordered(items, shuffle = false) { error("no shuffle") })
    assertEquals(listOf("c", "b", "a"), PhotoFrameDeck.ordered(items, shuffle = true) { it.reversed() })
    assertEquals(items.toSet(), PhotoFrameDeck.ordered(items, shuffle = true).toSet())
  }

  @Test fun theEnginePoolMeasuresASeedDropsJunkAndSkipsVideos() {
    val items = listOf(MediaItem("/p/a.jpg", false), MediaItem("/p/clip.mp4", true), MediaItem("/p/tiny.jpg", false),
        MediaItem("/p/unknown.jpg", false), MediaItem("/p/tall.jpg", false))
    val sizes = mapOf("/p/a.jpg" to (3000 to 2000), "/p/tiny.jpg" to (300 to 200), "/p/tall.jpg" to (2000 to 3000))
    val asked = ArrayList<String>()
    val pool = PhotoFrameDeck.enginePool(items) { asked += it; sizes[it] }
    assertEquals(listOf("file:///p/a.jpg", "file:///p/unknown.jpg", "file:///p/tall.jpg"), pool.urls)
    assertEquals(mapOf("file:///p/a.jpg" to (3000 to 2000), "file:///p/tall.jpg" to (2000 to 3000)), pool.dims.toMap())
    assertEquals("videos are never measured", listOf("/p/a.jpg", "/p/tiny.jpg", "/p/unknown.jpg", "/p/tall.jpg"), asked)
  }

  @Test fun theEnginePoolMeasuresOnlyTheSeedAndStopsAtItsCap() {
    val items = (0 until 400).map { MediaItem("/p/$it.jpg", false) }
    var measured = 0
    // Every unmeasured photo is junk-sized; only the seed is checked, so the rest stay in.
    val pool = PhotoFrameDeck.enginePool(items) { measured++; 100 to 100 }
    assertEquals(PhotoFrameDeck.DIMS_SEED, measured)
    assertEquals(PhotoFrameDeck.ENGINE_MAX_PHOTOS, pool.urls.size)
    assertEquals("file:///p/80.jpg", pool.urls.first())
    assertEquals(0, pool.dims.size)
    // A short side exactly at the minimum is a photo.
    assertEquals(1, PhotoFrameDeck.enginePool(listOf(MediaItem("/e.jpg", false))) { PhotoFrameDeck.MIN_PHOTO_EDGE to 4000 }.urls.size)
  }

  @Test fun engineImagesLeaveTheVideosOut() {
    val media = listOf(ImmichSource.Media("https://i/a", false), ImmichSource.Media("https://i/v", true))
    assertEquals(listOf("https://i/a"), PhotoFrameDeck.engineImages(media, { it.isVideo }, { it.url }))
  }

  @Test fun playlistArithmeticWrapsBothWays() {
    assertEquals(0, PhotoFrameDeck.step(-1, +1, 3))
    assertEquals(2, PhotoFrameDeck.step(0, -1, 3))
    assertEquals(0, PhotoFrameDeck.step(2, +1, 3))
    assertEquals(0, PhotoFrameDeck.step(0, +1, 1))
    assertEquals(emptyList<Int>(), PhotoFrameDeck.partnerIndices(0, 1))
    assertEquals(listOf(0), PhotoFrameDeck.partnerIndices(1, 2))
    assertEquals(listOf(3), PhotoFrameDeck.partnerIndices(2, 5))
    assertEquals(0, PhotoFrameDeck.after(4, 5))
    assertEquals(listOf("a", "new", "b"), PhotoFrameDeck.splice(listOf("a", "b"), 1, "new"))
    assertEquals(listOf("a", "b", "new"), PhotoFrameDeck.splice(listOf("a", "b"), 2, "new"))
  }

  @Test fun historyMovesForwardOnlyWhileThereIsMore() {
    assertEquals(1, PhotoFrameDeck.historyForward(0, 3))
    assertNull(PhotoFrameDeck.historyForward(2, 3))
    assertNull("nothing shown yet", PhotoFrameDeck.historyForward(-1, 3))
    assertNull(PhotoFrameDeck.historyForward(0, 0))
  }
}
