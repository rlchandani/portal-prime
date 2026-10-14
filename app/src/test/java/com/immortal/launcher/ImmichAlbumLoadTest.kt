/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class ImmichAlbumLoadTest {
    private val album = ImmichSource.Album("a", "Family", 3)
    @Test fun missingInitialAlbumResponseKeepsLibraryChoiceAndMarksFailure() {
        val state = ImmichAlbumLoad().refreshed(Result.success(null))
        assertTrue(state.failed); assertTrue(state.albums.isEmpty()); assertNull(state.selection(null))
    }
    @Test fun thrownInitialAlbumErrorIsRecoverable() {
        val state = ImmichAlbumLoad().refreshed(Result.failure(IllegalStateException("albums")))
        assertTrue(state.failed); assertTrue(state.albums.isEmpty())
    }
    @Test fun actualEmptyAlbumListIsSuccessRatherThanFailure() {
        val state = ImmichAlbumLoad().refreshed(Result.success(emptyList()))
        assertFalse(state.failed); assertTrue(state.albums.isEmpty())
    }
    @Test fun successfulAlbumResultRetainsMetadata() {
        val state = ImmichAlbumLoad().refreshed(Result.success(listOf(album)))
        assertFalse(state.failed); assertEquals(listOf(album), state.albums); assertEquals("a", state.selection("a"))
    }
    @Test fun failedRefreshKeepsPreviousAlbumsAndValidSelection() {
        val state = ImmichAlbumLoad(listOf(album)).refreshed(Result.failure(IllegalStateException("offline")))
        assertTrue(state.failed); assertEquals(listOf(album), state.albums); assertEquals("a", state.selection("a"))
    }
    @Test fun retryRecoveryClearsFailureAndKeepsValidSelection() {
        val state = ImmichAlbumLoad(listOf(album), failed = true).refreshed(Result.success(listOf(album)))
        assertFalse(state.failed); assertEquals("a", state.selection("a"))
    }
    @Test fun unavailableSelectionFallsBackToExplicitWholeLibraryChoice() {
        val state = ImmichAlbumLoad(listOf(album))
        assertNull(state.selection("removed"))
        assertNull(state.refreshed(Result.success(emptyList())).selection("a"))
    }
    @Test fun chosenWholeLibraryRemainsSelectedAfterAlbumRecovery() {
        val state = ImmichAlbumLoad(failed = true).refreshed(Result.success(listOf(album)))
        assertFalse(state.failed); assertNull(state.selection(null))
    }
}
