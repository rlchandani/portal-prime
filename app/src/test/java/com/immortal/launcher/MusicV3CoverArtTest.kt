/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/** Now Playing shows the song's square cover, like the YouTube Music app. */
class MusicV3CoverArtTest {
  @Test fun prefersTheSharpStandardThumbnailThenTheAlwaysPresentOne() =
      assertEquals(listOf("https://i.ytimg.com/vi/abc_DEF-123/sddefault.jpg", "https://i.ytimg.com/vi/abc_DEF-123/hqdefault.jpg"),
          MusicV3CoverArt.candidates("abc_DEF-123", "https://i.ytimg.com/vi/abc_DEF-123/default.jpg"))

  @Test fun anInvalidIdFallsBackToTheTracksOwnThumbnail() =
      assertEquals(listOf("https://example.test/t.jpg"), MusicV3CoverArt.candidates("../x", "https://example.test/t.jpg"))

  @Test fun nothingKnownMeansNoArt() = assertEquals(emptyList<String>(), MusicV3CoverArt.candidates("", ""))

  @Test fun theLetterboxedThumbnailIsZoomedToJustTheSquareCover() = assertEquals(4f / 3f, MusicV3CoverArt.ZOOM, 0.0001f)
}
