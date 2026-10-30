/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class MusicBrowseSearchTest {
  private val north = MusicBrowseCollection("north", "North shore", "Evening piano",
      tracks = listOf(MusicBrowseTrack("ocean", "Ocean light", "Ada Rivers")))
  private val quiet = MusicBrowseCollection("quiet", "Quiet hours", "Acoustic",
      tracks = listOf(MusicBrowseTrack("moon", "Moonrise", "Leo Gray")),
      available = false)
  private val library = listOf(north, quiet)

  @Test fun collectionTitleAndSubtitleMatchIgnoringCaseAndOuterWhitespace() {
    assertEquals(listOf("north"),
        musicBrowseSearchCollections(library, "  NORTH ").map { it.id })
    assertEquals(listOf("quiet"),
        musicBrowseSearchCollections(library, "aCOUSTic").map { it.id })
  }

  @Test fun nestedTrackTitleAndArtistFindTheirCollection() {
    assertEquals(listOf("north"),
        musicBrowseSearchCollections(library, "OCEAN LIGHT").map { it.id })
    assertEquals(listOf("quiet"),
        musicBrowseSearchCollections(library, "leo gray").map { it.id })
  }

  @Test fun clearingOrWhitespaceRestoresOriginalOrderAndUnavailableItems() {
    assertSame(library, musicBrowseSearchCollections(library, ""))
    assertSame(library, musicBrowseSearchCollections(library, " \t\n "))
    assertEquals(listOf("north", "quiet"),
        musicBrowseSearchCollections(library, "r").map { it.id })
  }

  @Test fun matchingTrackKeepsOriginalCollectionAndAllItsTracks() {
    val collection = north.copy(tracks = north.tracks + MusicBrowseTrack("other", "Other"))
    val result = musicBrowseSearchCollections(listOf(collection), "ocean")
    assertSame(collection, result.single())
    assertEquals(listOf("ocean", "other"), result.single().tracks.map { it.id })
  }

  @Test fun missingMetadataAndNoMatchReturnHonestEmptyResults() {
    val missing = MusicBrowseCollection("", "", tracks = listOf(MusicBrowseTrack("", "")))
    assertTrue(musicBrowseSearchCollections(library + missing, "not present").isEmpty())
    assertTrue(musicBrowseSearchCollections(emptyList(), "piano").isEmpty())
  }

  @Test fun artworkAndFallbackLabelsAreNotSearchableMetadata() {
    val missing = MusicBrowseCollection("", "", artworkUri = "https://example.test/ocean",
        tracks = listOf(MusicBrowseTrack("", "", artworkUri = "content://ocean")))
    assertTrue(musicBrowseSearchCollections(listOf(missing), "ocean").isEmpty())
    assertTrue(musicBrowseSearchCollections(listOf(missing), "Untitled").isEmpty())
  }
}
